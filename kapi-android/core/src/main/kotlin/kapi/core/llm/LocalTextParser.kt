package kapi.core.llm

import java.util.Calendar
import java.util.Locale

/**
 * 本地规则解析：完全离线，不消耗任何 token，不依赖任何 API。
 *
 * 这不是"AI 的降级版"，它是**独立的一条自动记账通路**，输入是**文本**而不是图片：
 *  - 通知栏文本（微信/支付宝/银行 App 的付款通知）
 *  - 剪贴板
 *  - 系统分享进来的文字
 *
 * 图片识别没有离线降级——没有模型就读不出图，那条路只能退回手动录入。这是
 * 事实，不该假装能绕过。
 *
 * 置信度**刻意压得很低**（上限 0.55）。规则匹配经常差一点点：把订单号当金额、
 * 把优惠券面额当实付、把退款当成支出。这些错在账本里是实打实的误导，所以
 * UI 应当**总是**要求用户确认，而模型识别在高置信度时才可以免确认。
 */
object LocalTextParser {

    /** 金额候选。刻意宽松，宁可多给候选让用户选，也不要给错一个。 */
    private val AMOUNT_PATTERNS = listOf(
        // 带货币符号或单位，最可靠
        Regex("""[¥￥]\s*(\d+(?:[.,]\d{1,2})?)"""),
        Regex("""(\d+(?:\.\d{1,2})?)\s*元"""),
        // 显式字段名
        Regex("""(?:实付|实收|付款金额|支付金额|交易金额|消费金额|金额|合计|总计|订单金额)\s*[:：]?\s*(\d+(?:[.,]\d{1,2})?)"""),
        // 英文
        Regex("""(?i)(?:paid|total|amount)\s*[:：]?\s*(\d+(?:\.\d{1,2})?)"""),
        // 兜底：裸金额。微信账单详情页写的是 `-80.00`，既无 ¥ 也无元，只有这一条认它。
        // 两侧环视防的是把 `8.0.55`（微信版本号）、`1.5.3` 啃掉一段当成 8.00 / 1.50。
        // 必须和 [kapi.core.auto.PaymentGate] / [kapi.core.auto.PaymentShape] 的
        // AMOUNT 保持同一套环视，否则门和解析器的口径就分叉了。
        Regex("""((?<![\d.])\d{1,8}\.\d{2}(?![\d.]))"""),
    )

    /**
     * 收入信号。
     *
     * ## 「收款」为什么不在里面
     *
     * 它几乎只出现在「**收款方**」这种**指代对方**的字段名里。2026-09-30 真机实测，
     * 支付宝转账成功页整屏文字是：
     * ```
     * 回首页
     * 完成
     * 中国银行储蓄卡(0416)
     * 付款方式
     * 啦啦啦(朱娜娜)
     * 收款方          <- 指对方，用户是付款人
     * 转账成功￥1.00
     * ```
     * 这笔是**用户转出去的钱**，是支出。而"收款"命中 [INCOME_HINTS]，
     * 于是它被记成了 1.00 元收入。
     *
     * 方向记反的代价和漏记不是一个量级：漏记用户会自己补，方向反了整本账都是歪的，
     * 而用户未必看得出"我明明是付钱怎么变成收入了"。所以宁可让每一笔都进草稿
     * 让用户点一下，也不能在这里猜错。
     *
     * 真正表示"钱进来了"的信号只有两类：钱到了谁的口袋（收款方式/付款方——那是我），
     * 或者钱退回给我（退款/退回/到账）。
     */
    private val INCOME_HINTS = listOf(
        "退款", "退回", "入账", "已到账", "到账", "返现", "红包", "收入",
        "向你转账", "向你付款", "收款方式", "付款方",
        "refund", "received",
    )

    /**
     * 支出信号。
     *
     * 「付款方式」和「收款方」是一对：**我用的是哪张卡**、**钱给了谁**。
     * 它们出现在同一屏上，就说明用户是付钱的那一方。
     * 收到转账时页面写的是反过来的一对——「付款方」（对方）和「收款方式」（我），
     * 那两个词在 [INCOME_HINTS] 里。
     */
    private val EXPENSE_HINTS = listOf(
        "付款", "支付", "消费", "扣款", "支出", "已支付",
        "付款方式", "收款方", "转出", "已转账",
        "paid", "payment", "消费成功",
    )

