package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.api.ui.WeConversationListViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

/**
 * 长昵称双行显示
 *
 * 聊天页群成员昵称、会话列表标题、通讯录联系人昵称过长时双行显示，不再单行截断。
 *
 * 覆盖三个界面：
 *  1. 聊天页 userTV：共享样式 maxWidth=240dp 单行硬裁剪 → 解除宽度上限 + 双行
 *  2. 会话列表标题：单行省略 → 双行
 *  3. 通讯录等联系人列表（MvvmList 机制，行由 WeChat 内部渲染）：
 *     通过 TextView.onAttachedToWindow 全局 hook + 页面包名过滤实现，
 *     每个新 attach 的 TextView 若处于联系人相关页面且字号达到标题档（>=17sp）则放开为两行
 *
 * 短昵称完全不受影响（文本不超一行时 TextView 不会凭空换行）。
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

    /** 通讯录等联系人列表页面的 Activity 类名前缀（onAttachedToWindow 过滤用） */
    private val CONTACTS_ACTIVITY_PREFIXES = arrayOf(
        "com.tencent.mm.ui.contact",      // 通讯录主页 / 联系人详情
        "com.tencent.mm.ui.chatting.atsomeone", // @成员选择器
    )

    /** 标题档字号阈值（px）；标题 17sp 明显大于摘要 13sp/时间 12sp */
    private val titleTextSizeThresholdPx: Float
        get() = 16f * HostInfo.application.resources.displayMetrics.density

    private val appliedViews = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    // ── 聊天页：群成员昵称（气泡上方）双行 ─────────────────────────────

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isSend != 0) return

        // userTV 位于消息视图 tag 持有的 ViewHolder；防御性获取, tag 结构异常时静默跳过
        val textView = runCatching {
            view.tag.reflekt()
                .firstFieldOrNull { name = "userTV"; superclass() }?.get() as? TextView
        }.getOrNull() ?: return

        applyTwoLine(textView, removeWidthCap = true)
        WeLogger.d(TAG, "chat userTV two-line applied: \"${textView.text?.take(20)}\"")
    }

    // ── 会话列表：标题双行 ───────────────────────────────────────────

    override fun onBind(
        param: XC_MethodHook.MethodHookParam,
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        // 行内字号最大的 TextView 即标题（摘要与时间字号更小）。
        // 即使个别版本启发式选错, 被误选的控件文本都很短（时间/计数）,
        // 设置 maxLines=2 不会产生任何视觉变化, 副作用为零。
        val title = largestTextView(row) ?: return
        applyTwoLine(title, removeWidthCap = false)
    }

    // ── 通讯录 / @成员选择器：联系人昵称双行 ──────────────────────────

    /**
     * 全局 TextView attach hook。联系人列表行由微信内部 MvvmList 渲染、
     * 没有公开的 bind 回调, 借助 attach 时机在每个新行出现的瞬间处理。
     * 页面过滤放在最前, 非联系人页面的 TextView 直接返回, 开销可忽略。
     */
    private fun installContactsListHook() {
        runCatching {
            TextView::class.reflekt()
                .firstMethod { name = "onAttachedToWindow" }
                .hookAfter {
                    val tv = thisObject as? TextView ?: return@hookAfter
                    if (tv in appliedViews) return@hookAfter

                    val activity = tv.context.findActivity() ?: return@hookAfter
                    val className = activity.javaClass.name
                    val inContactsPage = CONTACTS_ACTIVITY_PREFIXES.any { className.startsWith(it) }
                    if (!inContactsPage) return@hookAfter

                    // 只处理标题档字号的 TextView（昵称）, 摘要/字母索引等小字号跳过
                    if (tv.textSize < titleTextSizeThresholdPx) return@hookAfter
                    if (tv.text.isNullOrEmpty()) return@hookAfter

                    appliedViews.add(tv)
                    applyTwoLine(tv, removeWidthCap = false)
                    WeLogger.d(TAG, "contacts row two-line applied in $className: \"${tv.text?.take(20)}\"")
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook TextView.onAttachedToWindow for contacts list", it)
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        WeConversationListViewApi.addListener(this)
        installContactsListHook()
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeConversationListViewApi.removeListener(this)
    }

    // ── 实现 ─────────────────────────────────────────────────────────

    private fun applyTwoLine(tv: TextView, removeWidthCap: Boolean) {
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

    /** 深度优先遍历行视图树, 返回 textSize 最大的 TextView（即标题） */
    private fun largestTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view !is ViewGroup) return null

        var best: TextView? = null
        for (i in 0 until view.childCount) {
            val candidate = largestTextView(view.getChildAt(i)) ?: continue
            if (best == null || candidate.textSize > best.textSize) best = candidate
        }
        return best
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
