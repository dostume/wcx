package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.data
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeConversationListViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

/**
 * 让三处微信原生界面的「名字」完整显示, 过长时自动换行而不是截断成「...」:
 *
 *  1. 微信主界面「微信」标签页 —— 会话列表行的名字
 *  2. 微信主界面「通讯录」标签页 —— 联系人行的名字
 *  3. 好友聊天页 —— 顶部标题栏的名字
 *
 * 微信这三处显示的「名字」优先取**备注名** (未设备注时退化为昵称), 因此功能命名为
 * 「完整显示备注名」。
 *
 * 截断分两种, 处理方式不同:
 *  - **渲染级截断** (会话列表 / 通讯录): TextView 的 singleLine / maxLines / ellipsize,
 *    text 本身仍是完整的 —— 放开单行/省略号/写死高度即可完整显示。
 *  - **代码级截断** (聊天页标题): 微信在 setText 之前就把字符串改成了
 *    「头部...尾部」—— 注意是三个 ASCII 点, 不是 Android 原生 ellipsize 的
 *    单字符「…」, 说明文本自身已残缺, 放开 TextView 无效。需挂 TextWatcher,
 *    发现省略号后用备注名 (rcontact.conRemark → nickname) 还原完整文本。
 *
 * ## 8.0.76.3141 安装包反查实锤 (2026-10-13, 布局已混淆, 靠资源 id 定位)
 *
 *  - 会话行 (微信标签页): `MvvmConversationAdapter` (Lfh5/w0) inflate `layout/e45`
 *    (另有 a2v/a2w 两个历史变体), 行根是 `ConversationFolderItemView` (只覆写
 *    onMeasure), 标题是 **`com.tencent.mm.ui.base.NoMeasuredTextView`**,
 *    `tag="nickname_tv"`, `id=0x7f094c8f`, 字号 17dp (摘要 14dp / 时间 12dp),
 *    宽度 match_parent、高度 wrap_content、行最小高度 72dp。
 *  - 通讯录行: `MicroMsg.Mvvm.AddressItemConvert` (Lah5/f) 只有 4 个方法:
 *    `c(RecyclerView): View` (inflate `layout/de`)、
 *    `d(RecyclerView, View): ViewHolder` (即 convertViewHolder)、
 *    `h(holder, item, ...)` (即 onBindViewHolder)、`i(RecyclerView, holder, Int)`。
 *    名字是 `TableRow` 内的 `TextView id=0x7f094c8f`, 17dp,
 *    `maxLines=1 + ellipsize=end`, 宽 wrap_content、高 match_parent,
 *    外层的名字行容器是固定 56dp (`dimen/dj`); 17dp 两行约 40dp, 56dp 装得下。
 *
 * ## 备注名为什么原来不换行 (2026-10-13 逐条实锤)
 *
 *  1. **会话行卡的不是单行属性, 是高度**。style `0x7f110235` (style/j3) 里写死
 *     `singleLine=true + ellipsize=end`; `setSingleLine(true)` 被 NoMeasuredTextView
 *     覆写成 `setLines(1)` → 它自己的 `.r` 字段 = 1, 而 `getDesiredHeight()` 拿 `.r`
 *     当**行数**算高度 (`layout.getLineTop(.r)`) → 视图被量成只有一行高, 第二行直接
 *     被裁掉; 同时 `.h`(ellipsize) = END 让内部 Layout 打上省略号。
 *     放开必须同时做三件事: `setSingleLine(false)` (→ `.r = MAX`, 高度回来)、
 *     `maxLines = MAX`、`ellipsize = null` (→ `.h = null`, 去掉省略号)。
 *  2. **两个列表的绑定时机都不可靠, 所以定位标题不能依赖文本**。
 *     会话行走 MvvmList 框架异步绑定; 通讯录行的 `d(RecyclerView, convertView)`
 *     是在**视图刚 inflate、文本还没 setText** 时调的 (文本在随后的 `h(...)` 里才写)。
 *     旧实现用「行内字号最大**且有文字**的 TextView」找标题 → 这两个时机下永远取到
 *     null, 于是备注名一次都没被放开过。现在改为 **tag → 资源 id → 字号最大** 三级定位,
 *     只要有控件就能找到, 与绑定时机无关。
 *  3. `NoMeasuredTextView` 覆写了 `setSingleLine / setLines / setMaxLines / setEllipsize`
 *     **且不调 super**, 标准 getter 读不到真实值 (`getMaxLines` 没被覆写, 返回的是
 *     TextView 自己的 mMaxLines)。唯一可靠的是 **`getEllipsize()` 被覆写**, 读的就是
 *     `.h` —— 所以「原来是不是单行」用它判断, 还原时也用它。
 *  4. 因为 (3), 只改 setter 还不够: 微信可能在任何时机把单行写回去, 所以额外钩住这四个
 *     setter, 参数代表「单行」时立刻再放开一次。`setText` 故意不钩 (会互相触发)。
 *
 * ## v286: 为什么 v285 在设备上仍然截断
 *
 *  v285 的设备日志里只出现一条
 *  `行标题已放开 (微信标签页-会话行): TextView tag=null 字号=38.0 ...` ——
 *  控件类是**普通 `TextView` 且没有 tag**, 而 8.0.76 三个会话行布局
 *  (`e45` / `a2v` / `a2w`) 的标题都是带 `tag=nickname_tv` 的 `NoMeasuredTextView`。
 *  也就是说定位这一环拿到的**不是标题**: 前两级 (tag / 资源 id) 都没命中, 落在了当时
 *  的第三级兜底「字号最大**且有文字**」上 —— 绑定那一刻标题还是空的, 于是挑走了别的控件,
 *  真正的标题从头到尾没被放开过。v286 的修正:
 *
 *  - 第三级兜底改成「字号最大 **(不看有没有文字)**」, 并用 sp 下限把摘要 / 时间排除
 *    (名字 17dp, 摘要 14dp, 时间 12dp); 控件只要在树里就能命中, 与文本何时写入无关;
 *  - 行标题上再挂一个 `TextWatcher`: 文本晚到时补放开一次, 并把微信**代码级截断**的
 *    「头部...」还原成完整名字 (来源: 会话 / 联系人的备注名 → 会话列表缓存 → 按前缀查
 *    `rcontact`, 首尾吻合才采用);
 *  - 额外给普通 `TextView` 补一套「防回退」守卫 (`released` 里有记录的控件才生效),
 *    因为名字控件未必是 `NoMeasuredTextView`;
 *  - 每个入口前 3 行会 dump 一次完整的行内 TextView 清单 (类名 / id / tag / 字号 /
 *    截断状态 / 文字), 下次再看日志就能直接确认布局换代。
 *
 * 关闭功能时会把放开过的 TextView 逐一还原成原状态 (列表行是复用 View 的,
 * 不还原的话关掉开关后行里仍是多行), 并摘掉 TextWatcher。
 */
