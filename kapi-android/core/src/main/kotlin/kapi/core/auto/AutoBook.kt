package kapi.core.auto

import java.text.Normalizer
import java.util.Locale

/**
 * 自动记账的纯逻辑：指纹归一化、去重窗口、分流决策、跨源合并判据。
 *
 * 全部无 Android 依赖，全部可 JVM 单测。这个模块的正确性直接等于用户的
 * 账本正确性，所以**每一个会丢数的判据都必须有一组测试钉死**。
 *
 * ## 最重要的一条原则
 *
 * > **宁重复，不丢失。**
 *
 * 重复的代价是账本里多出一行，用户打开就看见了，删掉即可。
 * 丢失的代价是账本里少一行，用户**看不见**，只会在月底对不上账时才发现，
 * 然后再也不信这个 App。
 *
 * 所以去重只在**证据充分**时执行：同一来源 + 归一化后完全相同 + 同一时间窗。
 * 跨来源的相同金额**绝不自动丢弃**，而是并成一条草稿、在界面上明说"可能是
 * 同一笔"，让用户一键合并或一键拆开——看得见的合并不是丢失。
 */
object AutoBook {

    /** 采集渠道。决定指纹的域，也决定用户在哪句话里被告知数据来源。 */
    enum class CaptureChannel(val label: String) {
        A11Y("无障碍读屏"),
        NOTIFY("通知栏");

        companion object {
            fun from(s: String?): CaptureChannel =
                entries.firstOrNull { it.name == s } ?: NOTIFY
        }
    }

    /** 解析方式。决定一个关键分流规则，见 [route]。 */
    enum class ParserKind { RULE, LLM }

    sealed interface AutoOutcome {
        /** 直接入账。 */
        data class Post(val reason: String) : AutoOutcome

        /** 进待确认队列。[reason] 会显示在界面上，用来解释"为什么还要我确认"。 */
        data class Draft(val reason: String) : AutoOutcome
    }

    // ---------------------------------------------------------------- 指纹

    /**
     * 归一化文本，让**同一笔支付的第 N 次重绘**得到同一个指纹。
     *
     * 微信支付成功页上"支付成功"四个字会随动画重绘十几次，每次重绘都是一个
     * `TYPE_WINDOW_CONTENT_CHANGED` 事件。不归一化的话，一笔 23.50 会在账本里
     * 变成 23.50 × 50，而界面上看起来只是"重复了几行"。
     *
     * 抹掉三类东西：
     *  - 空白与全角半角差异（`%` 和 `％`、空格和换行）
     *  - 8 位以上的连续数字（订单号、交易流水号、时间戳——它们每次可能不同）
     *  - "余额 xxx" 片段（余额会随另一笔支付变化，留在指纹里会让同一笔的
     *    两次读取得到不同指纹，去重直接失效）
     *
     * **保留金额**。金额是唯一确定的东西，是这个指纹的锚。
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = BALANCE.replace(s, "")
        s = LONG_DIGITS.replace(s, "#")
        s = WHITESPACE.replace(s, "")
        return s.lowercase(Locale.ROOT)
    }

    /**
     * 同一来源下"是否就是同一个事件"的指纹。
     *
     * 域里放了 [channel]：A11Y 和 NOTIFY 看到的是两段不同的文本，它们的指纹
     * 必须不同，否则微信支付会同时被两条通路捕获却只留一条，用户去微信里
     * 反复滑几下会让账本凭空多出好几笔。
     *
     * 跨源的重复由 [isSamePaymentEvent] 在草稿层处理——那里有用户在场。
     */
    fun exactFingerprint(channel: CaptureChannel, rawText: String?): String {
        val body = normalize(rawText)
        // 归一化后什么都不剩，说明这段文本不含稳定信息，去重只能靠兜底：
        // 空指纹让所有空文本共用一个桶，等价于"这个来源目前没读到东西"。
        val key = if (body.isEmpty()) "" else channel.name + "|" + body
        return fnv1a64(key)
    }

