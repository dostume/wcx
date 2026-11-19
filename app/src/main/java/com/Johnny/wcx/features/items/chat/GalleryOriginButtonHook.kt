package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.ui.utils.allViews
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.Collections
import java.util.WeakHashMap

/**
 * 打开图片 / 视频查看页时**无感点击**「查看原图 / 查看原视频」按钮。
 *
 * 与「后台自动加载原图/原视频」([AutoPrefetchOriginalMedia]) 是同一诉求的两半:
 *  - 后台功能负责**提前把原图/原视频拉到本地** (不点开也拉);
 *  - 本 hook 负责**页面兜底**: 后台没拉及时 (或漏项) 时, 用户打开页面这一下
 *    把按钮在帧级拦掉并点掉 —— 用户既看不到按钮, 点开也已是原画质。
 *
 * 实现要点 (微信 8.0.76.3141 实测):
 *  - `ImageGalleryUI` 未被混淆, onCreate / onResume 用 reflekt 定位, **不依赖 dex 适配**;
 *  - 按钮运行时是 `android.widget.Button`, 文案带体积后缀 (「查看原视频 64MB」),
 *    只能按渲染文本 + 关键字定位;
 *  - 点击「查看原图 / 查看原视频」不会弹网络确认框, 静默点击不打断用户;
 *  - OnPreDrawListener 帧级拦截: 命中后**先 GONE 再 performClick**, 按钮一帧都不会
 *    被画出来; 已命中的控件持续压制 (微信下载中会改文案再显示按钮)。
 */
object GalleryOriginButtonHook {

    private const val TAG = "GalleryOriginBtn"

    /** 聊天图片 / 视频查看页 (8.0.76 未被混淆)。 */
    private const val GALLERY_UI_CLASS = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"

    /** 按钮文案关键字 (中英双语兜底)。 */
    private val KEYWORDS = listOf(
        "查看原图", "查看原视频", "看原视频",
        "View Full Video", "Full Image", "Original quality",
    )

    private const val INITIAL_DELAY_MS = 60L

    /** 兜底轮询间隔。正常路径由 OnPreDrawListener 逐帧驱动, 这条只是防「页面静止不重绘」。 */
    private const val FALLBACK_INTERVAL_MS = 500L

    /**
     * 单次起观察后的最长时长。图片/视频页内左右滑动**不会**重走 onResume,
     * 窗口要足够长以覆盖「打开页面后过一阵才划到视频」的情况; onResume 会重新续期。
     */
    private const val WATCH_TIMEOUT_MS = 300_000L

    /** Activity -> 页面观察器。Activity 销毁 / 超时后移除。 */
    private val watchers: MutableMap<Activity, PageWatcher> =
        Collections.synchronizedMap(WeakHashMap())

    /** 是否已安装 (避免重复 hook 生命周期)。 */
    private var installed = false

    /**
     * 引用计数 —— 「自动查看原图」和「后台自动加载原图/原视频」两个功能都会用到本 hook,
     * 任一功能关闭时不能把另一个正在用的 hook 拆掉。
     */
    private var refCount = 0

    // ─────────────────────────────── 安装 / 卸载 ───────────────────────────────

    /**
     * 安装页面兜底点击。
     *
     * @param host 宿主功能 —— `hookAfter` / `thisObject` 是 [BaseFeature] 的成员扩展,
     *   hook 也登记在它身上 (功能禁用时随 BaseFeature 的 unhook 机制一起清理)。
     */
    @Synchronized
    fun install(host: BaseFeature) {
        if (refCount > 0) {
            refCount++
            return
        }
        installGalleryLifecycleHook(host)
        installed = true
        refCount = 1
    }

    @Synchronized
    fun uninstall() {
        if (refCount <= 0) return
        refCount--
        if (refCount > 0) return
        installed = false
        val snapshot = synchronized(watchers) { watchers.values.toList() }
        snapshot.forEach { stop(it) }
        watchers.clear()
    }

    // ─────────────────────────────── 触发入口 ───────────────────────────────

    private fun installGalleryLifecycleHook(host: BaseFeature) {
        val clazz = runCatching { GALLERY_UI_CLASS.toClass() }.getOrNull()
        if (clazz == null) {
            WeLogger.w(TAG, "未找到 $GALLERY_UI_CLASS, 打开页面时不会自动点原图/原视频按钮")
            return
        }

        var hooked = 0
        listOf("onCreate" to 1, "onResume" to 0).forEach { (methodName, paramCount) ->
            val method = runCatching {
                clazz.reflekt().firstMethod { name = methodName; parameterCount = paramCount }
            }.getOrNull() ?: runCatching {
                clazz.reflekt().firstMethod {
                    name = methodName
                    parameterCount = paramCount
                    superclass()
                }
            }.getOrNull() ?: return@forEach

            with(host) {
                method.hookAfter {
                    // 命中父类实现时会收到全部 Activity 的回调, 需按类名过滤
                    val activity = thisObject as? Activity ?: return@hookAfter
                    if (activity.javaClass.name != GALLERY_UI_CLASS) return@hookAfter
                    armWatcher(activity)
                }
            }
            hooked++
        }
        if (hooked > 0) {
            WeLogger.i(TAG, "已 hook $GALLERY_UI_CLASS 的 $hooked 个生命周期方法")
        } else {
            WeLogger.w(TAG, "未能 hook $GALLERY_UI_CLASS 的生命周期方法, 打开页面时不会自动点按钮")
        }
    }

