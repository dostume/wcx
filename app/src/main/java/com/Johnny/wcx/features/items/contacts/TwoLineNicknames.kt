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
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.allViews
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

/**
 * 长昵称双行显示
 *
 * 三路覆盖（互相兜底, 幂等）：
 *  1. WeChatMessageViewApi.onCreateView（聊天页群成员昵称）：通过成熟API获取完整msgInfo，
 *     精确定位userTV，解除单行约束+240dp宽度上限+NoMeasuredTextView字段hack。
 *  2. 全局TextView.onAttachedToWindow hook（联系人页/会话列表）：
 *     单行+字号≥12sp即放开为两行；12sp阈值覆盖会话列表标题(~15sp)与底部标签(~10sp)。
 *     短文本不超一行永远不换行，无副作用。
 *  3. ActivityLifecycleCallbacks + decorView清扫（存量视图即时生效）：
 *     开启功能后无需重启微信，返回任意页面即生效。
 */
@Feature(
    name = "长昵称双行显示",
    categories = ["聊天", "联系人与群组"],
    description = "聊天页群成员昵称、会话列表与通讯录联系人昵称过长时双行显示, 不再单行截断; 同时解除群昵称 240dp 宽度限制"
)
object TwoLineNicknames : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "TwoLineNicknames"

    /**
     * 昵称/标题档字号阈值（px）。
     * 8.0.76实测: 底部标签~10sp, 会话摘要~13sp, 会话标题~15sp, 联系人昵称~17sp。
     * 取12sp覆盖全部标题档并排除摘要档（与Themes主/次文本13sp分界对齐）。
     */
    private val titleTextSizeThresholdPx: Float
        get() = 12f * HostInfo.application.resources.displayMetrics.density

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

        // userTV 位于消息视图 tag 持有的 ViewHolder
        val textView = runCatching {
            view.tag.reflekt()
                .firstFieldOrNull { name = "userTV"; superclass() }?.get() as? TextView
        }.getOrNull()

        if (textView == null) {
            WeLogger.d(TAG, "chat msg bound but no userTV found")
            return
        }

        applyTwoLine(textView, removeWidthCap = true)
        WeLogger.d(TAG, "chat userTV two-line applied: \"${textView.text?.take(20)}\"")
    }

    // ── 全局主力：TextView attach hook ─────────────────────────────────

    private fun installGlobalAttachHook() {
        runCatching {
            TextView::class.reflekt()
                .firstMethod { name = "onAttachedToWindow" }
                .hookAfter {
                    val tv = thisObject as? TextView ?: return@hookAfter
                    if (isTitleLikeSingleLine(tv)) {
                        applyTwoLine(tv, removeWidthCap = false)
                        WeLogger.d(TAG, "attach applied: \"${tv.text?.take(20)}\" size=${tv.textSize}px")
                    }
                }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook TextView.onAttachedToWindow", it)
            return
        }
        WeLogger.i(TAG, "global TextView attach hook installed (threshold 12sp)")
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
                WeLogger.d(TAG, "sweep on ${activity.javaClass.simpleName}: applied $applied")
            }
        }
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        installGlobalAttachHook()

        // 监听页面切换, 每次 onResume 清扫存量 —— 开关打开后无需重启微信
        runCatching {
            HostInfo.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
            lifecycleRegistered = true
        }.onFailure {
            WeLogger.w(TAG, "failed to register lifecycle callbacks", it)
        }
        WeLogger.i(TAG, "enabled: attach hook + lifecycle sweep + chat listener")
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
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
        // 标准 TextView：解除单行约束 → 显式设两行
        tv.setSingleLine(false)
        tv.maxLines = 2
        tv.ellipsize = TextUtils.TruncateAt.END

        // NoMeasuredTextView 会忽略 maxLines，直接反射写私有字段 mMaxMode=2
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                val mMaxMode = tv.javaClass.getDeclaredField("mMaxMode").apply { isAccessible = true }
                val mMaximum = tv.javaClass.getDeclaredField("mMaximum").apply { isAccessible = true }
                mMaxMode.setInt(tv, 2)       // 2 == MAX_LINES
                mMaximum.setInt(tv, 2)
            }
        }

        // 聊天页 userTV 的共享样式带 240dp 硬上限, 解除后由父布局约束实际宽度
        if (removeWidthCap) tv.maxWidth = Int.MAX_VALUE
        // 修改后强制重新布局 + 重绘, 确保已显示的行立即生效
        tv.requestLayout()
        tv.invalidate()
    }
}