    /**
     * 归一化给**语义判据**用（[PaymentGate]），与 [normalize] 有两处刻意的差别。
     *
     * ## 差别 1：保留空白
     *
     * [normalize] 把空白全删掉，这对算指纹是好事（重绘时缩进会变）。但判据要**数钱**，
     * 而删掉空白会把两笔相邻的金额粘成一个：
     * ```
     * 星巴克  -23.50
     * 2026-09-27 支付  地铁  -8.00
     * ```
     * 变成 `星巴克-23.502026-09-27支付地铁-8.00` 之后，`23.50` 后面直接跟 `2026`，
     * 整段读起来是一个 `23.502026` 的小数。任何"独立两位小数"的判据都会失效——
     * 账单列表页于是被判成"没有金额"而**放行**，一屏历史流水生成一条假账。
     *
     * ## 差别 2：不抹长数字
     *
     * 抹长数字是为了让重绘稳定，对判据没有好处，还会把 `4500000486202609301360845789`
     * 这种交易号变成 `#`，让"这一屏有没有交易号"这种问题永远问不出正确答案。
     *
     * 保留空白就够了：把连续空白压成**一个**空格，语义上等价于空白，但数字不会被粘住。
     */
    fun normalizeForJudging(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        s = BALANCE.replace(s, " ")
        s = WHITESPACE_RUN.replace(s, " ")
        return s.lowercase(Locale.ROOT).trim()
    }

