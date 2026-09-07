package com.Johnny.wcx.activity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Open_in_new
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Info
import com.composables.icons.materialsymbols.outlinedfilled.More_vert
import com.composables.icons.materialsymbols.outlinedfilled.Warning
import com.topjohnwu.superuser.Shell
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.WeChatVersions
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.IconButton
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.GitHubIcon
import com.Johnny.wcx.ui.utils.theme.darkScheme
import com.Johnny.wcx.ui.utils.theme.lightScheme
import com.Johnny.wcx.utils.android.androidUserId
import com.Johnny.wcx.utils.android.getEnabled
import com.Johnny.wcx.utils.android.runOnUiThread
import com.Johnny.wcx.utils.android.setEnabled
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.formatEpoch
import com.Johnny.wcx.utils.hook_status.HookStatus
import com.Johnny.wcx.utils.openInSystem
import com.Johnny.wcx.utils.registerBshSnapshotDecompileLaunchers
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private var isLaunchingWeChat = false

    override fun onStop() {
        super.onStop()
        if (isLaunchingWeChat) {
            isLaunchingWeChat = false
            finishAndRemoveTask()
        }
    }

    // 不同 root 方案 (Magisk/KernelSU/APatch) 的 su 挂载路径不同,
    // 部分 ROM 还会裁剪应用进程的 PATH, 导致裸 "su" 直接抛 IOException,
    // 因此按常见挂载点逐一尝试, 全部失败再交给 libsu 自动探测兜底
    private val SU_CANDIDATES = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    /**
     * 依次尝试各候选路径拉起 su 执行命令。
     * @param suArgs 传给 su 本身的参数 (如 -c/-mm 及其命令串), 不含 su 路径
     * @param libsuCmd 所有 su 候选路径都无法启动时, 交给 libsu 执行的命令
     * @return (退出码, 输出), 所有通道都拉不起 su 时返回 null
     */
    private fun execSu(suArgs: List<String>, libsuCmd: String): Pair<Int, String>? {
        for (su in SU_CANDIDATES) {
            try {
                val proc = ProcessBuilder(su, *suArgs.toTypedArray())
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                return proc.waitFor() to output.trim()
            } catch (_: IOException) {
                // 该路径不存在或不可执行, 继续尝试下一个候选
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return runCatching {
            val result = Shell.cmd(libsuCmd).exec()
            result.code to (result.out + result.err).joinToString("\n").trim()
        }.getOrNull()
    }

    @Composable
    private fun ModuleTheme(
        darkTheme: Boolean = isSystemInDarkTheme(),
        content: @Composable () -> Unit
    ) {
        val colorScheme = if (darkTheme) darkScheme else lightScheme
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }

    private val selectFileLauncher = registerBshSnapshotDecompileLaunchers()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
            runCatching { HookStatus.init(this) }
        }

        Shell.getShell()

        setContent {
            ModuleTheme {
                AppContent(
                    selectFileLauncher,
                    onUrlClick = { url ->
                        url.toUri().openInSystem(this, true)
                    }
                )
            }
        }
    }

    private data class ActivationState(
        val isActivated: Boolean,
        val title: String,
        val desc: String,
        val color: Color
    )

    @Composable
    private fun AppContent(resultLauncher: ActivityResultLauncher<String>, onUrlClick: (String) -> Unit) {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

        var showMenu by remember { mutableStateOf(false) }
        var showAboutDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteTinkerDialog by remember { mutableStateOf(false) }
        var showConfirmDeleteModuleDataDialog by remember { mutableStateOf(false) }
        var showNoRootDialog by remember { mutableStateOf(false) }
        var showModifyHostPkgNameDialog by remember { mutableStateOf(false) }
        var shortcutError by remember { mutableStateOf<String?>(null) }

        var isLauncherIconEnabled by remember {
            mutableStateOf(
                ComponentName(
                    this,
                    "${PackageNames.MODULE}.activity.MainActivityAlias"
                ).getEnabled(this)
            )
        }

        @Composable
        fun rememberActivationState(): ActivationState {
            val xposedService by HookStatus.xposedService.collectAsState()
            val isHookEnabled = remember(xposedService) {
                HookStatus.isActivated(this)
            }
            val service = xposedService

            return remember(isHookEnabled, service) {
                ActivationState(
                    isActivated = isHookEnabled,
                    title = if (isHookEnabled) "已激活" else "未激活",
                    desc = if (service != null) {
                        "${service.frameworkName} ${service.frameworkVersion} " +
                                "(${service.frameworkVersionCode}), API ${service.apiVersion}"
                    } else if (isHookEnabled) {
                        "Xposed 框架已注入（分身环境，状态检测受限）"
                    } else {
                        "未检测到 Xposed 框架, 请确认已在管理器中启用模块并勾选微信"
                    },
                    color = if (isHookEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = BuildConfig.TAG,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                            .copy(alpha = 0.9f)
                    ),
                    actions = {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                MaterialSymbols.OutlinedFilled.More_vert,
                                contentDescription = "Menu"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("设置宿主包名") },
                                onClick = {
                                    showMenu = false
                                    showModifyHostPkgNameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("修复模块加载") },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteTinkerDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清除模块数据") },
                                onClick = {
                                    showMenu = false
                                    showConfirmDeleteModuleDataDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("反编译 BeanShell 快照") },
                                onClick = {
                                    showMenu = false
                                    resultLauncher.launch("*/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isLauncherIconEnabled) "隐藏桌面图标" else "显示桌面图标") },
                                onClick = {
                                    showMenu = false
                                    val componentName = ComponentName(
                                        this@MainActivity,
                                        "${PackageNames.MODULE}.activity.MainActivityAlias"
                                    )
                                    val newState = !isLauncherIconEnabled
                                    componentName.setEnabled(this@MainActivity, newState)
                                    isLauncherIconEnabled = newState
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("关于") },
                                onClick = {
                                    showMenu = false
                                    showAboutDialog = true
                                }
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(top = 16.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // The activation card relies on the libxposed service, which is only
                // bound when the module ships the libxposed entry point (standard flavor).
                if (BuildConfig.HAS_LIBXPOSED_ENTRY) {
                    val activationState = rememberActivationState()
                    Card(
                        colors = CardDefaults.cardColors(containerColor = activationState.color),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(16.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (activationState.isActivated) MaterialSymbols.OutlinedFilled.Check_circle else MaterialSymbols.OutlinedFilled.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = activationState.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = activationState.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                MaterialSymbols.OutlinedFilled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("构建信息", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        InfoItem("构建提交哈希", BuildConfig.COMMIT_HASH.ifEmpty { "未知" })
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoItem(
                            "构建提交时间",
                            formatEpoch(BuildConfig.BUILD_TIMESTAMP, true)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoItem("已适配微信版本", getAdaptedWeChatVersions())
                        Spacer(modifier = Modifier.height(8.dp))
                        InfoItem("当前微信版本", getWeChatVersion())
                    }
                }

                ElevatedCard(
                    onClick = {
                        val userId = androidUserId
                        val hostPkg =
                            prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                        // 不用 Shell.isAppGrantedRoot() 的过期缓存, 每次点击直接实时拉起 su
                        thread {
                            val forceStopCmd =
                                "am force-stop --user $userId $hostPkg && " +
                                        "am start --user $userId -n $hostPkg/${PackageNames.WECHAT}.ui.LauncherUI"
                            val outcome = execSu(listOf("-c", forceStopCmd), forceStopCmd)

                            runOnUiThread {
                                when {
                                    outcome == null ->
                                        shortcutError = "无法拉起 su: 本应用可能被 root 管理器的" +
                                                "排除名单(DenyList)/隐藏应用屏蔽, 或设备未 root。请检查后重试"
                                    outcome.first == 0 ->
                                        finishAndRemoveTask()
                                    outcome.second.isEmpty() ||
                                            outcome.second.contains("denied", ignoreCase = true) ||
                                            outcome.second.contains("not found", ignoreCase = true) ->
                                        showNoRootDialog = true
                                    else ->
                                        shortcutError = outcome.second
                                            .ifBlank { "无法启动微信 (包名: $hostPkg)" }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Open_in_new,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "打开微信",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "一键强行停止并启动微信",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                ElevatedCard(
                    onClick = {
                        val hostPkg =
                            prefs.getString("host_pkg_name", PackageNames.WECHAT)!!
                        try {
                            startActivity(com.Johnny.wcx.utils.android.Intent {
                                setClassName(hostPkg, "${PackageNames.WECHAT}.ui.LauncherUI")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(BuildConfig.TAG, "1")
                            })
                            isLaunchingWeChat = true
                        } catch (e: Exception) {
                            shortcutError = e.message
                                ?: "无法打开微信 (包名: $hostPkg)"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "打开模块设置",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "打开微信内的模块设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showModifyHostPkgNameDialog) {
                    var pkgName by remember {
                        mutableStateOf(
                            prefs.getString(
                                "host_pkg_name",
                                PackageNames.WECHAT
                            )!!
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showModifyHostPkgNameDialog = false },
                        title = { Text("设置宿主包名") },
                        text = {
                            DefaultColumn {
                                Text("本选项控制模块应用首页三个快捷方式打开的目标微信包名, 适用于通过修改包名而非多用户实现多微信共存的用户")

                                TextField(
                                    label = { Text("宿主包名 (默认为 com.tencent.mm)") },
                                    value = pkgName,
                                    onValueChange = { pkgName = it }
                                )
                            }

                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showModifyHostPkgNameDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showModifyHostPkgNameDialog = false
                                prefs.edit {
                                    putString("host_pkg_name", pkgName)
                                }
                            }) { Text("确定") }
                        })
                }

                if (showConfirmDeleteTinkerDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/tinker",
                            "/data/data/${PackageNames.WECHAT}/tinker_server",
                            "/data/data/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/0/${PackageNames.WECHAT}/tinker",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/0/${PackageNames.WECHAT}/tinker_temp",
                            "/data/user/999/${PackageNames.WECHAT}/tinker",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_server",
                            "/data/user/999/${PackageNames.WECHAT}/tinker_temp"
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteTinkerDialog = false },
                        title = { Text("修复模块加载") },
                        text = {
                            Text(
                                "本操作将尝试修复微信热更新导致的模块不加载, 执行后请重启微信\n" +
                                        "将清空以下路径的内容, 并将其权限递归设置为 000 以阻止微信重读写, 请确认无误后再继续!\n${
                                            paths.joinToString("\n") { "- $it" }
                                        }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteTinkerDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteTinkerDialog = false
                                // 不用 isAppGrantedRoot() 的过期缓存, 直接拉起 su 实时判定
                                thread {
                                    val failed = paths.mapNotNull { path ->
                                        val cmd =
                                            "if [ -d '$path' ]; then " +
                                                    "find '$path' -mindepth 1 -exec rm -rf {} + ; " +
                                                    "chmod -R 000 '$path' ; " +
                                                    "fi"
                                        when (val r = execSu(listOf("-mm", "-c", cmd), cmd)) {
                                            null -> path to "无法拉起 su (可能被 root 管理器排除名单屏蔽或未 root)"
                                            else -> if (r.first == 0) null else path to r.second
                                        }
                                    }
                                    runOnUiThread {
                                        when {
                                            failed.isEmpty() ->
                                                showToast(this@MainActivity, "操作成功!")
                                            failed.any { (_, output) ->
                                                output.isEmpty() ||
                                                        output.contains("denied", ignoreCase = true) ||
                                                        output.contains("not found", ignoreCase = true)
                                            } -> showNoRootDialog = true
                                            else ->
                                                shortcutError = failed.joinToString("\n") { (path, output) ->
                                                    "$path: $output"
                                                }
                                        }
                                    }
                                }
                            }) { Text("确定") }
                        })
                }

                if (showConfirmDeleteModuleDataDialog) {
                    val paths = remember {
                        @Suppress("SdCardPath")
                        listOf(
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs",
                            "/data/data/${PackageNames.WECHAT}/files/mmkv/wekit_prefs.crc",
                        )
                    }

                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteModuleDataDialog = false },
                        title = { Text("清除模块数据") },
                        text = {
                            Text(
                                "本操作将删除模块的功能配置, 用于解决部分功能导致微信无限闪退, 删除后请重启微信\n将删除以下路径的文件, 请确认无误后再删除!\n${
                                    paths.joinToString("\n") { "- $it" }
                                }"
                            )
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                            }) { Text("取消") }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showConfirmDeleteModuleDataDialog = false
                                // 不用 isAppGrantedRoot() 的过期缓存, 直接拉起 su 实时判定
                                thread {
                                    val failed = paths.mapNotNull { path ->
                                        // if using Shell.cmd or su -c without -mm, the view of /data/user/0 is restricted
                                        when (val r = execSu(listOf("-mm", "-c", "rm -rf $path"), "rm -rf $path")) {
                                            null -> path to "无法拉起 su (可能被 root 管理器排除名单屏蔽或未 root)"
                                            else -> if (r.first == 0) null else path to r.second
                                        }
                                    }
                                    runOnUiThread {
                                        when {
                                            failed.isEmpty() ->
                                                showToast(this@MainActivity, "删除成功!")
                                            failed.any { (_, output) ->
                                                output.isEmpty() ||
                                                        output.contains("denied", ignoreCase = true) ||
                                                        output.contains("not found", ignoreCase = true)
                                            } -> showNoRootDialog = true
                                            else ->
                                                shortcutError = failed.joinToString("\n") { (path, output) ->
                                                    "$path: $output"
                                                }
                                        }
                                    }
                                }
                            }) { Text("确定") }
                        })
                }

                if (showNoRootDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoRootDialog = false },
                        title = { Text("未授予 Root 权限") },
                        text = { Text("请授予 Root 权限以执行此操作") },
                        confirmButton = {
                            Button(onClick = {
                                showNoRootDialog = false
                            }) { Text("确定") }
                        })
                }

                shortcutError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { shortcutError = null },
                        title = { Text("操作失败") },
                        text = { Text(error) },
                        confirmButton = {
                            Button(onClick = {
                                shortcutError = null
                            }) { Text("确定") }
                        })
                }

                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .alpha(0.1f),
                    color = MaterialTheme.colorScheme.onSurface
                )

                LinkCard(
                    icon = GitHubIcon,
                    title = "GitHub",
                    subtitle = "dostume/wcx",
                    onClick = { onUrlClick("https://github.com/dostume/wcx") }
                )
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = { Text(text = "关于") },
                    text = {
                        Column {
                            Text("${BuildConfig.TAG} 是一款基于 Xposed 框架的开源免费微信模块")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("版本: ${BuildConfig.VERSION_NAME}")
                            Text("版本号: ${BuildConfig.VERSION_CODE}")
                            Text("作者：dostume@github")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text("确定")
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun InfoItem(label: String, value: String) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    private fun getWeChatVersion(): String {
        return runCatching {
            val pm = packageManager ?: return@runCatching "未安装"
            val pkgInfo = pm.getPackageInfo(PackageNames.WECHAT, 0)
            val versionName = pkgInfo.versionName?.ifEmpty { null } ?: return@runCatching "版本未知"
            "v$versionName (${pkgInfo.longVersionCode})"
        }.getOrDefault("未安装")
    }

    private fun getAdaptedWeChatVersions(): String {
        return try {
            val versions = WeChatVersions::class.java.declaredFields
                .mapNotNull { field ->
                    val name = field.name
                    if (name.startsWith("MM_")) {
                        val parts = name.removePrefix("MM_").split("_")
                        if (parts.size >= 3) {
                            "${parts[0]}.${parts[1]}.${parts[2]}"
                        } else null
                    } else null
                }
                .sortedWith(compareBy(
                    { it.split(".").getOrNull(0)?.toIntOrNull() ?: 0 },
                    { it.split(".").getOrNull(1)?.toIntOrNull() ?: 0 },
                    { it.split(".").getOrNull(2)?.toIntOrNull() ?: 0 }
                ))
            if (versions.isNotEmpty()) {
                "完整支持 8.0.69 ~ 8.0.76 · 维护 8.0.65~8.0.68 · 低版本部分功能可能失效"
            } else {
                "推荐 8.0.69 ~ 8.0.76，8.0.65 以下部分功能可能失效"
            }
        } catch (e: Exception) {
            "推荐 8.0.69 ~ 8.0.76，8.0.65 以下部分功能可能失效"
        }
    }

    @Composable
    private fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
