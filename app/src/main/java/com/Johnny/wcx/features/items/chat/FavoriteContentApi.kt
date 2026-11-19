package com.Johnny.wcx.features.items.chat

import android.database.Cursor
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.utils.WeLogger

/**
 * 收藏内容读取 API（供「定时发送 → 从收藏添加」使用）。
 *
 * 微信收藏主表为 FavItemInfo（位于独立数据库 enFavorite.db，连接由 WeDatabaseApi hook 捕获）：
 *  - localId / id / type / itemStatus / flag / updateTime / fromUser
 *  - xml：明文 XML，含 <title>、<desc>、<...> 等，文本收藏的内容主要在这里
 *  - favProto：二进制 protobuf，媒体收藏的详细信息在这里
 *
 * 常见 type：1=文本, 2=图片, 3=语音, 4=视频, 5/6=链接/表情, 8/14=文件/应用消息等。
 *
 * 这里只做「内容选取 → 定时任务段」所需的轻量解析：
 *  - 文本类：从 xml 提取可读文本，直接作为 TEXT 段发送
 *  - 媒体类：从 xml 提取已落地的本地路径（filePath/datafmt 等），作为对应类型段；
 *    路径拿不到时降级为文本段（附类型说明），避免创建出无法发送的空段。
 */
object FavoriteContentApi {
    private const val TAG = "FavoriteContentApi"

    data class FavoriteEntry(
        val type: Type,
        val preview: String,
        val filePath: String = "",
        val duration: Int = 0
    ) {
        enum class Type(val label: String) {
            TEXT("文本"),
            IMAGE("图片"),
            VOICE("语音"),
            VIDEO("视频"),
            FILE("文件")
        }
    }

    /** 最多加载的收藏条数，避免一次性把整个收藏表读进内存。 */
    private const val LIMIT = 200

    fun loadFavorites(): List<FavoriteEntry> {
        val favDb = WeDatabaseApi.favoriteDb
        if (favDb == null) {
            WeLogger.w(TAG, "favorite db not ready")
            return emptyList()
        }
        return try {
            val cursor: Cursor = favDb.rawQuery(
                "SELECT type, xml FROM FavItemInfo " +
                    "WHERE itemStatus > 0 AND flag <> -1 ORDER BY updateTime DESC LIMIT $LIMIT",
                null
            )
            cursor.use { c ->
                val result = mutableListOf<FavoriteEntry>()
                while (c.moveToNext()) {
                    val type = c.getInt(0)
                    val xml = c.getString(1).orEmpty()
                    val resultEntry = parseEntry(type, xml)
                    if (resultEntry != null) result += resultEntry
                }
                WeLogger.i(TAG, "loaded ${result.size} favorites")
                result
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "loadFavorites failed", e)
            emptyList()
        }
    }

    private fun parseEntry(typeCode: Int, xml: String): FavoriteEntry? {
        if (xml.isBlank()) return null
        val title = extractTag(xml, "title")
        val desc = extractTag(xml, "desc")
        val localPath = firstExistingPath(
            extractTag(xml, "filePath"),
            extractTag(xml, "path"),
            extractAttr(xml, "path"),
            extractTag(xml, "fileid")
        )

        return when (typeCode) {
            1 -> FavoriteEntry(
                FavoriteEntry.Type.TEXT,
                desc.ifBlank { title }.ifBlank { "(文本收藏)" }.take(200)
            )
            2 -> FavoriteEntry(
                FavoriteEntry.Type.IMAGE,
                title.ifBlank { "[图片收藏]" }.take(200),
                filePath = localPath
            )
            3 -> FavoriteEntry(
                FavoriteEntry.Type.VOICE,
                title.ifBlank { "[语音收藏]" }.take(200),
                filePath = localPath
            )
            4 -> FavoriteEntry(
                FavoriteEntry.Type.VIDEO,
                title.ifBlank { "[视频收藏]" }.take(200),
                filePath = localPath
            )
            else -> {
                // 文件/链接/应用消息等：能定位到本地文件则按文件段，否则降级为可发送的文本摘要
                val text = buildString {
                    append(title)
                    if (desc.isNotBlank() && desc != title) append(" - ").append(desc)
                }.trim().ifBlank { "[收藏 type=$typeCode]" }
                if (localPath.isNotEmpty()) {
                    FavoriteEntry(FavoriteEntry.Type.FILE, text.take(200), filePath = localPath)
                } else {
                    FavoriteEntry(FavoriteEntry.Type.TEXT, text.take(200))
                }
            }
        }
    }

    private fun extractTag(xml: String, tag: String): String {
        val regex = Regex("<$tag\\b[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)
            ?.trim()?.ifBlank { null } ?: ""
    }

    private fun extractAttr(xml: String, attr: String): String {
        val regex = Regex("$attr\\s*=\\s*[\"']([^\"']+)[\"']")
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    private fun firstExistingPath(vararg candidates: String): String {
        return candidates.firstOrNull {
            it.isNotBlank() && !it.startsWith("http") && java.io.File(it).exists()
        }.orEmpty()
    }
}