    /** 64 位 FNV-1a，输出 16 位小写十六进制。够用且比 MessageDigest 便宜得多——
     *  无障碍每个事件都要算一次，一秒钟能调十几次。 */
    fun fnv1a64(input: String): String {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        for (i in input.indices) {
            h = h xor input[i].code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    // ------------------------------------------------------------ 时间窗

    /**
     * 生效的去重窗口，带下限保护。
     *
     * 下限 20 秒：支付页 → 返回列表的最短路径都在这个量级，窗口比这更短就
     * 一定漏。用户可以在设置里调小，但调到 20 秒以下没有任何实际收益，只有
     * 漏记的风险。
     */
    const val MIN_WINDOW_MS = 20_000L

    /** 3 分钟。理由见 [AutoBookStore] 的类注释。 */
    const val DEFAULT_WINDOW_MS = 3 * 60_000L

    fun effectiveWindowMs(configured: Long): Long =
        if (configured < MIN_WINDOW_MS) MIN_WINDOW_MS else configured

    /**
     * 平台交易号的去重窗口：**90 天**，且**不跟用户设置走**。
     *
     * 文本指纹用 3 分钟，是因为它只是「这屏刚被读到过吧」的弱证据。交易号不是——
     * 微信支付交易单号全局唯一，**同一个号只可能是一笔**。用户三个月前点开过一笔账单，
     * 今天再点开，必须判成重复；反过来，一个号第二次出现就是新数据，那不可能发生。
     *
     * 给 90 天而不是永久，有两个理由：
     *  - 存储是 SharedPreferences 里的一个 StringSet，永久会无界增长；
     *  - 交易号理论上有极小概率被平台复用（跨年度结转等），留个尾巴比写死更稳。
     *
     * 一年之内没有任何真实场景会让用户重看同一笔账单，90 天已经宽得离谱。
     */
    const val HARD_ID_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * 交易号是否算重复。与 [isDuplicate] 的区别是窗口不受用户设置影响。
     *
     * 语义比 [isDuplicate] 更强：返回 true 意味着「**确定**是同一笔」，不是「大概是」。
     * 调用方可以直接丢弃，不需要像近重复那样并成草稿让用户看。
     */
    fun isDuplicateId(lastSeenAt: Long, now: Long): Boolean {
        if (lastSeenAt <= 0L) return false
        if (now < lastSeenAt) return true
        return now - lastSeenAt < HARD_ID_WINDOW_MS
    }

    /**
     * 同一指纹在 [now] 时刻是否算重复。
     *
     * @param lastSeenAt 上一次处理这个指纹的时刻，0 表示从没见过。
     * @return true = 重复，调用方**必须丢弃**。
     */
    fun isDuplicate(lastSeenAt: Long, now: Long, windowMs: Long): Boolean {
        if (lastSeenAt <= 0L) return false
        // 墙钟被往回拨时 now < lastSeenAt，这里判成"刚发生过"而不是崩掉或判成
        // 新的——宁可少记一次，也不要因为时钟乱跳就放行一次重复。
        if (now < lastSeenAt) return true
        return now - lastSeenAt < effectiveWindowMs(windowMs)
    }

    // -------------------------------------------------------------- 分流

    /**
     * 决定这一笔是直接入账还是进待确认。
     *
     * 三条规则，缺一不可：
     *  1. **规则解析永不入账。** `LocalTextParser` 的 0.55 不是概率，是"命中了几
     *     个金额候选"的粗糙计数，拿它和用户设的阈值比是概念错误。规则解析的错
     *     没法靠阈值消掉，只能让人看过。
     *  2. 置信度必须是有限正数。`NaN` 会让所有比较都为 false 从而静默放行，
     *     而 `NaN` 恰恰是最不该放行的输入。
     *  3. 用户没开开关就一律进草稿。默认必须是确认。
     */
    fun route(
        parser: ParserKind,
        confidence: Double,
        autoPostEnabled: Boolean,
        threshold: Double,
    ): AutoOutcome {
        if (parser == ParserKind.RULE) {
            return AutoOutcome.Draft("规则解析的判断不能保证准，确认一下再记")
        }
        if (!autoPostEnabled) {
            return AutoOutcome.Draft("设置里没开「高置信度直接入账」")
        }
        if (confidence.isNaN() || confidence.isInfinite() || confidence <= 0.0) {
            return AutoOutcome.Draft("这次没读出可信的置信度")
        }
        if (confidence < threshold) {
            return AutoOutcome.Draft(
                "置信度 ${"%.2f".format(Locale.ROOT, confidence)} 低于阈值 " +
                    "${"%.2f".format(Locale.ROOT, threshold)}"
            )
        }
        return AutoOutcome.Post(
            "置信度 ${"%.2f".format(Locale.ROOT, confidence)} ≥ 阈值 ${"%.2f".format(Locale.ROOT, threshold)}"
        )
    }

    // ---------------------------------------------------------- 跨源合并

    /**
     * 跨来源的"可能是同一笔"判据。
     *
     * 用户用微信付了一笔，通知栏发一条通知（NOTIFY），无障碍同时读到了支付成功页
     * （A11Y）。两段文本不同 → 指纹不同 → 会生成两条草稿。两条都记就是**重复
     * 记账**，账凭空翻倍。
     *
     * 这里判成"疑似同一笔"，让草稿层把它们并成一条，并在界面上明说。用户可以
     * 一键确认合并，也可以一键拆成两笔——**用户在场就不算丢失**。
     *
     * 120 秒：跨 App 完成两笔同额支付不可能这么快。同一来源之间的重复不走这里，
     * 那由 [isDuplicate] 负责，窗口更长。
     */
    const val CROSS_SOURCE_WINDOW_MS = 120_000L

    /**
     * 同来源"疑似同一笔"的时间窗。
     *
     * ## 为什么同源也需要合并
     *
     * 精确指纹是按**整段文本**算的，而读屏读到的是**当前屏幕**。同一笔支付的结果页
     * 会被读到不止一次：页面有动画、有加载状态、有网络往返，节点集随时可能多一个
     * 少一个。2026-09-30 真机上就见过同一条转账成功页先被完整读到（8 个节点）、
     * 稍后又读到变体，指纹不同 → 两条草稿。
     *
     * 更根本的是：支付 App 的同一笔支付会横跨**多个屏**（确认页 → 结果页），
     * 那两屏的文字本来就不同，指纹必然不同。确认页那一条由
     * [PaymentGate] 的"待付"判据治掉；剩下的多屏变体在这里兜底。
     *
     * ## 为什么是 20 秒
     *
     * 一次支付从按"确认"到结果页出来，通常几秒内。两次**独立的**同额同向消费
     * 不可能落在 20 秒内——真要发生（比如支付页连点两次），
     * 把它们合成一条恰恰是用户想要的：那是**重复扣款**。
     *
     * 20 秒远比跨源的 120 秒短，因为同源不需要等另一条通路的证据。
     *
     * 代价是明确的：万一真有两笔同额同向的消费落在 20 秒内，用户会看到一条
     * 而不是两条。草稿是**要用户点确认**的，而且两段原文都会作为证据存下来，
     * 用户看得见、也改得回来。自动记账里"多记"比"少记"更伤——
     * 多记会让用户觉得账在乱记，然后关掉功能。
     */
    const val SAME_CHANNEL_WINDOW_MS = 20_000L

    /**
     * 同来源的"疑似同一笔"判据。与 [isSamePaymentEvent] 的区别只有一条：
     * 那条要求两边来自**不同**通路（跨源合并），这条要求来自**同一**通路。
     *
     * 名字里都带"疑似"：命中不代表一定是同一笔，只是合成一条草稿、
     * 把两段原文都留成证据，让用户在场时自己定。
     */
    fun isSameChannelNearMiss(
        amountMinorA: Long,
        directionA: String,
        atA: Long,
        amountMinorB: Long,
        directionB: String,
        atB: Long,
    ): Boolean {
        if (amountMinorA != amountMinorB) return false
        if (!directionA.equals(directionB, ignoreCase = true)) return false
        return kotlin.math.abs(atA - atB) <= SAME_CHANNEL_WINDOW_MS
    }

    fun isSamePaymentEvent(
        amountMinorA: Long,
        directionA: String,
        atA: Long,
        channelA: CaptureChannel,
        amountMinorB: Long,
        directionB: String,
        atB: Long,
        channelB: CaptureChannel,
    ): Boolean {
        if (channelA == channelB) return false // 同源的重复不是合并，是丢弃
        if (amountMinorA != amountMinorB) return false
        if (!directionA.equals(directionB, ignoreCase = true)) return false
        val delta = kotlin.math.abs(atA - atB)
        return delta <= CROSS_SOURCE_WINDOW_MS
    }

    // ---------------------------------------------------------------- 杂项

    private val WHITESPACE = Regex("""\s+""")

    /** 连续空白，压成单个空格。给 [normalizeForJudging] 用，不删。 */
    private val WHITESPACE_RUN = Regex("""\s+""")

    private val LONG_DIGITS = Regex("""\d{8,}""")

    /**
     * 抹掉"钱包¥1234.56"这类片段。
     *
     * 余额是**跨事件**的量：用户付完 A 再付 B，余额变了，于是同一条支付成功页
     * 在两次读取时指纹不同，去重直接失效，一次支付记成一堆。
     *
     * 词表必须和 [PaymentShape.FIELD_LABELS] 的「账户状态」那一组保持一致。
     * 2026-09-30 23:12 实测：微信钱包页那一屏的节点是 `钱包¥1008.30`，
     * 原来的表只有「余额」，于是它**没被抹掉**，同一屏两次读取的指纹
     * 还会随余额变化而变化——两个 bug 叠在一起。
     */
    private val BALANCE = Regex(
        """(?:账户|卡内|当前|剩余|可用)?余额[^0-9¥￥]{0,4}[¥￥]?\s*\d[\d.,]*""" +
            """|(?:钱包|零钱|零钱通|余额宝|总资产|资产总额)[^0-9¥￥]{0,4}[¥￥]?\s*\d[\d.,]*""" +
            """|(?:信用额度|可用额度|授信额度|待还|欠款)[^0-9¥￥]{0,4}[¥￥]?\s*\d[\d.,]*"""
    )
}
