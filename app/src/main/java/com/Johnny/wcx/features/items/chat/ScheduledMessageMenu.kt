package com.Johnny.wcx.features.items.chat

import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
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
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Cancel
import com.composables.icons.materialsymbols.outlined.Schedule
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.ScheduledMessage.MessageSegment
import com.Johnny.wcx.features.items.chat.ScheduledMessage.MessageType as ScheduleMessageType
import com.Johnny.wcx.features.items.chat.ScheduledMessage.ScheduleConfig
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.CancelIcon
import com.Johnny.wcx.ui.utils.SendIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString

@Feature(
    name = "消息定时发送菜单",
    categories = ["聊天"],
    description = "在消息长按菜单添加「定时发送」与「取消定时发送」: " +
            "将当前会话的文字/图片/语音等消息一键创建为定时任务; 多选消息时创建一条多段消息任务"
)
object ScheduledMessageMenu : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "ScheduledMessageMenu"

    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT,
        MessageType.QUOTE,
        MessageType.IMAGE,
        MessageType.VOICE,
        MessageType.VIDEO,
        MessageType.MICRO_VIDEO,
        MessageType.FILE
    )

    fun isSupportedMsg(msgInfo: MessageInfo): Boolean = msgInfo.type in SUPPORTED_MSG_TYPES

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777026,
                "定时发送",
                SendIcon,
                MaterialSymbols.Outlined.Schedule,
                isSupported = ::isSupportedMsg,
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { msgInfos ->
                        msgInfos.isNotEmpty() && msgInfos.all { isSupportedMsg(it) }
                    },
                    onClick = { view, _, msgInfos ->
                        showScheduleConfirmDialog(view, msgInfos)
                    }
                )
            ) { view, _, msgInfo ->
                showScheduleConfirmDialog(view, listOf(msgInfo))
            },
            WeChatMessageContextMenuApi.MenuItem(
                777027,
                "取消定时发送",
                CancelIcon,
                MaterialSymbols.Outlined.Cancel,
                isSupported = { true },
                // 取消定时发送针对当前会话的任务列表，多选消息下无意义
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                showCancelSchedulesDialog(view, msgInfo)
            }
        )
    }

    // ------------------------------------------------------------------
    // 功能点一: 定时发送确认弹窗
    // ------------------------------------------------------------------

    private fun showScheduleConfirmDialog(view: View, msgInfos: List<MessageInfo>) {
        CoroutineScope(Dispatchers.IO).launch {
            val talker = msgInfos.first().talker
            val talkerName = runCatching { WeDatabaseApi.getDisplayName(talker) }
                .getOrDefault(talker)

            // 媒体文件先落盘 moduleCache 再注册任务，防止原文件被微信清理
            val segments = buildSegments(msgInfos) ?: return@launch

            withContext(Dispatchers.Main) {
                showComposeDialog(view.context) {
                    ScheduleConfirmDialogContent(
                        talker = talker,
                        talkerName = talkerName,
                        segments = segments,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }

    /**
     * 将选中的消息按顺序转换为 MessageSegment 列表（一条多段任务）。
     * 任一消息媒体获取失败则返回 null（内部已 Toast 提示）。
     */
    @Suppress("DEPRECATION")
    private suspend fun buildSegments(msgInfos: List<MessageInfo>): List<MessageSegment>? {
        val segments = mutableListOf<MessageSegment>()

        msgInfos.forEachIndexed { index, msgInfo ->
            val segment = runCatching { convertToSegment(msgInfo) }
                .onFailure {
                    WeLogger.e(TAG, "failed to convert message #${index + 1} (type=${msgInfo.typeCode})", it)
                }
                .getOrNull()

            if (segment == null) {
                showToastSuspend("第${index + 1}条消息（${msgInfo.type?.displayName ?: "未知类型"}）无法创建定时任务")
                return null
            }
            segments += segment
        }

        return segments
    }

    @Suppress("DEPRECATION")
    private fun convertToSegment(msgInfo: MessageInfo): MessageSegment? {
        return when (msgInfo.type) {
            MessageType.TEXT -> MessageSegment(
                type = ScheduleMessageType.TEXT,
                content = msgInfo.actualContent
            )
            MessageType.QUOTE -> MessageSegment(
                type = ScheduleMessageType.TEXT,
                content = msgInfo.quoteMsgActualContent ?: msgInfo.actualContent
            )
            MessageType.IMAGE -> {
                val imagePath = WeMessageApi.downloadImage(msgInfo.serverId)
                    ?: return null
                val cached = copyMediaToCache(imagePath) ?: return null
                MessageSegment(type = ScheduleMessageType.IMAGE, filePath = cached)
            }
            MessageType.VOICE -> {
                val encPath = msgInfo.imagePath ?: return null
                val voicePath = WeMessageApi.getVoiceFullPath(encPath)
                val cached = copyMediaToCache(voicePath) ?: return null
                MessageSegment(
                    type = ScheduleMessageType.VOICE,
                    filePath = cached,
                    duration = AudioUtils.getDurationMs(voicePath).toInt()
                )
            }
            MessageType.VIDEO, MessageType.MICRO_VIDEO -> {
                val videoPath = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
                if (videoPath.isBlank()) return null
                val cached = copyMediaToCache(videoPath) ?: return null
                MessageSegment(type = ScheduleMessageType.VIDEO, filePath = cached)
            }
            MessageType.FILE -> {
                val filePath = WeMessageApi.downloadFile(msgInfo.instance) ?: return null
                val cached = copyMediaToCache(filePath) ?: return null
                MessageSegment(type = ScheduleMessageType.FILE, filePath = cached)
            }
            else -> null
        }
    }

    /** 把媒体文件复制到 KnownPaths.moduleCache，返回缓存后的绝对路径 */
    private fun copyMediaToCache(sourcePath: String): String? {
        return runCatching {
            val source = java.io.File(sourcePath)
            if (!source.exists() || !source.isFile) return null

            val dest = KnownPaths.moduleCache
                .resolve("sched_${System.currentTimeMillis()}_${source.name}")
            Files.copy(source.toPath(), dest, StandardCopyOption.REPLACE_EXISTING)

            WeLogger.i(TAG, "media file cached: ${dest.absolutePathString()}")
            dest.absolutePathString()
        }.onFailure {
            WeLogger.e(TAG, "copyMediaToCache failed for $sourcePath", it)
        }.getOrNull()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScheduleConfirmDialogContent(
        talker: String,
        talkerName: String,
        segments: List<MessageSegment>,
        onDismiss: () -> Unit
    ) {
        var subject by remember { mutableStateOf("") }
        val timePickerState = rememberTimePickerState(
            initialHour = 10,
            initialMinute = 0,
            is24Hour = true
        )
        var repeatDaily by remember { mutableStateOf(true) }

        AlertDialogContent(
            title = { Text("定时发送") },
            text = {
                DefaultColumn(scrollable = true) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("主题（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("发送对象", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ListItem(
                        headlineContent = { Text(if (talkerName.isNotBlank()) talkerName else talker) },
                        supportingContent = { Text(talker) }
                    )

                    Spacer(Modifier.height(8.dp))
                    Text("发送时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TimePicker(state = timePickerState)

                    ListItem(
                        modifier = Modifier.clickable { repeatDaily = !repeatDaily },
                        trailingContent = { Switch(checked = repeatDaily, onCheckedChange = null) },
                        headlineContent = { Text("每天重复") },
                        supportingContent = { Text(if (repeatDaily) "每天同一时间发送" else "仅发送一次") }
                    )

                    ListItem(
                        headlineContent = { Text("内容摘要") },
                        supportingContent = { Text(segmentsSummary(segments)) }
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    if (subject.isBlank()) {
                        showToast("请输入主题")
                        return@Button
                    }

                    val schedule = ScheduleConfig(
                        id = "${System.currentTimeMillis()}",
                        talker = talker,
                        talkerName = talkerName,
                        subject = subject.trim(),
                        hour = timePickerState.hour,
                        minute = timePickerState.minute,
                        repeatDaily = repeatDaily,
                        enabled = true,
                        segments = segments
                    )

                    ScheduledMessage.addSchedule(schedule)
                    showToast("定时任务已添加")
                    onDismiss()
                }) { Text("确认") }
            }
        )
    }

    // ------------------------------------------------------------------
    // 功能点二: 取消定时发送弹窗（仅单选消息）
    // ------------------------------------------------------------------

    private fun showCancelSchedulesDialog(view: View, msgInfo: MessageInfo) {
        val talker = msgInfo.talker
        val talkerSchedules = ScheduledMessage.getSchedulesFor(talker)

        showComposeDialog(view.context) {
            var selectedIds by remember { mutableStateOf(setOf<String>()) }

            AlertDialogContent(
                title = { Text("取消定时发送") },
                text = {
                    DefaultColumn(scrollable = true) {
                        if (talkerSchedules.isEmpty()) {
                            Text(
                                "当前会话暂无定时发送任务",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        } else {
                            talkerSchedules.forEach { schedule ->
                                val checked = schedule.id in selectedIds
                                ListItem(
                                    modifier = Modifier.clickable {
                                        selectedIds = if (checked) selectedIds - schedule.id
                                        else selectedIds + schedule.id
                                    },
                                    leadingContent = {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = null
                                        )
                                    },
                                    headlineContent = { Text(schedule.subject.ifBlank { "(无主题)" }) },
                                    supportingContent = {
                                        Text(
                                            "${schedule.hour.toString().padStart(2, '0')}:" +
                                                    "${schedule.minute.toString().padStart(2, '0')} " +
                                                    "${if (schedule.repeatDaily) "每天" else "单次"} · " +
                                                    scheduleSummary(schedule)
                                        )
                                    }
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
                confirmButton = {
                    if (talkerSchedules.isNotEmpty()) {
                        Button(onClick = {
                            if (selectedIds.isEmpty()) {
                                showToast("请先勾选要取消的任务")
                                return@Button
                            }

                            val count = selectedIds.size
                            talkerSchedules.filter { it.id in selectedIds }
                                .forEach { ScheduledMessage.deleteSchedule(it) }
                            showToast("已取消 $count 个定时任务")
                            onDismiss()
                        }) { Text("取消所选") }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------

    private fun segmentsSummary(segments: List<MessageSegment>): String {
        if (segments.isEmpty()) return "空任务"
        val typesStr = segments.joinToString("+") { it.type.description }
        return "${segments.size}段消息: $typesStr"
    }

    /** 兼容旧数据（单段 legacy 字段）的任务摘要 */
    private fun scheduleSummary(schedule: ScheduleConfig): String {
        return if (schedule.segments.isNotEmpty()) {
            segmentsSummary(schedule.segments)
        } else {
            schedule.messageType.description
        }
    }
}
