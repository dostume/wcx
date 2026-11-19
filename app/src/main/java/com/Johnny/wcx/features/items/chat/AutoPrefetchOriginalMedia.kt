package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * **不打开**图片 / 视频, 也把收到的原图与原画质视频拉下来。
 *
 * 与「自动查看原图」([AutoViewOriginalMedia]) 的分工:
 *  - 「自动查看原图」是**你点开时才**动作 —— 在图片/视频页出现的一瞬间把
 *    「查看原图 / 查看原视频」按钮压掉并替你点掉, 视觉上就是「打开即原画质」;
 *  - 本功能是**后台提前拉** —— 消息一落库就触发 CDN 下载, 等你真点开时原画质已到本地。
 *
 * ## 图片
 *
 * 复用 [WeMessageApi.cacheImage] 通道。该通道内部:
 *  - 用 `imageHasHd` 判定这张图**有没有独立原图档** (属性 18 有 / 属性 15 的 hdlength=-1 没有),
 *    据此选配方: 有原图 → HD 档 (param2=1), 没有 → 大图档 (param2=0);
 *  - 触发时 param0 传**消息自己的 ImgInfo2 行 localId** (不是 hdImgId 指向的原图占位行),
 *    `MsgIdTalker` 第一个字段传 `message.msgId`。
 *
 * 这两点是 v298~v301 实测踩出来的, 改错任一条微信都会静默忽略:
 * 传错行 → 只落 HEVC 中间件 → `iscomplete=1` → 之后所有重触发都是 "task already done"。
 *
 * ## 视频 (原画质)
 *
 * 微信的「查看原视频」是 **UI 层自己造的 CDN 任务**, 没有模型层入口, 因此这里按
 * 8.0.76 安装包反查结果**复刻微信自己那条链路**。精简后的原始逻辑
 * (ImageGalleryUI 里 "save origin video" 那个方法) 是:
 *
 * ```java
 * if (!gallery.o1.N(msg)) skip;                 // 静态: 是不是可下载原视频的消息
 * v2 = gallery.n1.t(msg)                        // 静态: 现场解析视频消息 -> v21.v2 (并写缓存)
 * j3 = v21.j3.h.d(v2.h())                       // VideoParseInfo.parse(消息内容 XML) -> 原视频记录
 * if (j3 == null || j3.d.isEmpty()) skip;        // 没有原视频信息就不做
 * c  = new rl4.c(System.currentTimeMillis());
 * g  = new gallery.g(msg, v2, c, j3);           // 下载回调 (实现 il4.q)
 * f  = new gallery.f(msg, v2.e(), true);        // 下载任务 (实现 rl4.a), v2.e() 是 CDN 地址
 * l  = new il4.l(g);                            // CDN 会话
 * l.a(f);
 * l.m = <gallery.n1 实例>;                       // 进度回调 (il4.u), 仅 UI 场景需要
 * l.p();                                        // 执行
 * ```
 *
 * 复刻时**保留微信自己的前置条件** (`j3 != null && j3.d` 非空 = 这条消息确实带原视频),
 * 避免对普通视频乱发下载任务; `l.m` 在后台没有 `n1` 实例, 用一个动态代理占位,
 * 只为避免进度回调时空指针。
 *
 * ### 几个必须记住的坑 (都是真机日志打出来的)
 *
 *  1. **必须走 `n1.t` 而不是 `n1.s`**。`n1.s` 只读 gallery 的缓存, 用户没打开过视频页时
 *     缓存是空的 → 返回 null。微信自己能用是因为页面加载时先调了 `n1.t`
 *     (日志 tag `MicroMsg.Imagegallery.handler.video` 的 "insert video info:"):
 *     它现场 `h35/d.fromXml(消息内容)` 解析出 `v21.v2` (url/长度/md5), 写库再塞进缓存。
 *  2. **解析器单例在 `v21.j3.h`, 不是 `v21.i3` 上**。`v21.i3` (真身 `MicroMsg.VideoParseInfo`)
 *     只有 a/b/c/d/e 五个解析方法, **没有任何字段**; 拿 `getDeclaredField("h")` 去
 *     `v21.i3` 上找会抛 NoSuchFieldException → 整条配方解析失败 → 记一次「链路不可用」
 *     后**当成已处理**, 再也不重试 (这就是 v285/v286「点开才下载」的根因)。
 *  3. **`v21.i3.d(key)` 的 key 是消息内容 XML** (即 `v2.h()`), 不是 fileid。
 *     记录里 `d` = cdnrawvideourl、`f` = cdnrawvideoaeskey、`b` = isplaceholder、`g` = rawlength。
 *  4. **别和微信抢同一个视频文件**。原视频下载写的就是普通视频那一个文件, 微信自己也在写。
 *     所以发起前先采样 `videoinfo2` 的进度, 只有**字节数确实在涨**才避让。
 *     兜底方向绝不能反: 查不到行 / 数字不动 → 一律放行 (宁可抢跑, 也不能把无感预取做成摆设)。
 *  5. **微信对「要原画质」的请求, 在自己认为已经持有该文件时会静默忽略** ——
 *     表现是 `accepted=false` 且零进度。连续被拒 2 次就结案, 别再每 3 分钟刷一轮。
 *
 * 这些类名是**混淆名** (il4 / v21 / rl4 / gallery 这类短包名), 微信改版后会整体失效 ——
 * 所以每一环都有独立日志说明卡在哪里。
 *
 * ## 触发与补偿
 *
 * 消息落库的 insert / update 事件驱动, 外加每 5 分钟一轮、回看 6 小时的补偿扫描。
 * 补偿扫描是必须的: 监听器注册晚于消息落库、开启功能时消息早已落库、
 * 图片 / 视频信息行写入过晚都会漏事件。
 */
