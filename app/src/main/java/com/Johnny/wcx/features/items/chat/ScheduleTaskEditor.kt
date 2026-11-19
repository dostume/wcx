package com.Johnny.wcx.features.items.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.items.chat.FavoriteContentApi.FavoriteEntry
import com.Johnny.wcx.features.items.chat.ScheduledMessage.MessageSegment
import com.Johnny.wcx.features.items.chat.ScheduledMessage.MessageType
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString

/**
 * 定时任务编辑结果（共享编辑器的输出）。
 *
 * 由 [ScheduleTaskEditor] 产出，两个入口（模块功能列表 / 消息长按菜单）都消费它，
 * 因此两个入口创建的定时任务字段语义完全一致。
 */
data class ScheduleTaskDraft(
    val subject: String = "",
    val talker: String = "",
    val talkerName: String = "",
    val segments: List<MessageSegment> = emptyList(),
    val hour: Int = 9,
    val minute: Int = 0,
    val repeatDaily: Boolean = true,
    val enabled: Boolean = true
)

/**
 * 共用的定时任务编辑器。
 *
 * 模块端（功能列表 → 添加定时任务）与长按端（消息长按 → 定时发送）**共用这一套 UI**，
 * 保证两个入口的功能完全一致：
 *  - 发送对象（联系人/群，可搜索）
 *  - 消息段列表（逐段显示、可删除）
 *  - 「+ 添加消息段」（文本/链接/图片/语音/视频/文件）
 *  - 「从收藏添加内容」（收藏多选，确定后追加到任务；图片/语音/视频/文件按微信原生类型发送）
 *  - 发送时间 / 每天重复 / 启用
 *
 * @param subjectRequired 长按入口要求填写主题；模块入口不显示主题输入框。
 */
