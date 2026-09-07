package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.api.ui.WeConversationListViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.allViews
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

/**
 * 长昵称双行显示
 *
 * 聊天页群成员昵称、会话列表标题、通讯录联系人昵称过长时双行显示，不再单行截断。
 *
 * 三路覆盖（互相兜底, 幂等）：
 *  1. 全局 TextView.onAttachedToWindow hook（主力）：每个新出现的单行大字号 TextView
 *     放开为两行。不按页面/Activity 过滤 —— 8.0.76 通讯录是 LauncherUI 内的
 *     MvvmAddressUIFragment, 没有"通讯录 Activity"可匹配, 按属性过滤才能覆盖所有列表。
 *  2. ActivityLifecycleCallbacks + decorView 清扫：每次页面切换（onResume）后遍历
 *     当前页存量单行 TextView。解决"开启开关后已渲染的行不重新 attach, 不重启微信
 *     不生效"的问题。
 *  3. 聊天页/会话列表 API listener（精确兜底）：行复用重新 bind 时若 adapter 重置了
 *     maxLines, 在 bind 时机重新应用；聊天页 userTV 额外解除 240dp 宽度硬上限。
 *
 * 启发式安全性：maxLines=2 只对"单行超宽被截断"的文本产生视觉变化（这正是需求），
 * 短文本不超一行永远不会凭空换行, 因此无需担心误伤按钮/时间戳等短文本控件。
 */
@Feature(
    name = "长昵称双行显示",
    categories = ["聊天", "联系人与群组"],
    description = "聊天页群成员昵称、会话列表与通讯录联系人昵称过长时双行显示, 不再单行截断; 同时解除群昵称 240dp 宽度限制"
)
object TwoLineNicknames : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeConversationListViewApi.IBindViewListener {

    private const val TAG = "TwoLineNicknames"

    /**
     * 昵称/标题档字号阈值（px）。会话列表标题约 15sp、聊天页/联系人昵称约 17sp,
     * 而会话摘要/时间约 13sp、底部标签页约 10sp —— 取 14sp 覆盖全部标题档并
     * 排除摘要档（与 Themes 主/次文本 13sp 分界一致）。
     */
    private val titleTextSizeThresholdPx: Float
        get() = 14f * HostInfo.application.resources.displayMetrics.density

    private val appliedViews = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    /** 记录处理前的 ellipsize, 关闭功能时恢复原状 */
    private val originalEllipsizes = java.util.WeakHashMap<TextView, TextUtils.TruncateAt?>()

