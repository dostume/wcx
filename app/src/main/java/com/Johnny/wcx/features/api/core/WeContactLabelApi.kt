package com.Johnny.wcx.features.api.core

import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.api.core.WeContactLabelApi.modifyLabel
import com.Johnny.wcx.features.api.net.WeNetSceneApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.DexKit
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import java.util.LinkedList

@Feature(name = "联系人标签服务", categories = ["API"], description = "提供联系人标签查询与修改能力")
object WeContactLabelApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeContactLabelApi"

    data class ContactLabel(val labelId: Int, val labelName: String)

    private val classNetSceneModifyContactLabelList by dexClass {
        matcher {
            usingEqStrings("/cgi-bin/micromsg-bin/modifycontactlabellist")
            addMethod {
                name = "<init>"
                paramTypes(LinkedList::class.java)
            }
        }
    }

    /**
     * `NetSceneAddContactLabel` (e.g. `e93.a` on 8.0.74). Anchored on the unique cgi path plus its
     * single-`String` constructor, which takes the new label's name. On success the host persists
     * the label (with a server-assigned id) into the `ContactLabel` table inside `onGYNetEnd`.
     */
    private val classNetSceneAddContactLabel by dexClass {
        matcher {
            usingEqStrings("/cgi-bin/micromsg-bin/addcontactlabel")
            addMethod {
                name = "<init>"
                paramTypes(String::class.java)
            }
        }
    }

    /**
     * Get all contact labels.
     * @return List of ContactLabel sorted alphabetically by name
     */
    fun getAllLabels(): List<ContactLabel> {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT labelID, labelName FROM ContactLabel ORDER BY labelName"
            )
            val labels = mutableListOf<ContactLabel>()
            cursor.use {
                while (it.moveToNext()) {
                    labels.add(
                        ContactLabel(
                            it.getInt(it.getColumnIndexOrThrow("labelID")),
                            it.getString(it.getColumnIndexOrThrow("labelName"))
                        )
                    )
                }
            }
            labels
        } catch (e: Exception) {
            WeLogger.e(TAG, "getAllLabels failed", e)
            emptyList()
        }
    }

    // rcontact 里存放标签 id 的列名在不同微信版本上并不一致 (历史上是 contactLabelIds)。
    // 写死列名的话, 版本不匹配时 SQL 直接抛错并被兜底吞成空列表 —— 表现就是
    // "标签列表能显示, 但选中某个标签后一个联系人都选不到"。这里运行时解析一次并缓存。
    private val KNOWN_LABEL_COLUMNS = listOf(
        "contactLabelIds",
        "contactLabelIDList",
        "contactLabels",
        "labelIds",
        "labelIDList",
    )
    // 分隔方式同样不统一 (逗号/分号/空格, 有的版本还带方括号), 统一按非数字切分。
    private val LABEL_ID_SEPARATOR = Regex("[^0-9]+")

    private var labelColumnResolved = false
    private var cachedLabelColumn: String? = null

    private fun tableColumns(table: String): List<String> {
        val columns = mutableListOf<String>()
        WeDatabaseApi.rawQuery("PRAGMA table_info($table)").use { cursor ->
            val index = cursor.getColumnIndex("name")
            if (index < 0) return emptyList()
            while (cursor.moveToNext()) columns.add(cursor.getString(index))
        }
        return columns
    }

    private fun resolveContactLabelColumn(): String? {
        if (labelColumnResolved) return cachedLabelColumn
        labelColumnResolved = true
        val available = runCatching { tableColumns("rcontact") }.getOrNull().orEmpty()
        val hit = available.firstOrNull { col ->
            KNOWN_LABEL_COLUMNS.any { it.equals(col, ignoreCase = true) }
        } ?: available.firstOrNull { col ->
            // 退一步: 名字同时含 label 与 id 的列
            val lower = col.lowercase()
            lower.contains("label") && lower.contains("id")
        } ?: available.firstOrNull { col -> col.lowercase().contains("label") }
        cachedLabelColumn = hit
        WeLogger.i(TAG, "rcontact 标签列解析结果 = $hit")
        return hit
    }

    private fun String.matchesLabelId(labelId: Int): Boolean =
        split(LABEL_ID_SEPARATOR).any { it.toIntOrNull() == labelId }

    /**
     * Get contacts belonging to a specific label, by label ID.
     *
     * 返回的 username 与 `WeContact.wxId` 同源 (都是 rcontact.username), 可直接用于筛选。
     * 解析不到标签列时会继续尝试标签关系表, 都失败才返回空列表。
     */
    fun getContactsByLabelId(labelId: Int): List<String> {
        val column = resolveContactLabelColumn()
        if (column != null) {
            runCatching {
                val wxids = mutableListOf<String>()
                WeDatabaseApi.rawQuery(
                    "SELECT username, $column FROM rcontact WHERE $column IS NOT NULL AND $column != ''"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val value = cursor.getString(1) ?: continue
                        if (value.matchesLabelId(labelId)) wxids.add(cursor.getString(0))
                    }
                }
                return wxids
            }.onFailure { e ->
                WeLogger.w(TAG, "getContactsByLabelId failed on column=$column", e)
            }
        }
        return getContactsByLabelIdFromRelationTable(labelId)
    }

    /**
     * 兜底: 有些版本把标签关系单独放在一张表里。扫描名字含 label 的表 (排除 ContactLabel 本身),
     * 取其中同时具备"标签列"与"用户名列"的那种结构来解析成员。
     */
    private fun getContactsByLabelIdFromRelationTable(labelId: Int): List<String> {
        val wxids = mutableListOf<String>()
        val tables = runCatching {
            val found = mutableListOf<String>()
            WeDatabaseApi.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND lower(name) LIKE '%label%'"
            ).use { cursor ->
                while (cursor.moveToNext()) found.add(cursor.getString(0))
            }
            found
        }.getOrNull().orEmpty()

        for (table in tables) {
            if (table.equals("ContactLabel", ignoreCase = true)) continue
            runCatching {
                val columns = tableColumns(table)
                val labelColumn = columns.firstOrNull { it.lowercase().contains("label") } ?: return@runCatching
                val userColumn = columns.firstOrNull {
                    val lower = it.lowercase()
                    lower == "username" || lower == "wxid" || lower.contains("username")
                } ?: return@runCatching
                WeDatabaseApi.rawQuery(
                    "SELECT $userColumn, $labelColumn FROM $table WHERE $labelColumn IS NOT NULL AND $labelColumn != ''"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val value = cursor.getString(1) ?: continue
                        if (value.matchesLabelId(labelId)) wxids.add(cursor.getString(0))
                    }
                }
                if (wxids.isNotEmpty()) {
                    WeLogger.i(TAG, "标签成员改由关系表 $table 解析得到")
                    return wxids
                }
            }.onFailure { e ->
                WeLogger.w(TAG, "failed to read label relation table=$table", e)
            }
        }
        return wxids
    }

    /**
     * Get the label names currently associated with a contact.
     * Reads the comma-separated `contactLabelIds` column off `rcontact` and resolves each id
     * back to its name. Useful for additive label edits, since [modifyLabel] replaces the whole
     * label set of a contact rather than appending to it.
     */
    fun getLabelNamesForContact(username: String): List<String> {
        return try {
            val column = resolveContactLabelColumn() ?: return emptyList()
            val labelIds = WeDatabaseApi.rawQuery(
                "SELECT $column FROM rcontact WHERE username = ?",
                arrayOf(username)
            ).use {
                if (it.moveToFirst()) it.getString(0) else null
            }

            if (labelIds.isNullOrBlank()) return emptyList()

            val idToName = getAllLabels().associate { it.labelId to it.labelName }
            labelIds.split(LABEL_ID_SEPARATOR)
                .mapNotNull { it.trim().toIntOrNull() }
                .mapNotNull { idToName[it] }
        } catch (e: Exception) {
            WeLogger.e(TAG, "getLabelNamesForContact failed", e)
            emptyList()
        }
    }

    /**
     * Get contacts belonging to a specific label, by label name.
     * First resolves the label name to label ID, then queries contacts.
     */
    fun getContactsByLabelName(labelName: String): List<String> {
        return try {
            val labelId = getLabelIdByName(labelName) ?: return emptyList()
            getContactsByLabelId(labelId)
        } catch (e: Exception) {
            WeLogger.e(TAG, "getContactsByLabelName failed", e)
            emptyList()
        }
    }

    /**
     * The contact-label protobuf class (e.g. `fs4.il6` on 8.0.65) cannot be located by structural
     * matching alone: ~250 protobuf classes share the exact same shape (extends
     * `com.tencent.mm.protobuf.f`, exactly two `String` fields). Instead, we anchor on the unique
     * string "UserLabelInfoList".
     *
     * The response handler that uses that string (e.g. `m03.m2.call`) iterates a
     * `LinkedList<ContactLabelPb>` and reads the `UserName` String field off each element. That
     * field read is an `iget-object` whose declaring class is exactly the protobuf class we want.
     * So: among methods that use "UserLabelInfoList", find a read of a `String` field whose
     * declaring class has exactly two fields, both `String` — that class is the target.
     */
    private val classContactLabelPb by dexClass()

    override fun resolveDex(dexKit: DexKitBridge) {
        val candidates = DexKit.findMethod {
            matcher {
                usingEqStrings("UserLabelInfoList")
            }
        }.asSequence()
            .flatMap { it.usingFields.asSequence() }
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .filter { it.typeName == "java.lang.String" }
            .map { it.declaredClass }
            .filter { clazz ->
                val fields = clazz.fields
                fields.size == 2 && fields.all { it.typeName == "java.lang.String" }
            }
            .map { it.name }
            .distinct()
            .toList()

        val className = candidates.singleOrNull()
        if (className == null) {
            WeLogger.e(TAG, "contactLabelPbClass resolution failed, candidates=$candidates")
            error("failed to locate classContactLabelPb")
        }

        classContactLabelPb.setDescriptor(className)
    }

    /**
     * Create a contact label by name, returning its label id once the server has assigned one.
     *
     * If a label with [labelName] already exists it is returned immediately. Otherwise the
     * `addcontactlabel` netscene is dispatched; since dispatching is fire-and-forget and the host
     * only writes the new label (with its server-assigned id) into the `ContactLabel` table once
     * the response arrives, this polls the DB up to [timeoutMs] for the id to show up.
     *
     * Must be called off the main thread (it sleeps while polling).
     *
     * @return the label id, or null if creation could not be confirmed within [timeoutMs].
     */
    fun createLabel(labelName: String, timeoutMs: Long = 10_000): Int? {
        getLabelIdByName(labelName)?.let { return it }

        try {
            WeLogger.i(TAG, "createLabel: name=$labelName")
            val netSceneInstance = classNetSceneAddContactLabel.clazz.createInstance(labelName)
            WeNetSceneApi.sendNetScene(netSceneInstance)
        } catch (e: Exception) {
            WeLogger.e(TAG, "createLabel failed to dispatch", e)
            return null
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            getLabelIdByName(labelName)?.let {
                WeLogger.i(TAG, "createLabel: name=$labelName resolved to id=$it")
                return it
            }
            Thread.sleep(200)
        }
        WeLogger.w(TAG, "createLabel: name=$labelName not confirmed within ${timeoutMs}ms")
        return null
    }

    /**
     * Modify contact labels.
     * @param username Target contact username
     * @param labelNames List of label names to associate with the contact
     */
    fun modifyLabel(username: String, labelNames: List<String>) {
        try {
            WeLogger.i(TAG, "modifyLabel: username=$username, labels=$labelNames")

            val labelIds = mutableListOf<String>()
            for (name in labelNames) {
                val id = getLabelIdByName(name)
                if (id != null) {
                    labelIds.add(id.toString())
                } else {
                    WeLogger.w(TAG, "modifyLabel: label '$name' not found in database, skipping")
                }
            }

            val pbClass = classContactLabelPb.clazz

            val pbInstance = pbClass.createInstance()

            val stringFields = pbClass.declaredFields.filter { it.type == String::class.java }
            if (stringFields.size < 2) {
                throw IllegalStateException("ContactLabelPb does not have at least 2 String fields")
            }

            val fieldUsername = stringFields[0].makeAccessible()
            val fieldLabelIds = stringFields[1].makeAccessible()

            fieldUsername.set(pbInstance, username)

            val joinedIds = labelIds.joinToString(",")
            fieldLabelIds.set(pbInstance, joinedIds)

            val linkedList = LinkedList<Any>()
            linkedList.push(pbInstance)

            val netSceneInstance = classNetSceneModifyContactLabelList.clazz.createInstance(linkedList)

            WeNetSceneApi.sendNetScene(netSceneInstance)
            WeLogger.i(TAG, "modifyLabel netscene dispatched successfully")
        } catch (e: Exception) {
            WeLogger.e(TAG, "modifyLabel failed", e)
        }
    }

    /**
     * Get a label ID from its name.
     */
    private fun getLabelIdByName(labelName: String): Int? {
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT labelID FROM ContactLabel WHERE labelName = ?",
            arrayOf(labelName)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return null
    }
}
