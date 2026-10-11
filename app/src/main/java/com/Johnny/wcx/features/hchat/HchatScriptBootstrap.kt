package com.Johnny.wcx.features.hchat

import com.Johnny.wcx.loader.entry.xp51.Xp51HookEntry
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.dexkit.DexFinder
import h.Hchat.event.EventBus
import h.Hchat.hooks.api.core.WechatApiFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.script.ScriptPluginFeature
import h.Hchat.preferences.ConfigStore
import h.Hchat.ui.UIRegistry
import org.luckypray.dexkit.DexKitBridge

/**
 * Hchat 脚本子系统在 WCX 中的引导层。
 *
 * Hchat 原项目的功能模块由 `h.Hchat.hooks.core.FeatureRegistry` 统一注册、由
 * `FeatureManager` 统一安装，但其中包含大量与脚本无关的功能（防撤回、朋友圈、红包等）。
 * WCX 只需要其中的「脚本插件」能力，因此这里不复用 Hchat 的全量 FeatureRegistry，
 * 而是按脚本功能的最小闭包，手动装配 [FeatureContext] 并安装两项必需 Feature：
 *
 * 1. [WechatApiFeature] —— 初始化公共微信 API（`WeChatApis`）。脚本运行时依赖它读取
 *    联系人数据库（rcontact/chatroom）、会话列表等；不安装它，脚本总开关会一直提示
 *    「微信联系人数据库尚未就绪」。
 * 2. [ScriptPluginFeature] —— 脚本插件运行时本体（插件加载、菜单/消息 Hook 等）。
 *
 * 关键点：安装完必须调用 [DexInstallScheduler.markDexBridgeReady]，否则 BRIDGE 阶段
 * 的延迟任务（含微信 API 的 `resolveAll()` 预热）永远不会执行，数据库始终不可用。
 * 这一步在原项目中由 `FeatureManager.installAll` 尾部完成。
 */
object HchatScriptBootstrap {

    private const val TAG = "HchatScriptBootstrap"

    @Volatile
    private var installed = false

    /** 持有 DexKitBridge 强引用，避免被回收；该实例独立于 WCX 的懒加载/idle 机制，长期存活供脚本运行时使用。 */
    @Volatile
    private var dexKitBridgeRef: DexKitBridge? = null

    @Synchronized
    fun install() {
        if (installed) return
        installed = true

        runCatching {
            val hostContext = HostInfo.application
            val hostClassLoader = ClassLoaders.HOST
            val lpparam = Xp51HookEntry.getLoadPackageParam()

            val dexKitBridge = DexKitBridge.create(HostInfo.appInfo.sourceDir)
            dexKitBridgeRef = dexKitBridge

            val dexFinder = DexFinder(dexKitBridge, hostClassLoader, hostContext)
            val dexBridgeHolder = DexBridgeHolder(
                dexKitBridge,
                dexFinder,
                hostClassLoader,
                lpparam.appInfo.sourceDir
            )

            // moduleContext：Hchat 原本通过 createPackageContext(模块包名) 获取，用于读取模块资源。
            // WCX 的脚本资源已随模块 ClassLoader 一起加载，这里直接用宿主 Context 兜底即可。
            val context = FeatureContext(
                hostContext,
                hostContext,
                hostClassLoader,
                lpparam,
                dexKitBridge,
                dexFinder,
                EventBus.get(),
                ConfigStore(hostContext),
                dexBridgeHolder,
                UIRegistry.get()
            )

            runCatching { WechatApiFeature().install(context) }
                .onFailure { WeLogger.e(TAG, "WechatApiFeature 安装失败", it) }
            runCatching { ScriptPluginFeature().install(context) }
                .onFailure { WeLogger.e(TAG, "ScriptPluginFeature 安装失败", it) }

            // 关键：推进调度器到 BRIDGE 阶段，触发 WechatApiFeature 注册的 warmup 任务
            // （resolveAll + 数据库 API 解析），数据库才会就绪。无论前面是否局部失败都要执行。
            runCatching { DexInstallScheduler.markDexBridgeReady() }
                .onFailure { WeLogger.e(TAG, "markDexBridgeReady 失败", it) }

            WeLogger.i(TAG, "Hchat 脚本子系统已安装（WechatApiFeature + ScriptPluginFeature + markDexBridgeReady）")
        }.onFailure {
            WeLogger.e(TAG, "Hchat 脚本子系统安装失败", it)
        }
    }
}