    private var lifecycleRegistered = false

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // 页面切换后清扫存量单行 TextView（幂等）;
            // post 等一帧, 保证新页面的视图已完成 inflate/attach。
            activity.window?.decorView?.post { sweepExistingViews(activity) }
        }

        override fun onActivityDestroyed(activity: Activity) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    }

    // ── 聊天页：群成员昵称（气泡上方）双行 + 解除 240dp 上限 ─────────────

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val msgInfo = runCatching { WeChatMessageViewApi.getMsgInfoFromParam(param) }.getOrNull()
        if (msgInfo?.isInGroupChat != true) return
        if (msgInfo.isSend != 0) return

        // userTV 位于消息视图 tag 持有的 ViewHolder；防御性获取, tag 结构异常时静默跳过
        val textView = runCatching {
            view.tag.reflekt()
                .firstFieldOrNull { name = "userTV"; superclass() }?.get() as? TextView
        }.getOrNull()

        if (textView == null) {
            // 诊断：确认 hook 已触发, 只是此版本 ViewHolder 结构不同
            WeLogger.d(TAG, "chat msg bound but no userTV found (holder=${view.tag?.javaClass?.simpleName})")
            return
        }

        applyTwoLine(textView, removeWidthCap = true)
        WeLogger.d(TAG, "chat userTV two-line applied: \"${textView.text?.take(20)}\"")
    }

    // ── 会话列表：行复用 bind 时兜底（adapter 可能重置 maxLines）────────

    override fun onBind(
        param: XC_MethodHook.MethodHookParam,
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        // 与全局 hook 相同的属性启发式, 在 bind 时机重新应用（幂等）
        for (v in row.allViews) {
            if (v is TextView && isTitleLikeSingleLine(v)) {
                applyTwoLine(v, removeWidthCap = false)
            }
        }
    }

    // ── 全局主力：TextView attach hook ─────────────────────────────────

    /**
     * 每个新 attach 的 TextView 若是"单行 + 大字号"（昵称/标题档）则放开为两行。
     * 短文本不受任何影响; 长文本双行显示正是本功能目标。
     */
    private fun installGlobalAttachHook() {
        runCatching {
            TextView::class.reflekt()
                .firstMethod { name = "onAttachedToWindow" }
                .hookAfter {
                    val tv = thisObject as? TextView ?: return@hookAfter
                    if (isTitleLikeSingleLine(tv)) {
                        applyTwoLine(tv, removeWidthCap = false)
                        WeLogger.d(TAG, "attach two-line applied: \"${tv.text?.take(20)}\"")
                    }
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook TextView.onAttachedToWindow", it)
            return
        }
        WeLogger.i(TAG, "global TextView attach hook installed (threshold 14sp)")
    }

    /** 属性启发式：单行 + 标题档字号的 TextView 视为昵称/标题 */
    private fun isTitleLikeSingleLine(tv: TextView): Boolean {
        if (tv in appliedViews) return false
        if (tv is EditText) return false
        if (tv.maxLines != 1) return false
        if (tv.textSize < titleTextSizeThresholdPx) return false
        return true
    }

    /** 遍历 Activity 全部视图, 对符合条件的存量 TextView 应用双行（幂等） */
    private fun sweepExistingViews(activity: Activity) {
        if (!isEnabled) return
        runCatching {
            var applied = 0
            activity.window?.decorView?.let { root ->
                for (v in root.allViews) {
                    if (v is TextView && isTitleLikeSingleLine(v)) {
                        applyTwoLine(v, removeWidthCap = false)
                        applied++
                    }
                }
            }
            if (applied > 0) {
                WeLogger.d(TAG, "sweep on ${activity.javaClass.simpleName}: applied $applied existing TextView(s)")
            }
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        WeConversationListViewApi.addListener(this)
        installGlobalAttachHook()

        // 监听页面切换, 每次 onResume 清扫存量 —— 开关打开后无需重启微信,
        // 返回微信任意页面即生效。
        runCatching {
            HostInfo.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            lifecycleRegistered = true
        }.onFailure {
            WeLogger.w(TAG, "failed to register lifecycle callbacks (sweep disabled)", it)
        }
        WeLogger.i(TAG, "enabled: attach hook + lifecycle sweep + chat/conversation listeners")
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeConversationListViewApi.removeListener(this)
        if (lifecycleRegistered) {
            runCatching { HostInfo.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks) }
            lifecycleRegistered = false
        }

        // 恢复已处理 TextView 的原状态
        for (view in appliedViews.toList()) {
            val tv = view as? TextView ?: continue
            runCatching {
                tv.maxLines = 1
                tv.ellipsize = originalEllipsizes[tv]
                tv.requestLayout()
            }
        }
        appliedViews.clear()
        originalEllipsizes.clear()
    }

    // ── 实现 ─────────────────────────────────────────────────────────────

    private fun applyTwoLine(tv: TextView, removeWidthCap: Boolean) {
        if (tv !in appliedViews) {
            originalEllipsizes[tv] = tv.ellipsize
            appliedViews.add(tv)
        }
        // setSingleLine(false) 先解除单行约束（含水平滚动模式）,
        // 再显式设 2 行, 避免依赖 setSingleLine 内部对 maxLines 的副作用顺序
        tv.setSingleLine(false)
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
        // 聊天页 userTV 的共享样式带 240dp 硬上限, 解除后由父布局约束实际宽度;
        // 列表标题保留原有 maxWidth（防止挤占右侧时间戳）, 仅放开行数
        if (removeWidthCap) tv.maxWidth = Int.MAX_VALUE
        // 修改 maxLines 后强制重新布局, 确保已显示的行立即生效
        tv.requestLayout()
        tv.invalidate()
    }
}
