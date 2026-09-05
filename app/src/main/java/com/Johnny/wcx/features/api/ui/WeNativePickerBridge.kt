package com.Johnny.wcx.features.api.ui

import android.app.Activity
import android.content.Intent
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClassOrNull

/**
 * 微信原生联系人/会话选择器桥接层。
 *
 * 模块自绘的联系人弹窗在体验上与微信原生不一致。本桥接让模块 UI 直接唤起微信自带的
 * 选择页面（默认即多选），用户勾选完成后由模块截获选中 wxid 列表回传给调用方。
 *
 * 契约依据（已在仓库内验证或由微信社区情报佐证）：
 *  - 多选目标页面：com.tencent.mm.ui.mvvm.MvvmSelectContactUI / MvvmContactListUI
 *    （微信转发批量选人页，RemoveMessageBatchForwardLimit 依赖同一批类）
 *  - 可选数量上限：Intent extra `max_limit_num`（WechatMagician/本模块均已实测有效，
 *    默认 9 人，置大数即可放开）
 *  - 结果回传：微信把选中集合以 extra 形式写到页面 Intent 后 finish
 *    （ConversationAggregation 注释记载 state center 写 "Select_Conv_User" extra 并 finish；
 *    这里兼容 ArrayList<String> 与单值 String 两种形态，多个 key 依次探测）
 *
 * hook 采用惰性注册（首次 launch 时幂等注册一次），且只会在存在 pending 请求时认领结果，
 * 因此不依赖任何功能开关、不干扰微信自身流程。页面/类缺失或启动失败时 launch 返回 false，
 * 调用方应降级到自绘选择器。
 */
object WeNativePickerBridge {

    private const val TAG = "WeNativePickerBridge"

    /** 微信原生选择页候选（按优先级）。Mvvm 系为多选页，SelectConversationUI 为点选即回页。 */
    private val PICKER_CLASSES = listOf(
        "com.tencent.mm.ui.mvvm.MvvmSelectContactUI",
        "com.tencent.mm.ui.mvvm.MvvmContactListUI",
        "com.tencent.mm.ui.transmit.SelectConversationUI",
    )

    /** 结果 extra 候选 key（依次探测 ArrayList<String> 再单值 String）。 */
    private val RESULT_KEYS = listOf("Select_Conv_User", "Select_Conv_Users", "Select_Conversation_User")

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
     * 发起一次原生多选。成功返回 true；不可用/启动异常返回 false（调用方自行降级自绘弹窗）。
     * 结果在主线程回调（[onResult] 收到的 wxid 已按 options 过滤类型）。
     */
    fun launch(
        activity: Activity,
        options: Options,
        onResult: (List<String>) -> Unit,
    ): Boolean {
        val pickerClass = PICKER_CLASSES.firstOrNull { it.toClassOrNull() != null }
        if (pickerClass == null) {
            WeLogger.w(TAG, "no native picker class found, fallback needed")
            return false
        }
        ensureHooks()
        return try {
            val intent = Intent().apply {
                setClassName(PackageNames.WECHAT, pickerClass)
                // 放开微信默认 9 人多选上限
                putExtra("max_limit_num", 9999)
                if (options.title.isNotEmpty()) putExtra("Select_Conv_User_Title", options.title)
            }
            pending = PendingRequest(options, onResult)
            activity.startActivity(intent)
            WeLogger.i(TAG, "launched $pickerClass")
            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "launch native picker failed", e)
            pending = null
            false
        }
    }

    /** 幂等注册页面 finish 钩子；仅在存在 pending 请求时认领结果。 */
    private fun ensureHooks() {
        if (hooksRegistered) return
        hooksRegistered = true
        for (name in PICKER_CLASSES) {
            val clazz = runCatching { name.toClassOrNull() }.getOrNull() ?: continue
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
        }
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
