package com.Johnny.wcx.features.items.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

@Feature(
    name = "定时发送消息",
    categories = ["聊天"],
    description = "在指定时间发送消息到群聊或私聊，支持每天重复或单次发送，支持多种消息类型"
)
object ScheduledMessage : ClickableFeature() {

    private const val TAG = "ScheduledMessage"
    private const val ALARM_ACTION = "com.Johnny.wcx.SCHEDULED_MESSAGE"
    private const val EXTRA_SCHEDULE_ID = "schedule_id"

    /**
     * 漏发补发窗口: 微信进程被杀/闹钟被系统吞掉时, 下次拉起微信后只要还没超过这个时长,
     * 就补发一次当天漏掉的任务。原实现只有 15 分钟, 用户几小时后才打开微信就会整天漏发。
     */
    private const val CATCHUP_WINDOW_MS = 12 * 60 * 60 * 1000L
    /** 守护轮询间隔: 进程内存活时兜底扫描到点任务, 同时补回丢失的进程内定时器 */
    private const val MONITOR_INTERVAL_MS = 30_000L
    /** 单段发送失败后的重试等待 */
    private const val SEGMENT_RETRY_DELAY_MS = 2_000L
    /** 单次任务迟到重试的间隔 */
    private const val ONETIME_RETRY_DELAY_MS = 30_000L
    /** 单个任务当天最多尝试次数, 超出则等明天, 防止失败时无限重试 */
    private const val MAX_FIRE_ATTEMPTS = 3

    /**
     * IMAGE 段 filePath 为空时, content 存 "svr:<msgSvrId>" 作为延迟下载标记:
     * 创建任务时图片 CDN 下载未落地不再让整个任务创建失败, 发送时自动重试下载。
     */
    const val DEFERRED_IMAGE_PREFIX = "svr:"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MessageSegment(
        val type: MessageType,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0,
        // 复读式引用: 创建时媒体未落地的消息只记录来源 (与转发/复读同一通道),
        // 发送时按引用从聊天记录现场解析媒体再发出
        val srcTalker: String = "",
        val srcMsgId: Long = 0,
        val srcSvrId: Long = 0
    ) : java.io.Serializable

    @Serializable
    data class ScheduleConfig(
        val id: String,
        val talker: String,
        val talkerName: String,
        // 任务主题（消息菜单创建时必填；旧数据无该字段，反序列化时取默认空串）
        val subject: String = "",
        val messageType: MessageType = MessageType.TEXT,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0,
        val hour: Int = 9,
        val minute: Int = 0,
        val repeatDaily: Boolean = true,
        var enabled: Boolean = true,
        val oneTimeOnly: Boolean = false,
        var nextSendTime: Long = 0,
        val segments: List<MessageSegment> = emptyList(),
        /**
         * 最近一次成功发送的日期 (yyyy-MM-dd)。用于"漏发补发"判定:
         * 进程被杀 / 闹钟被系统吞掉后重新拉起微信时, 只有当天还没发过才补发,
         * 避免每次冷启动都重复补发同一条消息。旧数据无此字段, 反序列化为空串。
         */
        var lastFiredDate: String = "",
        /** 当天已尝试发送的次数, 成功或跨天时归零; 用于限制失败重试次数 */
        var fireAttempts: Int = 0
    ) : java.io.Serializable

    enum class MessageType(val description: String) {
        TEXT("文本"),
        IMAGE("图片"),
        VOICE("语音"),
        VIDEO("视频"),
        FILE("文件"),
        LINK("链接"),
        // 微信收藏项: content 存收藏 localId, 发送时交给微信原生收藏发送通道,
        // 因此图片/语音/视频/文件/链接/位置/音乐/小程序/聊天记录都能原样发出。
        FAVORITE("收藏")
    }

    // 存储改用 kotlinx JSON (ignoreUnknownKeys=true): 原先的 Java 序列化对类结构零容忍,
    // ScheduleConfig 每加一个字段 serialVersionUID 就变, 导致旧任务整批读不出
    // (InvalidClassException: stream classdesc serialVersionUID 与 local 不一致)
    private var schedulesJson by prefOption("scheduled_messages_json", "")

    // 旧版 Java 序列化存储, 仅用于一次性迁移最近版本写入的任务
    private var legacySchedules by prefOption("scheduled_messages", emptyList<ScheduleConfig>())

    private var schedules: List<ScheduleConfig>
        get() {
            val raw = schedulesJson
            if (raw.isNotBlank()) {
                return runCatching { json.decodeFromString<List<ScheduleConfig>>(raw) }
                    .onFailure { WeLogger.e(TAG, "failed to parse schedules json", it) }
                    .getOrDefault(emptyList())
            }
            return migrateLegacySchedules()
        }
        set(value) {
            schedulesJson = runCatching { json.encodeToString(value) }
                .onFailure { WeLogger.e(TAG, "failed to encode schedules json", it) }
                .getOrDefault("[]")
        }