@Composable
internal fun ScheduleTaskEditor(
    title: String,
    initialTalker: String = "",
    initialTalkerName: String = "",
    initialSegments: List<MessageSegment> = emptyList(),
    initialHour: Int = 9,
    initialMinute: Int = 0,
    initialRepeatDaily: Boolean = true,
    initialEnabled: Boolean = true,
    subjectRequired: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleTaskDraft) -> Unit
) {
    var subject by remember { mutableStateOf("") }
    var talker by remember { mutableStateOf(initialTalker) }
    var talkerName by remember { mutableStateOf(initialTalkerName) }
    val segments = remember {
        mutableStateListOf<MessageSegment>().apply { addAll(initialSegments) }
    }
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    var repeatDaily by remember { mutableStateOf(initialRepeatDaily) }
    var enabled by remember { mutableStateOf(initialEnabled) }

    var showSegmentTypeDialog by remember { mutableStateOf(false) }
    var pendingSegmentType by remember { mutableStateOf<MessageType?>(null) }
    var showSegmentEditDialog by remember { mutableStateOf(false) }
    var tempContent by remember { mutableStateOf("") }
    var tempFilePath by remember { mutableStateOf("") }
    var tempDuration by remember { mutableStateOf("0") }

    val context = LocalContext.current

    AlertDialogContent(
        title = { Text(title) },
        text = {
            DefaultColumn(scrollable = true) {
                if (subjectRequired) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("主题（必填）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── 发送对象 ──
                ListItem(
                    modifier = Modifier.clickable {
                        openContactPicker(context, talker) { wxId, name ->
                            talker = wxId
                            talkerName = name
                        }
                    },
                    headlineContent = {
                        Text(if (talkerName.isNotBlank()) talkerName else "选择发送对象")
                    },
                    supportingContent = {
                        Text(if (talker.isNotBlank()) talker else "点击选择联系人/群")
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("消息段列表", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                if (segments.isEmpty()) {
                    Text(
                        "还没有消息段，点击下方按钮添加",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    segments.forEachIndexed { index, segment ->
                        ListItem(
                            headlineContent = { Text(segment.summary()) },
                            supportingContent = {
                                Text("第${index + 1}段 - ${segment.type.description}")
                            },
                            trailingContent = {
                                TextButton(onClick = { segments.removeAt(index) }) { Text("删除") }
                            }
                        )
                    }
                }

                Button(
                    onClick = { showSegmentTypeDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ 添加消息段") }

                Spacer(modifier = Modifier.height(4.dp))
                ListItem(
                    modifier = Modifier.clickable {
                        openFavoritePicker(context) { seg -> segments.add(seg) }
                    },
                    headlineContent = {
                        Text("从收藏添加内容", color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = { Text("点击选择收藏的消息，追加到本任务") }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("发送时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TimePicker(state = timePickerState)

                ListItem(
                    modifier = Modifier.clickable { repeatDaily = !repeatDaily },
                    trailingContent = { Switch(checked = repeatDaily, onCheckedChange = null) },
                    headlineContent = { Text("每天重复") },
                    supportingContent = {
                        Text(if (repeatDaily) "每天同一时间发送" else "仅发送一次")
                    }
                )

                ListItem(
                    modifier = Modifier.clickable { enabled = !enabled },
                    trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                    headlineContent = { Text("启用") },
                    supportingContent = {
                        Text(if (enabled) "任务将在指定时间执行" else "任务不会执行")
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                val h = timePickerState.hour
                val m = timePickerState.minute
                onConfirm(
                    ScheduleTaskDraft(
                        subject = subject.trim(),
                        talker = talker,
                        talkerName = talkerName,
                        segments = segments.toList(),
                        hour = h,
                        minute = m,
                        repeatDaily = repeatDaily,
                        enabled = enabled
                    )
                )
            }) { Text("确定") }
        }
    )

    // ── 段类型选择 ──
    if (showSegmentTypeDialog) {
        AlertDialogContent(
            title = { Text("选择消息段类型") },
            text = {
                DefaultColumn {
                    // FAVORITE 段只能通过「从收藏添加内容」产生, 不在这里手选。
                    MessageType.values().filter { it != MessageType.FAVORITE }.forEach { type ->
                        ListItem(
                            modifier = Modifier.clickable {
                                pendingSegmentType = type
                                tempContent = ""
                                tempFilePath = ""
                                tempDuration = "0"
                                showSegmentTypeDialog = false
                                showSegmentEditDialog = true
                            },
                            headlineContent = { Text(type.description) }
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSegmentTypeDialog = false }) { Text("取消") }
            },
            confirmButton = {}
        )
    }

    // ── 段内容编辑 ──
    if (showSegmentEditDialog && pendingSegmentType != null) {
        val type = pendingSegmentType!!
        AlertDialogContent(
            title = { Text("添加${type.description}消息段") },
            text = {
                DefaultColumn {
                    when (type) {
                        MessageType.TEXT -> {
                            OutlinedTextField(
                                value = tempContent,
                                onValueChange = { tempContent = it },
                                label = { Text("消息内容") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                        }
                        MessageType.LINK -> {
                            OutlinedTextField(
                                value = tempContent,
                                onValueChange = { tempContent = it },
                                label = { Text("链接URL") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                        MessageType.VOICE -> {
                            Button(
                                onClick = { pickMediaFile(type) { path -> tempFilePath = path } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("选择语音文件") }
                            if (tempFilePath.isNotBlank()) {
                                Text(
                                    "已选: ${tempFilePath.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedTextField(
                                value = tempDuration,
                                onValueChange = { tempDuration = it.filter { c -> c.isDigit() } },
                                label = { Text("语音时长（毫秒）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        MessageType.IMAGE, MessageType.VIDEO -> {
                            Button(
                                onClick = { pickMediaFile(type) { path -> tempFilePath = path } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("选择${type.description}文件") }
                            if (tempFilePath.isNotBlank()) {
                                Text(
                                    "已选: ${tempFilePath.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        MessageType.FILE -> {
                            Button(
                                onClick = { pickMediaFile(type) { path -> tempFilePath = path } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("选择文件") }
                            if (tempFilePath.isNotBlank()) {
                                Text(
                                    "已选: ${tempFilePath.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        MessageType.FAVORITE -> {
                            Text("收藏内容请用「从收藏添加内容」选择")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSegmentEditDialog = false
                    pendingSegmentType = null
                }) { Text("取消") }
            },
            confirmButton = {
                Button(onClick = {
                    segments.add(
                        MessageSegment(
                            type = type,
                            content = tempContent,
                            filePath = tempFilePath,
                            duration = tempDuration.toIntOrNull() ?: 0
                        )
                    )
                    showSegmentEditDialog = false
                    pendingSegmentType = null
                }) { Text("添加") }
            }
        )
    }
}

// ─────────────────────────────── 联系人选择 ───────────────────────────────

/** 打开联系人/群选择器（单选）。 */
internal fun openContactPicker(
    context: android.content.Context,
    initialWxId: String,
    onSelected: (String, String) -> Unit
) {
    showComposeDialog(context, fullScreen = true) {
        ContactPickerContent(
            initialWxId = initialWxId,
            onDismiss = onDismiss,
            onSelected = onSelected
        )
    }
}

@Composable
private fun ContactPickerContent(
    initialWxId: String,
    onDismiss: () -> Unit,
    onSelected: (String, String) -> Unit
) {
    var contacts by remember { mutableStateOf<List<IWeContact>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list: List<IWeContact> = runCatching {
                WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() +
                    WeDatabaseApi.getOfficialAccounts()
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { contacts = list }
        }
    }
    SingleContactSelector(
        title = "选择发送对象（联系人/群）",
        contacts = contacts,
        initialSelectedWxId = initialWxId.takeIf { it.isNotBlank() },
        onDismiss = onDismiss,
        onConfirm = { wxId ->
            val name = runCatching { WeDatabaseApi.getDisplayName(wxId) }.getOrDefault("")
                .takeIf { it.isNotBlank() && it != wxId }
                ?: contacts.firstOrNull { it.wxId == wxId }?.nickname?.takeIf { it.isNotBlank() }
                ?: wxId
            onSelected(wxId, name)
        }
    )
}

// ─────────────────────────────── 收藏选择 ───────────────────────────────

/**
 * 打开收藏选择器（多选）。
 *
 * 点条目只切换勾选态，**不立刻关闭弹窗** —— 否则用户既看不到选中效果、也没有反悔机会；
 * 选好后点「确定」一次性追加到任务。
 */
internal fun openFavoritePicker(
    context: android.content.Context,
    onSelected: (MessageSegment) -> Unit
) {
    showComposeDialog(context, fullScreen = true) {
        FavoritePickerContent(onDismiss = onDismiss, onSelected = onSelected)
    }
}

@Composable
private fun FavoritePickerContent(
    onDismiss: () -> Unit,
    onSelected: (MessageSegment) -> Unit
) {
    var items by remember { mutableStateOf<List<FavoriteEntry>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = FavoriteContentApi.loadFavorites()
            withContext(Dispatchers.Main) { items = list }
        }
    }
    AlertDialogContent(
        title = { Text("从收藏添加") },
        text = {
            DefaultColumn(scrollable = true) {
                if (items.isEmpty()) {
                    Text(
                        "暂无可添加的收藏，或收藏数据尚未就绪",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Text(
                        "可多选，选好后点「确定」添加到任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    items.forEach { entry ->
                        val selected = entry.localId in selectedIds
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedIds = if (selected) {
                                        selectedIds - entry.localId
                                    } else {
                                        selectedIds + entry.localId
                                    }
                                },
                            colors = ListItemDefaults.colors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            ),
                            leadingContent = {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        selectedIds = if (selected) {
                                            selectedIds - entry.localId
                                        } else {
                                            selectedIds + entry.localId
                                        }
                                    }
                                )
                            },
                            headlineContent = {
                                Text(
                                    entry.preview.ifBlank { "(空收藏)" },
                                    maxLines = 2,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (entry.summary.isNotBlank()) "${entry.type.label} · ${entry.summary}"
                                    else entry.type.label
                                )
                            }
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                enabled = selectedIds.isNotEmpty(),
                onClick = {
                    // 按列表顺序回传，段顺序与用户看到的顺序一致。
                    items.filter { it.localId in selectedIds }.forEach { entry ->
                        // 统一交给微信原生收藏发送通道: 图片/语音/视频/文件等
                        // 都由 favorites().send(talker, localId) 原样发出。
                        onSelected(
                            MessageSegment(
                                type = MessageType.FAVORITE,
                                content = entry.localId.toString()
                            )
                        )
                    }
                    onDismiss()
                }
            ) { Text("确定") }
        }
    )
}

// ─────────────────────────────── 媒体文件选择 ───────────────────────────────

/**
 * 通过 TransparentActivity 拉起系统文件选择器，把选中的 Uri 内容拷贝到 moduleCache
 * 并把本地路径回调给调用方。IMAGE/VIDEO 走 PickVisualMedia，VOICE/FILE 走 OpenDocument。
 */
private fun pickMediaFile(
    type: MessageType,
    onPicked: (String) -> Unit
) {
    TransparentActivity.launch(HostInfo.application) {
        if (type == MessageType.IMAGE || type == MessageType.VIDEO) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult
                copyUriToCache(uri, type)?.let(onPicked)
            }
            val visualMediaType = if (type == MessageType.IMAGE) {
                ActivityResultContracts.PickVisualMedia.ImageOnly
            } else {
                ActivityResultContracts.PickVisualMedia.VideoOnly
            }
            launcher.launch(PickVisualMediaRequest(visualMediaType))
        } else {
            val mimeTypes = when (type) {
                MessageType.VOICE -> arrayOf("audio/*")
                else -> arrayOf("*/*")
            }
            val launcher = registerForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult
                copyUriToCache(uri, type)?.let(onPicked)
            }
            launcher.launch(mimeTypes)
        }
    }
}

private fun FragmentActivity.copyUriToCache(uri: Uri, type: MessageType): String? {
    return try {
        val resolver = contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "picked_${System.currentTimeMillis()}"
        val safeName = displayName.ifBlank { "picked_${System.currentTimeMillis()}" }
        val destFile = KnownPaths.moduleCache.resolve("sched_${System.currentTimeMillis()}_$safeName")
        resolver.openInputStream(uri)?.use { input ->
            Files.copy(input, destFile, StandardCopyOption.REPLACE_EXISTING)
        } ?: run {
            WeLogger.e(TAG, "copyUriToCache: openInputStream returned null for $uri")
            return null
        }
        WeLogger.i(TAG, "picked media file cached: ${destFile.absolutePathString()}")
        destFile.absolutePathString()
    } catch (e: Exception) {
        WeLogger.e(TAG, "copyUriToCache failed for type=$type uri=$uri", e)
        null
    }
}

private const val TAG = "ScheduleTaskEditor"

// ─────────────────────────────── 摘要工具 ───────────────────────────────

/** 单个消息段的展示摘要。 */
internal fun MessageSegment.summary(): String {
    val byRef = filePath.isBlank() && (srcSvrId > 0 || srcMsgId > 0)
    return when (type) {
        MessageType.TEXT -> "文本: ${content.take(20)}"
        MessageType.LINK -> "链接: ${content.take(20)}"
        MessageType.IMAGE ->
            if (byRef) "图片: 引用原消息" else "图片: ${filePath.substringAfterLast('/').take(20)}"
        MessageType.VOICE ->
            if (byRef) "语音: 引用原消息" else "语音: ${duration}ms"
        MessageType.VIDEO ->
            if (byRef) "视频: 引用原消息" else "视频: ${filePath.substringAfterLast('/').take(20)}"
        MessageType.FILE ->
            if (byRef) "文件: 引用原消息" else "文件: ${filePath.substringAfterLast('/').take(20)}"
        MessageType.FAVORITE -> "收藏: ${content.take(30)}"
    }
}

/** 多个消息段的概览摘要。 */
internal fun segmentsSummary(segments: List<MessageSegment>): String {
    if (segments.isEmpty()) return ""
    val typesStr = segments.joinToString("+") { it.type.description }
    return "${segments.size}段消息: $typesStr"
}
