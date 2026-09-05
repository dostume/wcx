package com.Johnny.wcx.features.items.batch

import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeNativePickerBridge
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    name = "批量标为已读",
    categories = ["批量操作"],
    description = "选择多个好友或群聊后, 将它们的对话一次性标记为已读"
)
object BatchMarkAsRead : ClickableFeature() {

    private const val TAG = "BatchMarkAsRead"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        // 优先唤起微信原版多选页; 页面不可用时降级自绘选择器
        val launched = WeNativePickerBridge.launch(
            activity = context,
            options = WeNativePickerBridge.Options(
                title = "选择要标为已读的对话",
                multiSelect = true,
                allowOfficialAccounts = false,
            ),
            onResult = { wxIds ->
                if (wxIds.isEmpty()) {
                    WeNativePickerBridge.toastEmptySelection()
                    return@launch
                }
                markAsRead(wxIds.toSet())
            }
        )
        if (launched) return

        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要标为已读的对话",
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个对话")
                        return@ContactsSelector
                    }

                    onDismiss()
                    markAsRead(selectedWxIds)
                }
            )
        }
    }

    private fun markAsRead(wxIds: Set<String>) {
        // These are local DB writes (no server CGI), so no rate-limit pacing is needed.
        CoroutineScope(Dispatchers.IO).launch {
            wxIds.forEach { wxId ->
                runCatching { WeConversationApi.markAsRead(wxId) }
                    .onFailure { WeLogger.e(TAG, "failed to mark $wxId as read", it) }
            }
            WeConversationApi.reloadConversations()
            showToastSuspend("已将 ${wxIds.size} 个对话标为已读")
        }
    }
}
