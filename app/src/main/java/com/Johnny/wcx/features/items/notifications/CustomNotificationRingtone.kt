package com.Johnny.wcx.features.items.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Volume_off
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义通知铃声
 *
 * 实现原理（Android 通知渠道已创建后不允许改声音，因此不修改微信自带的渠道，也不走
 * 「拦截通知 + 模块自播音频」这种会与系统音量/免打扰/锁屏打架的方案）：
 *
 *  1. 由模块在微信进程内创建一组「微信渠道以外的私有通知渠道」（进程即微信 uid，
 *     渠道归微信所有，系统校验可通过）：
 *       - 每出现一个自定义铃声 URI 就惰性建一个渠道（channel id = wekit_ring_<uri哈希>，
 *         渠道创建后声音即固定——这正是系统限制所在，所以一个铃声一个渠道）；
 *       - 一个静音渠道（wekit_ring_silent，无声音无震动低打扰）。
 *  2. Hook 微信新消息通知构建的收尾点 Notification.Builder.build()，把命中规则的通知
 *     setChannelId() 改道到对应渠道。改道后系统仍按正常通知流程走：横幅/锁屏/点击/
 *     折叠/勿扰模式全部由系统接管，唯一变化是声音来自我们指定的渠道。
 *
 * 命中规则：先「指定对话/群」，再「关键词」（可限定只在该对话内）。消息内容来自
 * 通知 extras（与通知进化同一来源），关键词只匹配去掉群聊发送者前缀后的消息正文。
 */
@Feature(
    name = "自定义通知铃声",
    categories = ["通知"],
    description = "为指定个人/群的消息、或包含指定关键词的消息设置自定义通知铃声（可选静音），不干扰微信其它通知\n• 对话规则: 某个人或群来消息时用指定铃声\n• 关键词规则: 消息正文含关键词时用指定铃声（可限定仅在某对话内生效）\n• 通过私有通知渠道改道实现, 系统通知/横幅/锁屏/免打扰行为不受影响"
)
object CustomNotificationRingtone : ClickableFeature(), IResolveDex {

    private const val TAG = "CustomNotificationRingtone"

    // com.tencent.mm.booter.notification.x.d — 微信新消息通知入口 (同「通知进化」锚点)
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    private const val SILENT_CHANNEL = "wekit_ring_silent"
    private const val RING_CHANNEL_PREFIX = "wekit_ring_"

    private const val PREFS_RULES = "custom_ringtone_rules_v1"

    // 只 Hook 正常消息的 dealNotify 与 build 同一调用链 (同通知进化)
    override val shouldLoadInCurrentProcess
        get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_PUSH

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    enum class RuleKind {
        /** 指定个人/群 */
        CONVERSATION,

        /** 消息正文关键词 */
        KEYWORD,
    }

    @Serializable
    enum class RuleMode {
        RINGTONE,
        SILENT,
    }

    @Serializable
    data class RingtoneRule(
        val id: String,
        val kind: RuleKind = RuleKind.CONVERSATION,
        val target: String = "",          // 对话规则=wxid；关键词规则=关键词文本
        val targetName: String = "",      // 对话规则=对话昵称；关键词规则可不填
        // 关键词规则可限定仅在某个对话内生效；空=所有对话
        val scopeWxId: String = "",
        val scopeName: String = "",
        val mode: RuleMode = RuleMode.RINGTONE,
        val soundUri: String = "",
    ) : java.io.Serializable