@Feature(
    name = "完整显示备注名",
    categories = ["聊天"],
    description = "微信会话列表 / 通讯录 / 好友聊天页标题的备注名不再被截断成「...」，过长时自动换行显示"
)
object FullRemarkNameDisplay : SwitchFeature(),
    IResolveDex,
    WeConversationListViewApi.IBindViewListener {

    private const val TAG = "FullRemarkNameDisplay"

    /**
     * 更名前的功能名。开关状态以**功能名**为 key 持久化 ([SwitchFeature.startup] 用
     * `WePrefs.getBoolOrDef(name, ...)`), 所以 v281 把功能从「完整显示昵称」改名成
     * 「完整显示备注名」时, 老用户已开启的开关会静默回落到默认值 false —— 设备日志实锤:
     * v281 启动后本功能一条日志都没有 (连 onEnable 都没打), 而其它已开启功能都有 enable 日志。
     * 这里把旧 key 的状态作为默认值沿用, 用户在新名下拨动过开关后新 key 立即生效。
     */
    private const val LEGACY_NAME = "完整显示昵称"

    /**
     * 开关的默认值: 更名前开启过的老用户沿用旧 key 的状态, 避免改名把开关重置掉。
     * (只在用户从未在新名下拨动过开关时起作用。)
     */
    override val defaultEnabled: Boolean
        get() = runCatching { WePrefs.getBoolOrDef(LEGACY_NAME, false) }.getOrDefault(false)

    /**
     * 每次启动把开关的判定结果与两个 key 的存在情况打进日志, 便于下次一眼看出
     * 「是没开」还是「开了但 hook 没生效」。
     */
    override val shouldEnableOnStartup: Boolean
        get() = _isEnabled.also { on ->
            val newKey = runCatching { WePrefs.containsKey(name) }.getOrDefault(false)
            val oldKey = runCatching { WePrefs.containsKey(LEGACY_NAME) }.getOrDefault(false)
            WeLogger.i(
                TAG,
                "startup: 开关=$on (新key '$name' 存在=$newKey, 旧key '$LEGACY_NAME' 存在=$oldKey)"
            )
        }

    /** 记住的会话显示名 / 备注名查询缓存的上限。 */
    private const val NAME_CACHE_LIMIT = 128

    /** 会话行标题在布局里写死的 tag (a2v / a2w / e45 三个变体都是它)。 */
    private const val NICKNAME_TAG = "nickname_tv"

    /**
     * 会话行标题与通讯录名字**共用**的资源 id (8.0.76 实测 `0x7f094c8f`)。
     * 通讯录行的名字 TextView 上没有任何 tag, 只能靠资源 id 定位; 会话行用它兜底。
     */
    private const val ID_ROW_TITLE = 0x7f094c8f

    /**
     * 会话行标题控件的类名。8.0.76 未混淆, 用**写死的类名**定位 (与 ChattingUIFragment
     * 同款做法), 不新增 dex 委托 —— 避免改动 dex 缓存键导致用户升级后要重新适配。
     */
    private const val NO_MEASURED_TEXT_VIEW = "com.tencent.mm.ui.base.NoMeasuredTextView"

    /** setLines / setMaxLines 的参数落在这个区间时, 视为「被写回单行」。 */
    private const val MAX_SINGLE_LINE_LINES = 4

    /**
     * 行内「名字控件」的字号下限 (sp)。会话行的名字 17dp、摘要 14dp、时间 12dp;
     * 通讯录的名字 17dp —— 用字号就能把名字从摘要/时间里区分出来, **且不需要它已有文字**。
     * 这是 v286 新增的兜底定位: 微信的绑定时机不保证 tag / id 已就位时文本已填。
     */
    private const val MIN_TITLE_SP = 15f

    /** 每个入口最多 dump 几行视图树 (诊断用, 免得刷屏)。 */
    private const val MAX_DUMPED_ROWS = 3

    /**
     * 需要「防回退」的 setter: 方法名 → 参数是否代表单行。
     *
     * `NoMeasuredTextView` 把这四个 setter **全部覆写进自己的字段且不调 super**,
     * 所以微信在任何时机 (布局 style / 复用时的重新绑定) 写回单行, 都会经过这里;
     * 钩住它们就与绑定时机彻底解耦。`setText` 故意不钩 —— 会与放开动作互相触发。
     */
    private val TITLE_SETTERS = listOf(
        "setSingleLine" to true,
        "setLines" to true,
        "setMaxLines" to true,
        "setEllipsize" to false,
    )

    /** 诊断日志的入口名。 */
    private const val SITE_CONVERSATION = "微信标签页-会话行"
    private const val SITE_ADDRESS = "通讯录-联系人行"
    private const val SITE_PREFIX_LOOKUP = "行标题前缀查库"
    private const val SITE_PLAIN_GUARD = "普通 TextView 防回退"

    /** 已经放开过的行视图, 免得每次 onBind 都重新遍历一遍行内控件。 */
    private val processedRows = WeakHashMap<View, Boolean>()

    /** 每个入口 dump 过的行数 (诊断用)。 */
    private val dumpedRows = HashMap<String, Int>()

    /** 每个入口只打一次「行标题已放开」诊断日志, 免得滚动时刷屏。 */
    private val loggedSites: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    /** applyUnwrap 内部会调用微信覆写过的 setter, 需要防住观察器重入导致的无限递归。 */
    private val unwrapping = ThreadLocal.withInitial { false }

    /** 放开前的原始状态，供关闭功能时还原。 */
    private class TruncationState(
        val maxLines: Int,
        val singleLine: Boolean,
        val ellipsize: TextUtils.TruncateAt?,
        val height: Int,
    )

    /** 只记录被本功能改过的 TextView，WeakHashMap 让随行回收的 View 自然过期。 */
    private val released: MutableMap<TextView, TruncationState> =
        Collections.synchronizedMap(WeakHashMap<TextView, TruncationState>())

    /** 挂在标题 TextView 上的还原器，关闭功能时摘除。 */
    private val restorers = WeakHashMap<TextView, TitleRestorer>()

    /** 挂在会话行 / 通讯录行标题上的还原器 (代码级截断)，关闭功能时摘除。 */
    private val rowRestorers = WeakHashMap<TextView, RowTitleRestorer>()

    /** 是否已经给普通 TextView 挂过「防回退」守卫 (只在真的用到时才挂)。 */
    @Volatile
    private var plainTitleGuardInstalled = false

    /** 通讯录单元格的绑定器（addressbook_cell）。 */
    private val classAddressItemConvert by dexClass {
        matcher {
            usingStrings("MicroMsg.Mvvm.AddressItemConvert", "onBindViewHolder ", "addressbook_cell")
        }
    }

    /** ChattingContext (8.0.65 是 az4.c, 8.0.76 是 fd5.d)。 */
    private val classChattingContext by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
        }
    }

    /** ChattingContext.getTalker()。 */
    private val methodChattingContextGetTalker by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classChattingContext.data.name)
            usingEqStrings("getTalker returns null.")
        }
    }

    /** 当前聊天对象的 wxid (群聊为群 id)。 */
    @Volatile
    private var currentTalker: String? = null

    /** username -> 完整显示名 (会话行绑定时的文本, 渲染级截断下即完整名)。 */
    private val rememberedNames: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > NAME_CACHE_LIMIT
        }
    )

    /** username -> 备注名查询结果缓存, 切换聊天对象时清空。 */
    private val displayNameCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > NAME_CACHE_LIMIT
        }
    )

    /** 会话行代码级截断只记一次日志, 免得滚动时刷屏。 */
    private var rowRestoreLogged = false

    /** 还原失败按文本去重, 免得同一标题反复刷诊断日志。 */
    private var lastFailedRestoreText: String? = null

    /** 操作栏定位失败只打一次日志。 */
    private var noBarLogged = false

    override fun onEnable() {
        noBarLogged = false
        rowRestoreLogged = false
        lastFailedRestoreText = null
        loggedSites.clear()
        WeConversationListViewApi.addListener(this)
        installAddressHook()
        installConversationTitleGuard()
        installChatTitleHook()
        installTalkerCapture()
        WeLogger.i(TAG, "enabled: 会话列表 / 通讯录 / 聊天页标题的备注名完整显示")
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(this)
        synchronized(released) {
            released.forEach { (tv, state) ->
                tv.setSingleLine(state.singleLine)
                tv.maxLines = state.maxLines
                tv.ellipsize = state.ellipsize
                val lp = tv.layoutParams
                if (lp != null && lp.height != state.height) {
                    lp.height = state.height
                    tv.layoutParams = lp
                }
            }
        }
        released.clear()
        restorers.forEach { (tv, watcher) -> tv.removeTextChangedListener(watcher) }
        restorers.clear()
        rowRestorers.forEach { (tv, watcher) -> tv.removeTextChangedListener(watcher) }
        rowRestorers.clear()
        processedRows.clear()
        dumpedRows.clear()
        loggedSites.clear()
        currentTalker = null
        rememberedNames.clear()
        displayNameCache.clear()
        lastFailedRestoreText = null
        noBarLogged = false
    }

    // ---------------------------------------------------------------------------------------------
    // 1. 「微信」标签页: 会话列表行
    // ---------------------------------------------------------------------------------------------

    override fun onBind(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        // 1) 放开单行截断。定位只看控件本身 (tag → 资源 id → 字号最大的控件), 不看文本是否
        //    已绑定 —— 会话行的绑定时机不受我们控制, 文本可能晚于这里才 setText。
        processRow(row, SITE_CONVERSATION, conversation)

        // 2) 代码级截断: 行文本自身已是「头部...尾部」时用备注名还原
        val title = rowTitleView(row) ?: return
        val text = title.text?.toString() ?: return
        val username = usernameOf(conversation)

        if (!text.contains("...")) {
            // 渲染级截断: 行文本即完整显示名, 记住供聊天页标题还原
            if (username != null) rememberName(username, text)
            return
        }
        val full = restoreIfTruncated(text, username?.let { displayNameOf(it) }) ?: return
        title.setText(full)
        if (username != null) rememberName(username, full)
        if (!rowRestoreLogged) {
            rowRestoreLogged = true
            WeLogger.i(TAG, "会话行备注名被代码级截断，已还原: '$text' -> '$full'")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. 「通讯录」标签页: 联系人行
    // ---------------------------------------------------------------------------------------------

    private fun installAddressHook() {
        if (classAddressItemConvert.isPlaceholder) {
            WeLogger.w(TAG, "AddressItemConvert 未定位，通讯录备注名保持原样")
            return
        }
        runCatching {
            classAddressItemConvert.clazz.reflekt()
                .firstMethod {
                    parameters("androidx.recyclerview.widget.RecyclerView", View::class.java)
                }.hookAfter {
                    // 该方法是 convertViewHolder(recyclerView, convertView): 参数名在 8.0.76
                    // 的 dex 里就叫 "convertView", 返回 `po5.s0`(RecyclerView 的 ViewHolder),
                    // 而 args[1] 与返回值的 itemView 是同一个行视图 —— 所以这里两种取法都行,
                    // 拿 itemView 更直观。注意此刻**文本还没 setText**(文本在随后的
                    // AddressItemConvert.h(...) 里才写), 所以定位标题绝不能依赖文字。
                    val item = itemViewOf(result) ?: (args.getOrNull(1) as? View) ?: return@hookAfter
                    // 这里拿不到 item 对象 (方法只收 convertView), 交给行视图自己的 tag:
                    // 微信的 Mvvm ViewHolder 常把 item 挂在 itemView 的 tag 上, 有就直接用,
                    // 没有就走「按前缀查 rcontact」的兜底。
                    processRow(item, SITE_ADDRESS, item.tag)
                }
        }.onFailure {
            WeLogger.w(TAG, "通讯录行 hook 安装失败，备注名保持原样", it)
        }
    }

    /**
     * 会话行标题的「防回退」加固: 标题是微信自绘的 `NoMeasuredTextView`, 它把
     * `setSingleLine / setLines / setMaxLines / setEllipsize` 全部覆写进自己的字段、
     * **不调 super**, 所以只要微信在任何时机(布局 style、复用时的重新绑定、异步刷新)
     * 把它写回单行, 这里就立刻放开一次 —— 与绑定时机的耦合被彻底切断。
     *
     * 类名在 8.0.76 未混淆, 用 **写死的类名** 定位(与 ChattingUIFragment 同款做法),
     * 不新增 dex 委托 —— 避免改动 dex 缓存键导致用户升级后需要重新适配。
     */
    private fun installConversationTitleGuard() {
        val clazz = runCatching { NO_MEASURED_TEXT_VIEW.toClass() }.getOrElse {
            WeLogger.w(TAG, "$NO_MEASURED_TEXT_VIEW 未找到，会话行标题仅靠每行绑定放开")
            return
        }
        var hooked = 0
        for ((methodName, checkArgument) in TITLE_SETTERS) {
            // setSingleLine / setLines 在 TextView 里各有多个重载, 逐个钩才不漏
            val methods = runCatching { clazz.reflekt().methods { name = methodName } }.getOrNull()
                ?: continue
            for (method in methods) {
                runCatching {
                    method.hookAfter {
                        // checkArgument=true: 参数是行数或布尔, 只有「单行」才需要放开
                        // checkArgument=false: 参数是 TruncateAt, 非 null 就是又加了省略号
                        val value = args.getOrNull(0)
                        val turnsSingleLine = if (checkArgument) {
                            value == true || (value is Int && value in 1..MAX_SINGLE_LINE_LINES)
                        } else {
                            value != null
                        }
                        if (turnsSingleLine) guardConversationTitle(thisObject)
                    }
                }.onSuccess { hooked++ }
            }
        }
        WeLogger.i(TAG, "会话行标题加固: 已 hook $hooked 个 $NO_MEASURED_TEXT_VIEW 方法")
    }

    /** 只有会话行标题(tag=nickname_tv)才需要防回退; 其余 NoMeasuredTextView 直接跳过。 */
    private fun guardConversationTitle(target: Any?) {
        val tv = target as? TextView ?: return
        if (tv.tag != NICKNAME_TAG) return
        applyToTitle(SITE_CONVERSATION, tv)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. 好友聊天页: 顶部标题栏
    // ---------------------------------------------------------------------------------------------

    private fun installChatTitleHook() {
        runCatching {
            "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().reflekt()
                .firstMethod { name = "dealContentView" }.hookAfter {
                    // dealContentView 的形态在版本间变过: 可能是 Fragment 方法 (thisObject 是
                    // Fragment)、参数里带内容视图、或 thisObject 本身就是视图, 三种都兼容。
                    val contentView = args.getOrNull(0) as? ViewGroup
                        ?: thisObject as? ViewGroup
                        ?: (thisObject as? Fragment)?.view as? ViewGroup
                    if (contentView == null) {
                        WeLogger.w(TAG, "dealContentView 已触发但取不到内容视图，标题备注名可能保持原样")
                        return@hookAfter
                    }
                    // 独立聊天窗口 (ChattingUI Activity) 的 intent 带 Chat_User
                    captureTalkerFromHost(contentView.context)
                    // 标题栏在 dealContentView 返回后才填完, post 一帧再取
                    contentView.post { releaseChatTitleBar(contentView) }
                    // 标题数据是异步取回的 (会话资料未就绪时先空后填), 再补两次迟到的检查
                    contentView.postDelayed({ releaseChatTitleBar(contentView) }, 500)
                    contentView.postDelayed({ releaseChatTitleBar(contentView) }, 1500)
                }
        }.onFailure {
            WeLogger.w(TAG, "聊天页标题 hook 安装失败，标题备注名保持原样", it)
        }

        // onResume 兜底: 微信在标题数据异步到位后才设置截断文本; 且每次回到聊天页都重新
        // 放开一次 (dealContentView 只在页面创建时走, 旧版靠它单点处理, 标题TextView 被
        // 重建或微信后配置 singleLine 时就漏了)。
        runCatching {
            "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().reflekt()
                .firstMethod { name = "onResume"; superclass() }.hookAfter {
                    val fragment = thisObject as? Fragment ?: return@hookAfter
                    captureTalker(fragment)
                    (fragment.view as? ViewGroup)?.let { releaseChatTitleBar(it) }
                }
        }.onFailure {
            WeLogger.w(TAG, "聊天页 onResume hook 安装失败，标题备注名兜底失效", it)
        }
    }

    /**
     * 全局捕获 ChattingContext.getTalker 的返回值作为当前聊天对象: 标题文本是微信自己
     * setText 的, 那一刻拿不到稳定的 talker; 而 getTalker 在聊天页生命周期里会被反复
     * 调用, 最近一次非空结果就是当前聊天对象。
     */
    private fun installTalkerCapture() {
        if (methodChattingContextGetTalker.isPlaceholder) {
            WeLogger.w(TAG, "ChattingContext.getTalker 未定位，聊天标题还原只能依赖会话列表缓存")
            return
        }
        runCatching {
            methodChattingContextGetTalker.hookAfter {
                (result as? String)?.takeIf { it.isNotEmpty() }?.let { setCurrentTalker(it) }
            }
        }.onFailure {
            WeLogger.w(TAG, "getTalker hook 安装失败，聊天标题还原只能依赖会话列表缓存", it)
        }
    }

    /** 独立聊天窗口 (ChattingUI Activity) 的 intent 带 Chat_User; LauncherUI 托管的聊天页没有。 */
    private fun captureTalkerFromHost(context: android.content.Context?) {
        val talker = (context as? Activity)?.intent?.getStringExtra("Chat_User") ?: return
        if (talker.isNotEmpty()) setCurrentTalker(talker)
    }

    /** 从 Fragment 字段里找 ChattingContext, 调 getTalker 拿当前聊天对象。 */
    private fun captureTalker(fragment: Fragment) {
        if (classChattingContext.isPlaceholder || methodChattingContextGetTalker.isPlaceholder) return
        val context = runCatching {
            fragment.reflekt().fields { superclass = true }.map { it.get() }
                .firstOrNull { it != null && classChattingContext.clazz.isInstance(it) }
        }.getOrNull() ?: return
        val talker = runCatching {
            methodChattingContextGetTalker.method.invoke(context) as? String
        }.getOrNull()
        if (!talker.isNullOrEmpty()) setCurrentTalker(talker)
    }

    private fun setCurrentTalker(talker: String) {
        if (currentTalker != talker) {
            currentTalker = talker
            displayNameCache.clear()
        }
    }

    /**
     * 聊天页标题栏: 放开操作栏内所有 TextView 的单行截断, 并给它们挂上还原器 ——
     * 标题 (备注名) 与下方的状态行 (如「对方正在输入」) 都会被微信按 8+8 字截断。
     */
    private fun releaseChatTitleBar(contentView: ViewGroup) {
        val bar = chatActionBarRoot(contentView) ?: run {
            if (!noBarLogged) {
                noBarLogged = true
                WeLogger.w(TAG, "聊天页操作栏未定位，标题备注名保持原样；${describeChatLayout(contentView)}")
            }
            return
        }
        var processed = 0
        forEachTextView(bar) { tv ->
            if (!released.containsKey(tv)) {
                release(tv)
                val watcher = TitleRestorer(tv, ::restoreFullTitle, ::applyUnwrap)
                restorers[tv] = watcher
                tv.addTextChangedListener(watcher)
                processed++
            }
            // 标题可能在我们挂监听前就已设置, 立即补一次还原
            val text = tv.text?.toString()
            if (!text.isNullOrBlank() && text.contains("...")) {
                restoreFullTitle(text)?.let { tv.setText(it) }
            }
        }
        if (processed > 0) {
            WeLogger.i(TAG, "聊天页操作栏处理 $processed 个 TextView, 操作栏容器=${bar.javaClass.name}")
        }
    }

    /** 定位不到操作栏时的诊断信息: ChattingUILayout 第一层子视图的类名。 */
    private fun describeChatLayout(contentView: ViewGroup): String = runCatching {
        val layout = contentView.findViewWhich<View> {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
        } as? ViewGroup ?: return@runCatching "未找到 ChattingUILayout"
        val names = (0 until layout.childCount).joinToString(", ") { i ->
            layout.getChildAt(i).javaClass.simpleName
        }
        "ChattingUILayout 子视图: [$names]"
    }.getOrElse { "无法遍历聊天页布局: $it" }

    /**
     * 定位聊天页操作栏容器: 优先 ChattingUILayout 的第一个子视图 (与主题功能同款定位,
     * 整个操作栏子树, 含「返回」按钮与标题); 定位不到时从「返回」按钮逐级向上, 取最远的
     * 仍含文字的祖先容器。
     */
    private fun chatActionBarRoot(contentView: ViewGroup): ViewGroup? {
        val layout = contentView.findViewWhich<View> {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
        } as? ViewGroup
        val barFromLayout = layout?.getChildAt(0) as? ViewGroup
        if (barFromLayout != null && hasNonBlankText(barFromLayout)) return barFromLayout

        val back = contentView.findViewWhich<View> { it.contentDescription == "返回" } ?: return barFromLayout
        var parent = back.parent as? ViewGroup
        var best: ViewGroup? = null
        var depth = 0
        while (parent != null && depth < 4) {
            if (hasNonBlankText(parent)) best = parent
            parent = parent.parent as? ViewGroup
            depth++
        }
        return best ?: barFromLayout
    }

    private fun hasNonBlankText(root: View): Boolean {
        var found = false
        forEachTextView(root) { tv -> if (!tv.text.isNullOrBlank()) found = true }
        return found
    }

    // ---------------------------------------------------------------------------------------------
    // 公共
    // ---------------------------------------------------------------------------------------------

    /**
     * 行内「名字控件」的定位, 三级兜底, **全程不要求控件已有文字**:
     *
     *  1. `tag="nickname_tv"` —— 会话行布局 `e45` / `a2v` / `a2w` 写死的 tag;
     *  2. 资源 id `0x7f094c8f` (`id/kbq`) —— 会话行标题与通讯录名字共用;
     *  3. **字号最大的控件** —— 名字 17dp、摘要 14dp、时间 12dp, 字号即可区分。
     *
     * 第 3 级是 v286 补上的: 微信两个列表的绑定时机都不保证文本已就位,
     * 而旧实现要求「有文字」, 结果在这种时机下取到摘要 / 时间甚至 null。
     */
    private fun rowTitleView(root: View): TextView? {
        runCatching { root.findViewWithTag<View>(NICKNAME_TAG) as? TextView }.getOrNull()
            ?.let { return it }
        runCatching { root.findViewById<TextView>(ID_ROW_TITLE) }.getOrNull()
            ?.let { return it }
        return biggestTitle(root)
    }

    /** 行里字号最大、且达到名字字号下限的 TextView (会话行/通讯录的名字都是 17dp)。 */
    private fun biggestTitle(root: View): TextView? {
        val scaled = runCatching { root.resources.displayMetrics.scaledDensity }.getOrDefault(1f)
        var best: TextView? = null
        var bestPx = 0f
        forEachTextView(root) { tv ->
            val px = tv.textSize
            if (px <= 0f || px / scaled < MIN_TITLE_SP) return@forEachTextView
            if (px > bestPx) {
                bestPx = px
                best = tv
            }
        }
        return best
    }

    /**
     * 从 ViewHolder 里取 itemView (字段名固定, 定义在 `androidx.recyclerview.widget.ViewHolder`)。
     * 通讯录的 `convertViewHolder(recyclerView, convertView)` 返回的就是它, 行视图从这儿拿。
     */
    private fun itemViewOf(holder: Any?): View? {
        if (holder == null) return null
        return runCatching {
            holder.reflekt().firstFieldOrNull { name = "itemView"; superclass() }?.get() as? View
        }.getOrNull() ?: runCatching {
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull()
    }

    /**
     * 处理一行: dump 一次诊断日志 → 定位名字控件 → 放开单行截断 → 挂上代码级截断还原器。
     *
     * [item] 是行对应的数据对象 (会话对象 / 联系人对象, 也可能 null), 用来把
     * 「头部...」还原成完整备注名; 取不到时退化为按前缀查库。
     */
    private fun processRow(root: View, site: String, item: Any?) {
        if (!processedRows.containsKey(root)) {
            processedRows[root] = true
            dumpRowTree(root, site)
        }
        val title = rowTitleView(root) ?: run {
            if (loggedSites.add("$site-未定位")) {
                WeLogger.w(TAG, "行里没有找到名字控件 ($site): 根=${root.javaClass.name}")
            }
            return
        }
        applyToTitle(site, title)
        attachRowRestorer(site, title, usernameOf(item))
    }

    /**
     * 一次性行视图诊断: 把行里每个 TextView 的类名 / id / tag / 字号 / 截断状态打出来。
     * 微信的列表布局在版本间换代很频繁, 这一条日志能直接回答「名字控件到底是谁」。
     */
    private fun dumpRowTree(root: View, site: String) {
        val count = dumpedRows[site] ?: 0
        if (count >= MAX_DUMPED_ROWS) return
        dumpedRows[site] = count + 1
        val scaled = runCatching { root.resources.displayMetrics.scaledDensity }.getOrDefault(1f)
        val sb = StringBuilder()
        sb.append("行视图诊断 ($site #${count + 1}): 根=${root.javaClass.name}")
            .append(" 根id=0x").append(Integer.toHexString(root.id))
            .append(" 根tag=").append(root.tag)
            .append(" 根高=").append(describeHeight(root))
        var index = 0
        forEachTextView(root) { tv ->
            sb.append("\n  #").append(index++)
                .append(' ').append(tv.javaClass.name)
                .append(" id=0x").append(Integer.toHexString(tv.id))
                .append(" tag=").append(tv.tag)
                .append(" 字号=").append((tv.textSize / scaled).toInt()).append("sp")
                .append(" maxLines=").append(tv.maxLines)
                .append(" 单行=").append(tv.isSingleLine)
                .append(" ellipsize=").append(tv.ellipsize)
                .append(" 高=").append(describeHeight(tv))
                .append(" 文字=").append(tv.text?.toString()?.take(30))
        }
        WeLogger.i(TAG, sb.toString())
    }

    /**
     * 给行标题挂「代码级截断」还原器: 微信可能在 setText 之前就把名字改成了「头部...」,
     * 这种情况放开单行属性也没用, 必须把文本换回完整名。
     *
     * 挂在标题上而不是每次 bind 检查一次, 是因为绑定时机不受我们控制。
     */
    private fun attachRowRestorer(site: String, tv: TextView, username: String?) {
        if (rowRestorers.containsKey(tv)) return
        val watcher = RowTitleRestorer(
            tv = tv,
            resolve = { text -> resolveRowName(text, username) },
            // 参数 = 是否需要重设文本 (从 TextWatcher 回调进来时不重设, 免得自己触发自己)
            apply = { resetText -> applyToTitle(site, tv, resetText) },
        )
        rowRestorers[tv] = watcher
        runCatching { tv.addTextChangedListener(watcher) }
    }

    /** 会话对象 / 联系人对象 → 用户名 (wxid)。字段名固定, 取不到再试行视图上的 tag。 */
    private fun usernameOf(item: Any?): String? {
        if (item == null) return null
        if (item is View) return null
        return runCatching {
            item.reflekt().firstFieldOrNull { name = "field_username"; superclass() }?.get() as? String
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 行标题的「头部...」/「头部...尾部」还原器。还原来源按优先级:
     * ① 该会话 / 联系人的备注名; ② 会话列表里记住的完整显示名; ③ 按前缀查 rcontact。
     */
    private class RowTitleRestorer(
        private val tv: TextView,
        private val resolve: (String) -> String?,
        private val apply: (resetText: Boolean) -> Unit,
    ) : TextWatcher {
        private var busy = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (busy) return
            val text = s?.toString() ?: return
            if (text.isEmpty()) return
            if (text.contains("...")) {
                val full = resolve(text)
                if (!full.isNullOrEmpty() && full != text) {
                    busy = true
                    try {
                        apply(true)
                        tv.setText(full)
                    } finally {
                        busy = false
                    }
                    return
                }
            }
            // 文本比定位时晚到的情形 (Mvvm 异步绑定): 文字一到就再放开一次 (不重设文本, 防自触发)。
            apply(false)
        }
    }

    /** 把行标题里的「头部...」还原成完整名字, 找不到就返回 null (保持原样)。 */
    private fun resolveRowName(truncated: String, username: String?): String? {
        val parsed = parseTruncated(truncated) ?: return null
        val (head, tail) = parsed
        if (!username.isNullOrEmpty()) {
            displayNameOf(username)?.let { if (fullMatches(it, truncated, head, tail)) return it }
        }
        synchronized(rememberedNames) {
            rememberedNames.values.toList().firstOrNull { fullMatches(it, truncated, head, tail) }
        }?.let { return it }
        return lookupNameByPrefix(head, tail, truncated)
    }

    /** 完整名比截断串长、且首(尾)吻合才算命中, 避免误伤正常含「...」的文本。 */
    private fun fullMatches(full: String, truncated: String, head: String, tail: String?): Boolean =
        full.length > truncated.length && full.startsWith(head) &&
            (tail.isNullOrEmpty() || full.endsWith(tail))

    /** 解析「头部...尾部」/「头部...」; 省略号在开头或整串就是省略号时返回 null。 */
    private fun parseTruncated(text: String): Pair<String, String?>? {
        val idx = text.indexOf("...")
        if (idx <= 0) return null
        val head = text.substring(0, idx)
        if (head.isEmpty()) return null
        val tail = text.substring(idx + 3)
        return head to tail.ifEmpty { null }
    }

    /** 兜底: 拿「头部」当前缀去 rcontact / chatroom 里找完整名字。 */
    private fun lookupNameByPrefix(head: String, tail: String?, truncated: String): String? {
        if (!WeDatabaseApi.isReady) return null
        val escaped = head.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val like = "$escaped%"
        val names = runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT conRemark, nickname FROM rcontact " +
                    "WHERE conRemark LIKE ? ESCAPE '\\' OR nickname LIKE ? ESCAPE '\\' LIMIT 50",
                arrayOf<Any>(like, like)
            ).use { cursor ->
                buildList<String> {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                        cursor.getString(1)?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
            }
        }.getOrNull().orEmpty()
        val hit = names.firstOrNull { fullMatches(it, truncated, head, tail) } ?: return null
        if (loggedSites.add("$SITE_PREFIX_LOOKUP")) {
            WeLogger.i(TAG, "行标题按前缀找到完整名: '$truncated' -> '$hit' (候选 ${names.size} 个)")
        }
        return hit
    }

    /**
     * 真正放开一处名字控件, 并打一条一次性诊断日志 (每个入口只打一条, 滚动不刷屏)。
     *
     * `unwrapping` 是重入闸: 放开动作会调用微信覆写过的 setter, 而 setter 又被我们
     * 钩住做防回退, 没有它就会自己触发自己。
     */
    private fun applyToTitle(site: String, tv: TextView, resetText: Boolean = true) {
        if (unwrapping.get() == true) return
        // 名字控件不是 NoMeasuredTextView 时 (微信换了布局), 上面那套守卫钩不到它,
        // 得补一套挂在普通 TextView 上的守卫 —— 只在真的遇到时才挂, 避免全局开销。
        if (tv.javaClass.name != NO_MEASURED_TEXT_VIEW) ensurePlainTextViewGuard()
        unwrapping.set(true)
        try {
            recordOriginal(tv)
            applyUnwrap(tv)
            // 微信把 Layout 缓存在自己的字段里, 已经量过的行不会自动按新属性重排;
            // 重设一次同样的文本 + requestLayout 把它顶回测量流程 (首帧时文本还是空的, 跳过)。
            // 从 TextWatcher 里回调进来时不再重设文本, 免得自己触发自己。
            val text = if (resetText) tv.text else null
            if (!text.isNullOrBlank()) runCatching { tv.setText(text) }
            runCatching { tv.requestLayout() }
        } finally {
            unwrapping.set(false)
        }
        if (loggedSites.add(site)) {
            WeLogger.i(
                TAG,
                "行标题已放开 ($site): ${tv.javaClass.name} tag=${tv.tag} " +
                    "字号=${tv.textSize} maxLines=${tv.maxLines} ellipsize=${tv.ellipsize} " +
                    "高=${describeHeight(tv)}"
            )
        }
    }

    /**
     * 普通 `TextView` 的「防回退」守卫: 微信把 singleLine / maxLines / ellipsize 写回来的
     * 时候把它们再放开一次。只对**本功能动过**的控件生效 ([released] 里有记录),
     * 不会干扰微信其它 TextView。
     */
    private fun ensurePlainTextViewGuard() {
        if (plainTitleGuardInstalled) return
        plainTitleGuardInstalled = true
        val clazz = runCatching { "android.widget.TextView".toClass() }.getOrNull() ?: run {
            plainTitleGuardInstalled = false
            return
        }
        var hooked = 0
        for ((methodName, checkArgument) in TITLE_SETTERS) {
            val methods = runCatching { clazz.reflekt().methods { name = methodName } }.getOrNull() ?: continue
            for (method in methods) {
                runCatching {
                    method.hookAfter {
                        val tv = thisObject as? TextView ?: return@hookAfter
                        if (!released.containsKey(tv)) return@hookAfter
                        val value = args.getOrNull(0)
                        val turnsSingleLine = if (checkArgument) {
                            value == true || (value is Int && value in 1..MAX_SINGLE_LINE_LINES)
                        } else {
                            value != null
                        }
                        if (turnsSingleLine) applyToTitle(SITE_PLAIN_GUARD, tv)
                    }
                }.onSuccess { hooked++ }
            }
        }
        WeLogger.i(TAG, "普通 TextView 防回退守卫: 已 hook $hooked 个方法")
    }

    private fun describeHeight(view: View): String = when (val h = view.layoutParams?.height) {
        null -> "无"
        ViewGroup.LayoutParams.MATCH_PARENT -> "match_parent"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "wrap_content"
        else -> "${h}px"
    }

    private fun forEachTextView(root: View, action: (TextView) -> Unit) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is TextView) action(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
        }
    }

    /** 放开单个 TextView 的单行截断；记下原状态，关闭功能时还原。 */
    private fun release(tv: TextView) {
        recordOriginal(tv)
        applyUnwrap(tv)
    }

    /**
     * 记下放开前的原始状态 (只记第一次), 关闭功能时按它还原。
     *
     * `NoMeasuredTextView` 把单行属性存进自己的字段且不调 super, 所以标准 getter 读不到
     * 真实值 —— 但它覆写的 `getEllipsize()` 读的就是布局里设的 ellipsize (会话行 style
     * 里是 end), 用它判断「原来是不是单行」才准。不这样处理的话, 关掉开关后会话行会
     * 停在多行状态 (还原成 singleLine=false)。
     */
    private fun recordOriginal(tv: TextView) {
        synchronized(released) {
            if (released.containsKey(tv)) return
            val lp = tv.layoutParams
            val noMeasured = tv.javaClass.name == NO_MEASURED_TEXT_VIEW
            val wasSingleLine = if (noMeasured) tv.ellipsize != null else tv.isSingleLine
            released[tv] = TruncationState(
                maxLines = if (noMeasured && wasSingleLine) 1 else tv.maxLines,
                singleLine = wasSingleLine,
                ellipsize = tv.ellipsize,
                height = lp?.height ?: 0,
            )
        }
    }

    private fun applyUnwrap(tv: TextView) {
        // 顺序有讲究: setSingleLine(false) 在 NoMeasuredTextView 里被实现成
        // setMaxLines(0x7fffffff) → 写它的行数字段 .r, 高度 (getDesiredHeight) 才会跟着放开。
        tv.setSingleLine(false)
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        // 高度被微信写死的，改成 wrap 才能把第二行显示出来；MATCH_PARENT/WRAP_CONTENT 不动。
        val lp = tv.layoutParams
        if (lp != null && lp.height > 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            tv.layoutParams = lp
        }
    }

    private fun rememberName(username: String, name: String) {
        rememberedNames[username] = name
    }

    /** 备注名优先的显示名 (rcontact.conRemark → nickname)，带缓存。 */
    private fun displayNameOf(username: String): String? {
        displayNameCache[username]?.let { return it }
        val name = runCatching { WeDatabaseApi.getDisplayName(username) }.getOrNull() ?: return null
        displayNameCache[username] = name
        return name
    }

    /** 解析「头部...尾部」; 省略号不在中部或头尾为空时返回 null。 */
    private fun parseMiddleTruncated(text: String): Pair<String, String>? {
        val idx = text.indexOf("...")
        if (idx <= 0 || idx + 3 >= text.length) return null
        val head = text.substring(0, idx)
        val tail = text.substring(idx + 3)
        return if (head.isNotEmpty() && tail.isNotEmpty()) head to tail else null
    }

    /**
     * 代码级截断还原 (会话行用): [truncated] 形如「头部...尾部」时, 用 [fullName] 验证并
     * 还原。必须中部省略、头尾非空、完整名更长且首尾吻合, 避免误伤含「...」的正常文本。
     */
    private fun restoreIfTruncated(truncated: String, fullName: String?): String? {
        if (fullName.isNullOrEmpty()) return null
        val (head, tail) = parseMiddleTruncated(truncated) ?: return null
        if (fullName.length <= truncated.length) return null
        if (!fullName.startsWith(head) || !fullName.endsWith(tail)) return null
        return fullName
    }

    /**
     * 聊天页标题的代码级截断还原: 文本形如「头部...尾部」时, 依次尝试
     * ① 当前聊天对象的备注名 (查库) ② 会话列表记住的完整显示名, 首尾吻合才采用。
     */
    private fun restoreFullTitle(truncated: String): String? {
        val (head, tail) = parseMiddleTruncated(truncated) ?: return null
        val talker = currentTalker
        if (!talker.isNullOrEmpty()) {
            displayNameOf(talker)?.let { full ->
                if (full.length > truncated.length && full.startsWith(head) && full.endsWith(tail)) {
                    WeLogger.i(TAG, "聊天标题还原: '$truncated' -> '$full'")
                    return full
                }
            }
        }
        val remembered = synchronized(rememberedNames) {
            rememberedNames.values.toList()
                .firstOrNull { it.length > truncated.length && it.startsWith(head) && it.endsWith(tail) }
        }
        if (remembered != null) {
            WeLogger.i(TAG, "聊天标题还原(会话列表缓存): '$truncated' -> '$remembered'")
            return remembered
        }
        if (truncated != lastFailedRestoreText) {
            lastFailedRestoreText = truncated
            WeLogger.i(
                TAG,
                "聊天标题含省略号但未找到完整备注名: '$truncated', talker=$talker, 缓存 ${rememberedNames.size} 条"
            )
        }
        return null
    }

    /**
     * 聊天页标题的代码级截断还原器: 微信在 setText 之前已把备注名改成「头部...尾部」,
     * 文本监听发现省略号时用备注名还原, 并放开单行截断让完整名换行显示。
     */
    private class TitleRestorer(
        private val tv: TextView,
        private val restore: (String) -> String?,
        private val unwrap: (TextView) -> Unit,
    ) : TextWatcher {
        private var lastRestored: String? = null

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            val text = s?.toString() ?: return
            if (text == lastRestored) return
            val full = restore(text) ?: return
            lastRestored = full
            tv.setText(full)
            unwrap(tv)
        }
    }
}