    private fun migrateLegacySchedules(): List<ScheduleConfig> {
        val legacy = runCatching { legacySchedules }
            .onFailure { WeLogger.e(TAG, "failed to read legacy schedules", it) }
            .getOrDefault(emptyList())
        if (legacy.isEmpty()) return emptyList()
        WeLogger.i(TAG, "migrated ${legacy.size} legacy schedules to json storage")
        schedules = legacy
        return legacy
    }
    /**
     * 任务存储的读写锁。
     * 多个任务可能同时触发, 而 updateSchedule 是"读出整表 -> 替换一项 -> 整表写回",
     * 无锁并发会互相覆盖: 后写的一份把先写的 nextSendTime/lastFiredDate 抹掉,
     * 表现为任务状态回退、下次触发时间算错, 是定时发送偶发失灵的诱因之一。
     */
    private val storageLock = Any()

    /**
     * 闹钟 requestCode 分配表。
     * 原实现直接用 schedule.id.hashCode() 作 requestCode: 不同 id 一旦哈希相撞,
     * PendingIntent(FLAG_UPDATE_CURRENT) 会互相覆盖, 被覆盖的那个任务再也收不到闹钟。
     * 这里改为按任务 id 分配稳定且互不相同的小整数并持久化, 哈希不再参与。
     */
    private val alarmCodes = ConcurrentHashMap<String, Int>()
    private var alarmCodeSeq by prefOption("scheduled_alarm_code_seq", 1000)

    private val activeAlarms = ConcurrentHashMap<String, PendingIntent>()
    private val timerJobs = ConcurrentHashMap<String, Job>()

