package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.utils.WeLogger
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatFavoriteItem

/**
 * 收藏内容读取 API（供「定时发送 → 从收藏添加」使用）。
 *
 * 直接复用 Hchat 子系统已经验证成熟的 WeChatFavoriteApi：
 *  - listAll()/listRecent() 返回收藏项（localId / type / title / summary / 大小 / 时间）
 *  - 发送由其 favorites().send(talker, localId) 走微信原生收藏发送通道，
 *    图片/语音/视频/文件/链接/位置/音乐/小程序/聊天记录等全部原样支持。
 *
 * 这样无需解析 enFavorite.db 的 favProto BLOB 或硬编码各媒体类型的 protobuf 字段号。
 */
object FavoriteContentApi {
    private const val TAG = "FavoriteContentApi"

    data class FavoriteEntry(
        /** 微信收藏项本地主键，发送时用它调用 favorites().send(talker, localId)。 */
        val localId: Long,
        val type: Type,
        val preview: String,
        val summary: String = ""
    ) {
        enum class Type(val label: String) {
            TEXT("文本"),
            IMAGE("图片"),
            VOICE("语音"),
            VIDEO("视频"),
            FILE("文件"),
            OTHER("其他")
        }
    }

    /** 最多加载的收藏条数，避免一次性把整个收藏表读进内存。 */
    private const val LIMIT = 200

    fun loadFavorites(): List<FavoriteEntry> {
        val api = runCatching { WeChatApis.media()?.favorites() }.getOrNull()
        if (api == null) {
            WeLogger.w(TAG, "favorite api not ready")
            return emptyList()
        }
        return try {
            // listAll 带分页缓存；失败时退回最近若干条。
            val allItems: List<WeChatFavoriteItem>? = try {
                api.listAll()
            } catch (e: Exception) {
                WeLogger.w(TAG, "listAll failed, fallback to listRecent", e)
                null
            }
            val items: List<WeChatFavoriteItem> =
                allItems?.takeIf { it.isNotEmpty() } ?: api.listRecent(LIMIT)
            items.take(LIMIT).map(::mapItem)
        } catch (e: Exception) {
            WeLogger.e(TAG, "loadFavorites failed", e)
            emptyList()
        }
    }

    private fun mapItem(item: WeChatFavoriteItem): FavoriteEntry {
        val type = when (item.type) {
            1 -> FavoriteEntry.Type.TEXT
            2 -> FavoriteEntry.Type.IMAGE
            3 -> FavoriteEntry.Type.VOICE
            4, 15 -> FavoriteEntry.Type.VIDEO
            8 -> FavoriteEntry.Type.FILE
            else -> FavoriteEntry.Type.OTHER
        }
        return FavoriteEntry(
            localId = item.localId,
            type = type,
            preview = item.displayTitle().take(200),
            summary = item.displaySummary().take(120)
        )
    }
}
