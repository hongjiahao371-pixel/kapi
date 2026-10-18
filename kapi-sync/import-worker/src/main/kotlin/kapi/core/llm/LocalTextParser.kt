package kapi.core.llm

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

    /**
     * 金额候选。刻意宽松，宁可多给候选让用户选，也不要给错一个。
     *
     * ## 千分位逗号是 2026-10-02 真机踩出来的
     *
     * 支付宝账单列表/详情页对 ≥1000 的金额写 `10,000.00` / `12,100.00`（2026-10-02
     * realme 实测），这一类之前**整笔解析失败**（「没解析出金额」→ 静默漏记）：
     * 裸金额正则从逗号后只咬出 `000.00` → Money 解析成 0 → 被 `filter { it > 0 }`
     * 过滤掉，整屏无候选。
     *
     * 更危险的是 `[.,]` 可选组的**截断**：`\d+(?:[.,]\d{1,2})?` 对
     * `¥12,100.00` 只捕获到 `12,1`——Money 把它当**小数逗号**（见 Money.normalize
     * 的「后段不是 3 位 → 小数」规则）读成 12.1 元，**金额错 100 倍还入了账**。
     * 所以带金额捕获组的模式全部改用 `(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?`：
     * 千分位分组要求**每组正好 3 位**，`1,36` 这种半截分组不会被误当成金额。
     */
    private val AMOUNT_PATTERNS = listOf(
        // 带货币符号或单位，最可靠
        Regex("""[¥￥]\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)"""),
        Regex("""((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)\s*元"""),
        // 显式字段名
        Regex("""(?:实付|实收|付款金额|支付金额|交易金额|消费金额|金额|合计|总计|订单金额)\s*[:：]?\s*((?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?)"""),
        // 英文
        Regex("""(?i)(?:paid|total|amount)\s*[:：]?\s*(\d+(?:\.\d{1,2})?)"""),
        // 千分位裸金额（无货币符号无单位）：支付宝账单页对 ≥1000 写 `-10,000.00`、
        // `12,100.00`（2026-10-02 realme 真机，原文见 RealScreenMerchantTest）。
        // 少了这一条，这笔账在「形状像一笔」「支付门」全过之后死在解析——
        // 日志只剩一行「没解析出金额」，界面上什么都不出现。
        Regex("""((?<![\d.,])\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?(?![\d.,]))"""),
        // 兜底：裸金额。微信账单详情页写的是 `-80.00`，既无 ¥ 也无元，只有这一条认它。
        // 两侧环视防的是把 `8.0.55`（微信版本号）、`1.5.3` 啃掉一段当成 8.00 / 1.50。
        // 必须和 [kapi.core.auto.PaymentGate] / [kapi.core.auto.PaymentShape] 的
        // AMOUNT 保持同一套环视，否则门和解析器的口径就分叉了。
        // **后视比它们多排一个 `,`，这是刻意的**：门只数个数（尾段匹配照样算
        // 命中），解析器要取值——`10,000.00` 从这里咬出尾段 `000.00` 会变成
        // 一笔 0 元候选。两边后视不一致不是漂移，别「对齐」它。
        Regex("""((?<![\d.,])\d{1,8}\.\d{2}(?![\d.年月日]))"""),
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
        "退款", "退回", "入账", "已到账", "到账", "返现", "收到红包", "领取红包", "红包收入", "收入",
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

    /**
     * **界面外壳**词：菜单项 / 运营入口的名字，**不是**对这笔钱的描述。
     *
     * 2026-10-01 realme 支付宝，同一笔转账（收到盖被吱 12100）被读到了**两个屏变体**，
     * 方向却翻了个个儿：
     * ```
     * 变体甲：账单分类 │ 我的消费图鉴 │ 账单管理 │ …  → 支出（错）
     * 变体乙：账单分类 │ 账单管理 │ …                → 收入（对）
     * ```
     * 唯一的差别是「我的消费图鉴」这一行。它是账单页的一个**运营入口**，
     * 却因为含「消费」二字进了 [EXPENSE_HINTS]，而且离金额比收入词更近，
     * 于是 [decideDirection] 的"最近者赢"判给了支出。
     *
     * 后果不是方向记错一笔，是**两笔草稿被并成一笔**——第二笔凭空消失。
     *
     * 这和 2026-09-30 修的「付款方 vs 付款方式」是同一类错：**子串匹配不讲道理**，
     * 界面上任何一处恰好含有方向词的地方都会劫持方向判断。修掉一个还会有下一个，
     * 所以这里单列一张表，而不是继续往 [EXPENSE_HINTS] 里加词。
     *
     * 收词标准：这个词**只会**出现在菜单/入口上，**且**含有方向词。
     * 「账单管理」「更多」「为您推荐」不含方向词，不收——收了只是白占。
     */
    private val CHROME_LABELS = listOf(
        "消费图鉴", // 我的消费图鉴 / 消费图鉴
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
     * 5. 全文根本没有方向词 → 判支出，**但有一种结构例外**：见
     *    [isReceivedMoneyShape]——支付宝收款详情页整屏没有方向词，
     *    唯一的方向证据是「对方账户」在而「付款方式」不在。
     */
    private fun decideDirection(
        t: String,
        amountStart: Int,
        amountEnd: Int,
        receivedShape: Boolean = false,
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
            income == null && expense == null ->
                if (receivedShape) Direction.INCOME else Direction.EXPENSE
            income == null -> Direction.EXPENSE
            expense == null -> Direction.INCOME
            expense <= income -> Direction.EXPENSE
            else -> Direction.INCOME
        }
    }

    /**
     * 「对方账户」在、而「付款方式 / 付款账户」不在——这笔钱是**进来的**。
     *
     * ## 2026-10-02 realme 真机的完整对照（6 页富账单详情）
     *
     * | 页 | 金额节点 | 对方账户 | 付款方式 | 真实方向 |
     * |---|---|---|---|---|
     * | 转账-盖被吱 +12100 | `12100元` | ✓ | **✗** | 收入 |
     * | 转账-啦啦啦 -10000 | `支出10000元` | ✓ | ✓ | 支出（「支出」前缀命中词表） |
     * | 转账收款到余额宝 12100 | `12100元` | ✗ | ✓ | 自转账（OwnAccount 拦） |
     * | 信用卡还款 1360.11 | `1360.11元` | ✗ | ✓ | 自转账（OwnAccount 拦） |
     * | 商户消费 -20 | `支出20元` | ✗ | ✓ | 支出 |
     *
     * 关键事实：**收入的金额节点是裸的**——屏幕上画着 `+12,100.00`，
     * 无障碍节点文本却是 `12100元`（+号、千分位、小数点全被支付宝自己丢了），
     * 整屏没有「收入/到账/收款」任何一个方向词。老兜底在这种页上把真收入
     * 判成支出。而每一类**支出**页都带「付款方式」行或「支出」前缀，
     * 所以「对方账户 + 无付款方式」只在收款页成立——这是结构性证据，
     * 不是词表能覆盖的（[AutoBook] 的 OwnAccount 注释里记过同一个事实：
     * 「对方账户」两个方向都会出现，所以单独它不够，必须配上付款方式缺席）。
     *
     * 只在**词表完全没命中**的兜底分支生效：有方向词的屏（包括带「支出」
     * 前缀的转账支出页）一律照旧，结构判断不去和词表抢。
     */
    private fun isReceivedMoneyShape(t: String): Boolean =
        t.contains("对方账户") &&
            !t.contains("付款方式") &&
            !t.contains("付款账户")

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
        // 2026-10-02 微信「腾讯文档会员」页：优惠行「中国银行首绑有礼优惠￥5.00」
        // 被当成实付金额（真正的实付 -13.00 在裸金额模式里被短路跳过）。
        // 「原价」同行：划线原价 ¥18.00 也不是实付。
        "优惠", "原价", "首绑",
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
        if (kapi.core.auto.PeerPaymentReceipt.rejectionReason(t) != null) return null

        kapi.core.auto.PeerPaymentReceipt.read(t)?.let { receipt ->
            return LocalParseResult(listOf(receipt.amountMinor),receipt.direction,receipt.merchant,
                guessTime(receipt.text,now,receipt.direction),0.55)
        }
        // A wallet peer receipt must establish its status and signed direction.
        // Falling through to merchant defaults can turn an unsupported incoming status
        // into an expense (e.g. a newly introduced credited-to-savings label).
        if(t.lineSequence().map(String::trim).any {
            it.matches(Regex("^(?:转账[-－—]来自|微信红包[-－—]?来自).{1,45}$"))
        }) return null
        kapi.core.auto.WechatPaymentResult.read(t)?.let { receipt ->
            return LocalParseResult(listOf(receipt.amountMinor), Direction.EXPENSE,
                receipt.merchant, now, 0.55)
        }
        kapi.core.auto.WechatMerchantPaymentResult.read(t)?.let { receipt ->
            return LocalParseResult(listOf(receipt.amountMinor), Direction.EXPENSE,
                receipt.merchant, now, 0.55)
        }

        val amounts = extractAmounts(t)
        if (amounts.isEmpty()) return null

        // 判方向的文本要先剥掉两类干扰：界面外壳，和「X方式」这组复合词。
        //
        // 「X方式」的原因是子串匹配不讲道理：「付款方」是「付款方式」的前三个字。
        // 而这两个词在支付 App 里表示的恰恰是**相反**的两件事——
        //   「付款方式」= 我用哪张卡付的 → 我是付款人 → 支出
        //   「付款方」   = 对方是谁       → 我是收款人 → 收入
        //
        // 不剥掉的话，一笔真实的支付宝付款（"…付款方式 余额宝"）会命中收入侧的
        // 「付款方」被判成收入。2026-09-30 真机上转出页和这条通知**同时**中招。
        //
        // 外壳词是同一类错（见 [CHROME_LABELS]）：界面上恰好含有方向词的菜单项
        // 会劫持方向判断，2026-10-01 realme 上它把一笔收到的转账判成了支出。
        //
        // 顺序无所谓——两组词互不重叠，且都要在 [decideDirection] 之前剥干净。
        // Keep character offsets: amountSpan below refers to the original screen text.
        val directionText = CHROME_LABELS
            .fold(t.lowercase(Locale.ROOT)) { acc, w -> acc.replace(w, " ".repeat(w.length)) }
            .replace("付款方式", "    ")
            .replace("收款方式", "    ")

        val span = amountSpan(t)
        // 结构判据必须看**原始**文本：directionText 已经剥掉了「付款方式」，
        // 在它上面判「付款方式缺席」永远为真。
        val receivedShape = isReceivedMoneyShape(t)
        // OCR retains the signed header that Alipay accessibility sometimes drops.
        // An explicit peer receipt sign outranks the missing-method income fallback.
        val signedPeer=if(t.contains("对方账户")) kapi.core.auto.ScreenFields.collapse(t.lines()).take(8).singleOrNull {
            Regex("^[+\\-−]\\s*(?:[¥￥]\\s*)?\\d[\\d,]*(?:\\.\\d{1,2})?(?:元)?$").matches(it)
        }?.firstOrNull() else null
        val dir = if(signedPeer!=null) {
            if(signedPeer=='+') Direction.INCOME else Direction.EXPENSE
        } else if (span.isEmpty()) {
            decideDirection(directionText, 0, directionText.length, receivedShape)
        } else {
            decideDirection(directionText, span.first, span.last + 1, receivedShape)
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
            occurredAt = guessTime(t, now, dir),
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
     *
     * 命中之后还要**按行质量排序**（[lineRank]）：2026-10-02 真机话费页，
     * 展开态的商品名「云南手机充值**50元**电信话费直充快充」在文本序里
     * 先于「支出49.5元」——按原序取第一个就是 50 元，而实际支付是 49.5。
     * 更糟的是同一页两次读取（折叠/展开）选中不同金额，合并判据同额才并，
     * 于是**同一笔建了两条草稿**。带方向词的实付行永远赢过商品名里的数字。
     *
     * ## 跨模式收集（2026-10-02 微信，第二处同族缺陷）
     *
     * 原先「某模式一命中就 break」：微信「腾讯文档会员」页上，
     * `原价 ¥18.00`（划线）和 `优惠 …￥5.00` 都是带 ￥ 的行，¥ 模式先命中
     * 就短路——真正的实付 `-13.00` 在**后面的裸金额模式**里，永远没机会。
     * 改为全模式收集（按 minor 去重），排序时把「优惠/原价邻居行」的候选
     * 压到最后（[lineRank] 的 3 分档）。trustworthyLine 的否定词表同步补
     * 「优惠/原价」：优惠行自身整行被否定是第一道闸，邻接降权是第二道。
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
        }
        return out.map { it.value to it.key }.sortedBy { (range, _) -> lineRank(t, range) }
    }

    /**
     * 金额候选所在行的质量分（小者胜）。
     *
     * 0 = 行里带方向/实付词（`支出49.5元`、`实付 ¥88.00`）——这是账本要的那一笔
     * 1 = 干净短行（≤24 字，且自己与紧邻行都不含优惠/原价标记）
     * 2 = 长行——商品名/描述里的数字（`云南手机充值50元电信话费直充快充`）
     * 3 = **优惠/原价邻接**的金额——它是折扣额或划线原价，不是实付
     *     （2026-10-02 微信「腾讯文档会员」页：实付 -13.00，划线原价 ¥18.00，
     *     优惠行 ￥5.00——原价和优惠都必须排在 13.00 之后）
     */
    private fun lineRank(t: String, range: IntRange): Int {
        val (line, prev, next) = lineWithNeighbours(t, range.first)
        // 带正负号的金额行（微信 -13.00 / +100.00）是账本要的那一笔：
        // 原价/优惠行的数字从不带符号。与方向词同档，同档内保持模式优先序。
        val signed = line.trimStart().let { it.startsWith("-") || it.startsWith("+") || it.startsWith("−") }
        return when {
            AMOUNT_LINE_SIGNALS.any { line.contains(it) } || signed -> 0
            DISCOUNT_LABELS.any { line.contains(it) || prev.contains(it) || next.contains(it) } -> 3
            line.length <= 24 -> 1
            else -> 2
        }
    }

    /** 优惠/原价类标记：这些词旁边的金额是折扣额或划线原价，不是实付。 */
    private val DISCOUNT_LABELS = listOf("原价", "优惠", "立减", "满减", "折扣", "抵扣", "划线价")

    /** 金额所在行及其前后行（邻接判断用）。返回三元组 (本行, 前行, 后行)。 */
    private fun lineWithNeighbours(t: String, charIndex: Int): Triple<String, String, String> {
        var lineIndex = 0
        var acc = 0
        for ((i, l) in t.lines().withIndex()) {
            val next = acc + l.length + 1
            if (charIndex < next) {
                lineIndex = i
                break
            }
            acc = next
        }
        val lines = t.lines()
        val line = lines.getOrNull(lineIndex).orEmpty()
        val prev = lines.getOrNull(lineIndex - 1).orEmpty()
        val next = lines.getOrNull(lineIndex + 1).orEmpty()
        return Triple(line, prev, next)
    }

    private fun extractAmounts(t: String): List<Long> {
        val candidates = amountMatches(t)
        // 主金额为零时，不能跳过去拿说明里的未来扣款金额。支付宝先用后付的
        // 零元关联订单会写「订单完成后再付款10.10元」，实际收支另有一笔账单。
        if (candidates.firstOrNull()?.second == 0L) return emptyList()
        return candidates.map { it.second }.filter { it > 0 }.take(3)
    }

    /** [lineRank] 最高优先级（0 分）的行信号：说明这行说的是"这笔实付了多少"。 */
    private val AMOUNT_LINE_SIGNALS = listOf(
        "支出", "收入", "实付", "实收", "付款金额", "支付金额", "消费金额", "交易金额",
    )

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
        kapi.core.auto.WechatQrCounterparty.read(t)?.let { return it }
        // A verified native cashier receipt names the recipient explicitly. This
        // field is intentionally skipped by the generic near-amount heuristic.
        kapi.core.auto.AlipayPaymentResult.canonicalText(t)?.let { receipt ->
            kapi.core.auto.ScreenFields.valueNear(receipt.lines(), "收款方", null)
                ?.let { candidate(it, 100) }?.let { return it }
        }
        // 明确的商户全称字段比金额邻居可靠；已验证的订单规范化也使用此字段。
        val orientation = kapi.core.auto.ScreenFields.orientation(lines)
        for (label in listOf("商户全称", "收款方全称", "商户名称", "商家名称")) {
            kapi.core.auto.ScreenFields.valueNear(lines, label, orientation)
                ?.let { candidate(it, 100, allowDigits = true) }?.let { return it }
        }

        // 按**数值**定位金额行，见上面第 1 条。
        val idx = lines.indexOfFirst { line -> amountMatches(line).any { it.second == target } }
        if (idx < 0) return null

        // 先下后上，各看两行。顺序与窗口见上面第 2 条。
        val order = listOf(idx + 1, idx + 2, idx - 1, idx - 2)
        val namedBill = kapi.core.auto.PaymentId.authoritative(t) != null &&
            kapi.core.auto.PaymentPageIdentity.isDetail(t) &&
            (t.contains("支付方式") || t.contains("付款方式")) && kapi.core.auto.PaymentGate.hasStrongVerb(t)
        // Collapsed marketplace bills omit transaction IDs and mask the seller
        // title (e.g. tb**7). Only this restricted name shape may contain digits,
        // backed by settled status, dated fields, payment method and actual product.
        val maskedBill = kapi.core.auto.PaymentPageIdentity.isDetail(t) && orientation != null &&
            (t.contains("支付方式") || t.contains("付款方式")) &&
            lines.any(kapi.core.auto.PaymentGate::isSettledStatusLine) &&
            !kapi.core.auto.NoteComposer.productText(t).isNullOrBlank()
        val maskedName = Regex("""^[\p{L}][\p{L}\p{N}_·-]{0,10}\*{2,6}[\p{L}\p{N}_·-]{1,10}$""")
        for (i in order) {
            if (i !in lines.indices) continue
            merchantAt(lines, i, namedBill || (maskedBill && maskedName.matches(lines[i])))?.let { return it }
        }
        return null
    }

    /** 金额行前面那个前缀（`实付 ¥` 这种）先单独试一次。 */
    private fun merchantAt(lines: List<String>, i: Int, allowDigits: Boolean = false): String? {
        candidate(lines[i], allowDigits = allowDigits) ?: return null
        // 下一行是纯标签 ⇒ 这一行是那个字段的值，不是商户。见上面第 3 条。
        if (lines.getOrNull(i + 1)?.let(::isPureLabelLine) == true) return null
        return candidate(lines[i], allowDigits = allowDigits)
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
        // 同一次实测抓到的另一个：详情页还在加载时读到的整屏，第一行就是
        // 「加载中」，而金额那一屏恰好离它很近——它被当成商户写进了草稿。
        "加载中", "正在加载", "加载失败", "请稍候", "网络异常",
    )

    private val CURRENCY_CHARS = setOf('¥', '￥', '$', '元', '圆')

    private fun candidate(raw: String, maxLength: Int = 24, allowDigits: Boolean = false): String? {
        var head = raw.trim().trim('-', '·', '|', ':', '：', '*', '=', ' ')
        if (head.isEmpty() || head in setOf("计入收支","管理极速付款","主页","首页","查看更多","全部账单")) return null
        if (kapi.core.auto.MerchantName.isInterfaceLabel(head)) return null
        // 含货币符号说明这只是金额的修饰语，不是商户名
        if (head.any { it in CURRENCY_CHARS }) return null
        if (head.any { it.isDigit() } && (!allowDigits || !head.any(Char::isLetter) ||
                Regex("\\d{8,}|\\d{4}[-/年]|\\d{1,2}:\\d{2}").containsMatchIn(head) ||
                kapi.core.auto.PaymentGate.hasAmount(head))) return null
        if (head.length !in 2..maxLength) return null
        // 命中字段标签（含"实付 ¥"这种标签被货币符号切剩的情况）
        if (FIELD_LABELS.any { head.contains(it) }) return null
        // 状态行。"支付成功"这种词不含数字也不含货币符号，靠上面几条筛不掉
        if (STATUS_LINES.any { head.contains(it) }) return null
        return head
    }

    /** Resolve business time consistently; unknown clocks retain explicit review evidence. */
    private fun guessTime(t: String, now: Long, direction: Direction): Long =
        kapi.core.auto.TransactionTime.resolve(t,now,direction.name).selectedAt
}