    /** 统一作用域: 子协程失败不会连坐取消其它任务的定时器 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    // 在途互斥: 进程内定时器与 AlarmManager 闹钟会在同一时刻先后触发,
    // 时间守卫只能挡"提前触发"; 若第一个触发者正在发送(媒体解析/下载可能耗时数十秒),
    // 第二个触发者重读任务时 nextSendTime 尚未推进, 守卫照样放行 → 重复发送。
    // 发送开始前占位、结束后释放, 保证同一任务同一时刻只有一个触发者在执行。
    private val triggering = ConcurrentHashMap.newKeySet<String>()
    private lateinit var alarmReceiver: BroadcastReceiver

    override fun onEnable() {
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                // goAsync: 声明本次广播需要延长生命周期, 避免 onReceive 返回后
                // 系统提前回收广播上下文导致发送中途被中断
                val pending = goAsync()
                scope.launch {
                    try {
                        handleScheduleTrigger(scheduleId)
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
            }
        }

        runCatching {
            val filter = IntentFilter(ALARM_ACTION)
            // Android 13+ 必须声明接收器导出标志，否则注册抛 SecurityException
            ContextCompat.registerReceiver(
                HostInfo.application,
                alarmReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            WeLogger.e(TAG, "failed to register alarm receiver", it)
        }

        runCatching {
            // 注意: 先取一次快照再逐个排期, 不要边遍历边改存储
            val enabled = synchronized(storageLock) { schedules }.filter { it.enabled }
            enabled.forEach { schedule ->
                // 冷启动允许补发当天漏掉的任务（宽限窗仅此一处使用）
                scheduleAlarm(schedule, allowCatchUp = true)
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to schedule alarms", it)
        }

        startMonitor()
    }

    /**
     * 守护轮询: 进程内存活时兜底扫描。
     * 进程内定时器用 delay 计时, 一旦 job 因异常/取消而消失、或设备深度休眠期间
     * 计时未推进, 到点了也没人触发; 这里每 30 秒按墙上时钟复查一次,
     * 到点即触发, 并顺手补回丢失的定时器。
     */
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    delay(MONITOR_INTERVAL_MS)
                    sweepSchedules()
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "monitor loop error", e)
                }
            }
        }
    }

    private fun sweepSchedules() {
        val now = System.currentTimeMillis()
        val list = runCatching { synchronized(storageLock) { schedules } }
            .onFailure { WeLogger.e(TAG, "failed to read schedules in monitor", it) }
            .getOrDefault(emptyList())
        list.filter { it.enabled }.forEach { schedule ->
            runCatching {
                when {
                    // 到点未触发(定时器丢失/休眠错过): 立即执行
                    schedule.nextSendTime in 1..now -> {
                        WeLogger.i(TAG, "monitor: ${schedule.id} overdue (next=${schedule.nextSendTime}), triggering now")
                        scope.launch { handleScheduleTrigger(schedule.id) }
                    }
                    // 定时器缺失(异常退出)但还没到点: 重新挂上
                    schedule.nextSendTime > now && timerJobs[schedule.id]?.isActive != true -> {
                        WeLogger.i(TAG, "monitor: re-arm in-process timer for ${schedule.id}")
                        scheduleInProcess(schedule)
                    }
                    // 从未排过期(存储被清理/新增任务时进程忙): 重新计算
                    schedule.nextSendTime <= 0 -> scheduleAlarm(schedule, allowCatchUp = true)
                }
            }.onFailure {
                WeLogger.e(TAG, "monitor: failed to handle ${schedule.id}", it)
            }
        }
    }

    override fun onDisable() {
        monitorJob?.cancel()
        monitorJob = null
        activeAlarms.values.forEach { it.cancel() }
        activeAlarms.clear()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        runCatching {
            HostInfo.application.unregisterReceiver(alarmReceiver)
        }.onFailure {
            WeLogger.e(TAG, "failed to unregister alarm receiver", it)
        }
    }

    /**
     * 为每个任务分配稳定唯一的闹钟 requestCode 并持久化。
     * 不能再用 id.hashCode(): 哈希相撞时两个任务共用一个 PendingIntent,
     * FLAG_UPDATE_CURRENT 会让后者顶掉前者, 被顶掉的那个从此收不到闹钟。
     */
    private fun requestCodeFor(scheduleId: String): Int {
        alarmCodes[scheduleId]?.let { return it }
        return synchronized(storageLock) {
            alarmCodes[scheduleId] ?: run {
                val code = alarmCodeSeq + 1
                alarmCodeSeq = code
                alarmCodes[scheduleId] = code
                code
            }
        }
    }

    private fun scheduleAlarm(schedule: ScheduleConfig, allowCatchUp: Boolean = false) {
        val context = HostInfo.application
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ALARM_ACTION).apply {
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeFor(schedule.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = calculateNextTriggerTime(schedule, allowCatchUp)
        if (triggerTime <= 0) {
            // 单次任务已过窗口期 / 重试次数用尽: 关掉它,
            // 否则任务会一直挂在"已启用"却永远不再触发, 用户以为它还在工作
            if (schedule.enabled) {
                schedule.enabled = false
                synchronized(storageLock) { updateSchedule(schedule) }
                cancelAlarm(schedule)
                WeLogger.i(TAG, "schedule ${schedule.id} retired: no further trigger time")
            }
            return
        }
        schedule.nextSendTime = triggerTime
        synchronized(storageLock) { updateSchedule(schedule) }

        // 进程内定时器：只要微信进程存活就保证准点触发，不受系统闹钟权限/省电策略影响
        scheduleInProcess(schedule)

        runCatching {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // 未授予精确闹钟权限：退化为不精确闹钟，系统会尽量在相近时间触发
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            activeAlarms[schedule.id] = pendingIntent
        }.onFailure {
            WeLogger.e(TAG, "failed to set exact alarm for schedule ${schedule.id}", it)
            // 最后兜底：精确闹钟被拒绝时再尝试一次不精确闹钟
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                activeAlarms[schedule.id] = pendingIntent
            }.onFailure { e2 ->
                WeLogger.e(TAG, "fallback alarm also failed for ${schedule.id}", e2)
            }
        }
    }

    /**
     * 进程内准点定时器：微信进程存活时按 nextSendTime 用 delay 触发，
     * 与 AlarmManager 互为冗余（配合 handleScheduleTrigger 的时间守卫去重，不会重复发送）。
     */
    private fun scheduleInProcess(schedule: ScheduleConfig) {
        timerJobs.remove(schedule.id)?.cancel()
        val next = schedule.nextSendTime
        val delayMs = next - System.currentTimeMillis()
        if (delayMs <= 0) return
        val job = scope.launch {
            try {
                // 深度休眠期间 delay 依赖的单调时钟不会推进, 醒来时可能已经过点了;
                // 过点也照常触发, 由 handleScheduleTrigger 内部的时间守卫与去重兜底
                delay(delayMs)
                handleScheduleTrigger(schedule.id)
            } catch (e: CancellationException) {
                // 任务被取消（停用/删除/重设闹钟）
            } catch (e: Throwable) {
                WeLogger.e(TAG, "in-process timer failed for ${schedule.id}", e)
            }
        }
        timerJobs[schedule.id] = job
    }

    private fun todayKey(): String = LocalDate.now().toString()

    private fun tomorrowTarget(schedule: ScheduleConfig): Long {
        val targetTime = LocalTime.of(schedule.hour, schedule.minute)
        return java.time.LocalDateTime.now().plusDays(1).with(targetTime)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun todayTargetOf(schedule: ScheduleConfig): Long {
        val targetTime = LocalTime.of(schedule.hour, schedule.minute)
        return java.time.LocalDateTime.now().with(targetTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun calculateNextTriggerTime(schedule: ScheduleConfig, allowCatchUp: Boolean): Long {
        val now = System.currentTimeMillis()
        val todayTarget = todayTargetOf(schedule)

        if (todayTarget > now) return todayTarget

        val missed = now - todayTarget
        // 只有"确实排过闹钟且已经过点"才算漏发, 才允许补发。
        // nextSendTime==0 表示这个任务从未排过期(新建 / 首次升级 / 存储被清理),
        // 此时凭空补发一条几小时前的旧消息只会吓用户一跳, 直接按明天(或作废)处理。
        val wasScheduled = schedule.nextSendTime > 0 && schedule.nextSendTime <= now
        if (!wasScheduled) {
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        // 当天已经成功发过 -> 只能等明天(重复任务)或作废(单次任务)。
        // 这条判据取代了原来的 15 分钟窗口: 现在哪怕用户隔了几个小时才打开微信,
        // 只要当天还没发成功就补发一次, 而发过的绝不会二次补发。
        if (schedule.lastFiredDate == todayKey()) {
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        // 试过太多次仍失败 -> 今天不再折腾, 避免半夜疯狂重试
        if (schedule.fireAttempts >= MAX_FIRE_ATTEMPTS) {
            WeLogger.w(TAG, "schedule ${schedule.id} exhausted ${schedule.fireAttempts} attempts today, giving up")
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        // 超出补发窗口(比如隔天甚至更晚才拉起微信) -> 按明天/作废处理
        if (missed > CATCHUP_WINDOW_MS) {
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        // 当天应发而未发: 重复任务立刻补发, 单次任务稍作退避后重试
        return if (schedule.repeatDaily) now + 2000L else now + ONETIME_RETRY_DELAY_MS
    }

    private suspend fun handleScheduleTrigger(scheduleId: String) {
        val schedule = synchronized(storageLock) { schedules.find { it.id == scheduleId } } ?: return

        if (!schedule.enabled) return

        // 时间守卫：进程内定时器与闹钟可能先后触发，只允许到点后执行，避免重复发送
        val nowMs = System.currentTimeMillis()
        if (schedule.nextSendTime > 0 && nowMs < schedule.nextSendTime - 2000) {
            WeLogger.i(TAG, "trigger for ${schedule.id} fired too early, skipping (next=${schedule.nextSendTime}, now=$nowMs)")
            return
        }
        WeLogger.i(TAG, "scheduled trigger fired for ${schedule.id} (next=${schedule.nextSendTime}, now=$nowMs)")

        if (!triggering.add(scheduleId)) {
            WeLogger.i(TAG, "trigger for ${schedule.id} already in flight, skipping duplicate")
            return
        }
        try {
            // 当天已成功发过: 只推进排期, 不再重复发送
            if (schedule.lastFiredDate == todayKey()) {
                WeLogger.i(TAG, "schedule ${schedule.id} already fired today, advancing schedule only")
                advanceAfterTrigger(schedule, sentOk = true)
                return
            }

            schedule.fireAttempts += 1
            val sentOk = runCatching {
                if (schedule.segments.isNotEmpty()) {
                    sendSegments(schedule)
                } else {
                    sendLegacySingle(schedule)
                }
            }.onFailure {
                WeLogger.e(TAG, "failed to send scheduled message for ${schedule.id}", it)
            }.getOrDefault(false)

            if (sentOk) {
                schedule.lastFiredDate = todayKey()
                schedule.fireAttempts = 0
                WeLogger.i(TAG, "scheduled message sent to ${schedule.talker}")
            } else {
                WeLogger.w(
                    TAG,
                    "scheduled message to ${schedule.talker} incomplete (attempt ${schedule.fireAttempts}/$MAX_FIRE_ATTEMPTS)"
                )
            }
            advanceAfterTrigger(schedule, sentOk)
        } finally {
            triggering.remove(scheduleId)
        }
    }

    /**
     * 逐段发送: 每段独立成败互不影响。
     * 原实现把整段循环包在一个 runCatching 里, 第一段(常见于图片/视频)一旦抛异常,
     * 后面的文本段就全都发不出去了 —— 用户看到的就是"整条定时消息没发出来"。
     * 媒体段常常只是文件还没落地/CDN 未就绪, 因此对失败段退避重试一次。
     */
    private suspend fun sendSegments(schedule: ScheduleConfig): Boolean {
        var allOk = true
        schedule.segments.forEachIndexed { index, segment ->
            val ok = runCatching { sendSegment(schedule.talker, segment) }
                .onFailure { WeLogger.e(TAG, "segment $index (${segment.type}) threw", it) }
                .getOrDefault(false)
            if (!ok) {
                WeLogger.w(TAG, "segment $index (${segment.type}) failed, retrying once")
                delay(SEGMENT_RETRY_DELAY_MS)
                val retried = runCatching { sendSegment(schedule.talker, segment) }
                    .onFailure { WeLogger.e(TAG, "segment $index (${segment.type}) retry threw", it) }
                    .getOrDefault(false)
                if (!retried) {
                    WeLogger.e(TAG, "segment $index (${segment.type}) gave up for ${schedule.id}")
                    allOk = false
                }
            }
            if (index < schedule.segments.size - 1) {
                delay(500)
            }
        }
        return allOk
    }

    /** 触发结束后的收尾: 落库 -> 单次任务关闭 -> 重排下一次(含失败重试) */
    private fun advanceAfterTrigger(schedule: ScheduleConfig, sentOk: Boolean) {
        runCatching {
            if (schedule.oneTimeOnly && sentOk) {
                schedule.enabled = false
            }
            synchronized(storageLock) { updateSchedule(schedule) }
            if (schedule.oneTimeOnly && sentOk) {
                cancelAlarm(schedule)
                return
            }
            scheduleAlarm(schedule)
        }.onFailure {
            WeLogger.e(TAG, "failed to advance schedule ${schedule.id}", it)
        }
    }

    /** 返回 true 表示这一段确实发出去了; false 交给上层决定是否重试 */
    private fun sendSegment(talker: String, segment: MessageSegment): Boolean {
        return when (segment.type) {
            MessageType.TEXT -> sendTextSafely(talker, segment.content)
            MessageType.LINK -> sendTextSafely(talker, segment.content)
            MessageType.IMAGE -> {
                when {
                    segment.filePath.isNotBlank() ->
                        runCatching { WeMessageApi.sendImage(talker, segment.filePath); true }
                            .onFailure { WeLogger.e(TAG, "send image failed (path=${segment.filePath})", it) }
                            .getOrDefault(false)
                    segment.content.startsWith(DEFERRED_IMAGE_PREFIX) -> {
                        // 旧版本创建的延迟段: svrId 存在 content 里
                        val svrId = segment.content.removePrefix(DEFERRED_IMAGE_PREFIX).toLongOrNull() ?: 0
                        sendImageByReference(talker, segment.srcTalker.ifEmpty { talker }, svrId, segment.srcMsgId)
                    }
                    segment.srcSvrId > 0 || segment.srcMsgId > 0 ->
                        // 复读式引用段: 与转发功能同一通道, 发送时从聊天记录现场解析
                        sendImageByReference(talker, segment.srcTalker.ifEmpty { talker }, segment.srcSvrId, segment.srcMsgId)
                    else -> {
                        WeLogger.e(TAG, "image segment has neither path nor source reference")
                        false
                    }
                }
            }
            MessageType.VOICE -> {
                if (segment.filePath.isNotBlank()) {
                    runCatching { WeMessageApi.sendVoice(talker, segment.filePath, segment.duration); true }
                        .onFailure { WeLogger.e(TAG, "send voice failed (path=${segment.filePath})", it) }
                        .getOrDefault(false)
                } else if (segment.srcSvrId > 0 || segment.srcMsgId > 0) {
                    sendVoiceByReference(talker, segment.srcTalker.ifEmpty { talker }, segment)
                } else {
                    WeLogger.e(TAG, "voice segment has neither path nor source reference")
                    false
                }
            }
            MessageType.VIDEO -> {
                if (segment.filePath.isNotBlank()) {
                    runCatching { WeMessageApi.sendVideo(talker, segment.filePath); true }
                        .onFailure { WeLogger.e(TAG, "send video failed (path=${segment.filePath})", it) }
                        .getOrDefault(false)
                } else if (segment.srcSvrId > 0 || segment.srcMsgId > 0) {
                    sendVideoByReference(talker, segment.srcTalker.ifEmpty { talker }, segment)
                } else {
                    WeLogger.e(TAG, "video segment has neither path nor source reference")
                    false
                }
            }
            MessageType.FILE -> {
                when {
                    segment.filePath.isNotBlank() -> {
                        val fileName = segment.filePath.substringAfterLast('/')
                        runCatching { WeMessageApi.sendFile(talker, segment.filePath, fileName); true }
                            .onFailure { WeLogger.e(TAG, "send file failed (path=${segment.filePath})", it) }
                            .getOrDefault(false)
                    }
                    segment.srcSvrId > 0 || segment.srcMsgId > 0 ->
                        // 复读式引用段: 发送时现场触发文件下载 (与用户手动下载文件同机制)
                        sendFileByReference(talker, segment.srcTalker.ifEmpty { talker }, segment)
                    else -> {
                        WeLogger.e(TAG, "file segment has neither path nor source reference")
                        false
                    }
                }
            }
            MessageType.FAVORITE -> sendFavoriteSegment(talker, segment)
        }
    }

    /**
     * 收藏段: 交给微信原生收藏发送通道 (与 Hchat 定时任务的 TYPE_FAVORITE 同一条路径)。
     * content 存收藏 localId。图片/语音/视频/文件等各种类型均由微信自己处理,
     * 我们无需解析 favProto 或定位媒体文件。
     */
    private fun sendFavoriteSegment(talker: String, segment: MessageSegment): Boolean {
        val localId = segment.content.trim().toLongOrNull()
        if (localId == null || localId <= 0L) {
            WeLogger.e(TAG, "favorite segment has invalid localId: ${segment.content}")
            return false
        }
        return runCatching {
            h.Hchat.hooks.api.core.WeChatApis.media()?.favorites()?.send(talker, localId) == true
        }.onFailure {
            WeLogger.e(TAG, "send favorite failed (localId=$localId)", it)
        }.getOrDefault(false)
    }

    private fun sendTextSafely(talker: String, content: String): Boolean {
        if (content.isBlank()) {
            // 空文本段视为无需发送, 不算失败
            return true
        }
        return runCatching { WeMessageApi.sendText(talker, content); true }
            .onFailure { WeLogger.e(TAG, "send text failed (talker=$talker)", it) }
            .getOrDefault(false)
    }

    /**
     * 复读式发送: 按引用重建权威消息对象后, 走与 ForwardMessages(转发) 完全相同的通道
     * (图片 md5 / 语音本地 silk / 视频本地 mp4), 媒体在发送时刻从聊天记录解析。
     * 本地缓存已被清理时退回 CDN 下载。
     */
    private fun resolveSourceInstance(srcTalker: String, svrId: Long, msgId: Long): Any? {
        return runCatching {
            when {
                svrId > 0 -> WeMessageApi.getMsgInfoInstanceByMsgSvrId(svrId, srcTalker.takeIf { it.isNotEmpty() })
                msgId > 0 -> WeMessageApi.getMsgInfoInstanceByMsgId(msgId)
                else -> null
            }
        }.onFailure {
            WeLogger.w(TAG, "resolve source instance failed (svrId=$svrId, msgId=$msgId)", it)
        }.getOrNull()
    }

    private fun sendImageByReference(talker: String, srcTalker: String, svrId: Long, msgId: Long): Boolean {
        val instance = resolveSourceInstance(srcTalker, svrId, msgId)
        if (instance != null) {
            runCatching {
                val md5 = WeServiceApi.getImageMd5FromMsgInfo(MessageInfo(instance))
                if (md5.isNotBlank()) {
                    WeMessageApi.sendImageByMd5(talker, md5, null)
                    return true
                }
                WeLogger.w(TAG, "repeat-path image md5 blank (svrId=$svrId)")
            }.onFailure {
                WeLogger.w(TAG, "repeat-path image via md5 failed, fallback to cdn (svrId=$svrId)", it)
            }
        }
        // 本地缓存已清理等场景: 退回 CDN 下载
        val path = svrId.takeIf { it > 0 }?.let { WeMessageApi.downloadImage(it) }
        if (path != null) {
            return runCatching { WeMessageApi.sendImage(talker, path); true }
                .onFailure { WeLogger.e(TAG, "send image failed (path=$path)", it) }
                .getOrDefault(false)
        }
        WeLogger.e(TAG, "deferred image failed at send time (svrId=$svrId, msgId=$msgId)")
        return false
    }

    private fun sendVoiceByReference(talker: String, srcTalker: String, segment: MessageSegment): Boolean {
        val instance = resolveSourceInstance(srcTalker, segment.srcSvrId, segment.srcMsgId)
        if (instance == null) {
            WeLogger.e(TAG, "voice source not found at send time (svrId=${segment.srcSvrId}, msgId=${segment.srcMsgId})")
            return false
        }
        return runCatching {
            val msgInfo = MessageInfo(instance)
            val encPath = msgInfo.imagePath ?: error("voice encPath missing")
            val voicePath = WeMessageApi.getVoiceFullPath(encPath)
            // 微信语音仅在落盘后可重发; 文件不存在时 getDurationMs(JNI) 行为不可控, 先行拦截
            if (!java.io.File(voicePath).exists()) error("voice file not on disk at send time: $voicePath")
            val durationMs = segment.duration.takeIf { it > 0 }
                ?: AudioUtils.getDurationMs(voicePath).toInt()
            if (durationMs <= 0) error("voice duration unavailable at send time: $voicePath")
            WeMessageApi.sendVoice(talker, voicePath, durationMs)
            true
        }.onFailure {
            WeLogger.e(TAG, "repeat-path voice failed (svrId=${segment.srcSvrId})", it)
        }.getOrDefault(false)
    }

    private fun sendVideoByReference(talker: String, srcTalker: String, segment: MessageSegment): Boolean {
        val instance = resolveSourceInstance(srcTalker, segment.srcSvrId, segment.srcMsgId)
        if (instance == null) {
            WeLogger.e(TAG, "video source not found at send time (svrId=${segment.srcSvrId}, msgId=${segment.srcMsgId})")
            return false
        }
        return runCatching {
            val msgInfo = MessageInfo(instance)
            val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
            if (mp4Path.isBlank()) error("video mp4 path blank")
            if (!java.io.File(mp4Path).exists()) error("video mp4 not on disk at send time: $mp4Path")
            WeMessageApi.sendVideo(talker, mp4Path)
            true
        }.onFailure {
            WeLogger.e(TAG, "repeat-path video failed (svrId=${segment.srcSvrId})", it)
        }.getOrDefault(false)
    }

    private fun sendFileByReference(talker: String, srcTalker: String, segment: MessageSegment): Boolean {
        val instance = resolveSourceInstance(srcTalker, segment.srcSvrId, segment.srcMsgId)
        if (instance == null) {
            WeLogger.e(TAG, "file source not found at send time (svrId=${segment.srcSvrId}, msgId=${segment.srcMsgId})")
            return false
        }
        return runCatching {
            val path = WeMessageApi.downloadFile(instance) ?: error("file download failed at send time")
            WeMessageApi.sendFile(talker, path, path.substringAfterLast('/'))
            true
        }.onFailure {
            WeLogger.e(TAG, "repeat-path file failed (svrId=${segment.srcSvrId})", it)
        }.getOrDefault(false)
    }

    /** 老版本创建的单条任务(无 segments) */
    private fun sendLegacySingle(schedule: ScheduleConfig): Boolean {
        return when (schedule.messageType) {
            MessageType.TEXT -> sendTextSafely(schedule.talker, schedule.content)
            MessageType.LINK -> sendTextSafely(schedule.talker, schedule.content)
            MessageType.IMAGE -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy image schedule has no file path")
                    return false
                }
                runCatching { WeMessageApi.sendImage(schedule.talker, schedule.filePath); true }
                    .onFailure { WeLogger.e(TAG, "legacy image send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.VOICE -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy voice schedule has no file path")
                    return false
                }
                runCatching { WeMessageApi.sendVoice(schedule.talker, schedule.filePath, schedule.duration); true }
                    .onFailure { WeLogger.e(TAG, "legacy voice send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.VIDEO -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy video schedule has no file path")
                    return false
                }
                runCatching { WeMessageApi.sendVideo(schedule.talker, schedule.filePath); true }
                    .onFailure { WeLogger.e(TAG, "legacy video send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.FILE -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy file schedule has no file path")
                    return false
                }
                val fileName = schedule.filePath.substringAfterLast('/')
                runCatching { WeMessageApi.sendFile(schedule.talker, schedule.filePath, fileName); true }
                    .onFailure { WeLogger.e(TAG, "legacy file send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.FAVORITE -> sendFavoriteSegment(
                schedule.talker,
                MessageSegment(type = MessageType.FAVORITE, content = schedule.content)
            )
        }
    }

    private fun cancelAlarm(schedule: ScheduleConfig) {
        timerJobs.remove(schedule.id)?.cancel()
        val pi = activeAlarms.remove(schedule.id) ?: return
        pi.cancel()
        runCatching {
            val am = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }.onFailure { WeLogger.w(TAG, "cancel alarm failed for ${schedule.id}", it) }
    }

    fun addSchedule(schedule: ScheduleConfig) {
        synchronized(storageLock) {
            schedules = schedules + schedule
        }
        if (schedule.enabled) {
            scheduleAlarm(schedule)
        }
    }

    fun getSchedulesFor(talker: String): List<ScheduleConfig> {
        return synchronized(storageLock) { schedules.filter { it.talker == talker } }
    }

    private fun updateSchedule(schedule: ScheduleConfig) {
        schedules = schedules.map { if (it.id == schedule.id) schedule else it }
    }

    fun deleteSchedule(schedule: ScheduleConfig) {
        cancelAlarm(schedule)
        synchronized(storageLock) {
            schedules = schedules.filter { it.id != schedule.id }
        }
    }





    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var showAddDialog by remember { mutableStateOf(false) }
            var showEditDialog by remember { mutableStateOf(false) }
            var editingSchedule by remember { mutableStateOf<ScheduleConfig?>(null) }
            // 本地可观察快照: schedules 每次访问都会重新解析 JSON, 不是可观察状态,
            // 开关/增删改后必须刷新本快照才能驱动列表 UI 即时重组
            var taskItems by remember { mutableStateOf(synchronized(storageLock) { schedules }) }
            fun refreshTasks() { taskItems = synchronized(storageLock) { schedules } }

            if (showAddDialog) {
                ScheduleEditorDialog(
                    onDismiss = { showAddDialog = false },
                    onSave = { schedule ->
                        addSchedule(schedule)
                        showToast("定时任务已添加")
                        showAddDialog = false
                        refreshTasks()
                    }
                )
            } else if (showEditDialog && editingSchedule != null) {
                ScheduleEditorDialog(
                    onDismiss = { showEditDialog = false },
                    onSave = { schedule ->
                        updateSchedule(schedule)
                        // 编辑后重新计算并设置闹钟（时间可能已变更）；关闭时务必撤掉旧闹钟
                        if (schedule.enabled) scheduleAlarm(schedule) else cancelAlarm(schedule)
                        showToast("定时任务已更新")
                        showEditDialog = false
                        refreshTasks()
                    },
                    existing = editingSchedule!!
                )
            } else {
                AlertDialogContent(
                    title = { Text("定时发送消息") },
                    text = {
                        DefaultColumn(scrollable = true) {
                            if (taskItems.isEmpty()) {
                                Text(
                                    "还没有定时任务，点击下方按钮添加",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                taskItems.forEach { schedule ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            editingSchedule = schedule
                                            showEditDialog = true
                                        },
                                        headlineContent = { Text(schedule.talkerName) },
                                        supportingContent = {
                                            val summary = if (schedule.segments.isNotEmpty()) {
                                                segmentsSummary(schedule.segments)
                                            } else {
                                                schedule.messageType.description
                                            }
                                            // 有主题时前置显示，便于对应消息菜单创建的任务
                                            val subjectPrefix =
                                                if (schedule.subject.isBlank()) "" else "「${schedule.subject}」"
                                            Text(
                                                "$subjectPrefix${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')} " +
                                                        "${if (schedule.repeatDaily) "每天" else "单次"} $summary"
                                            )
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = schedule.enabled,
                                                onCheckedChange = { enabled ->
                                                    // 先写本地副本并持久化, 再刷新快照驱动开关即时翻转
                                                    val updated = schedule.copy(enabled = enabled)
                                                    if (enabled) {
                                                        scheduleAlarm(updated)
                                                    } else {
                                                        cancelAlarm(updated)
                                                    }
                                                    updateSchedule(updated)
                                                    taskItems = taskItems.map {
                                                        if (it.id == updated.id) updated else it
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
                    confirmButton = {
                        Button(onClick = { showAddDialog = true }) { Text("添加任务") }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScheduleEditorDialog(
        onDismiss: () -> Unit,
        onSave: (ScheduleConfig) -> Unit,
        existing: ScheduleConfig? = null
    ) {
        val isEditing = existing != null
        val initialSegments = remember {
            existing?.let {
                if (it.segments.isNotEmpty()) {
                    it.segments
                } else if (it.content.isNotBlank() || it.filePath.isNotBlank()) {
                    listOf(
                        MessageSegment(
                            type = it.messageType,
                            content = it.content,
                            filePath = it.filePath,
                            duration = it.duration
                        )
                    )
                } else {
                    emptyList()
                }
            } ?: emptyList()
        }

        // 与长按菜单端「定时发送」共用同一个编辑器组件, 两个入口功能完全一致:
        // 发送对象 / 消息段列表 / 添加消息段 / 从收藏添加 / 发送时间 / 每天重复 / 启用。
        ScheduleTaskEditor(
            title = if (isEditing) "编辑定时任务" else "添加定时任务",
            initialTalker = existing?.talker ?: "",
            initialTalkerName = existing?.talkerName ?: "",
            initialSegments = initialSegments,
            initialHour = existing?.hour ?: 9,
            initialMinute = existing?.minute ?: 0,
            initialRepeatDaily = existing?.repeatDaily ?: true,
            initialEnabled = existing?.enabled ?: true,
            subjectRequired = false,
            onDismiss = onDismiss,
            onConfirm = { draft ->
                if (draft.talker.isBlank()) {
                    showToast("请选择发送对象")
                    return@ScheduleTaskEditor
                }
                if (draft.segments.isEmpty()) {
                    showToast("请至少添加一个消息段")
                    return@ScheduleTaskEditor
                }
                draft.segments.forEachIndexed { index, segment ->
                    val valid = when (segment.type) {
                        MessageType.TEXT, MessageType.LINK, MessageType.FAVORITE -> segment.content.isNotBlank()
                        MessageType.IMAGE, MessageType.VOICE,
                        MessageType.VIDEO, MessageType.FILE -> segment.filePath.isNotBlank()
                    }
                    if (!valid) {
                        showToast("第${index + 1}段消息内容无效")
                        return@ScheduleTaskEditor
                    }
                }

                onSave(
                    ScheduleConfig(
                        id = existing?.id ?: "${System.currentTimeMillis()}",
                        talker = draft.talker,
                        talkerName = draft.talkerName,
                        subject = draft.subject,
                        messageType = existing?.messageType ?: MessageType.TEXT,
                        content = existing?.content ?: "",
                        filePath = existing?.filePath ?: "",
                        duration = existing?.duration ?: 0,
                        hour = draft.hour,
                        minute = draft.minute,
                        repeatDaily = draft.repeatDaily,
                        // 关闭"每天重复"即为一次性任务：发送一次后自动停用
                        oneTimeOnly = !draft.repeatDaily,
                        enabled = draft.enabled,
                        segments = draft.segments
                    )
                )
            }
        )
    }

    @Composable
    private fun Spacer(modifier: Modifier) {
        androidx.compose.foundation.layout.Spacer(modifier)
    }
}