@Feature(
    name = "后台自动加载原图/原视频",
    categories = ["聊天"],
    description = "不打开图片 / 视频也自动把收到的原图与原画质视频拉下来, 点开时已是原画质"
)
object AutoPrefetchOriginalMedia : SwitchFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AutoPrefetchOriginalMedia"

    /**
     * 默认跟随「自动查看原图」开关。
     *
     * 两个功能是同一诉求的两半: 那个只能在**点开**图片/视频页时动作, 本功能才做得到
     * 「不点开也拉」。所以已经明确开过「自动查看原图」的用户, 升级后本功能自动生效;
     * 在本功能里拨过开关的用户, 以本功能的 key 为准 (不会覆盖用户的显式选择)。
     */
    private const val VIEW_FEATURE_KEY = "自动查看原图"

    /**
     * 默认跟随「自动查看原图」开关。
     *
     * 用户既然显式开过「自动查看原图」, 说明他要的就是「原画质自动到本地」;
     * 本功能补上「不点开也拉」的那一半, 所以直接继承。
     * 一旦用户在本功能的设置项里拨过开关, `WePrefs` 里就有本功能的 key 了,
     * `getBoolOrDef` 不会再走到这里 —— 用户的显式选择永远优先。
     */
    override val defaultEnabled: Boolean
        get() = runCatching { WePrefs.getBoolOrDef(VIEW_FEATURE_KEY, false) }.getOrDefault(false)

    /**
     * 启动日志。
     *
     * 之前这功能**一行日志都没有**, 没法区分「开关没开」和「开了但静默失败」——
     * 这次把开关值 + 两个 key 的存在性都打出来, 下次一眼就能定论。
     */
    override val shouldEnableOnStartup: Boolean
        get() {
            val selfKey = runCatching { WePrefs.containsKey(name) }.getOrDefault(false)
            val viewKey = runCatching { WePrefs.containsKey(VIEW_FEATURE_KEY) }.getOrDefault(false)
            WeLogger.i(
                TAG,
                "startup: 开关=$_isEnabled (本功能 key '$name' 存在=$selfKey; " +
                        "未拨动过时跟随「$VIEW_FEATURE_KEY」: 该 key 存在=$viewKey)"
            )
            return _isEnabled
        }

    /** 单条消息单轮内的尝试次数 (图片/视频信息落库可能晚于消息本身)。 */
    private const val MAX_ATTEMPTS = 3

    /** 跨补偿轮次的最大轮数, 到顶就结案不再被拾起。 */
    private const val MAX_TOTAL_ATTEMPTS = 6

    /** 视频: 微信连续拒绝该 fileid 多少次后结案。 */
    private const val MAX_LAUNCH_REJECT = 2

    /** 视频结案 key 的类型前缀 (与图片区分)。 */
    private const val PREFETCH_KEY_VIDEO = "43"

    /** 视频消息落库后的安全延迟: 刚落库时微信随时可能自己启动下载。 */
    private const val VIDEO_SAFE_DELAY_MS = 20_000L

    /** 视频: 两次进度采样之间的间隔, 用于确认字节数是否真在涨。 */
    private const val VIDEO_GROW_PROBE_MS = 1_500L

    /** 视频: 被拒后提前重试的冷却 (比正常冷却短, 让它早点接上)。 */
    private const val VIDEO_RETRY_COOLDOWN_MS = 60_000L

    /** 视频: 同一条视频发起原画质下载的冷却。 */
    private const val VIDEO_LAUNCH_COOLDOWN_MS = 3 * 60_000L

    /** 补偿扫描: 回看多久内的消息。 */
    private const val SCAN_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** 补偿扫描: 单轮最多处理多少条。 */
    private const val SCAN_LIMIT = 200

    /** 补偿扫描: 轮询间隔。 */
    private const val SCAN_INTERVAL_MS = 300_000L

    /** 并发上限。 */
    private const val MAX_CONCURRENT = 2

    /**
     * 等并发槽位的超时。
     *
     * v300 血的教训: `withPermit` 不带超时的话, 一条卡死的图片任务能占满槽位 6~10 分钟,
     * 视频任务排队 5 分钟直接饿死。**等共享资源的等待必须有上限**。
     */
    private const val PERMIT_WAIT_MS = 90_000L

    /** 已完成的 key (type:msgSvrId)。 */
    private val doneKeys = ConcurrentHashMap.newKeySet<String>()

    /** 已结案的 key (确认拿不到, 不再重试)。 */
    private val settledKeys = ConcurrentHashMap.newKeySet<String>()

    /** 每条消息已尝试的轮数。 */
    private val attemptsBySvrId = ConcurrentHashMap<Long, Int>()

    /** 正在处理中的 msgSvrId。 */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    /** 已见过但还没拿到结果的 msgSvrId (等它的图片/视频信息行落库)。 */
    private val pendingSvrIds = ConcurrentHashMap.newKeySet<Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val semaphore = Semaphore(MAX_CONCURRENT)

    private fun isTarget(type: Int?): Boolean =
        type == MessageType.IMAGE.code || type == MessageType.VIDEO.code

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
        // 页面兜底: 后台没拉及时 (或漏项) 时, 用户打开图片/视频页这一下由它接管,
        // 按钮在帧级被拦掉并点掉, 点开已是原画质。
        GalleryOriginButtonHook.install(this)
        WeLogger.i(
            TAG,
            "enabled: 收到图片/视频即后台拉原图与原画质视频 " +
                    "(补偿扫描每 ${SCAN_INTERVAL_MS / 1000}s, 回看 ${SCAN_WINDOW_MS / 3600000}h, 并发 $MAX_CONCURRENT)"
        )
        startCompensation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        doneKeys.clear()
        settledKeys.clear()
        attemptsBySvrId.clear()
        inFlight.clear()
        pendingSvrIds.clear()
        GalleryOriginButtonHook.uninstall()
        OriginVideoDownload.reset()
        WeLogger.i(TAG, "disabled: 监听已移除, 补偿扫描已停止, 页面兜底点击已卸载")
    }

    // ---------------------------------------------------------------------------------------------
    // 事件入口
    // ---------------------------------------------------------------------------------------------

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        // 自己发出的媒体本来就在本地
        if (values.getAsInteger("isSend") == 1) return
        // insert 事件里的列不全 (type 可能缺失), 统一按 msgSvrId 回查 message 行
        dispatch(values.getAsLong("msgSvrId") ?: return, "insert")
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "message") return

        val msgSvrId = values.getAsLong("msgSvrId") ?: run {
            val msgId = extractMsgIdFromUpdate(values, whereClause, whereArgs) ?: return
            WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: return
        }
        if (msgSvrId <= 0L) return

        // update 事件绝大多数与媒体无关 (已读、状态位等)。只有两种值得跟进:
        //  ① 事件自带 type 且是图片/视频  ② 这条消息是之前 insert 时见过的 (等它的下载信息落库)
        val explicitType = values.getAsInteger("type")
        if (!isTarget(explicitType) && msgSvrId !in pendingSvrIds) return

        dispatch(msgSvrId, "update")
    }

    /**
     * 从 update 事件的 ContentValues / where 子句里捞 msgId。
     *
     * update 事件的 values 常常不带 msgSvrId, 只带 status 之类的列, 这时只能靠 where 条件。
     */
    private fun extractMsgIdFromUpdate(
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Long? {
        values.getAsLong("msgid")?.takeIf { it > 0L }?.let { return it }
        values.getAsLong("msgId")?.takeIf { it > 0L }?.let { return it }
        val clause = whereClause?.replace(" ", "")?.lowercase() ?: return null
        if (!clause.contains("msgid")) return null
        whereArgs?.forEach { arg ->
            arg?.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        }
        return null
    }

    /**
     * 按 msgSvrId 回查 message 行并串行处理。
     *
     * 注意**不能在 hook 回调里直接查库** —— insert 事件跑在微信自己的写库线程上,
     * 在那里再发一次查询容易出问题, 统一丢到 IO 协程里做。
     */
    private fun dispatch(msgSvrId: Long, via: String) {
        if (msgSvrId <= 0L) return
        if (!inFlight.add(msgSvrId)) return
        scope.launch {
            try {
                val row = queryRow(msgSvrId) ?: return@launch
                if (!isTarget(row.type)) return@launch
                val key = "${row.type}:${row.msgSvrId}"
                if (key in doneKeys || key in settledKeys) return@launch
                // 自己发出的媒体本地就有原文件, 不需要 (也不该) 去 CDN 拉。
                // insert 事件的 ContentValues 经常缺 isSend 列, 必须按 msgSvrId 回查到行再判。
                if (row.isSend == 1) {
                    WeLogger.i(TAG, "跳过 $key (via $via): 自己发出的媒体, 本地已有原文件")
                    doneKeys.add(key)
                    return@launch
                }
                pendingSvrIds.add(row.msgSvrId)
                WeLogger.i(
                    TAG,
                    "检测到媒体消息 ($via): msgSvrId=$msgSvrId type=${row.type} talker=${row.talker}, 后台下载"
                )
                // 等槽位也要有上限 (见 [PERMIT_WAIT_MS] 的饿死事故)。
                // 拿不到就撤: inFlight 在 finally 里释放, 补偿扫描下轮再来。
                val acquired = withTimeoutOrNull(PERMIT_WAIT_MS) {
                    semaphore.withPermit { runTask(row, via, key); true }
                }
                if (acquired == null) {
                    WeLogger.w(
                        TAG,
                        "等待并发槽位超时 (${PERMIT_WAIT_MS / 1000}s) $key (via $via), " +
                                "本轮不发起, 留给补偿扫描重试"
                    )
                }
            } finally {
                inFlight.remove(msgSvrId)
            }
        }
    }

    private class Row(
        val type: Int,
        val msgId: Long,
        val msgSvrId: Long,
        val talker: String?,
        val isSend: Int,
        val content: String?,
        val imgPath: String?,
        val createTime: Long = 0L,
    )

    private fun queryRow(msgSvrId: Long): Row? {
        return try {
            WeDatabaseApi.rawQuery(
                "SELECT type, msgId, msgSvrId, talker, isSend, content, imgPath, createTime " +
                        "FROM message WHERE msgSvrId=? LIMIT 1",
                arrayOf<Any>(msgSvrId)
            ).use { c ->
                if (!c.moveToFirst()) return null
                Row(
                    type = c.getInt(0),
                    msgId = c.getLong(1),
                    msgSvrId = c.getLong(2),
                    talker = c.getString(3),
                    isSend = c.getInt(4),
                    content = c.getString(5),
                    imgPath = c.getString(6),
                    createTime = c.getLong(7),
                )
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "查询 message 行失败 (msgSvrId=$msgSvrId)", e)
            null
        }
    }

    private suspend fun runTask(row: Row, via: String, key: String) {
        val round = attemptsBySvrId.merge(row.msgSvrId, 1, Int::plus) ?: 1
        if (round > MAX_TOTAL_ATTEMPTS) {
            // 跨轮次重试到顶: 给「为什么放弃」一个定论, 不再被补偿扫描每轮拾起。
            if (attemptsBySvrId.remove(row.msgSvrId) != null) {
                settledKeys.add(key)
                WeLogger.w(
                    TAG,
                    "放弃 $key: 已重试 $MAX_TOTAL_ATTEMPTS 轮仍拿不到 (原视频信息始终未就绪, " +
                            "或本条确实没有原图/原视频)"
                )
            }
            return
        }
        for (attempt in 1..MAX_ATTEMPTS) {
            val ok = try {
                if (row.type == MessageType.IMAGE.code) prefetchImage(row.msgSvrId) else prefetchOriginVideo(row)
            } catch (t: Throwable) {
                WeLogger.e(TAG, "后台下载异常 ($key, 第 $attempt 次, via $via)", t)
                false
            }
            if (ok) {
                doneKeys.add(key)
                attemptsBySvrId.remove(row.msgSvrId)
                pendingSvrIds.remove(row.msgSvrId)
                return
            }
            if (attempt < MAX_ATTEMPTS) {
                // 图片服务 / 视频信息行落库可能晚于消息本身, 且 CDN 未必就绪
                delay(if (attempt == 1) 5_000 else 10_000)
            }
        }
        WeLogger.w(
            TAG,
            "后台下载未完成 $key (via $via, 第 $round/$MAX_TOTAL_ATTEMPTS 轮), 留给补偿扫描重试"
        )
    }

    /** 原图: 复用 [WeMessageApi.cacheImage] 通道 (内部按有无原图选配方)。@return 是否已落地。 */
    private fun prefetchImage(msgSvrId: Long): Boolean {
        val path = WeMessageApi.cacheImage(msgSvrId)
        return if (path != null) {
            WeLogger.i(TAG, "原图已落地: msgSvrId=$msgSvrId -> $path")
            true
        } else {
            false
        }
    }

    /** 原画质视频: 取完整 MsgInfo, 交给复刻的微信下载链路。 */
    private suspend fun prefetchOriginVideo(row: Row): Boolean {
        val msgId = if (row.msgId > 0) row.msgId else queryRow(row.msgSvrId)?.msgId ?: 0L
        if (msgId <= 0L) {
            WeLogger.w(TAG, "视频消息没有有效 msgId, 跳过: msgSvrId=${row.msgSvrId}")
            return false
        }
        val msgInfo = WeMessageApi.getMsgInfoInstanceByMsgId(msgId) ?: run {
            WeLogger.w(TAG, "取不到 MsgInfo, 跳过: msgId=$msgId")
            return false
        }
        return OriginVideoDownload.request(msgInfo, row)
    }

    // ---------------------------------------------------------------------------------------------
    // 补偿扫描
    // ---------------------------------------------------------------------------------------------

    private fun startCompensation() {
        scope.launch {
            delay(30_000)
            while (isActive) {
                scanRecentMessages()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun scanRecentMessages() {
        if (!WeDatabaseApi.isReady) {
            WeLogger.w(TAG, "数据库未就绪 (重适配窗口), 本轮跳过, 稍后重试")
            return
        }
        val since = System.currentTimeMillis() - SCAN_WINDOW_MS
        val rows = try {
            WeDatabaseApi.rawQuery(
                "SELECT type, msgId, msgSvrId, talker, isSend, content, imgPath, createTime " +
                        "FROM message WHERE type IN (?, ?) AND isSend=0 AND createTime>? " +
                        "ORDER BY createTime DESC LIMIT ?",
                arrayOf<Any>(MessageType.IMAGE.code, MessageType.VIDEO.code, since, SCAN_LIMIT)
            ).use { cursor ->
                buildList<Row> {
                    while (cursor.moveToNext()) {
                        add(
                            Row(
                                type = cursor.getInt(cursor.getColumnIndexOrThrow("type")),
                                msgId = cursor.getLong(cursor.getColumnIndexOrThrow("msgId")),
                                msgSvrId = cursor.getLong(cursor.getColumnIndexOrThrow("msgSvrId")),
                                talker = cursor.getString(cursor.getColumnIndexOrThrow("talker")),
                                isSend = cursor.getInt(cursor.getColumnIndexOrThrow("isSend")),
                                content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                                imgPath = cursor.getString(cursor.getColumnIndexOrThrow("imgPath")),
                                createTime = cursor.getLong(cursor.getColumnIndexOrThrow("createTime")),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "补偿扫描查询失败", e)
            return
        }

        var launched = 0
        rows.forEach { row ->
            if (row.msgSvrId <= 0L) return@forEach
            val key = "${row.type}:${row.msgSvrId}"
            if (key in doneKeys || key in settledKeys || row.msgSvrId in inFlight) return@forEach
            pendingSvrIds.add(row.msgSvrId)
            launched++
            dispatch(row.msgSvrId, "scan")
        }
        WeLogger.i(TAG, "补偿扫描: 近 ${SCAN_WINDOW_MS / 3600000}h 内 ${rows.size} 条媒体消息, 本轮发起 $launched 条")
    }

    // ---------------------------------------------------------------------------------------------
    // 原画质视频下载 (复刻微信自己那条链路)
    // ---------------------------------------------------------------------------------------------

    private object OriginVideoDownload {

        private const val TAG = "AutoPrefetchOriginalMedia"

        /** 视频消息解析 + 原视频下载记录查询 (静态方法所在地)。 */
        private const val GALLERY_VIDEO_HELPER = "com.tencent.mm.ui.chatting.gallery.n1"

        /** 视频消息判定 (静态 N(e9): Z)。 */
        private const val GALLERY_VIDEO_CHECK = "com.tencent.mm.ui.chatting.gallery.o1"

        const val TASK_DOWNLOAD = "com.tencent.mm.ui.chatting.gallery.f"
        const val TASK_CALLBACK = "com.tencent.mm.ui.chatting.gallery.g"

        /** CDN 会话: 构造参数是下载回调接口, 有 a(任务) / p() 执行 / m 进度回调。 */
        private const val CDN_SESSION = "il4.l"

        /**
         * 原视频信息解析器 (真身 `MicroMsg.VideoParseInfo`): 实例方法 `d(String)`
         * 按**消息内容 XML** 解析出原视频 CDN 记录 (cdnrawvideourl / aeskey / rawlength)。
         */
        private const val VIDEO_PARSER = "v21.i3"

        /**
         * 解析器单例的宿主: 静态字段 `h`(类型 v21.i3) 就是解析器实例。
         * **别把 h 当成 v21.i3 自己的字段** —— v21.i3 一个字段都没有 (v287 踩过)。
         */
        private const val VIDEO_RECORD_HOLDER = "v21.j3"

        /** 原视频下载记录: d = cdnrawvideourl, f = aeskey, b = isplaceholder, g = rawlength。 */
        private const val VIDEO_RECORD = "v21.j3"

        /** 下载计时对象, 构造参数是 long。 */
        private const val CDN_TIME = "rl4.c"

        /** 只在同一条消息上打一次「已发起」日志。 */
        private val logged = ConcurrentHashMap.newKeySet<Long>()

        @Volatile
        private var recipe: Recipe? = null

        @Volatile
        private var resolveFailureLogged = false

        private class Recipe(
            val checkVideo: Method,
            /** `n1.t(e9)`: 现场解析视频消息并写缓存 (微信自己先走它)。 */
            val buildVideoInfo: Method,
            /** `n1.s(e9)`: 只读 gallery 缓存, 作为兜底。 */
            val cachedVideoInfo: Method,
            val lookupRecord: Method,
            val registry: Any,
            val recordUrl: Field,
            val videoInfoUrl: Method,
            val videoInfoKey: Method,
            val newTime: Constructor<*>,
            val newCallback: Constructor<*>,
            val newDownloadTask: Constructor<*>,
            val newSession: Constructor<*>,
            val sessionAdd: Method,
            val sessionRun: Method,
            val sessionProgress: Field?,
            val progressType: Class<*>?,
        )

        fun resolve(className: String): Class<*>? = className.toClassOrNull()

        // -----------------------------------------------------------------------------------------
        // 「别和微信抢同一个文件」的两道闸
        // -----------------------------------------------------------------------------------------

        /** 同一条视频 (CDN fileid) 上次发起的时间戳, 用于 [VIDEO_LAUNCH_COOLDOWN_MS] 冷却。 */
        private val lastLaunchAt = ConcurrentHashMap<String, Long>()

        /** 每个 fileId 被微信拒绝 (accepted=false) 的次数, 用于结案判定。 */
        private val rejectedLaunches = ConcurrentHashMap<String, Int>()

        /** videoinfo(2) 表结构探测结果 (只探一次)。 */
        @Volatile
        private var videoTable: VideoTable? = null

        @Volatile
        private var videoTableProbed = false

        private class VideoTable(
            val name: String,
            val keyColumn: String,
            val columns: Set<String>,
        )

        private fun videoTableOrNull(): VideoTable? {
            if (videoTableProbed) return videoTable
            videoTableProbed = true
            videoTable = runCatching {
                for (name in listOf("videoinfo2", "videoinfo")) {
                    val cols: List<String>? = WeDatabaseApi.rawQuery(
                        "PRAGMA table_info($name)",
                        arrayOf<Any>()
                    ).use { c ->
                        val idx = c.getColumnIndex("name")
                        if (idx < 0) null else buildList<String> {
                            while (c.moveToNext()) add(c.getString(idx))
                        }
                    }
                    if (cols.isNullOrEmpty()) continue
                    val key = listOf("filename", "msglocalid", "msgLocalId").firstOrNull { it in cols }
                        ?: continue
                    return@runCatching VideoTable(name, key, cols.toSet())
                }
                null
            }.onFailure {
                WeLogger.w(TAG, "视频信息表探测失败: ${it.message}")
            }.getOrNull()
            WeLogger.i(
                TAG,
                "视频信息表探测: ${videoTable?.let { "${it.name} (key=${it.keyColumn}, ${it.columns.size} 列)" }
                    ?: "未找到 → 退化为「消息落库 ${VIDEO_SAFE_DELAY_MS / 1000}s 后才拉原视频」"}"
            )
            return videoTable
        }

        /**
         * videoinfo2 里「正在下载」的 status 集合。
         *
         * 日志实测: `111` = 已入库**待下载** (微信没在传, 它在等用户点开),
         * `121` = 正在下载 (file 已有部分字节), `199` = 完成。
         * 这里只当作「值得再采样确认」的嫌疑信号, 结论一律由字节是否增长来下。
         */
        private val VIDEO_DOWNLOADING_STATUS = setOf(112L, 113L, 120L, 121L, 122L, 123L, 130L, 131L, 198L)

        private class VideoProgress(
            val status: Long?,
            val total: Long,
            val offset: Long,
            val fileSize: Long,
        ) {
            /** 已落地的字节数 (CDN 进度与实际文件长度取大者)。 */
            val bytes: Long get() = maxOf(offset, fileSize)

            /** 有没有「微信确实在传」的正向证据。 */
            val suspicious: Boolean
                get() = status in VIDEO_DOWNLOADING_STATUS ||
                    (total > 0L && ((offset > 0L && offset < total) || (fileSize > 0L && fileSize < total)))

            fun describe(): String = "status=$status, offset=$offset, file=$fileSize/$total"
        }

        /**
         * 采样一次视频下载进度。查不到行 / 表结构对不上都返回 null (= 判据不可用)。
         */
        private fun sampleVideoProgress(row: Row): VideoProgress? {
            if (!WeDatabaseApi.isReady) return null
            val table = videoTableOrNull() ?: return null
            val keyValue: Any =
                if (table.keyColumn == "filename") row.imgPath ?: return null else row.msgId
            if (keyValue is String && keyValue.isEmpty()) return null

            val want = listOf("status", "netoffset", "totallen", "filenowsize").filter { it in table.columns }
            if (want.isEmpty()) return null

            return runCatching {
                WeDatabaseApi.rawQuery(
                    "SELECT ${want.joinToString(", ")} FROM ${table.name} " +
                            "WHERE ${table.keyColumn}=? LIMIT 1",
                    arrayOf<Any>(keyValue)
                ).use { c ->
                    fun long(col: String): Long? {
                        val i = c.getColumnIndex(col)
                        if (i < 0) return null
                        return runCatching { c.getLong(i) }
                            .getOrNull() ?: runCatching { c.getString(i)?.trim()?.toLongOrNull() }.getOrNull()
                    }
                    if (!c.moveToNext()) return@use null
                    VideoProgress(
                        status = long("status"),
                        total = long("totallen") ?: 0L,
                        offset = long("netoffset") ?: 0L,
                        fileSize = long("filenowsize") ?: 0L,
                    )
                }
            }.onFailure {
                WeLogger.w(TAG, "查询 ${table.name} 失败: ${it.message}")
            }.getOrNull()
        }

        /**
         * 此刻能不能拉原视频 —— 也就是**微信有没有正在写同一个视频文件**。
         *
         * @return true = 可以拉 (没有竞争写者); false = 微信正在下普通画质, 稍后重试。
         *
         * 【兜底方向绝不能反】曾写成 "status < 199 就一律避让", 被这两种情况打穿:
         *   · `status=111, offset=0, file=0` —— 微信**根本没开始下**, 它在等用户点开;
         *   · `status=0, totallen=0, filenowsize=0` —— 查到空行 / 键没对上。
         * 这两种状态会**一直持续** (不点开就不下), 于是预取被无限期挂起, 无感完全失效。
         *
         * 所以改成**正向证据 + 增长确认**:
         *   1. 先要有「微信确实在传」的证据 (CDN offset 有进展 / 文件已写了一部分 / status 在下载中集合);
         *   2. 有证据也不直接下结论, 隔 [VIDEO_GROW_PROBE_MS] 再采样一次, **只有字节数真的在涨**才避让;
         *   3. 查不到行 / 状态不明 / 数字不动 -> 一律放行。
         */
        private suspend fun ordinaryVideoReady(row: Row): Boolean {
            val age = if (row.createTime > 0L) System.currentTimeMillis() - row.createTime else -1L
            // 刚落库: 微信随时可能启动自动下载, 这种时刻一律先采样确认
            val fresh = age in 0 until VIDEO_SAFE_DELAY_MS
            val first = sampleVideoProgress(row)

            // 判据说没在传, 也不是刚落库的高风险时刻 -> 直接放行
            if (!fresh && (first == null || !first.suspicious)) return true

            delay(VIDEO_GROW_PROBE_MS)
            val second = sampleVideoProgress(row)
            if (second == null) return true

            val growing = second.bytes > (first?.bytes ?: 0L)
            if (growing) {
                diagnoseOnce(row.msgSvrId) {
                    "微信正在下载普通视频 (${first?.describe()} -> ${second.describe()}), 暂不拉原视频" +
                            " —— 并发写同一个文件会把视频写坏"
                }
                return false
            }
            WeLogger.i(
                TAG,
                "普通视频无下载进展 (${first?.describe()} -> ${second.describe()}), 照常拉原视频 " +
                        "(msgSvrId=${row.msgSvrId})"
            )
            return true
        }

        /** 关闭功能时清掉冷却与表结构缓存, 下次开启重新探测。 */
        fun reset() {
            logged.clear()
            lastLaunchAt.clear()
            rejectedLaunches.clear()
            videoTable = null
            videoTableProbed = false
        }

        /** @return true 表示这条消息已经有了定论 (已发起 / 确实没有原视频 / 不是视频消息)。 */
        suspend fun request(msgInfo: Any, row: Row): Boolean {
            val r = resolveRecipe()
            if (r == null) {
                if (!resolveFailureLogged) {
                    resolveFailureLogged = true
                    WeLogger.w(
                        TAG,
                        "原视频下载链路不可用, 后台只拉原图 (微信改版后需按日志校准混淆名)"
                    )
                }
                return true
            }
            return runCatching { start(r, msgInfo, row) }.getOrElse { t ->
                WeLogger.e(TAG, "原视频下载复刻执行异常 (msgSvrId=${row.msgSvrId})", t)
                false
            }
        }

        /**
         * @return true = 有定论 (已发起下载 / 本条确实没有原视频 / 不是视频消息);
         *         false = 信息还不完整 (视频信息未就绪), 交给重试与补偿扫描。
         */
        private suspend fun start(r: Recipe, msgInfo: Any, row: Row): Boolean {
            val msgSvrId = row.msgSvrId

            // 0) 别和微信抢同一个视频文件
            val fileId = row.imgPath
            if (!fileId.isNullOrEmpty()) {
                val last = lastLaunchAt[fileId]
                if (last != null && System.currentTimeMillis() - last < VIDEO_LAUNCH_COOLDOWN_MS) {
                    WeLogger.i(
                        TAG,
                        "同一条视频刚发起过 (fileId=$fileId, " +
                                "${(System.currentTimeMillis() - last) / 1000}s 前), 本轮不重复发起"
                    )
                    return false
                }
            }
            // 微信还在下普通视频时, 这里连 n1.t 都不能调: 它会写 VideoInfo 行,
            // 与微信正在进行的下载抢同一个文件 / 同一行状态。
            if (!ordinaryVideoReady(row)) return false

            // 1) 不是视频消息: 与微信同款静态判定
            val isVideo = runCatching { r.checkVideo.invoke(null, msgInfo) as? Boolean }.getOrNull()
            if (isVideo != true) {
                WeLogger.i(TAG, "跳过 msgSvrId=$msgSvrId: 不是可下载原视频的消息 (isVideo=$isVideo)")
                return true
            }

            // 2) 现场解析视频消息 -> v21.v2
            //    必须走 n1.t (insert video info): n1.s 只读 gallery 的缓存,
            //    用户没打开过视频页时缓存是空的 -> 这就是「点开才下载」的根因。
            val videoInfo = runCatching { r.buildVideoInfo.invoke(null, msgInfo) }.getOrNull()
                ?: runCatching { r.cachedVideoInfo.invoke(null, msgInfo) }.getOrNull()

            // 3) 原视频信息 (cdnrawvideourl / aeskey / rawlength) 藏在视频消息的 `<msg>` XML 里,
            //    微信自己就是 `v21.j3.h.d(v2.h())` —— v2.h() 应该返回 `<msg>` XML。
            //
            //    消息刚落库那一刻 `v2.h()` 还不是 XML (实测是一条 `wxid_xxx:0:0\n` 之类的
            //    中间态), 于是解析出的记录 d 为空 -> 被误判成「该视频没有原视频信息」并结案;
            //    一分钟后用户点开视频页, 微信自己却弹出了「查看原视频 28MB」。所以这里改为:
            //      · 按优先级取多个候选 XML (DB 直读的 content / MsgInfo.content / v2.h()),
            //        只要有一个是完整的视频 XML 就用它;
            //      · 全都不像 XML -> 判「信息未就绪」返回 false (稍后重试), **绝不结案**。
            val candidates = buildCandidates(r, msgInfo, row, videoInfo)
            val picked = candidates.firstOrNull { looksLikeVideoXml(it.second) }
            if (picked == null) {
                diagnoseOnce(msgSvrId) {
                    "视频内容还不是可解析的 XML (msgSvrId=$msgSvrId), 稍后重试; 候选: " +
                            candidates.joinToString(" | ") { "${it.first}=${describe(it.second)}" }
                }
                return false
            }

            // 下载任务的地址参数 = v2.e() (= msg.imgPath); 微信自己的 gallery 也传这个值。
            val url = runCatching { videoInfo?.let { r.videoInfoUrl.invoke(it) as? String } }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?: row.imgPath?.takeIf { it.isNotEmpty() }
            if (url.isNullOrEmpty()) {
                diagnoseOnce(msgSvrId) { "视频地址尚未就绪 (msgSvrId=$msgSvrId), 稍后重试" }
                return false
            }

            // 4) 原视频下载记录 —— 微信自己就是靠它判断「这条消息确实带原视频」
            val record = runCatching { r.lookupRecord.invoke(r.registry, picked.second) }.getOrNull()
            val recordUrl = record?.let { runCatching { r.recordUrl.get(it) as? String }.getOrNull() }
            if (record == null || recordUrl.isNullOrEmpty()) {
                // 到这里视频 XML 已经确认含 <videomsg>, 仍解析不出 cdnrawvideourl 说明
                // 这条视频本来就没有原画质版本 —— 这才是可以结案的情形。
                WeLogger.i(
                    TAG,
                    "该视频没有原视频信息 (msgSvrId=$msgSvrId, XML 来源 ${picked.first}), 无需下载"
                )
                return true
            }

            // 5) 复刻微信那条链路
            val now = r.newTime.newInstance(System.currentTimeMillis())
            val callback = r.newCallback.newInstance(msgInfo, videoInfo, now, record)
            val task = r.newDownloadTask.newInstance(msgInfo, url, true)
            val session = r.newSession.newInstance(callback)
            r.sessionAdd.invoke(session, task)
            installProgressPlaceholder(r, session)
            if (!fileId.isNullOrEmpty()) lastLaunchAt[fileId] = System.currentTimeMillis()
            val accepted = runCatching { r.sessionRun.invoke(session) as? Boolean }.getOrNull()

            if (accepted != true && !fileId.isNullOrEmpty()) {
                // 微信对这类「要原画质」的请求, 在自己认为「已经持有该文件」时会**静默忽略** ——
                // 表现就是 accepted=false 且零进度。所以不该再假装它在排队, 而要:
                //   1. 记下被拒次数, 连续被拒就**结案**, 不再占着 6 轮补偿扫描空转;
                //   2. 首次被拒时把原因写清楚, 便于下一轮直接定论。
                val rejected = rejectedLaunches.merge(fileId, 1, Int::plus) ?: 1
                if (rejected == 1) {
                    WeLogger.w(
                        TAG,
                        "原视频请求被微信拒绝 (accepted=false): fileId=$fileId url=$url。" +
                                "已发起过但微信未接受 —— 微信在已持有该文件时会静默忽略原画质请求" +
                                "(与图片侧 totalLen 已完成同一机制)。连续被拒 $MAX_LAUNCH_REJECT 次后结案。"
                    )
                }
                if (rejected >= MAX_LAUNCH_REJECT) {
                    rejectedLaunches.remove(fileId)
                    // 结案: 这个 fileId 微信不接, 别再每 3 分钟刷一轮
                    settledKeys.add("$PREFETCH_KEY_VIDEO:$msgSvrId")
                    WeLogger.w(
                        TAG,
                        "原视频结案: fileId=$fileId 连续 $MAX_LAUNCH_REJECT 次被微信拒绝, " +
                                "判定微信不接受该原画质请求, 不再重试 (msgSvrId=$msgSvrId)"
                    )
                    return false
                }
                // 还没到结案线, 放宽冷却让它早点接上
                lastLaunchAt[fileId] =
                    System.currentTimeMillis() - (VIDEO_LAUNCH_COOLDOWN_MS - VIDEO_RETRY_COOLDOWN_MS)
            } else if (accepted == true && !fileId.isNullOrEmpty()) {
                rejectedLaunches.remove(fileId)
            }

            if (logged.add(msgSvrId)) {
                WeLogger.i(
                    TAG,
                    "原视频下载已发起: msgSvrId=$msgSvrId url=$url accepted=$accepted " +
                            "(XML 来源 ${picked.first})"
                )
                if (logged.size > 512) logged.clear()
            } else {
                WeLogger.i(TAG, "原视频下载再次发起: msgSvrId=$msgSvrId accepted=$accepted")
            }
            return accepted == true
        }

        /**
         * 候选视频消息 XML, 按可靠性排序。
         *
         * `db.content` 是我们自己 `SELECT content` 读出来的原文, 不经过
         * `convertFrom(Cursor)` 的字段映射; `msgInfo.content` 与 `v2.h()` 用于交叉验证
         * (设备日志里三者可能不一致, 谁是真 XML 就用谁)。
         */
        private fun buildCandidates(
            r: Recipe,
            msgInfo: Any,
            row: Row,
            videoInfo: Any?,
        ): List<Pair<String, String?>> = buildList {
            add("db.content" to row.content)
            add("msgInfo.content" to runCatching { MessageInfo(msgInfo).content }.getOrNull())
            add("v2.h()" to runCatching { videoInfo?.let { r.videoInfoKey.invoke(it) as? String } }
                .getOrNull())
        }.filter { !it.second.isNullOrEmpty() }.distinctBy { it.second }

        /** 是不是一条完整的视频消息 XML (`<msg>` + `<videomsg>`)。 */
        private fun looksLikeVideoXml(text: String?): Boolean =
            text != null && text.contains("<msg") && text.contains("videomsg")

        /** 日志用: 把候选内容压成一行摘要 (换行转义, 只留头部)。 */
        private fun describe(text: String?): String {
            if (text == null) return "null"
            val head = text.replace("\r", "\\r").replace("\n", "\\n").take(72)
            return "\"$head\"(len=${text.length})"
        }

        /** 同一条消息只打一次「信息未就绪」的诊断, 免得补偿轮刷屏。 */
        private val diagnosed = ConcurrentHashMap.newKeySet<Long>()

        private fun diagnoseOnce(msgSvrId: Long, message: () -> String) {
            if (diagnosed.add(msgSvrId)) {
                WeLogger.i(TAG, message())
                if (diagnosed.size > 512) diagnosed.clear()
            }
        }

        /**
         * 进度回调 (`il4.l.m`, 类型是 `il4.u`) 只在 UI 场景用得到, 后台没有 gallery.n1 实例,
         * 用一个空实现的动态代理占位, 避免进度回调时空指针。
         */
        private fun installProgressPlaceholder(r: Recipe, session: Any) {
            val field = r.sessionProgress ?: return
            val type = r.progressType ?: return
            runCatching {
                field.set(
                    session,
                    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                        when (method.name) {
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                            "toString" -> "WcxOriginVideoProgress"
                            else -> defaultValue(method.returnType)
                        }
                    }
                )
            }.onFailure { WeLogger.w(TAG, "进度回调占位失败 (可忽略): ${it.message}") }
        }

        /** 动态代理方法的默认返回值。 */
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }

        /** 解析一次并缓存; 失败不重试, 免得每条消息都刷一遍日志。 */
        private fun resolveRecipe(): Recipe? {
            recipe?.let { return it }
            synchronized(this) {
                recipe?.let { return it }
                val built = runCatching { buildRecipe() }
                    .onFailure { WeLogger.e(TAG, "原视频下载链路解析失败 (微信改版后混淆名已变)", it) }
                    .getOrNull()
                recipe = built
                if (built != null) {
                    WeLogger.i(TAG, "原视频下载链路已解析: ${built.sessionRun.declaringClass.name}.p()")
                }
                return built
            }
        }

        private fun buildRecipe(): Recipe {
            val helper = need(GALLERY_VIDEO_HELPER)
            val checkCls = need(GALLERY_VIDEO_CHECK)
            val sessionCls = need(CDN_SESSION)
            val parserCls = need(VIDEO_PARSER)
            val holderCls = need(VIDEO_RECORD_HOLDER)
            val recordCls = need(VIDEO_RECORD)
            val timeCls = need(CDN_TIME)

            val checkVideo = staticMethod(checkCls, "N", 1)
            // n1.t = 现场解析 (insert video info); n1.s = 只读缓存, 兜底用
            val buildVideoInfo = staticMethodOrNull(helper, "t", 1)
                ?: error("$GALLERY_VIDEO_HELPER.t(e9) 不存在")
            val cachedVideoInfo = staticMethodOrNull(helper, "s", 1)
                ?: error("$GALLERY_VIDEO_HELPER.s(e9) 不存在")
            val videoInfoCls = buildVideoInfo.returnType

            // 解析器单例挂在 v21.j3 上 (静态字段 h), 不在 v21.i3 自己身上
            val registry = holderCls.getDeclaredField("h").apply { isAccessible = true }.get(null)
                ?: error("$VIDEO_RECORD_HOLDER.h 为 null")
            val lookupRecord = declaredMethod(parserCls, "d", String::class.java)
                ?: error("$VIDEO_PARSER.d(String) 不存在")
            val recordUrl = declaredField(recordCls, "d", String::class.java)
                ?: error("$VIDEO_RECORD.d (String) 不存在")

            // v2.e() 是 CDN 地址 (下载任务第 2 个参数), v2.h() 是下载记录的 key (消息内容 XML)
            val videoInfoUrl = declaredMethod(videoInfoCls, "e")
                ?: error("${videoInfoCls.name}.e() 不存在")
            val videoInfoKey = declaredMethod(videoInfoCls, "h")
                ?: error("${videoInfoCls.name}.h() 不存在")

            val msgInfoCls = buildVideoInfo.parameterTypes[0]
            val callbackCls = need(TASK_CALLBACK)
            val callbackInterface = callbackCls.interfaces.firstOrNull()
                ?: error("$TASK_CALLBACK 没有实现下载回调接口")

            val newTime = declaredConstructor(timeCls, java.lang.Long.TYPE)
                ?: error("$CDN_TIME(long) 不存在")
            val newCallback = declaredConstructor(callbackCls, msgInfoCls, videoInfoCls, timeCls, recordCls)
                ?: error("$TASK_CALLBACK(${msgInfoCls.simpleName}, v2, ${timeCls.simpleName}, ${recordCls.simpleName}) 不存在")
            val newDownloadTask = declaredConstructor(
                need(TASK_DOWNLOAD), msgInfoCls, String::class.java, java.lang.Boolean.TYPE
            ) ?: error("$TASK_DOWNLOAD(e9, String, boolean) 不存在")
            val newSession = declaredConstructor(sessionCls, callbackInterface)
                ?: error("$CDN_SESSION(${callbackInterface.simpleName}) 不存在")
            val sessionAdd = declaredMethod(sessionCls, "a", callbackOrTaskInterface = true)
                ?: error("$CDN_SESSION.a(下载任务) 不存在")
            val sessionRun = declaredMethod(sessionCls, "p")
                ?: error("$CDN_SESSION.p() 不存在")

            // 进度回调字段: 接口类型 + gallery.n1 实现了它 (原始逻辑里 l.m 被赋成 n1),
            // 且不能是构造参数里那个下载回调接口 (那个我们已经传了真对象)
            val sessionProgress = sessionCls.declaredFields
                .firstOrNull {
                    it.type.isInterface &&
                        it.type != callbackInterface &&
                        it.type.isAssignableFrom(helper)
                }
                ?.apply { isAccessible = true }

            return Recipe(
                checkVideo = checkVideo,
                buildVideoInfo = buildVideoInfo,
                cachedVideoInfo = cachedVideoInfo,
                lookupRecord = lookupRecord,
                registry = registry,
                recordUrl = recordUrl,
                videoInfoUrl = videoInfoUrl,
                videoInfoKey = videoInfoKey,
                newTime = newTime,
                newCallback = newCallback,
                newDownloadTask = newDownloadTask,
                newSession = newSession,
                sessionAdd = sessionAdd,
                sessionRun = sessionRun,
                sessionProgress = sessionProgress,
                progressType = sessionProgress?.type,
            )
        }

        private fun need(className: String): Class<*> = resolve(className) ?: error("类不存在: $className")

        private fun staticMethod(clazz: Class<*>, name: String, paramCount: Int): Method =
            staticMethodOrNull(clazz, name, paramCount)
                ?: error("${clazz.name} 里找不到静态方法 $name/$paramCount")

        private fun staticMethodOrNull(clazz: Class<*>, name: String, paramCount: Int): Method? =
            clazz.declaredMethods
                .firstOrNull {
                    it.name == name && it.parameterCount == paramCount && Modifier.isStatic(it.modifiers)
                }
                ?.apply { isAccessible = true }

        private fun declaredMethod(
            clazz: Class<*>,
            name: String,
            param: Class<*>? = null,
            callbackOrTaskInterface: Boolean = false,
        ): Method? = clazz.declaredMethods
            .firstOrNull { m ->
                m.name == name && when {
                    param != null -> m.parameterCount == 1 && m.parameterTypes[0] == param
                    callbackOrTaskInterface -> m.parameterCount == 1 && m.parameterTypes[0].isInterface
                    else -> m.parameterCount == 0
                }
            }
            ?.apply { isAccessible = true }

        private fun declaredField(clazz: Class<*>, name: String, type: Class<*>): Field? =
            clazz.declaredFields
                .firstOrNull { it.name == name && it.type == type }
                ?.apply { isAccessible = true }

        private fun declaredConstructor(clazz: Class<*>, vararg params: Class<*>): Constructor<*>? =
            runCatching {
                clazz.getDeclaredConstructor(*params).apply { isAccessible = true }
            }.getOrNull()
    }
}