    data class LocalParseResult(
        val amountsMinor: List<Long>,
        val direction: Direction,
        val merchant: String?,
        val occurredAt: Long,
        val confidence: Double,
    ) {
        val best: TransactionDraft?
            get() = amountsMinor.firstOrNull()?.let {
                TransactionDraft(
                    amountMinor = it,
                    direction = direction,
                    occurredAt = occurredAt,
                    merchant = merchant,
                    confidence = confidence,
                )
            }
    }

    /**
     * @param now 注入的当前时间，便于测试确定性。
     */
    /**
     * 判方向：**离金额最近的那个词**说了算，而不是整屏第一个命中的词。
     *
     * ## 为什么不能整屏扫 + 收入优先
     *
     * 2026-09-30 23:14 真机实测，支付宝一屏 38 个节点里这一行是：
     * ```
     * 支出0.1元
     * ```
     * 方向本该是支出，判出来是**收入**。原因是老写法
     * `INCOME_HINTS.any { ... } -> INCOME` 排在前面：只要整屏**任何一个**节点
     * 出现过「到账」「退款」「红包」这类词（账单页上很可能有），收入就先赢了，
     * 哪怕「支出」两个字就贴在金额前面。
     *
     * 方向记反的代价和漏记不是一个量级：漏记用户会自己补，方向反了整本账都是歪的，
     * 而用户未必看得出"我明明是付钱怎么变成收入了"。
     *
     * ## 判据
     *
     * 1. 先把 `付款方式` / `收款方式` 这组复合词剥掉（见 [parse]）。
     * 2. 找金额在文本里的位置。
     * 3. 两侧的方向词按**字符距离**排序，最近的赢。
     * 4. 距离相同（正前方 vs 正后方）→ 判支出。钱的默认归属是"我付出去了"，
     *    这也是老代码在两个都没命中时的兜底，保持一致。
     * 5. 全文根本没有方向词 → 判支出，**但置信度要压低**（见 [parse]）。
     */
    private fun decideDirection(
        t: String,
        amountStart: Int,
        amountEnd: Int,
    ): Direction {
        val center = (amountStart + amountEnd) / 2

        fun nearest(hints: List<String>): Int? {
            var best: Int? = null
            for (hint in hints) {
                var from = 0
                while (true) {
                    val i = t.indexOf(hint, from)
                    if (i < 0) break
                    val d = kotlin.math.abs(i + hint.length / 2 - center)
                    if (best == null || d < best) best = d
                    from = i + 1
                }
            }
            return best
        }

        val income = nearest(INCOME_HINTS)
        val expense = nearest(EXPENSE_HINTS)
        return when {
            income == null && expense == null -> Direction.EXPENSE
            income == null -> Direction.EXPENSE
            expense == null -> Direction.INCOME
            expense <= income -> Direction.EXPENSE
            else -> Direction.INCOME
        }
    }

    /**
     * **账户状态**词。出现时，附近的数字是"账户里有多少钱"，不是"花了多少钱"。
     *
     * 与 [kapi.core.auto.PaymentShape] 的 ACCOUNT_LABELS 同一批词，两边口径必须一致。
     */
    private val ACCOUNT_STATE = listOf(
        "余额", "钱包", "零钱通", "零钱", "余额宝", "资产", "总额", "可用",
        "信用额度", "额度", "授信", "待还", "欠款",
    )

    /**
     * **促销**词。出现在运营位/banner 上，带的数字是门槛、面额、期数，不是消费。
     *
     * 2026-09-30 23:15 支付宝首页实测那一行是 `闲鱼100元以下免密支付`，
     * 「100元」是免密门槛。老解析器取候选时它排在「支出55元」前面，
     * 于是这一屏记成了一笔 100 元支出——而用户当天可能在那一栏里一分钱没花。
     */
    private val PROMO = listOf(
        "免密支付", "免密", "立减", "满减", "领券", "券后", "限时", "秒杀",
        "拼团", "首单", "新人专享", "最高可省", "随机立减", "开卡", "抽红包",
    )

    /**
     * **交易动词**。这一行明确在说"发生了一笔收支"，可以推翻上面两组的否定。
     *
     * 只收有把握的。「支付」这种裸词不收——`免密支付` 里就有它，收了 promo
     * 那条就永远不成立。支付方式那种写法单列为 `支付方式` / `付款方式`。
     */
    private val TXN_WORDS = listOf(
        "实付", "实收", "支付成功", "付款成功", "已支付", "支付方式", "付款方式",
        "消费", "支出", "收入", "退款", "交易成功", "交易", "转账", "转出",
        "扣款", "订单",
    )

