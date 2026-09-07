package com.Johnny.wcx.features.items.contacts

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

/**
 * 长昵称双行显示
 *
 * 聊天界面群成员昵称与会话列表标题过长时改为双行显示，不再单行截断。
 *
 * 微信的换行限制来自两处：
 *  1. 聊天页 userTV 使用共享样式，maxWidth 固定 240dp 且单行（超长直接硬裁剪），
 *     本功能解除该宽度上限（与「解除群成员昵称长度限制」同思路），并允许两行；
 *  2. 会话列表标题 TextView 为单行省略（maxLines=1），本功能放开为两行。
 *
 * 短昵称完全不受影响（文本不超一行时 TextView 不会凭空换行），只有超过一行
 * 宽度的长昵称才会折到第二行显示，超出两行时尾部省略号截断。
 */
@Feature(
    name = "长昵称双行显示",
    categories = ["聊天", "联系人与群组"],
    description = "聊天界面群成员昵称与会话列表标题过长时双行显示, 不再单行截断; 同时解除群昵称 240dp 宽度限制"
)
object TwoLineNicknames : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeConversationListViewApi.IBindViewListener {

    private const val TAG = "TwoLineNicknames"

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        WeConversationListViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeConversationListViewApi.removeListener(this)
    }

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

    // ── 实现 ─────────────────────────────────────────────────────────

    private fun applyTwoLine(tv: TextView, removeWidthCap: Boolean) {
        // setSingleLine(false) 先解除单行约束（含水平滚动模式）,
        // 再显式设 2 行, 避免依赖 setSingleLine 内部对 maxLines 的副作用顺序
        tv.setSingleLine(false)
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END
        // 聊天页 userTV 的共享样式带 240dp 硬上限, 解除后由父布局约束实际宽度;
        // 会话列表标题保留原有 maxWidth（防止挤占右侧时间戳）, 仅放开行数
        if (removeWidthCap) tv.maxWidth = Int.MAX_VALUE
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
}
