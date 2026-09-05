package com.Johnny.wcx.features.api.ui

import android.app.Activity
import android.content.Intent
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClassOrNull
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
        val pickerClass = PICKER_CLASSES.firstOrNull { it.toClassOrNull() != null } ?: return false
        ensureHooks()

        // 关键：只有学习到微信真实打开参数后才尝试原生页，否则直接降级自绘
        val template = WePrefs.getString(PREFS_TEMPLATE_PREFIX + pickerClass)
        if (template.isNullOrBlank()) {
            WeLogger.i(TAG, "no native template for $pickerClass yet, fallback to custom picker")
            return false
        }

        return try {
            val intent = Intent().apply {
                setClassName(PackageNames.WECHAT, pickerClass)
                // 放开微信默认 9 人多选上限
                putExtra("max_limit_num", 9999)
                putExtra(MARKER, true)
                if (options.title.isNotEmpty()) putExtra("Select_Conv_User_Title", options.title)
                // 重放微信真实场景参数（仅基本类型；控制项在 TEMPLATE_IGNORE 内由上面覆盖）
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

    /** 微信真实打开选择页时把基本类型场景参数快照成模板。 */
    private fun learnTemplate(className: String, intent: Intent) {
        val extras = intent.extras ?: return
        if (extras.keySet().isEmpty()) return
        val obj = JSONObject()
        for (key in extras.keySet()) {
            if (TEMPLATE_IGNORE.contains(key)) continue
            val value = try {
                extras.get(key)
            } catch (e: Throwable) {
                continue
            }
            // 只记录可安全重放的基本类型，忽略 Parcelable（大对象/不可复用）
            val simple = when (value) {
                is String -> value
                is Int -> value
                is Long -> value
                is Boolean -> value
                is Double -> value
                is Float -> value
                else -> continue
            }
            runCatching { obj.put(key, simple) }
        }
        if (obj.length() == 0) return
        val stored = WePrefs.getStringOrDef(PREFS_TEMPLATE_PREFIX + className, null)
        if (stored == obj.toString()) return // 模板没变化
        WePrefs.putString(PREFS_TEMPLATE_PREFIX + className, obj.toString())
        WeLogger.i(TAG, "learned native picker template for $className: ${obj.length()} keys")
    }

    private fun replayTemplateExtras(intent: Intent, templateJson: String) {
        runCatching {
            val obj = JSONObject(templateJson)
            for (key in obj.keys()) {
                if (TEMPLATE_IGNORE.contains(key)) continue
                val v = obj.opt(key)
                when (v) {
                    is String -> intent.putExtra(key, v)
                    is Int -> intent.putExtra(key, v)
                    is Long -> intent.putExtra(key, v)
                    is Boolean -> intent.putExtra(key, v)
                    is Double -> intent.putExtra(key, v)
                    is Float -> intent.putExtra(key, v)
                    else -> Unit
                }
            }
        }.onFailure { WeLogger.e(TAG, "replay template failed", it) }
    }

    private fun deliverIfPending(activity: Activity) {
        val request = pending ?: return
        val intent = activity.intent ?: return
        val ids = extractResultIds(intent)
        if (ids == null) {
            // 未命中结果 key：可能是取消返回，也可能是未知结果形态（打印 extras 便于适配）。
            if (intent.extras != null) {
                WeLogger.i(TAG, "picker finish without known result: ${intent.extras!!.keySet()}")
            }
            return
        }
        pending = null
        val filtered = ids.filter { id -> matchesOptions(id, request.options) }
        WeLogger.i(TAG, "picker returned ${ids.size} ids, kept ${filtered.size}")
        request.onResult(filtered)
    }

    private fun extractResultIds(intent: Intent): List<String>? {
        for (key in RESULT_KEYS) {
            runCatching {
                val list = intent.getStringArrayListExtra(key)
                if (list != null && list.isNotEmpty()) return list
            }
        }
        for (key in RESULT_KEYS) {
            val single = intent.getStringExtra(key)
            if (!single.isNullOrBlank()) return listOf(single)
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
