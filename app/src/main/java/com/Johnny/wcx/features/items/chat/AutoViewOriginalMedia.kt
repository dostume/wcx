package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

/**
 * 打开图片 / 视频时**自动加载原图 / 原视频**。
 *
 * 实现由 [GalleryOriginButtonHook] 承担: 它 hook `ImageGalleryUI` 的 onCreate / onResume,
 * 在视图树里逐帧拦截「查看原图 / 查看原视频」按钮 —— 命中后**先 GONE 再 performClick**,
 * 按钮一帧都不会被画出来, 用户点开图片/视频时已是原画质。
 *
 * 为什么不走 dex 锚点:
 *  - `ImageGalleryUI` 在 8.0.76 未被混淆, 用反射直接定位生命周期方法, **不依赖 dex 适配**;
 *  - 按钮运行时是 `android.widget.Button`, 文案带体积后缀 (「查看原视频 64MB」),
 *    只能按渲染文本 + 关键字定位, 没有稳定的 dex 锚点;
 *  - 原实现只在 `setHdImageActionDownloadable` / `checkNeedShowOriginVideoBtn` 这两个
 *    dex 锚点上挂 after 钩子, 命中时机不可控 (按钮可能还没创建), 实测经常点不到。
 *
 * 与「后台自动加载原图/原视频」([AutoPrefetchOriginalMedia]) 的分工:
 * 本功能是**你点开时才**动作; 后台功能是**消息一落库就**提前拉。后台没拉及时,
 * 由本功能在页面上兜底。
 */
@Feature(
    name = "自动查看原图",
    categories = ["聊天"],
    description = "打开图片和视频时自动点击查看原图 / 查看原视频 (按钮无感隐藏, 打开即原画质)"
)
object AutoViewOriginalMedia : SwitchFeature() {

    private const val TAG = "AutoViewOriginalMedia"

    override fun onEnable() {
        GalleryOriginButtonHook.install(this)
        WeLogger.i(TAG, "enabled: 打开图片/视频页时将无感点击「查看原图 / 查看原视频」")
    }

    override fun onDisable() {
        GalleryOriginButtonHook.uninstall()
        WeLogger.i(TAG, "disabled: 页面兜底点击已卸载")
    }
}
