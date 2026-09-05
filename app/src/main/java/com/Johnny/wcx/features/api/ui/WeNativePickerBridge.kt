package com.Johnny.wcx.features.api.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 微信原生联系人/会话选择器桥接层。
 *
 * 模块自绘的联系人弹窗在体验上与微信原生不一致。本桥接让模块 UI 直接唤起微信自带的
 * 选择页面（默认即多选），用户勾选完成后由模块截获选中 wxid 列表回传给调用方。
 *
 * ⚠️ 微信的 Mvvm 选人页（MvvmSelectContactUI / MvvmContactListUI）是**转发流程专用页面**，
 * 由微信内部以特定"场景参数"启动；模块从零裸启动（只带 max_limit_num）会导致页面空白
 * （列表无数据源）。因此本桥接采用「先学习后启动」：
 *
 *  1. 注册 onCreate 钩子：当微信**自己**打开这些页面时（如用户在聊天里走转发→多选），
 *     自动把当时的 Intent 场景参数（基本类型）快照成"模板"存入 MMKV；
 *  2. launch() 只有在已存在该页模板时才尝试用模板+max_limit_num 启动；
 *     没有模板 → 立即返回 false，调用方降级到自绘选择器（保证功能永不自屏）。
 *
 * 结果回传：微信把选中集合以 extra 形式写到页面 Intent 后 finish
 *    （ConversationAggregation 注释记载 state center 写 "Select_Conv_User" extra 并 finish；
 *    这里兼容 ArrayList<String> 与单值 String 两种形态，多个 key 依次探测）。
 *
 * hook 采用惰性注册（首次 launch 时幂等注册一次），且只会在存在 pending 请求时认领结果，
 * 因此不依赖任何功能开关、不干扰微信自身流程。
 */
object WeNativePickerBridge {

    private const val TAG = "WeNativePickerBridge"

    /** 微信原生选择页候选（按优先级）。Mvvm 系为转发流程多选页。 */
    private val PICKER_CLASSES = listOf(
        "com.tencent.mm.ui.mvvm.MvvmSelectContactUI",
        "com.tencent.mm.ui.mvvm.MvvmContactListUI",
        "com.tencent.mm.ui.transmit.SelectConversationUI",
    )

    /** 结果 extra 候选 key（依次探测 ArrayList<String> 再单值 String）。 */
    private val RESULT_KEYS = listOf("Select_Conv_User", "Select_Conv_Users", "Select_Conversation_User")

    /** 自身启动标记（与 WeStartActivityApi 一致），用于区分"我们启动的页"与"微信自己打开的页"。 */
    private const val MARKER = "wekit_native_picker"

    private const val PREFS_TEMPLATE_PREFIX = "wekit_picker_template_"

    /** 会被模板忽略的 key（我们的控制项 / 微信结果项），重放时自行覆盖。 */
    private val TEMPLATE_IGNORE = setOf(MARKER, "max_limit_num", "Select_Conv_User_Title")

    class Options(
        val title: String = "",
        val multiSelect: Boolean = true,
        val allowFriends: Boolean = true,
        val allowChatrooms: Boolean = true,
        val allowOfficialAccounts: Boolean = false,
    )

    class PendingRequest(
        val options: Options,
        val onResult: (List<String>) -> Unit,
    )

    @Volatile
    private var pending: PendingRequest? = null

    @Volatile
    private var hooksRegistered = false

