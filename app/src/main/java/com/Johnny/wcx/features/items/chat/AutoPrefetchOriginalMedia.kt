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
import dev.ujhhgtg.reflekt.reflekt
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
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * **不打开**图片 / 视频, 也把收到的原图与原画质视频拉下来。
 *
 * 与「自动查看原图/原视频」的分工:
 *  - 「自动查看原图/原视频」是**你点开时才**动作 —— 在图片/视频页出现的一瞬间把
 *    「查看原图 / 查看原视频」按钮压掉并替你点掉, 视觉上就是"打开即原画质";
 *  - 本功能是**后台提前拉** —— 消息一落库就触发 CDN 下载, 等你真点开时原画质已到本地。
 *
 * ## 图片
 *
 * 复用已在「自动缓存图片」里验证过的通道: [WeMessageApi.cacheImage] →
 * `ImageService.downloadImage(...)`, 该通道**优先下载原图** (`ImgInfo2.hdImgId > 0` 时
 * 取原图那一行, 见 `WeMessageApi.ensureImageCachedFile`)。
 *
 * ## 视频 (原画质)
 *
 * 微信的「查看原视频」是 **UI 层自己造的 CDN 任务**, 没有模型层入口, 因此这里按
 * 8.0.76 安装包反查结果**复刻微信自己那条链路** (classes3/4/12.dex)。精简后的原始逻辑
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
 * ### v287: v285/v286 为什么在设备上"不点开视频就不下载"
 *
 * 反汇编 8.0.76 (classes12.dex) 后确认两处硬伤, 都是**静默失败**——
 * 失败时日志只打"该视频没有原视频信息", 看起来像"这条视频本来就没有原片":
 *
 *  1. **注册表找错类**: 原来读 `v21.i3.h` 这个静态字段, 但 `v21.i3` 就是
 *     `MicroMsg.VideoParseInfo` 本身 (只有 a/b/c/d/e 五个解析方法, **没有任何字段**)。
 *     真正的单例是 **`v21.j3.h`(static final, 类型 v21.i3)**, 两个解析缓存
 *     `v21/j3;->i` / `->j` 也在 `v21.j3` 上。所以旧代码 `getDeclaredField("h")`
 *     直接抛 NoSuchFieldException → 整个配方解析失败 → 记一次"链路不可用"后
 *     **当成已处理**, 再也不重试。
 *  2. **视频信息走了只读缓存**: 原来用 `gallery.n1.s(msg)`, 它内部只是
 *     `v21.d3.h(msg.y0())` 查 gallery 的缓存 —— 用户没打开过视频页时缓存是空的,
 *     返回 null。微信自己能用是因为页面加载时先调了 **`gallery.n1.t(msg)`**
 *     (日志 tag `MicroMsg.Imagegallery.handler.video` 的 "insert video info:"):
 *     它现场 `h35/d.fromXml(消息内容)` 解析出 `v21.v2` (url/长度/md5), 写
 *     `com.tencent.mm.storage.f9.Ta(msgId, msg, true)` 落库, 再塞进缓存。
 *     **后台提前拉原视频必须走 `t`**, 这才是"自动查看原视频"的等价动作。
 *
 * 另外 `v21.i3.d(key)` 的 key 是**消息内容 XML** (`v2.h()` = `e9.j()`,
 * 内部 `aa.d(xml, "msg", null)` 解析, 取 `.msg.videomsg.$.cdnrawvideourl` 等),
 * 不是 fileid; 记录里 `d` = cdnrawvideourl、`f` = cdnrawvideoaeskey、
 * `b` = isplaceholder、`g` = rawlength。
 *
 * 这些类名是**混淆名** (il4 / v21 / rl4 这类短包名), 微信改版后会整体失效 —— 所以
 * 每一环都有独立日志说明卡在哪里; 并且装了**探针**: 微信自己走一次原视频下载
 * (打开视频页时「自动查看原图/原视频」会替你点掉按钮) 就会把真实参数打进日志,
 * 下次据此校准。
 *
 * ## 触发与补偿
 *
 * 消息落库的 insert / update 事件驱动, 外加每 5 分钟一轮、回看 6 小时的补偿扫描
 * (与「自动缓存图片」同款设计: 监听器注册晚于消息落库、开启功能时消息早已落库、
 * 图片 / 视频信息行写入过晚都会漏事件, 必须靠扫描兜底)。
 *
 * ### v288: 默认跟随「自动查看原图/原视频」的开关
 *
 * 设备日志 (2026-10-14) 里本功能**一行日志都没有** —— 因为它的开关一直没人拨过
 * ([SwitchFeature.startup] 读 `WePrefs.getBoolOrDef(name, defaultEnabled)`,
 * 而 `defaultEnabled` 原本是 false), 于是 `onEnable()` 从未执行, 用户看到的现象就是
 * "收到视频不下载, 点开才下载"。用户真正拨过的开关只有「自动查看原图/原视频」。
 *
 * 两个功能是同一诉求的两半: 那个只能在**点开**图片/视频页时动作, 本功能才做得到
 * "不点开也拉"。所以 [defaultEnabled] 改为**跟随**前者的开关值 —— 已经明确开过
 * "自动查看原图/原视频" 的用户, 升级后本功能自动生效; 在本功能里拨过开关的用户,
 * 以本功能的 key 为准 (不会覆盖用户的显式选择)。
 *
 * ### v290: 读不出原视频信息 ≠ 没有原视频 (v287 的静默失败还有第二个入口)
 *
 * 2026-10-14 设备日志 (微信 8.0.76) 复现了 v287 之后仍然"点开才下载"的现象, 根因是
 * **假结案**: 21:40:33 收到一条视频, 本功能判"该视频没有原视频信息"并记入 done,
 * 一分钟后用户点开视频页, 微信自己却弹出了「查看原视频 28MB」。日志里那条判定的
 * `key` 只有 24 个字符: `wxid_xxx:0:0\n` —— 也就是说**消息刚落库那一刻**,
 * `n1.t(msg)` 建出的 `v21.v2` 的 `w` 字段 (微信自己也是把它当 `<msg>` XML 传给
 * `v21.j3.h.d()` 的) 还不是 XML, 解析出来自然没有 cdnrawvideourl。
 *
 * 于是 v290 做了三件事:
 *  1. **候选 XML 交叉验证**: DB 直读的 `content` / `MsgInfo.content` / `v2.h()` 三个来源里,
 *     取第一个"含 `<msg>` 且含 `videomsg`"的; 全都不像 XML → 判"信息未就绪",
 *     返回"稍后重试"而**不是**结案。
 *  2. 只有 XML 已确认是视频消息 XML、却仍解析不出 cdnrawvideourl 时, 才判"没有原视频"结案。
 *  3. 补上跨轮次重试上限 ([MAX_TOTAL_ATTEMPTS]), 让"每 5 分钟补偿扫描"能真正重试,
 *     又不至于永久刷屏; 同时跳过 `isSend=1` (自己发的媒体本地就有原文件, 不必拉 CDN)。
 *
 * ### v291: 别和微信抢同一个视频文件 (修复"接收视频后有几率播放不了")
 *
 * v290 修好"点开才下载"之后, 用户反馈**收到视频后点开转圈 / 黑屏播不出**。
 * 根因是**写者冲突**: 原视频下载写的就是普通视频那一个文件 (微信「查看原视频」
 * 是覆盖本地文件), 而 v290 会在消息落库后几秒内就发起 —— 此刻微信自己正在写
 * 同一个文件 (普通视频链路), 两个写者并发 → 文件不完整。设备日志佐证:
 * `23:40:36 收到 → 23:40:42 我们就发起了原视频下载 (accepted=true)`,
 * 期间微信自己的下载任务也在跑; 之后同一条视频 30 秒内被重复发起 5 次。
 *
 * 于是加两道闸:
 *  1. **等微信把普通视频下完** ([OriginVideoDownload.ordinaryVideoReady]): 查
 *     `videoinfo2` / `videoinfo` 的 `status` / `netoffset` / `totallen` / `filenowsize`
 *     (表结构运行时探测, 探测不到就退化为「消息落库 [VIDEO_SAFE_DELAY_MS] 后才动」)。
 *     在此之前连 `n1.t` 都不调 —— 它也会写 VideoInfo 行, 同样会打扰微信的下载。
 *  2. **同一条视频 [VIDEO_LAUNCH_COOLDOWN_MS] 内只发起一次**, 顺带压掉补偿扫描
 *     对同一条消息的反复建任务。
 *
 * ### v293: 合并「自动查看原图/原视频」, 功能列表只留一个入口
 *
 * 用户反馈功能列表里「后台自动加载原图/原视频」和「自动查看原图/原视频」描述几乎一样,
 * 是两个重复入口 (2026-10-15 截图)。实际上它们是同一诉求的两半: 后台"不点开也拉"
 * (本功能) + 打开页面时"按钮无感点掉" ([GalleryOriginButtonHook])。
 *
 * 处理: 保留本功能作为唯一入口, 原「自动查看原图/原视频」的**页面兜底能力**整体并入
 * (帧级拦截 + 点击「查看原图 / 查看原视频」按钮), 由同一个开关控制; 独立功能
 * `AutoViewOriginalMedia` 删除。它的开关 key 由 [VIEW_FEATURE_KEY] 继续继承 ——
 * 以前开过那个功能的用户, 升级后本功能默认就是开的。
 * 兜底意义: 后台拉取有漏项或没来得及完成时, 用户打开页面这一下仍能无感拿到原画质。
 *
 * ### v294: 原图侧的"假落地" (与视频侧同源的判据问题)
 *
 * 设备日志 (2026-10-15) 显示原图预取"看起来成功"、实际没下完:
 * ```
 * 14:49:48.606 start cdn download: localId=37952           ← 触发下载
 * 14:49:50.887 原图已落地: .../image2/2b/8f/2b8f...jpg    ← 2.3 秒就宣布成功
 * 14:50:57.317 无感加载「查看原图」: 查看原图 3MB (Button) ← 69 秒后微信仍说没下好
 * ```
 * 根因在 `WeMessageApi.resolveExistingImageFile`: 判据只有"磁盘文件存在且非空", 而微信的
 * 下载服务**在写入第一个字节之前**就创建文件并把 `bigImgPath` 从 `SERVERID://` 改写成最终
 * 文件名 —— 下载刚开始 1~2 秒就能命中, 拿到的其实是半成品。视频侧在 v291/v292 已经因为
 * 同类问题翻车两次 (详见 [OriginVideoDownload.ordinaryVideoReady]), 这次图片侧是同一枚硬币。
 *
 * 修法在 `WeMessageApi` (公共通道, `AutoCacheImages` / `DownloadImagesToLocalStorage` /
 * `ScheduledMessage` / ApiServer 都走它): 改用**微信内部同款口径** `ImgInfo2.totalLen == offset`
 * (列名运行时探测), 并要求磁盘字节数不低于 `totalLen`; 拿不到长度信息 (本地图 / GIF /
 * 表结构变化) 时退回老行为, 不把功能卡死。等待策略同步改为进度感知
 * (`IMG_IDLE_TIMEOUT_MS` 45s 无进展才补一次触发, `IMG_MAX_WAIT_MS` 3min 总预算)。
 *
 * ### v295: v294 的兜底方向反了, 修回来 + 修重复触发
 *
 * 上面 v294 那版的判据写反了方向, 装上去**1 秒**就打「原图已落地」, 比修之前还快:
 * ```
 * 15:49:51.137 triggerDownload result=0
 * 15:49:52.140 ImgInfo2 列探测: totalLen=totalLen offset=offset
 * 15:49:52.142 image landed: 触发 1 次, 用时 1s             ← 1 秒就宣布成功
 * 15:51:41.089 无感加载「查看原图」: 查看原图 2MB            ← 112 秒后仍未下好
 * ```
 * 因为**下载刚触发时 `ImgInfo2.totalLen` 就是 0** (要等 CDN 回包才填), 正好命中
 * 「`totalLen <= 0` → 退回『文件存在即算』」那条兜底, 又一次在微信预创建的空壳文件上
 * 宣布成功。**教训: 拿不到进度 ≠ 可以当成完成, 任何兜底都不能朝「提前宣布成功」的方向退。**
 *
 * 现在改用**消息 XML 的 `<img hdlength="...">`** 作权威判据 —— 那正是微信「查看原图 2MB」
 * 那个数字的来源, 落库即有、不依赖下载进度; 进度观测也改为以**磁盘字节数**为准。
 *
 * 另一处: 日志里同一条消息 `start cdn download` 打了 **3 次** —— 本功能与
 * `AutoCacheImages` 各有各的去重集合 (`inFlight` / `pendingSvrIds`), 彼此看不见, 而
 * 微信的下载任务被重复触发会互相打断 (视频侧 v290 早已踩过)。去重因此上移到
 * `WeMessageApi.ensureImageCachedFile` 的 single-flight: 同一张图同一时刻只有一个功能在下,
 * 其余的搭车等结果。
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
     * 已合并的「自动查看原图/原视频」的开关 key (见 [defaultEnabled])。
     *
     * v293 起那个功能已并入本功能 (它的页面兜底能力见 [GalleryOriginButtonHook]),
     * 这个 key 现在只作为**兼容旧用户选择**的默认值来源。
     */
    private const val VIEW_FEATURE_KEY = "自动查看原图/原视频"

    /** 上面那个功能改名前的 key (v281 事故留下的兼容 key)。 */
    private const val VIEW_FEATURE_LEGACY_KEY = "自动查看原图"

    /**
     * 本功能从未被拨动过时的默认值 = **继承已合并的「自动查看原图/原视频」开关值**。
     *
     * 用户既然显式开过 "自动查看原图/原视频", 说明他要的就是"原画质自动到本地";
     * v293 起那个功能的页面兜底能力也由本功能提供, 所以直接继承。
     * 一旦用户在本功能的设置项里拨过开关, `WePrefs` 里就有本功能的 key 了,
     * `getBoolOrDef` 不会再走到这里 —— 用户的显式选择永远优先。
     */
    override val defaultEnabled: Boolean
        get() = runCatching {
            WePrefs.getBoolOrDef(
                VIEW_FEATURE_KEY,
                WePrefs.getBoolOrDef(VIEW_FEATURE_LEGACY_KEY, false)
            )
        }.getOrDefault(false)

    /**
     * 启动日志 (v288 新增)。
     *
     * v285/v286 的日志里本功能**一行都没有**, 没法区分"开关没开"和"开了但静默失败" ——
     * 这次把开关值 + 三个 key 的存在性都打出来, 下次一眼就能定论。
     */
    override val shouldEnableOnStartup: Boolean
        get() {
            val selfKey = runCatching { WePrefs.containsKey(name) }.getOrDefault(false)
            val viewKey = runCatching { WePrefs.containsKey(VIEW_FEATURE_KEY) }.getOrDefault(false)
            val viewLegacyKey =
                runCatching { WePrefs.containsKey(VIEW_FEATURE_LEGACY_KEY) }.getOrDefault(false)
            WeLogger.i(
                TAG,
                "startup: 开关=$_isEnabled (本功能 key '$name' 存在=$selfKey; " +
                    "未拨动过时跟随「自动查看原图/原视频」: " +
                    "新key 存在=$viewKey, 旧key 存在=$viewLegacyKey)"
            )
            return _isEnabled
        }

    /** 单次 dispatch 内的最多尝试次数 (立即 + 两次延迟重试)。 */
    private const val MAX_ATTEMPTS = 3

    /**
     * 同一条消息**跨轮次** (事件重投 + 补偿扫描) 的总尝试上限。
     *
     * v289 之前这里只有"成功才收工"一种终态, 读不出原视频记录时会被当成
     * "该视频没有原视频信息"**直接结案** —— 设备日志 (2026-10-14, 微信 8.0.76) 里就出现了
     * 假结案: 21:40:33 收到的视频被判"没有原视频信息", 一分钟后用户点开视频页,
     * 微信自己却弹出了「查看原视频 28MB」。也就是说**消息刚落库那一刻**拿到的视频信息
     * 还不完整 (n1.t 建出的 v2 里 w 字段不是 `<msg>` XML), 之后再重试就能成功。
     * 所以"读不出"只能算"信息不足, 稍后重试", 由 [MAX_TOTAL_ATTEMPTS] 兜底防刷屏。
     */
    private const val MAX_TOTAL_ATTEMPTS = 6

    /**
     * 【v291】原视频下载写的是**同一个视频文件**: 微信收到视频时自己也在往这个文件里写
     * (普通视频链路), 两个写者并发 → 文件不完整 → 用户点开就是"转圈 / 黑屏播不出"。
     *
     * 所以发起前必须先确认微信没有在写这个文件 (见 [OriginVideoDownload.ordinaryVideoReady])。
     *
     * 【v292 修正】v291 把它写成了"status < 199 就一律避让", 兜底方向反了 —— 见
     * [OriginVideoDownload.ordinaryVideoReady] 的注释。现在这条时间门槛只用来判断
     * "消息是不是刚落库" (微信随时可能启动自动下载), 不再单独决定放行与否。
     */
    private const val VIDEO_SAFE_DELAY_MS = 20_000L

    /** 【v292】"疑似在下"时的二次采样间隔 —— 只有字节数真的在涨才认定微信在下载。 */
    private const val VIDEO_GROW_PROBE_MS = 1_500L

    /**
     * 【v292】微信侧暂时没接受我们的下载任务时的重试间隔。
     *
     * 这通常意味着"同一个 fileid 的任务已在队列里" (微信自己正在下普通画质), 此时
     * 不该按 [VIDEO_LAUNCH_COOLDOWN_MS] 那样等满 3 分钟 —— 微信下完我们就该接上。
     */
    private const val VIDEO_RETRY_COOLDOWN_MS = 60_000L

    /**
     * 同一条视频 (CDN fileid) 两次发起之间的最小间隔。既防与微信自己的任务并发,
     * 也防补偿扫描在同一条消息上反复建任务 (设备日志里出现过 5 次/30 秒的重复发起)。
     */
    private const val VIDEO_LAUNCH_COOLDOWN_MS = 3 * 60_000L

    /** 补偿扫描回看窗口。 */
    private const val SCAN_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** 补偿扫描单轮条数上限, 避免开启瞬间发起过多下载任务。 */
    private const val SCAN_LIMIT = 200

    /** 补偿扫描周期。 */
    private const val SCAN_INTERVAL_MS = 300_000L

    /** 同时进行的下载任务上限 (原视频动辄几十 MB)。 */
    private const val MAX_CONCURRENT = 2

    /** 已完成 (已拉到本地) 的消息, 键为 "type:msgSvrId"。 */
    private val doneKeys = ConcurrentHashMap.newKeySet<String>()

    /** 已放弃的消息 (重试到顶仍拿不到), 键同 [doneKeys]; 任何路径都不再处理。 */
    private val settledKeys = ConcurrentHashMap.newKeySet<String>()

    /** 每条消息跨轮次的尝试轮数 (见 [MAX_TOTAL_ATTEMPTS])。 */
    private val attemptsBySvrId = ConcurrentHashMap<Long, Int>()

    /** 正在处理中的 msgSvrId, 防止 insert/update/扫描 三路重复发起。 */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    /** 已发现但尚未完成的 msgSvrId (update 事件据此判断是否值得跟进)。 */
    private val pendingSvrIds = ConcurrentHashMap.newKeySet<Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = Semaphore(MAX_CONCURRENT)

    private fun isTarget(type: Int?): Boolean =
        type == MessageType.IMAGE.code || type == MessageType.VIDEO.code

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
        installDownloadProbes()
        // v293: 原「自动查看原图/原视频」已合并进本功能 —— 后台没拉及时 (或漏项) 时,
        // 用户打开图片/视频页这一下由它兜底: 按钮在帧级被拦掉并点掉, 点开已是原画质。
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
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs)
                ?: return
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
                semaphore.withPermit { runTask(row, via, key) }
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
        /** message.content: 视频消息的 `<msg>` XML (原视频信息就在里面), 图片用不到。 */
        val content: String?,
        /** message.imgPath: 视频/图片的 CDN fileid。 */
        val imgPath: String?,
        /** message.createTime: 用来识别"消息刚落库"这种高风险时刻 (见 [VIDEO_SAFE_DELAY_MS])。 */
        val createTime: Long = 0L,
    )

    private fun queryRow(msgSvrId: Long): Row? {
        if (!ensureDatabase()) return null
        return try {
            WeDatabaseApi.rawQuery(
                "SELECT type, msgId, msgSvrId, talker, isSend, content, imgPath, createTime " +
                    "FROM message WHERE msgSvrId=? LIMIT 1",
                arrayOf<Any>(msgSvrId)
            ).use { cursor ->
                if (!cursor.moveToNext()) {
                    WeLogger.w(TAG, "message 表里找不到 msgSvrId=$msgSvrId")
                    null
                } else {
                    Row(
                        type = cursor.getInt(cursor.getColumnIndexOrThrow("type")),
                        msgId = cursor.getLong(cursor.getColumnIndexOrThrow("msgId")),
                        msgSvrId = msgSvrId,
                        talker = cursor.getString(cursor.getColumnIndexOrThrow("talker")),
                        isSend = cursor.getInt(cursor.getColumnIndexOrThrow("isSend")),
                        content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                        imgPath = cursor.getString(cursor.getColumnIndexOrThrow("imgPath")),
                        createTime = cursor.getLong(cursor.getColumnIndexOrThrow("createTime")),
                    )
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "查询 message 行失败 (msgSvrId=$msgSvrId)", e)
            null
        }
    }

    private suspend fun runTask(row: Row, via: String, key: String) {
        val round = attemptsBySvrId.merge(row.msgSvrId, 1, Int::plus) ?: 1
        if (round > MAX_TOTAL_ATTEMPTS) {
            // 跨轮次重试到顶: 给"为什么放弃"一个定论, 不再被补偿扫描每轮拾起。
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

    /** 原图: 复用「自动缓存图片」的通道 (内部优先取 hdImgId 原图行)。@return 是否已落地。 */
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

    private fun ensureDatabase(): Boolean {
        if (WeDatabaseApi.isReady) return true
        runCatching { WeDatabaseApi.ensureDatabase() }
        if (WeDatabaseApi.isReady) return true
        WeLogger.w(TAG, "数据库未就绪 (重适配窗口), 本轮跳过, 稍后重试")
        return false
    }

    private fun scanRecentMessages() {
        if (!ensureDatabase()) return
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
    // 探针: 让微信自己跑一次原视频下载, 把真实参数打进日志 (用来校准上面的复刻配方)
    // ---------------------------------------------------------------------------------------------

    private var probesInstalled = false

    private fun installDownloadProbes() {
        if (probesInstalled) return
        probesInstalled = true

        // 打开带原视频的视频页时, 「自动查看原图/原视频」会自动点掉「查看原视频」按钮,
        // 于是这两个构造器必然被调到 —— 日志里的真实参数就是复刻配方对不对的判据。
        hookFirstConstructor(OriginVideoDownload.TASK_DOWNLOAD, "下载任务 gallery.f") { args ->
            "msgId=${msgIdOf(args.getOrNull(0))} url=${args.getOrNull(1)} 原视频标记=${args.getOrNull(2)}"
        }
        hookFirstConstructor(OriginVideoDownload.TASK_CALLBACK, "下载回调 gallery.g") { args ->
            "msgId=${msgIdOf(args.getOrNull(0))} 视频信息=${args.getOrNull(1)?.javaClass?.simpleName} " +
                "记录=${args.getOrNull(3)?.javaClass?.simpleName}"
        }
    }

    private fun hookFirstConstructor(
        className: String,
        label: String,
        describe: (Array<Any?>) -> String,
    ) {
        runCatching {
            val clazz = className.toClassOrNull() ?: run {
                WeLogger.w(TAG, "探针跳过: $className 不存在")
                return@runCatching
            }
            val ctor = clazz.reflekt().firstConstructorOrNull { } ?: run {
                WeLogger.w(TAG, "探针跳过: $label 没有构造器")
                return@runCatching
            }
            ctor.hookAfter {
                runCatching { WeLogger.i(TAG, "探针[$label] 微信侧调用: ${describe(args)}") }
            }
            WeLogger.i(TAG, "探针已装: $label")
        }.onFailure { WeLogger.w(TAG, "探针安装失败: $label", it) }
    }

    private fun msgIdOf(msgInfo: Any?): Long = runCatching {
        msgInfo?.javaClass?.getMethod("getMsgId")?.invoke(msgInfo) as? Long ?: 0L
    }.getOrDefault(0L)

    // ---------------------------------------------------------------------------------------------
    // 复刻微信的「原视频」下载链路 (8.0.76 混淆名, 失效时靠日志定位到具体一环)
    // ---------------------------------------------------------------------------------------------

    private object OriginVideoDownload {

        private const val TAG = "AutoPrefetchOriginalMedia"

        /** 视频消息解析 + 原视频下载记录查询 (静态方法所在地)。 */
        private const val GALLERY_VIDEO_HELPER = "com.tencent.mm.ui.chatting.gallery.n1"

        /** 视频消息判定 (静态 N(MsgInfo): Z)。 */
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
         * 解析器单例的宿主: 静态字段 `h`(类型 v21.i3) 就是解析器实例,
         * `i` / `j` 是它内部的两个解析缓存。**别把 h 当成 v21.i3 自己的字段** ——
         * v21.i3 一个字段都没有 (v287 踩过)。
         */
        private const val VIDEO_RECORD_HOLDER = "v21.j3"

        /** 原视频下载记录: d = cdnrawvideourl, f = aeskey, b = isplaceholder, g = rawlength。 */
        private const val VIDEO_RECORD = "v21.j3"

        /** 下载计时对象, 构造参数是 long。 */
        private const val CDN_TIME = "rl4.c"

        /** 只在同一条消息上打一次"已发起"日志。 */
        private val logged = ConcurrentHashMap.newKeySet<Long>()

        @Volatile
        private var recipe: Recipe? = null

        @Volatile
        private var resolveFailureLogged = false

        private class Recipe(
            val checkVideo: Method,
            /** `n1.t(MsgInfo)`: 现场解析视频消息并写缓存 (微信自己先走它)。 */
            val buildVideoInfo: Method,
            /** `n1.s(MsgInfo)`: 只读 gallery 缓存, 作为兜底。 */
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
        // v291: 两条"别和微信抢同一个文件"的闸
        //
        // 原视频下载写的就是普通视频那一个文件 (微信「查看原视频」是覆盖本地文件)。
        // 2026-10-14 v290 的设备日志里, 收到视频后几秒内我们就发起了原视频下载, 而此刻
        // 微信自己正在写同一个文件 —— 用户之后点开就是"转圈 / 黑屏播不出"。
        // 所以: (1) 等微信把普通视频下完; (2) 同一条视频不重复发起。
        // -----------------------------------------------------------------------------------------

        /** 同一条视频 (CDN fileid) 上次发起的时间戳, 用于 [VIDEO_LAUNCH_COOLDOWN_MS] 冷却。 */
        private val lastLaunchAt = ConcurrentHashMap<String, Long>()

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
         * videoinfo2 里"正在下载"的 status 集合。
         *
         * 日志实测: `111` = 已入库**待下载** (微信没在传, 它在等用户点开),
         * `121` = 正在下载 (file 已有部分字节), `199` = 完成。
         * 这里只当作"值得再采样确认"的嫌疑信号, 结论一律由字节是否增长来下。
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

            /** 有没有"微信确实在传"的正向证据。 */
            val suspicious: Boolean
                get() = status in VIDEO_DOWNLOADING_STATUS ||
                    (total > 0L && ((offset > 0L && offset < total) || (fileSize > 0L && fileSize < total)))

            fun describe(): String = "status=$status, offset=$offset, file=$fileSize/$total"
        }

        /**
         * 采样一次视频下载进度。查不到行 / 表结构对不上都返回 null (= 判据不可用)。
         */
        private fun sampleVideoProgress(row: Row): VideoProgress? {
            if (!ensureDatabase()) return null
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
         * 【v291 的教训：兜底方向绝不能反】
         * v291 写的是 "status < 199 就一律避让", 实测被这两种情况打穿:
         *   · `status=111, offset=0, file=0` —— 微信**根本没开始下**, 它在等用户点开;
         *   · `status=0,  totallen=0, filenowsize=0` —— 查到空行 / 键没对上。
         * 关键问题是这两种状态会**一直持续** (不点开就不下), 于是预取被无限期挂起。
         *
         * 2026-10-15 设备日志: 同一批视频 5 分钟内被这样拦掉 13 次, 全程只成功发起 1 次;
         * 而用户点开视频页后微信自己立刻就开始下载 —— 也就是"无感"完全失效。
         *
         * 所以改成**正向证据 + 增长确认**:
         *   1. 先要有"微信确实在传"的证据（CDN offset 有进展 / 文件已写了一部分 / status 在下载中集合）；
         *   2. 有证据也不直接下结论，隔 [VIDEO_GROW_PROBE_MS] 再采样一次，**只有字节数真的在涨**才避让；
         *   3. 查不到行 / 状态不明 / 数字不动 → 一律放行。宁可抢跑，也不能把无感预取做成摆设。
         */
        private suspend fun ordinaryVideoReady(row: Row): Boolean {
            val age = if (row.createTime > 0L) System.currentTimeMillis() - row.createTime else -1L
            // 刚落库: 微信随时可能启动自动下载, 这种时刻一律先采样确认
            val fresh = age in 0 until VIDEO_SAFE_DELAY_MS
            val first = sampleVideoProgress(row)

            // 判据说没在传, 也不是刚落库的高风险时刻 → 直接放行
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
            lastLaunchAt.clear()
            videoTable = null
            videoTableProbed = false
        }

        /** @return true 表示这条消息已经有了定论 (已发起 / 确实没有原视频 / 不是视频消息)。 */
        suspend fun request(msgInfo: Any, row: Row): Boolean {
            val r = resolveRecipe()
            if (r == null) {
                if (!resolveFailureLogged) {
                    resolveFailureLogged = true
                    WeLogger.w(TAG, "原视频下载链路不可用, 后台只拉原图 (微信改版后需按日志校准混淆名)")
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

            // 0) v291: 别和微信抢同一个视频文件
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
            //    用户没打开过视频页时缓存是空的 → v285/v286 就是这样静默失败的。
            val videoInfo = runCatching { r.buildVideoInfo.invoke(null, msgInfo) }.getOrNull()
                ?: runCatching { r.cachedVideoInfo.invoke(null, msgInfo) }.getOrNull()

            // 3) 原视频信息 (cdnrawvideourl / aeskey / rawlength) 藏在视频消息的 `<msg>` XML 里,
            //    微信自己就是 `v21.j3.h.d(v2.h())` —— v2.h() 应该返回 `<msg>` XML。
            //
            //    【v289 的真机结论】消息刚落库那一刻 `v2.h()` 还不是 XML (实测是一条
            //    `wxid_xxx:0:0\n` 之类的中间态, 见 2026-10-14 设备日志), 于是解析出的记录
            //    d 为空 → 被误判成"该视频没有原视频信息"并结案; 一分钟后用户点开视频页,
            //    微信自己却弹出了「查看原视频 28MB」。所以这里改为:
            //      · 按优先级取多个候选 XML (DB 直读的 content / MsgInfo.content / v2.h()),
            //        只要有一个是完整的视频 XML 就用它;
            //      · 全都不像 XML → 判"信息未就绪"返回 false (稍后重试), **绝不结案**。
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

            // 4) 原视频下载记录 —— 微信自己就是靠它判断"这条消息确实带原视频"
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
                // 微信没接这个任务, 几乎总是"同一个 fileid 的任务已经在队列里" —— 也就是微信
                // 自己正在下普通画质。别按长冷却压满 3 分钟, 让它早一点接上。
                lastLaunchAt[fileId] =
                    System.currentTimeMillis() - (VIDEO_LAUNCH_COOLDOWN_MS - VIDEO_RETRY_COOLDOWN_MS)
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

        /** 同一条消息只打一次"信息未就绪"的诊断, 免得补偿轮刷屏。 */
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
                ?: error("$GALLERY_VIDEO_HELPER.t(MsgInfo) 不存在")
            val cachedVideoInfo = staticMethodOrNull(helper, "s", 1)
                ?: error("$GALLERY_VIDEO_HELPER.s(MsgInfo) 不存在")
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
            ) ?: error("$TASK_DOWNLOAD(MsgInfo, String, boolean) 不存在")
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