    /**
     * 这一行上的金额能不能当消费。
     *
     * 判据是**"否定 + 豁免"**：先看这行是不是在说账户状态或促销，
     * 是的话再看它有没有同时说"这是一笔交易"。两个都占，才算数。
     *
     * ```
     * 钱包¥1008.30            账户状态，无交易动词 → 不可信（余额不是消费）
     * 闲鱼100元以下免密支付      促销位，无交易动词 → 不可信（门槛不是消费）
     * 券后实付 ¥88.00          促销词但有"实付" → 可信
     * 支出55元                 没有否定词         → 可信
     * 消费支出23.50元            同上               → 可信
     * 零钱支付 ¥0.01           账户状态 + 支付方式  → 可信（豁免命中）
     * ```
     *
     * 只按行判、不按整屏判，是因为读屏的节点边界本身就不可靠——同一屏里
     * 「零钱」是导航项、「零钱¥0.10」是余额项，整屏扫会互相污染。
     */
    private fun trustworthyLine(line: String): Boolean {
        val negated = ACCOUNT_STATE.any { line.contains(it) } || PROMO.any { line.contains(it) }
        if (!negated) return true
        return TXN_WORDS.any { line.contains(it) }
    }

    /** 取包含 [index] 那一整行的文本。 */
    private fun lineAt(t: String, index: Int): String {
        var start = 0
        for (i in 0 until index) if (t[i] == '\n') start = i + 1
        var end = t.length
        for (i in index until t.length) {
            if (t[i] == '\n') { end = i; break }
        }
        return t.substring(start, end)
    }

    /**
     * 金额在文本中的位置。找不到就返回 -1（此时退化成"以全文中部为基准"）。
     *
     * 直接复用 [amountMatches] 而不是自己再扫一遍正则：判方向锚在哪个数字上，
     * 和最终取哪个数字当金额，**必须是同一个**。两处各写一套正则时，
     * 一处加了行过滤另一处没加，方向就会锚到一个已经被丢掉的钱包余额上。
     */
    private fun amountSpan(t: String): IntRange =
        amountMatches(t).firstOrNull()?.first ?: IntRange.EMPTY

    fun parse(text: String?, now: Long = System.currentTimeMillis()): LocalParseResult? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        if (t.length > 4000) return null // 明显不是付款通知，别浪费正则

        val amounts = extractAmounts(t)
        if (amounts.isEmpty()) return null

        // 判方向的文本要先剥掉「X方式」这组复合词。
        //
        // 原因是子串匹配不讲道理：「付款方」是「付款方式」的前三个字。
        // 而这两个词在支付 App 里表示的恰恰是**相反**的两件事——
        //   「付款方式」= 我用哪张卡付的 → 我是付款人 → 支出
        //   「付款方」   = 对方是谁       → 我是收款人 → 收入
        //
        // 不剥掉的话，一笔真实的支付宝付款（"…付款方式 余额宝"）会命中收入侧的
        // 「付款方」被判成收入。2026-09-30 真机上转出页和这条通知**同时**中招。
        val directionText = t.lowercase(Locale.ROOT)
            .replace("付款方式", "")
            .replace("收款方式", "")

        val span = amountSpan(t)
        val dir = if (span.isEmpty()) {
            decideDirection(directionText, 0, directionText.length)
        } else {
            decideDirection(directionText, span.first, span.last + 1)
        }

        val merchant = guessMerchant(t, amounts)
        // 候选越少越可信。多个金额时不能确定哪个是实付，信心要再压低。
        val confidence = when (amounts.size) {
            1 -> 0.55
            2 -> 0.40
            else -> 0.25
        }