    // 从通知里解析出的消息正文（去除群聊「发送者: 」前缀），供关键词匹配
    private val messageRegex = Regex("""^(\[\d+条])?(.+?)?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    // 使用AtomicReference确保线程安全，dealNotify和build()可能在不同线程调用
    private val currentTalker = java.util.concurrent.atomic.AtomicReference<String?>(null)

    // dealNotify 写入 talker 的时间戳：超过 TTL 就不再用于匹配，避免旧 talker 误伤无关通知
    private val currentTalkerStamp = java.util.concurrent.atomic.AtomicLong(0L)

    private const val TALKER_TTL_MS = 10_000L

    /** 取出仍然"新鲜"的 talker；过期返回 null（防止无关通知被改道） */
    private fun freshTalker(): String? {
        val talker = currentTalker.get() ?: return null
        if (System.currentTimeMillis() - currentTalkerStamp.get() > TALKER_TTL_MS) return null
        return talker
    }

    // ─── 规则存取（模块 App 与微信进程共用 MMKV，即时生效） ───

    var rules: List<RingtoneRule>
        get() {
            val raw = WePrefs.getString(PREFS_RULES).orEmpty()
            if (raw.isBlank()) return emptyList()
            return runCatching { json.decodeFromString<List<RingtoneRule>>(raw) }
                .onFailure { WeLogger.e(TAG, "parse rules failed", it) }
                .getOrDefault(emptyList())
        }
        set(value) {
            WePrefs.putString(
                PREFS_RULES,
                runCatching { json.encodeToString(value) }.getOrDefault("[]")
            )
        }

    fun addRule(rule: RingtoneRule) {
        rules = rules + rule
        grantRingtoneReadPermission(rule.soundUri)
    }

    fun updateRule(rule: RingtoneRule) {
        rules = rules.map { if (it.id == rule.id) rule else it }
        grantRingtoneReadPermission(rule.soundUri)
    }

    fun deleteRule(id: String) {
        rules = rules.filterNot { it.id == id }
    }

    // ─── Hook ────────────────────────────────────────────────────────────────

    override fun onEnable() {
        runCatching { ensureSilentChannel() }
            .onFailure { WeLogger.e(TAG, "create silent channel failed", it) }

        runCatching {
            methodDealNotify.hookBefore {
                val talker = args.getOrNull(1) as? String
                if (!talker.isNullOrBlank()) {
                    currentTalker.set(talker)
                    currentTalkerStamp.set(System.currentTimeMillis())
                    WeLogger.i(TAG, "dealNotify talker=$talker")
                }
            }
        }.onFailure { WeLogger.e(TAG, "hook dealNotify failed", it) }

        runCatching {
            hookNotificationBuildBefore()
        }.onFailure { WeLogger.e(TAG, "hook Notification.Builder.build failed", it) }

        runCatching {
            hookNotificationManagerNotify()
        }.onFailure { WeLogger.e(TAG, "hook NotificationManager notify failed", it) }

        WeLogger.i(TAG, "ringtone hooks ready (dealNotify talker + channel reroute, rules=${rules.size})")
    }

    // 诊断日志去重：同一关键现象只记一次，避免来一条消息刷一条
    private val diagLogged = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    private fun diagOnce(key: String, message: () -> String) {
        if (diagLogged.size > 512) diagLogged.clear()
        if (diagLogged.add(key)) WeLogger.w(TAG, message())
    }

    /**
     * Hook Notification.Builder.build()：命中规则后把通知**改道到模块私有渠道**。
     *
     * 关键：Android 8.0+ 只要通知指定了 channelId，声音一律由 NotificationChannel 决定，
     * Builder.setSound() 会被系统彻底忽略（这正是"自定义铃声不生效、还是系统提示音"的根因）。
     * 因此这里必须 setChannelId() 改道到带目标铃声的私有渠道；setSound() 仅作兜底。
     */
    private fun hookNotificationBuildBefore() {
        XposedBridge.hookAllMethods(
            Notification.Builder::class.java, "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val builder = param.thisObject as Notification.Builder
                        val convWxId = freshTalker() ?: return
                        val rule = matchRule(convWxId, builder) ?: run {
                            diagOnce("no_rule_$convWxId") { "no ringtone rule matched $convWxId, leaving as-is" }
                            return
                        }
                        val channel = applyRule(rule) { builder.setChannelId(it) }
                        // 兜底：即便系统忽略，也把 sound 写进去（部分 ROM / 无渠道场景仍会读到）
                        builder.setSound(
                            if (rule.mode == RuleMode.RINGTONE && rule.soundUri.isNotBlank()) {
                                Uri.parse(rule.soundUri)
                            } else {
                                null
                            }
                        )
                        WeLogger.i(TAG, "reroute(build) $convWxId -> channel=$channel mode=${rule.mode}")
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "apply ringtone override failed", e)
                    }
                }
            }
        )
    }

    /**
     * 兜底 Hook：部分微信版本不走 Notification.Builder.build()（直接构造 Notification 或走
     * buildForBundle），此时在真正投递前反射改写通知的渠道与声音。
     * Notification.sound 是 public 字段可直接赋值；mChannelId 需要反射（Android 8~15 同名）。
     */
    private fun hookNotificationManagerNotify() {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val notification = param.args.firstOrNull { it is Notification } as? Notification ?: return
                    val convWxId = freshTalker() ?: return
                    val rule = matchRule(convWxId, notification.extras) ?: return
                    val channel = applyRule(rule) { channelId -> setNotificationChannelId(notification, channelId) }
                    notification.sound = if (rule.mode == RuleMode.RINGTONE && rule.soundUri.isNotBlank()) {
                        Uri.parse(rule.soundUri)
                    } else {
                        null
                    }
                    WeLogger.i(TAG, "reroute(notify) $convWxId -> channel=$channel mode=${rule.mode}")
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "apply ringtone override on notify failed", e)
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(NotificationManager::class.java, "notify", callback) }
            .onFailure { WeLogger.e(TAG, "hook NotificationManager.notify failed", it) }
        runCatching { XposedBridge.hookAllMethods(NotificationManager::class.java, "notifyAsUser", callback) }
            .onFailure { WeLogger.e(TAG, "hook NotificationManager.notifyAsUser failed", it) }
    }

    private val notificationChannelIdField: java.lang.reflect.Field? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            Notification::class.java.getDeclaredField("mChannelId").apply { isAccessible = true }
        }.onFailure { WeLogger.w(TAG, "Notification.mChannelId not found: ${it.message}") }.getOrNull()
    }

    private fun setNotificationChannelId(notification: Notification, channelId: String) {
        val f = notificationChannelIdField ?: return
        f.set(notification, channelId)
    }

    /**
     * 按规则准备私有渠道并返回渠道 id；通过 setChannel 回调写回（Builder 或反射字段）。
     * @return 实际使用的渠道 id，失败返回 null
     */
    private fun applyRule(rule: RingtoneRule, setChannel: (String) -> Unit): String? {
        val channelId = when (rule.mode) {
            RuleMode.SILENT -> if (ensureSilentChannel()) SILENT_CHANNEL else null
            RuleMode.RINGTONE -> {
                if (rule.soundUri.isBlank()) null else ensureRingtoneChannel(rule.soundUri)
            }
        }
        // 渠道没建成就不能改道：指向不存在的渠道会导致通知被系统直接丢弃
        if (channelId.isNullOrBlank()) {
            WeLogger.w(TAG, "channel unavailable for rule ${rule.id}, skip reroute")
            return null
        }
        setChannel(channelId)
        return channelId
    }

    private fun matchRule(convWxId: String, builder: Notification.Builder): RingtoneRule? =
        matchRule(convWxId, builder.extras)

    private fun matchRule(convWxId: String, extras: android.os.Bundle?): RingtoneRule? {
        val allRules = rules
        if (allRules.isEmpty()) return null

        val rawText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val body = rawText?.let { text ->
            messageRegex.find(text)?.groupValues?.get(3)?.takeIf { it.isNotEmpty() } ?: text
        }.orEmpty()

        // ① 精确对话规则优先
        allRules.firstOrNull {
            it.kind == RuleKind.CONVERSATION && it.target == convWxId
        }?.let { return it }

        // ② 关键词规则：先限定本会话的，再全局的
        allRules.firstOrNull {
            it.kind == RuleKind.KEYWORD &&
                it.target.isNotBlank() &&
                it.scopeWxId == convWxId &&
                body.contains(it.target)
        }?.let { return it }
        allRules.firstOrNull {
            it.kind == RuleKind.KEYWORD &&
                it.target.isNotBlank() &&
                it.scopeWxId.isEmpty() &&
                body.contains(it.target)
        }?.let { return it }

        return null
    }

    // ─── 私有通知渠道 ─────────────────────────────────────────────────────────

    /** @return 渠道是否可用（不可用则不能改道，否则通知会被系统丢弃） */
    private fun ensureSilentChannel(): Boolean {
        return runCatching {
            val nm = HostInfo.application.getSystemService<NotificationManager>()
            if (nm.getNotificationChannel(SILENT_CHANNEL) != null) return@runCatching true
            val ch = NotificationChannel(
                SILENT_CHANNEL, "WCX 静音通知", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = longArrayOf()
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
            true
        }.onFailure { WeLogger.e(TAG, "create silent channel failed", it) }.getOrDefault(false)
    }

    /** 每个自定义铃声 URI 对应一个固定渠道 id；渠道首次创建后声音不再变化（系统限制），
     *  换铃声即换渠道 id，从而在不动微信原渠道的前提下实现任意铃声切换。
     *  @return 渠道 id；创建失败返回 null（调用方应放弃改道） */
    private fun ensureRingtoneChannel(soundUri: String): String? {
        val id = "$RING_CHANNEL_PREFIX${soundUri.hashCode().and(0x7fffffff)}"
        val uri = Uri.parse(soundUri)
        return runCatching {
            val nm = HostInfo.application.getSystemService<NotificationManager>()
            val existing = nm.getNotificationChannel(id)
            if (existing != null) {
                // 渠道已存在且声音一致 → 直接复用
                if (existing.sound == uri) return@runCatching id
                // 声音变了（铃声文件被同名覆盖）：系统不允许更新已建渠道的声音，只能删了重建
                runCatching { nm.deleteNotificationChannel(id) }
                    .onFailure { WeLogger.w(TAG, "delete stale ringtone channel $id failed: ${it.message}") }
            }
            val ch = NotificationChannel(id, ringtoneChannelName(soundUri), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                    setShowBadge(true)
                }
            nm.createNotificationChannel(ch)
            id
        }.onFailure { WeLogger.e(TAG, "create ringtone channel failed: $id", it) }.getOrNull()
    }

    /** 把铃声 URI 的读权限授予微信进程，尽量保证系统播放端能访问到该音频。 */
    private fun grantRingtoneReadPermission(soundUri: String) {
        if (soundUri.isBlank()) return
        runCatching {
            val ctx = HostInfo.application
                val uri = Uri.parse(soundUri)
                if (uri.scheme == "content") {
                    // 宿主进程（微信）与当前进程都授予，尽量保证系统播放端能读到该音频
                    setOf("com.tencent.mm", HostInfo.packageName).forEach { pkg ->
                        runCatching {
                            ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
        }.onFailure { WeLogger.w(TAG, "grant uri permission failed: ${it.message}") }
    }

    private fun ringtoneChannelName(soundUri: String): String {
        val ctx = HostInfo.application
        return runCatching {
            RingtoneManager.getRingtone(ctx, Uri.parse(soundUri))?.getTitle(ctx)
        }.getOrNull() ?: "WCX 自定义铃声"
    }

    /** 解析铃声 URI 的可读标题；必须带 application context（null 会抛异常被吞而显示"未选择"）。 */
    fun ringtoneTitle(soundUri: String): String? {
        if (soundUri.isBlank()) return null
        val ctx = HostInfo.application
        return runCatching {
            RingtoneManager.getRingtone(ctx, Uri.parse(soundUri))?.getTitle(ctx)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ─── 设置界面 ─────────────────────────────────────────────────────────────

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            RulesDialog(onDismiss = onDismiss)
        }
    }
}

@Composable
private fun RulesDialog(onDismiss: () -> Unit) {
    val feature = CustomNotificationRingtone
    // 本地可观察快照（rules 每次访问都会重新解析 MMKV，非可观察状态）
    var items by remember { mutableStateOf(feature.rules) }
    fun refresh() { items = feature.rules }

    var showEditor by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomNotificationRingtone.RingtoneRule?>(null) }
    var showContactPicker by remember { mutableStateOf(false) }   // 新建「对话规则」选人

    // 联系人列表（数据库就绪时加载；异步，避免阻塞主线程）
    var contacts by remember { mutableStateOf<List<com.Johnny.wcx.features.api.core.models.IWeContact>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (WeDatabaseApi.isReady) {
                val list: List<com.Johnny.wcx.features.api.core.models.IWeContact> =
                    runCatching {
                        WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() +
                            WeDatabaseApi.getOfficialAccounts()
                    }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) { contacts = list }
            }
        }
    }

    // 联系人选择 → 以选中对话创建规则并进入编辑
    if (showContactPicker) {
        SingleContactSelector(
            title = "选择要自定义铃声的个人/群",
            contacts = contacts,
            initialSelectedWxId = null,
            onDismiss = { showContactPicker = false },
            onConfirm = { wxId ->
                showContactPicker = false
                val name = contacts.firstOrNull { it.wxId == wxId }
                    ?.let { c ->
                        com.Johnny.wcx.features.api.core.WeDatabaseApi.getDisplayName(c.wxId)
                    }
                    ?: wxId
                editingRule = CustomNotificationRingtone.RingtoneRule(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = CustomNotificationRingtone.RuleKind.CONVERSATION,
                    target = wxId,
                    targetName = name.ifBlank { wxId },
                )
                showEditor = true
            }
        )
        return
    }

    if (showEditor && editingRule != null) {
        RuleEditorDialog(
            rule = editingRule!!,
            contacts = contacts,
            onDismiss = {
                showEditor = false
                editingRule = null
            },
            onSave = { saved ->
                if (items.any { it.id == saved.id }) {
                    feature.updateRule(saved)
                } else {
                    feature.addRule(saved)
                }
                showEditor = false
                editingRule = null
                refresh()
            }
        )
        return
    }

    AlertDialogContent(
        title = { Text("自定义通知铃声") },
        text = {
            DefaultColumn(scrollable = true) {
                Text(
                    "给指定个人/群的消息或包含关键词的消息设置专属铃声，其它消息不受影响。规则生效需要微信处于运行状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (contacts.isEmpty()) {
                                // 数据库不可用也允许手动输入微信ID
                                editingRule = CustomNotificationRingtone.RingtoneRule(
                                    id = java.util.UUID.randomUUID().toString(),
                                    kind = CustomNotificationRingtone.RuleKind.CONVERSATION,
                                )
                                showEditor = true
                            } else {
                                showContactPicker = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("添加对话规则") }

                    Button(
                        onClick = {
                            editingRule = CustomNotificationRingtone.RingtoneRule(
                                id = java.util.UUID.randomUUID().toString(),
                                kind = CustomNotificationRingtone.RuleKind.KEYWORD,
                            )
                            showEditor = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("添加关键词规则") }
                }

                if (items.isEmpty()) {
                    Text(
                        "还没有规则。例如：设置「女朋友」来消息响专属铃声、消息含「老板」响铃声、某个群静音。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    items.forEach { rule ->
                        ListItem(
                            modifier = Modifier.clickable {
                                editingRule = rule
                                showEditor = true
                            },
                            headlineContent = { Text(ruleSummaryHeadline(rule)) },
                            supportingContent = {
                                Text(ruleSummaryBody(rule), maxLines = 2)
                            },
                            trailingContent = {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (rule.mode == CustomNotificationRingtone.RuleMode.SILENT) {
                                            MaterialSymbols.Outlined.Volume_off
                                        } else {
                                            MaterialSymbols.Outlined.Music_note
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier
                                            .clickable {
                                                feature.deleteRule(rule.id)
                                                refresh()
                                            }
                                            .padding(start = 12.dp)
                                            .size(20.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun ruleSummaryHeadline(rule: CustomNotificationRingtone.RingtoneRule): String =
    when (rule.kind) {
        CustomNotificationRingtone.RuleKind.CONVERSATION ->
            rule.targetName.ifBlank { rule.target }
        CustomNotificationRingtone.RuleKind.KEYWORD ->
            "关键词「${rule.target.ifBlank { "(未填写)" }}」"
    }

private fun ruleSummaryBody(rule: CustomNotificationRingtone.RingtoneRule): String = buildString {
    if (rule.kind == CustomNotificationRingtone.RuleKind.KEYWORD && rule.scopeWxId.isNotEmpty()) {
        append("仅 ${rule.scopeName.ifBlank { rule.scopeWxId }} · ")
    }
    if (rule.mode == CustomNotificationRingtone.RuleMode.SILENT) {
        append("静音")
    } else {
        val uri = rule.soundUri
        val title = if (uri.isBlank()) null else {
            CustomNotificationRingtone.ringtoneTitle(uri)
                ?: runCatching { Uri.parse(uri).lastPathSegment }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        append("铃声：${title ?: "未选择铃声"}")
    }
}

@Composable
private fun RuleEditorDialog(
    rule: CustomNotificationRingtone.RingtoneRule,
    contacts: List<com.Johnny.wcx.features.api.core.models.IWeContact>,
    onDismiss: () -> Unit,
    onSave: (CustomNotificationRingtone.RingtoneRule) -> Unit,
) {
    var cur by remember { mutableStateOf(rule) }
    val isConv = rule.kind == CustomNotificationRingtone.RuleKind.CONVERSATION
    var showPickTarget by remember { mutableStateOf(false) }   // 更换目标对话
    var showPickScope by remember { mutableStateOf(false) }    // 关键词限定会话

    if (showPickTarget) {
        SingleContactSelector(
            title = "选择个人/群",
            contacts = contacts,
            initialSelectedWxId = null,
            onDismiss = { showPickTarget = false },
            onConfirm = { wxId ->
                showPickTarget = false
                val name = contacts.firstOrNull { it.wxId == wxId }
                    ?.let { com.Johnny.wcx.features.api.core.WeDatabaseApi.getDisplayName(it.wxId) }
                    ?: wxId
                cur = cur.copy(target = wxId, targetName = name.ifBlank { wxId })
            }
        )
        return
    }

    if (showPickScope) {
        SingleContactSelector(
            title = "限定仅此对话生效（或返回取消以作用于所有对话）",
            contacts = contacts,
            initialSelectedWxId = cur.scopeWxId.ifEmpty { null },
            onDismiss = { showPickScope = false },
            onConfirm = { wxId ->
                showPickScope = false
                val name = contacts.firstOrNull { it.wxId == wxId }
                    ?.let { com.Johnny.wcx.features.api.core.WeDatabaseApi.getDisplayName(it.wxId) }
                    ?: wxId
                cur = cur.copy(scopeWxId = wxId, scopeName = name.ifBlank { wxId })
            }
        )
        return
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data
            ?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            cur = cur.copy(mode = CustomNotificationRingtone.RuleMode.RINGTONE, soundUri = uri.toString())
        }
    }

    AlertDialogContent(
        title = {
            Text(if (isConv) "对话铃声设置" else "关键词铃声设置")
        },
        text = {
            DefaultColumn(scrollable = true) {
                if (isConv) {
                    if (contacts.isNotEmpty()) {
                        ListItem(
                            modifier = Modifier
                                .clickable { showPickTarget = true }
                                .fillMaxWidth(),
                            headlineContent = { Text(cur.targetName.ifBlank { cur.target }) },
                            supportingContent = { Text("点击更换个人/群") }
                        )
                    } else {
                        Text(
                            "联系人数据库不可用，请输入目标微信ID（个人为 wxid/gh_xxx，群聊为 @chatroom 结尾的 ID）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = cur.target,
                            onValueChange = { cur = cur.copy(target = it.trim()) },
                            label = { Text("微信ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = cur.target,
                        onValueChange = { cur = cur.copy(target = it.trim()) },
                        label = { Text("关键词（消息正文包含即触发）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "匹配的是去掉「发送者:」前缀后的消息正文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ListItem(
                        modifier = Modifier
                            .clickable(enabled = contacts.isNotEmpty()) { showPickScope = true }
                            .fillMaxWidth(),
                        headlineContent = {
                            Text(if (cur.scopeWxId.isEmpty()) "所有对话" else cur.scopeName.ifBlank { cur.scopeWxId })
                        },
                        supportingContent = {
                            if (contacts.isEmpty()) {
                                Text("联系人数据库不可用，当前作用于所有对话")
                            } else if (cur.scopeWxId.isEmpty()) {
                                Text("当前关键词在所有对话生效 · 点此限定个人/群")
                            } else {
                                Text("点此更换 · 删除限定请点下方按钮")
                            }
                        },
                        trailingContent = {
                            if (cur.scopeWxId.isNotEmpty()) {
                                TextButton(onClick = {
                                    cur = cur.copy(scopeWxId = "", scopeName = "")
                                }) { Text("不限会话") }
                            }
                        }
                    )
                }

                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                Text(
                    "铃声效果",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (cur.mode == CustomNotificationRingtone.RuleMode.SILENT) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("当前：静音（消息仍会提示，但不响不震）", fontWeight = FontWeight.Medium)
                        }
                        Button(onClick = { picker.launch(ringtonePickerIntent(cur.soundUri)) }) {
                            Text("改为铃声")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            val currentTitle = cur.soundUri.takeIf { it.isNotBlank() }?.let { uri ->
                                CustomNotificationRingtone.ringtoneTitle(uri)
                                    ?: runCatching { Uri.parse(uri).lastPathSegment }
                                        .getOrNull()?.takeIf { it.isNotBlank() }
                            }
                            Text(
                                "当前铃声：${currentTitle ?: "未选择"}",
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = {
                                cur = cur.copy(mode = CustomNotificationRingtone.RuleMode.SILENT)
                            }
                        ) { Text("改为静音") }
                    }
                    Button(
                        onClick = { picker.launch(ringtonePickerIntent(cur.soundUri)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("选择铃声…") }
                }
            }
        },
        confirmButton = {
            val valid = when {
                isConv && cur.target.isBlank() -> false
                !isConv && cur.target.isBlank() -> false
                cur.mode == CustomNotificationRingtone.RuleMode.RINGTONE && cur.soundUri.isBlank() -> false
                else -> true
            }
            Button(
                onClick = { onSave(cur) },
                enabled = valid
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun ringtonePickerIntent(existingUri: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        existingUri?.takeIf { it.isNotBlank() }?.let {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
        }
    }