    // ─────────────────────────────── 页面观察 ───────────────────────────────

    /** 起 (或续期) 页面观察器; 同一 Activity 只跑一个。 */
    private fun armWatcher(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return

        synchronized(watchers) {
            val existing = watchers[activity]
            if (existing != null) {
                existing.deadline = SystemClock.uptimeMillis() + WATCH_TIMEOUT_MS
                return
            }
        }

        val watcher = PageWatcher(activity, decor)
        val observer = decor.viewTreeObserver
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            processFrame(watcher)
            true
        }
        val fallback = object : Runnable {
            override fun run() {
                if (processFrame(watcher)) {
                    decor.postDelayed(this, FALLBACK_INTERVAL_MS)
                }
            }
        }

        watcher.observer = observer
        watcher.preDraw = preDraw
        watcher.fallback = fallback
        watchers[activity] = watcher

        // 逐帧驱动: 按钮在被绘出之前就被拦下 —— 这是「无感」的关键
        observer.addOnPreDrawListener(preDraw)
        decor.postDelayed(fallback, INITIAL_DELAY_MS)
        WeLogger.i(TAG, "开始观察图片/视频页 (${activity.javaClass.simpleName})")
    }

    /**
     * 处理一帧 / 一次兜底轮询。
     * @return 是否继续 (false 表示观察已结束)
     */
    private fun processFrame(watcher: PageWatcher): Boolean {
        if (!isAlive(watcher)) {
            stop(watcher)
            return false
        }
        return try {
            suppressOriginButtons(watcher)
            true
        } catch (t: Throwable) {
            WeLogger.w(TAG, "原图/原视频观察异常: ${t.message}")
            true
        }
    }

    private fun isAlive(watcher: PageWatcher): Boolean {
        val activity = watcher.activity
        if (activity.isFinishing || activity.isDestroyed) return false
        return SystemClock.uptimeMillis() < watcher.deadline
    }

    private fun stop(watcher: PageWatcher) {
        watchers.remove(watcher.activity)
        runCatching {
            watcher.preDraw?.let { watcher.observer?.removeOnPreDrawListener(it) }
        }
        runCatching {
            watcher.fallback?.let { watcher.decor.removeCallbacks(it) }
        }
        watcher.suppressed.clear()
        watcher.clicked.clear()
    }

    /**
     * 在视图树里找「查看原图 / 查看原视频」按钮:
     *  - 命中且首次发现 -> **先隐藏 (GONE) 再点击**, 按钮不会被绘制出来;
     *  - 命中但已点过 (文案不同 -> 换到下一个视频) -> 隐藏后再次点击;
     *  - 曾经命中过的控件 (不论当前文案) -> 只要又显示出来就再压回 GONE。
     */
    private fun suppressOriginButtons(watcher: PageWatcher) {
        for (view in watcher.decor.allViews) {
            val textView = view as? TextView ?: continue
            val target = clickTarget(textView)
            val id = System.identityHashCode(target)
            val alreadySuppressed = id in watcher.suppressed

            // 曾经命中过的控件: 无条件保持隐藏 (微信下载中会改文案再显示)
            if (alreadySuppressed && target.visibility != View.GONE) {
                target.visibility = View.GONE
            }

            val text = textView.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue

            val keyword = KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) } ?: continue

            // 首次发现要求按钮是真的显示着 (避免误伤不可见区域里残留的文案)
            if (!alreadySuppressed && !textView.isShown) continue
            if (!alreadySuppressed && !target.isShown) continue

            // 先隐藏: preDraw 阶段设 GONE 后, 本帧 draw 就会跳过它 —— 用户看不到任何「查看原图」
            if (target.visibility != View.GONE) target.visibility = View.GONE
            watcher.suppressed.add(id)
            if (watcher.suppressed.size > 128) watcher.suppressed.clear()

            val key = "$id|$text"
            if (!watcher.clicked.add(key)) continue
            if (watcher.clicked.size > 256) watcher.clicked.clear()

            target.performClick()
            WeLogger.i(TAG, "无感加载「$keyword」: $text (${textView.javaClass.simpleName})")
        }
    }

    /** 按钮本体可能不可点击 (文字是子 TextView), 向上找最近的可点击祖先。 */
    private fun clickTarget(view: View): View {
        var current: View? = view
        var depth = 0
        while (current != null && depth <= 3) {
            if (current.isClickable) return current
            current = current.parent as? View
            depth++
        }
        return view
    }

    /**
     * 单个图片/视频页的观察器。
     *
     * [suppressed] 记录**曾经命中过关键字**的控件 (按身份): 之后微信把它们重新显示
     * (下载中会改文案再 setVisibility) 时无条件压回 GONE, 保证用户始终看不到这个按钮。
     * [clicked] 按「控件身份 + 文案」去重: 同一个控件换到下一个视频时文案会变
     * (设备日志: 64MB -> 19MB -> 2MB 各自触发过一次点击), 必须允许再次点击。
     */
    private class PageWatcher(val activity: Activity, val decor: ViewGroup) {
        val suppressed: MutableSet<Int> = Collections.synchronizedSet(HashSet())
        val clicked: MutableSet<String> = Collections.synchronizedSet(HashSet())

        @Volatile
        var deadline: Long = SystemClock.uptimeMillis() + WATCH_TIMEOUT_MS

        var observer: ViewTreeObserver? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var fallback: Runnable? = null
    }
}