        return LocalParseResult(
            amountsMinor = amounts,
            direction = dir,
            merchant = merchant,
            occurredAt = guessTime(t, now),
            confidence = confidence,
        )
    }

    /**
     * 按可靠度从高到低试各条规则，**只取第一条真正有命中的**规则的结果。
     *
     * 每一处命中都要过 [trustworthyLine]：钱包余额和促销门槛不是消费。
     * 被全过滤掉的那条规则**不算命中**，继续往下试——这样
     * 「闲鱼100元以下免密支付 / 支出55元」这一屏里，`\d+\s*元` 规则虽然
     * 匹到了两个数，但第一个不可信之后剩下的 55 元仍然会被留下。
     */
    private fun amountMatches(t: String): List<Pair<IntRange, Long>> {
        val out = LinkedHashMap<Long, IntRange>()
        for (p in AMOUNT_PATTERNS) {
            for (m in p.findAll(t)) {
                val g = m.groupValues.getOrNull(1) ?: continue
                val money = Money.parse(g) ?: continue
                if (!trustworthyLine(lineAt(t, m.range.first))) continue
                if (!out.containsKey(money.minor)) out[money.minor] = m.range
            }
            if (out.isNotEmpty()) break // 命中更可靠的模式就不再往下试
        }
        return out.map { it.value to it.key }
    }

    private fun extractAmounts(t: String): List<Long> =
        amountMatches(t).map { it.second }.filter { it > 0 }.take(3)

    /**
     * 商户名猜测。
     *
     * ## 真实样本长什么样（2026-10-01 真机）
     *
     * 微信转账详情页（`RealScreenMerchantTest`）：
     * ```
     * 转账说明
     * 提醒对方收款
     * 等待对方确认收钱     ← 「当前状态」这个字段的**值**
     * 当前状态
     * -0.10               ← 金额
     * 转账-转给橘生         ← 商户，在金额下面
     * ```
     *
     * 支付宝账单详情页：
     * ```
     * …抽屉菜单 20 行…
     * 交易成功
     * 支出10.1元            ← 金额
     * 杭州淘宝通信技术有限公司  ← 商户，在金额下面
     * ```
     *
     * 通知文本（老的形状）：
     * ```
     * 交易成功
     * 盒马鲜生             ← 商户，在金额**上面**
     * 实付 ¥156.80
     * ```
     *
     * ## 三条改动的理由
     *
     * 1. **按数值找金额行，不拿展示串去 `contains`。**
     *    支付宝写的是 `10.1元`（尾零被它自己丢掉了），而
     *    `Money.toYuanDisplay(1010)` 是 `10.10`——两个字符串永远不相等，
     *    整屏 30 行里找不到金额行，商户直接返回 null。金额长什么样是对方 App
     *    决定的，拿自己的格式去反查，等于假设对方按你的格式写。
     *
     * 2. **只在金额附近找（±2 行），且先下后上。**
     *    原来是从金额行**一路向上**扫到屏幕顶。支付宝那一屏上方隔着二十行抽屉
     *    菜单，于是抓到了 `账单分类`。真机详情页的商户标题渲染在金额**下方**，
     *    所以先下后上；通知文本的商户在上方，两边都覆盖得到。
     *
     * 3. **跳过「字段的值」。**
     *    节点顺序是**值在标签前面**（见 [PaymentId] 的类注释），所以一行如果
     *    后面紧跟着一行**纯标签**（只有标签、没有值），它就是那个字段的值。
     *    `等待对方确认收钱 / 当前状态` 就是这个形状——它不含数字、不含货币
     *    符号、长度合规，所有词表都拦不住，只有结构能认出来。
     *
     * 仍然刻意保守：猜错商户不如不猜。错误的商户名会污染历史统计，而空着
     * 用户一秒就能补。
     */
    private fun guessMerchant(t: String, amounts: List<Long>): String? {
        val target = amounts.firstOrNull() ?: return null
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        // 按**数值**定位金额行，见上面第 1 条。
        val idx = lines.indexOfFirst { line -> amountMatches(line).any { it.second == target } }
        if (idx < 0) return null

        // 先下后上，各看两行。顺序与窗口见上面第 2 条。
        val order = listOf(idx + 1, idx + 2, idx - 1, idx - 2)
        for (i in order) {
            if (i !in lines.indices) continue
            merchantAt(lines, i)?.let { return it }
        }
        return null
    }

    /** 金额行前面那个前缀（`实付 ¥` 这种）先单独试一次。 */
    private fun merchantAt(lines: List<String>, i: Int): String? {
        candidate(lines[i]) ?: return null
        // 下一行是纯标签 ⇒ 这一行是那个字段的值，不是商户。见上面第 3 条。
        if (lines.getOrNull(i + 1)?.let(::isPureLabelLine) == true) return null
        return candidate(lines[i])
    }

    /**
     * 整行**只有标签、没有值**。
     *
     * 判据是「命中标签，且标签后面的东西被分隔符剔完之后**什么都不剩**」。
     *
     * ## 为什么不能用"不含数字"来判
     *
     * 第一版写的是「短 + 命中标签 + 不含数字」，结果 `当前状态 支付成功`
     * 被判成纯标签——「支付成功」里没有数字啊——于是微信支付成功页上的
     * `星巴克` 被当成它的值丢掉了，商户变成 null。
     *
     * 真正的分界是**标签后面还有没有内容**：
     * ```
     * 当前状态          ← 纯标签（值在它上面一行）
     * 当前状态 支付成功   ← 标签与值同行（值在它自己这一行）
     * 实付 ¥156.80      ← 同上
     * ```
     */
    private fun isPureLabelLine(line: String): Boolean {
        val head = line.trim()
        if (head.isEmpty()) return false
        for (label in FIELD_LABELS) {
            val i = head.indexOf(label)
            if (i < 0) continue
            val rest = head.substring(i + label.length)
                .trim('：', ':', ' ', '　', '|', '-', '·', '*', '=')
            if (rest.isEmpty()) return true
        }
        return false
    }

    /** 字段标签、纯符号行、含数字的行，都不是商户名。 */
    private val FIELD_LABELS = listOf(
        "实付", "实收", "支付金额", "付款金额", "交易金额", "消费金额", "金额",
        "合计", "总计", "订单", "订单号", "时间", "日期", "方式", "余额", "优惠",
        "原价", "现价", "支付方式", "付款方式", "交易单号", "商户单号", "状态",
        // 2026-09-30 实测：转账成功页上「收款方」被当成了商户名，
        // 草稿里就出现了 merchant="收款方" 这种一眼假的值。
        "收款方", "付款方", "回首页", "完成", "备注",
    )

    /**
     * 支付页上的**状态行**，不是商户名。
     *
     * 真实通知长这样，商户夹在状态行和金额行之间：
     * ```
     * 支付成功
     * ¥23.50
     * 星巴克
     * 当前状态 支付成功
     * ```
     * 上面的过滤条件挡不住"支付成功"：它不含数字、不含货币符号、长度合规、
     * 也不含任何字段标签——`"支付金额"` 不是 `"支付成功"` 的子串。于是商户被
     * 猜成"支付成功"，而这会**污染历史统计**：以后在商户一栏搜"星巴克"搜不到。
     *
     * 宁可不猜。空着用户一秒能补，猜错了他不会发现。
     */
    private val STATUS_LINES = listOf(
        "支付成功", "付款成功", "交易成功", "收款成功", "转账成功", "扣款成功",
        "消费成功", "退款成功", "已成功", "支付完成", "交易完成", "已完成",
        "交易中", "处理中", "等待付款", "待付款", "已取消", "已关闭", "失败",
        "成功", "当前状态", "交易详情", "订单详情", "支付详情", "结果",
        // 2026-10-01 真机：微信 P2P 转账详情页的「当前状态」取值。
        // 结构上已经被"下一行是纯标签"那条规则挡掉了（见 merchantAt），
        // 这里再收一遍是**双保险**——词表和结构任一失效都不至于把状态写成商户。
        "等待对方确认收钱", "等待对方收款", "等待确认收款", "对方已收钱",
        "已收款", "已退还", "已退回", "转账已收钱", "收款已到账",
    )

    private val CURRENCY_CHARS = setOf('¥', '￥', '$', '元', '圆')

    private fun candidate(raw: String): String? {
        var head = raw.trim().trim('-', '·', '|', ':', '：', '*', '=', ' ')
        if (head.isEmpty()) return null
        // 含货币符号说明这只是金额的修饰语，不是商户名
        if (head.any { it in CURRENCY_CHARS }) return null
        if (head.any { it.isDigit() }) return null
        if (head.length !in 2..24) return null
        // 命中字段标签（含"实付 ¥"这种标签被货币符号切剩的情况）
        if (FIELD_LABELS.any { head.contains(it) }) return null
        // 状态行。"支付成功"这种词不含数字也不含货币符号，靠上面几条筛不掉
        if (STATUS_LINES.any { head.contains(it) }) return null
        return head
    }

    /**
     * 从「今天/昨天/03-15/2026-09-15」里取时间，取不到就用 [now]。
     *
     * ## 为什么不归到"今天中午"
     *
     * 早期版本在读不出时间时一律设成当天 12:00，理由是"避开零点边界"。这个理由
     * 站不住，而后果是**静默丢账**：
     *
     *  - 15:30 的通知和 15:35 的另一笔，**时间戳完全相同**（都是当天 12:00）
     *  - 自动记账的跨来源合并判据是"同额、同向、时间差 120 秒内"
     *  - 于是**两笔真实的同额消费被合并成一笔**，用户少了一笔钱却毫无察觉
     *
     * 这是设备测试 `twoRealSameAmountPaymentsInDifferentAppsDoNotMerge` 逮到的，
     * 而纯逻辑测试全绿——因为那些用例一次只解析一条。
     *
     * 所以这里保留 [now] 的**时分秒**：既然读不出准确时刻，用"事件发生的那一刻"
     * 一定比"当天中午"更接近真相，而且它保住了先后顺序——而顺序正是去重和
     * 合并判据赖以工作的东西。
     */
    /**
     * `21:14` 或 `21:14:23`。允许中文冒号——账单页的「支付时间 21：14」两种都有。
     */
    private val CLOCK = Regex("""(?<!\d)(\d{1,2})[:：](\d{2})(?::(\d{2}))?(?!\d)""")

    /**
     * 只在日期**紧邻**的窗口里找时分秒，窗口是 [NEIGHBOR_CHARS] 个字符，先右后左。
     *
     * ## 为什么不能全段兜底
     *
     * 账单详情页整屏里除了「支付时间 2026年9月30日 21:14:23」，还有商户全称、
     * 商品订单号、收单机构那一堆串。全段扫第一个 `xx:xx` 形态，取到的可能压根不是
     * 交易时刻，而时间点正是去重和近重复合并赖以工作的锚——取错了等于凭空造了一笔
     * 落在别处的消费。
     *
     * 窗口取 24 是够宽的：「支付时间 2026年9月30日 21:14:23」里日期和时刻之间
     * 只隔一个空格；而「9月29日15:30」这种贴着的写法更是紧挨着。
     */
    private fun clockNear(t: String, dateStart: Int, dateEnd: Int): Triple<Int, Int, Int>? {
        val right = t.substring(dateEnd.coerceIn(0, t.length)).take(NEIGHBOR_CHARS)
        val left = t.substring(0, dateStart.coerceIn(0, t.length)).takeLast(NEIGHBOR_CHARS)
        for (segment in listOf(right, left)) {
            val m = CLOCK.find(segment) ?: continue
            return Triple(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0,
            )
        }
        return null
    }

    /** 时分秒只在日期左右这么多个字符里找。理由见 [clockNear]。 */
    private const val NEIGHBOR_CHARS = 24

    private fun guessTime(t: String, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MILLISECOND, 0)
        }

        when {
            t.contains("昨天") || t.contains("昨日") -> cal.add(Calendar.DAY_OF_YEAR, -1)
            t.contains("前天") -> cal.add(Calendar.DAY_OF_YEAR, -2)
            else -> {
                Regex("""(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})""").find(t)?.let { m ->
                    val (h, mi, s) = clockNear(t, m.range.first, m.range.last + 1)
                        ?: Triple(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), 0)
                    return runCatching {
                        Calendar.getInstance().apply {
                            timeInMillis = now
                            clear()
                            set(
                                m.groupValues[1].toInt(),
                                m.groupValues[2].toInt() - 1,
                                m.groupValues[3].toInt(),
                                h, mi, s,
                            )
                        }.timeInMillis
                    }.getOrDefault(now)
                }
                Regex("""(?<!\d)(\d{1,2})[-/月](\d{1,2})日?(?!\d)""").find(t)?.let { m ->
                    val mo = m.groupValues[1].toIntOrNull()
                    val day = m.groupValues[2].toIntOrNull()
                    if (mo != null && day != null && mo in 1..12 && day in 1..31) {
                        cal.set(Calendar.MONTH, mo - 1)
                        cal.set(Calendar.DAY_OF_MONTH, day)
                        // 「9月29日15:30」这种贴着的写法，时分不能丢
                        clockNear(t, m.range.first, m.range.last + 1)?.let { (h, mi, s) ->
                            cal.set(Calendar.HOUR_OF_DAY, h)
                            cal.set(Calendar.MINUTE, mi)
                            cal.set(Calendar.SECOND, s)
                        }
                    }
                }
            }
        }
        return cal.timeInMillis
    }
}