    /**
     * 发起一次原生多选。成功返回 true；没有可用模板/页面不可用/启动异常返回 false
     * （调用方自行降级自绘弹窗）。结果在主线程回调（[onResult] 收到的 wxid 已按 options 过滤类型）。
     */
    fun launch(
        activity: Activity,
        options: Options,
        onResult: (List<String>) -> Unit,
    ): Boolean {
        val available = PICKER_CLASSES.filter { it.toClassOrNull() != null }
        if (available.isEmpty()) {
            diagOnce("no_picker_class") { "none of $PICKER_CLASSES exist in this wechat build" }
            return false
        }
        ensureHooks()

        // 关键：哪个候选页已学习到微信真实参数就用哪个（各页模板独立学习）；
        // 一个都没有才降级自绘，避免"学了转发页却仍因选人页无模板而降级"。
        val pickerClass = available.firstOrNull { c ->
            !WePrefs.getString(PREFS_TEMPLATE_PREFIX + c).isNullOrBlank()
        }
        if (pickerClass == null) {
            WeLogger.i(TAG, "no native template learned yet (available: $available), fallback to custom picker")
            hintLearnOnce()
            return false
        }
        val template = WePrefs.getString(PREFS_TEMPLATE_PREFIX + pickerClass)!!

        return try {
            val intent = Intent().apply {
                setClassName(PackageNames.WECHAT, pickerClass)
                // 放开微信默认 9 人多选上限
                putExtra("max_limit_num", 9999)
                putExtra(MARKER, true)
                if (options.title.isNotEmpty()) putExtra("Select_Conv_User_Title", options.title)
                // 重放微信真实场景参数（控制项在 TEMPLATE_IGNORE 内由上面覆盖）
                replayTemplateExtras(this, template)
            }
            pending = PendingRequest(options, onResult)
            activity.startActivity(intent)
            WeLogger.i(TAG, "launched $pickerClass with learned template")
            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "launch native picker failed", e)
            pending = null
            false
        }
    }

    private val diagLogged = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    private fun diagOnce(key: String, message: () -> String) {
        if (diagLogged.size > 256) diagLogged.clear()
        if (diagLogged.add(key)) WeLogger.w(TAG, message())
    }

    /** 降级自绘时提示一次如何启用原生多选（引导用户在微信走一次选人界面完成"模板学习"）。 */
    @Volatile
    private var hintShown = false

    private fun hintLearnOnce() {
        if (hintShown) return
        hintShown = true
        runCatching {
            showToast("想用微信原生多选？请先在微信里使用一次「选联系人」界面（转发消息选人 / 发起群聊选人均可），本功能将自动改用原生页面（本次已用内置选择器）")
        }
    }

    /** 幂等注册：①页面 finish 钩子（认领结果）②微信自开页面时学习场景参数模板。 */
    private fun ensureHooks() {
        if (hooksRegistered) return
        hooksRegistered = true
        for (name in PICKER_CLASSES) {
            val clazz = runCatching { name.toClassOrNull() }.getOrNull() ?: continue
            // 结果认领
            runCatching {
                XposedBridge.hookAllMethods(clazz, "finish", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        try {
                            deliverIfPending(activity)
                        } catch (e: Throwable) {
                            WeLogger.e(TAG, "deliver result failed", e)
                        }
                    }
                })
                WeLogger.i(TAG, "hooked finish of $name")
            }.onFailure { WeLogger.e(TAG, "hook finish failed $name", it) }

            // 模板学习：微信自己打开这些页时（非我们启动）记录场景参数
            runCatching {
                XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = runCatching { activity.intent }.getOrNull() ?: return
                        if (intent.getBooleanExtra(MARKER, false)) return // 我们自己启动的
                        learnTemplate(name, intent)
                    }
                })
                WeLogger.i(TAG, "hooked onCreate of $name for template learning")
            }.onFailure { WeLogger.e(TAG, "hook onCreate failed $name", it) }
        }
    }

    /** 微信真实打开选择页时把场景参数快照成模板。
     *  微信场景参数多数是 Bundle / ArrayList 等复合类型（此前只收基本类型导致模板永远为空），
     *  这里做类型标注递归编码，可安全回放的类型都收（Parcelable 大对象除外）。 */
    private fun learnTemplate(className: String, intent: Intent) {
        val extras = intent.extras ?: run { diagOnce("learn_no_extras") { "wechat opened $className without extras" }; return }
        if (extras.keySet().isEmpty()) return
        val out = JSONObject()
        var ignored = 0
        for (key in extras.keySet()) {
            if (TEMPLATE_IGNORE.contains(key)) continue
            val value = try {
                extras.get(key)
            } catch (e: Throwable) {
                ignored++
                continue
            }
            val encoded = encodeValue(value, 0)
            if (encoded == null) {
                ignored++
                continue
            }
            runCatching { out.put(key, encoded) }
        }
        if (out.length() == 0) {
            WeLogger.w(TAG, "learn $className: nothing learnable (${extras.keySet().size} keys, $ignored unencodable)")
            return
        }
        val json = out.toString()
        if (json.length > MAX_TEMPLATE_LEN) {
            WeLogger.w(TAG, "learn $className: template too large (${json.length} chars), skipped")
            return
        }
        val stored = WePrefs.getStringOrDef(PREFS_TEMPLATE_PREFIX + className, null)
        if (stored == json) return // 模板没变化
        WePrefs.putString(PREFS_TEMPLATE_PREFIX + className, json)
        WeLogger.i(TAG, "learned native picker template for $className: ${out.length()} keys, ${json.length} chars")
    }

    private const val MAX_TEMPLATE_LEN = 8192

    /** 把 extras 值编码成带类型标注的 JSON（深度受限，防递归失控）。 */
    private fun encodeValue(value: Any?, depth: Int): JSONObject? {
        if (depth > 5 || value == null) return null
        val o = JSONObject()
        when (value) {
            is String -> { o.put("t", "s"); o.put("v", value) }
            is Int -> { o.put("t", "i"); o.put("v", value) }
            is Long -> { o.put("t", "l"); o.put("v", value) }
            is Boolean -> { o.put("t", "b"); o.put("v", value) }
            is Double -> { o.put("t", "d"); o.put("v", value) }
            is Float -> { o.put("t", "f"); o.put("v", value.toDouble()) }
            is Bundle -> {
                if (value.keySet().isEmpty()) return null
                val inner = JSONObject()
                var count = 0
                for (k in value.keySet()) {
                    val ev = encodeValue(runCatching { value.get(k) }.getOrNull(), depth + 1) ?: continue
                    runCatching { inner.put(k, ev) }
                    count++
                }
                if (count == 0) return null
                o.put("t", "bundle"); o.put("v", inner)
            }
            is ArrayList<*> -> {
                if (value.isEmpty() || value.size > 128) return null
                val elemType = value.firstOrNull() ?: return null
                val uniform = value.all { it != null && it::class == elemType::class }
                if (!uniform) return null
                when (elemType) {
                    is String -> {
                        val arr = JSONArray()
                        value.forEach { arr.put(it as String) }
                        o.put("t", "al_s"); o.put("v", arr)
                    }
                    is Int -> {
                        val arr = JSONArray()
                        value.forEach { arr.put(it as Int) }
                        o.put("t", "al_i"); o.put("v", arr)
                    }
                    is Long -> {
                        val arr = JSONArray()
                        value.forEach { arr.put(it as Long) }
                        o.put("t", "al_l"); o.put("v", arr)
                    }
                    else -> return null
                }
            }
            else -> return null
        }
        return o
    }

    private fun replayTemplateExtras(intent: Intent, templateJson: String) {
        runCatching {
            val obj = JSONObject(templateJson)
            for (key in obj.keys()) {
                if (TEMPLATE_IGNORE.contains(key)) continue
                val encoded = obj.optJSONObject(key) ?: continue
                replayValue(intent, key, encoded, 0)
            }
        }.onFailure { WeLogger.e(TAG, "replay template failed", it) }
    }

    private fun replayValue(intent: Intent, key: String, o: JSONObject, depth: Int) {
        if (depth > 5) return
        runCatching {
            when (o.optString("t")) {
                "s" -> intent.putExtra(key, o.optString("v"))
                "i" -> intent.putExtra(key, o.optInt("v"))
                "l" -> intent.putExtra(key, o.optLong("v"))
                "b" -> intent.putExtra(key, o.optBoolean("v"))
                "d" -> intent.putExtra(key, o.optDouble("v"))
                "f" -> intent.putExtra(key, o.optDouble("v").toFloat())
                "al_s" -> {
                    val arr = o.optJSONArray("v") ?: return@runCatching
                    val list = ArrayList<String>()
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    if (list.isNotEmpty()) intent.putStringArrayListExtra(key, list)
                }
                "al_i" -> {
                    val arr = o.optJSONArray("v") ?: return@runCatching
                    val list = ArrayList<Int>()
                    for (i in 0 until arr.length()) list.add(arr.optInt(i))
                    if (list.isNotEmpty()) intent.putIntegerArrayListExtra(key, list)
                }
                "al_l" -> {
                    val arr = o.optJSONArray("v") ?: return@runCatching
                    val list = ArrayList<Long>()
                    for (i in 0 until arr.length()) list.add(arr.optLong(i))
                    if (list.isNotEmpty()) intent.putExtra(key, list)
                }
                "bundle" -> {
                    val inner = o.optJSONObject("v") ?: return@runCatching
                    val bundle = Bundle()
                    for (k in inner.keys()) {
                        val child = inner.optJSONObject(k) ?: continue
                        replayBundleValue(bundle, k, child, depth + 1)
                    }
                    if (!bundle.isEmpty) intent.putExtra(key, bundle)
                }
                else -> Unit
            }
        }.onFailure { WeLogger.e(TAG, "replay key $key failed", it) }
    }

    private fun replayBundleValue(bundle: Bundle, key: String, o: JSONObject, depth: Int) {
        if (depth > 5) return
        runCatching {
            when (o.optString("t")) {
                "s" -> bundle.putString(key, o.optString("v"))
                "i" -> bundle.putInt(key, o.optInt("v"))
                "l" -> bundle.putLong(key, o.optLong("v"))
                "b" -> bundle.putBoolean(key, o.optBoolean("v"))
                "d" -> bundle.putDouble(key, o.optDouble("v"))
                "f" -> bundle.putFloat(key, o.optDouble("v").toFloat())
                "al_s" -> {
                    val arr = o.optJSONArray("v") ?: return@runCatching
                    val list = ArrayList<String>()
                    for (i in 0 until arr.length()) list.add(arr.optString(i))
                    if (list.isNotEmpty()) bundle.putStringArrayList(key, list)
                }
                "al_i" -> {
                    val arr = o.optJSONArray("v") ?: return@runCatching
                    val list = ArrayList<Int>()
                    for (i in 0 until arr.length()) list.add(arr.optInt(i))
                    if (list.isNotEmpty()) bundle.putIntegerArrayList(key, list)
                }
                "bundle" -> {
                    val inner = o.optJSONObject("v") ?: return@runCatching
                    val child = Bundle()
                    for (k in inner.keys()) {
                        val grand = inner.optJSONObject(k) ?: continue
                        replayBundleValue(child, k, grand, depth + 1)
                    }
                    if (!child.isEmpty) bundle.putBundle(key, child)
                }
                else -> Unit
            }
        }.onFailure { WeLogger.e(TAG, "replay bundle key $key failed", it) }
    }

    private fun deliverIfPending(activity: Activity) {
        val request = pending ?: return
        val ids = extractResultIds(activity)
        if (ids == null) {
            // 明确取消（resultCode=CANCELED 且无数据）：清掉 pending，避免残留在下次误投。
            if (isCancelled(activity)) {
                pending = null
                WeLogger.i(TAG, "picker cancelled, cleared pending request")
                return
            }
            // 未命中结果 key：逐个打印 key+值类型便于适配。
            val extras = activity.intent?.extras
            if (extras != null) {
                val desc = buildString {
                    for (key in extras.keySet()) {
                        val v = runCatching { extras.get(key) }.getOrNull()
                        append(key)
                        append("=")
                        append(v?.javaClass?.simpleName ?: "null")
                        append("; ")
                    }
                }
                WeLogger.i(TAG, "picker finish without known result; intent extras: $desc")
            }
            val resultData = readActivityResultData(activity)
            if (resultData?.extras != null) {
                val desc = buildString {
                    for (key in resultData.extras!!.keySet()) {
                        val v = runCatching { resultData.extras!!.get(key) }.getOrNull()
                        append(key)
                        append("=")
                        append(v?.javaClass?.simpleName ?: "null")
                        append("; ")
                    }
                }
                WeLogger.i(TAG, "picker finish without known result; resultData extras: $desc")
            }
            return
        }
        pending = null
        val filtered = ids.filter { id -> matchesOptions(id, request.options) }
        WeLogger.i(TAG, "picker returned ${ids.size} ids, kept ${filtered.size}")
        request.onResult(filtered)
    }

    /** 微信把选中结果 setResult() 出去（转发/选人流程标准做法），数据存在 Activity 的
     *  mResultData/mResultCode 私有字段而非 getIntent() 里。finish 后系统才做结果派发，
     *  因此在 finish hook 中反射读取这两个字段拿回结果（对 startActivity 无请求码启动同样有效）。 */
    private fun readActivityResultData(activity: Activity): Intent? = runCatching {
        val f = Activity::class.java.getDeclaredField("mResultData")
        f.isAccessible = true
        f.get(activity) as? Intent
    }.getOrNull()

    private fun isCancelled(activity: Activity): Boolean = runCatching {
        val f = Activity::class.java.getDeclaredField("mResultCode")
        f.isAccessible = true
        (f.get(activity) as? Int) == Activity.RESULT_CANCELED
    }.getOrDefault(false)

    private fun extractResultIds(activity: Activity): List<String>? {
        // 微信结果可能在页面自身 intent，也可能在 setResult 的 data 里，两处都探测
        val candidates = ArrayList<Intent>(2)
        activity.intent?.let { candidates.add(it) }
        readActivityResultData(activity)?.let { candidates.add(it) }
        for (intent in candidates) {
            for (key in RESULT_KEYS) {
                runCatching {
                    val list = intent.getStringArrayListExtra(key)
                    if (list != null && list.isNotEmpty()) return list
                }
            }
        }
        for (intent in candidates) {
            for (key in RESULT_KEYS) {
                val single = intent.getStringExtra(key)
                if (!single.isNullOrBlank()) return listOf(single)
            }
        }
        return null
    }

    private fun matchesOptions(wxId: String, options: Options): Boolean {
        val isChatroom = wxId.endsWith("@chatroom")
        val isGhost = wxId.startsWith("gh_")
        return when {
            isChatroom -> options.allowChatrooms
            isGhost -> options.allowOfficialAccounts
            else -> options.allowFriends
        }
    }

    /** 便捷提示：结果为空且非取消时的提示文案。 */
    fun toastEmptySelection() {
        showToast("请选择至少一个对话")
    }
}
