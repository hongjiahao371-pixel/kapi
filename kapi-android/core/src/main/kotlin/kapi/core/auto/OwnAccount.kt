package kapi.core.auto

/**
 * 这一笔是**自己口袋之间的挪动**，还是真的花了钱。
 *
 * ## 为什么要有它
 *
 * 2026-10-01 realme 真机，支付宝「账单详情」里这一屏：
 * ```
 * 转账收款到余额宝
 * 商品说明
 * 账户余额
 * 付款方式
 * 2026-10-01 08:54:54
 * 创建时间
 * 交易成功
 * 12100元
 * 余额宝
 * ```
 * 用户的 121 块从**账户余额**挪进了**余额宝**——两个兜都在自己身上，
 * 支付宝自己还把这一笔的「计入收支」开关**关着**。
 *
 * 而我们记下来的是：`12100 EXPENSE / 商户=None`。用户什么都没买，
 * 账本里凭空多了一笔支出，还会污染月度统计和分类饼图。
 *
 * 和「假账」的两兄弟相比，这一笔更难被发现：余额和转出页长得都像正常消费，
 * 金额也对，只有对着两个账户才看得出这笔钱没离开过自己。
 *
 * ## 判据：两端都是自己的
 *
 * 只需要问一个问题——**钱的来处和去处，是不是都在我兜里**。这和方向无关，
 * 所以不依赖 [kapi.core.llm.LocalTextParser] 那套方向判断（它在这个场景下不可靠，
 * 原因见该类的 [CHROME_LABELS]）。
 *
 * ```
 * 账户余额 → 余额宝     两端都是自己的钱包   → 内部划转
 * 余额宝   → 啦啦啦(朱娜娜)  去处是个人       → 真的转出去了，支出
 * （无）    → 盖被吱     来处没写，但对方不是自己的钱包 → 真的收入
 * ```
 *
 * ## 两条独立的判据
 *
 * 1. **交易类型名字本身**就说明了是自转账（`信用卡还款`、`花呗还款`…）。
 *    这种屏往往**根本不写付款账户**（见 [Sides] 的注释），靠两端比不出来，
 *    只能靠名字。
 * 2. **两端都落在自己的钱包词表里**。
 *
 * 两条都判为"不是消费"才算数，任何一条判不出来就**照记**——
 * 判错成内部划转的代价是一笔**静默消失**的消费，比多记严重得多
 * （[AutoBook] 类注释：宁重复，不丢失）。
 */
object OwnAccount {

    /** 判完的结果。 */
    sealed interface Verdict {
        /** 普通交易，照常记。 */
        data object NotSelfTransfer : Verdict

        /**
         * 自己的钱在挪动。[why] 写进日志，排查时能看出是哪条判据命中的。
         */
        data class SelfTransfer(
            val from: String?,
            val to: String?,
            val why: String,
        ) : Verdict
    }

    /**
     * 平台自带的**子钱包**名字。
     *
     * 只收「产品里固定存在的入口」，不收任何可能是**人名**或**商户名**的东西——
     * 这张表判错的代价是吃掉一笔真消费，而人名、商户名在支付 App 里遍地都是。
     *
     * 银行卡**故意不在表里**：叫「招商银行储蓄卡(1234)」的是用户自己的卡没错，
     * 但同样的写法也可能出现在给别人转账的**收款方**上，光看名字分不出是哪边。
     * 要判「我用招行卡还了我自己的招行信用卡」得知道卡号属于谁，那是另一件事
     * （见类注释里"学到的账户"那条后续）。
     */
    private val WALLET_WORDS = listOf(
        "余额宝", "账户余额", "零钱", "零钱通", "花呗", "存钱罐",
    )

    /**
     * **交易类型名本身就说明是自转账的词。**
     *
     * 收词标准：这个词在支付 App 里只可能出现在「还自己的钱」的场景。
     * 裸的「还款」**不收**——`XX还款`（还贷款给放贷方）是一笔真支出，
     * 收了就会静默吃掉它。
     */
    private val SELF_TRANSFER_WORDS = listOf(
        "信用卡还款", "花呗还款", "分期还款",
        "余额宝转入", "余额宝转出", "余额宝转账",
        "零钱通转入", "零钱通转出",
        "账户余额转入", "账户余额转出",
    )

    /** 「收款到余额宝」这种写法里的目标账户名。 */
    private val CREDIT_TO = Regex("收款到\\s*(\\S+)")

    /**
     * 从「付款方式」/「收款方」这类标签上取**值**。
     *
     * 取值走 [ScreenFields]：先用页面日期行探明这一页值在标签前还是后，
     * 再按同一方向取——2026-10-02 realme 实测两种方向**都存在**
     * （转账页值在前、富账单详情页值在后），写死任何一侧都会在另一半
     * 页面上静默取错。取不到返回 null。
     */
    private fun valueOf(lines: List<String>, label: String): String? =
        ScreenFields.valueNear(lines, label, ScreenFields.orientation(lines))

    /**
     * 这一屏的两端分别是谁。
     *
     * `from` 来自 `付款方式`——那是**账户**字段，子串匹配安全。
     *
     * `to` 有四个来源，可信度**不一样**，所以必须分开记：
     * 1. 标题行里的 `收款到X` —— **应用自己写的说明**，说"收款到余额宝"就是余额宝
     * 2. `收款方` 标签旁的值
     * 3. `对方账户` 标签旁的值
     * 4. `还款到` 标签旁的值（信用卡还款页的"去处"字段；值是自己的卡，
     *    所以只影响日志里的 from/to 记录，判自转账靠的是名词表）
     *
     * 2 和 3 装的是**人名或店名**，子串匹配在那里是危险的：有一家便利店就叫
     * 「余额宝便利店」，按子串它就是我们的钱包，于是往那儿付的 20 块会被
     * **静默吃掉**。所以这两种来源只认**全等**。
     *
     * 第 3 条在真实转账里**两个方向都会出现**——2026-10-01 抓到的
     * 「转出给啦啦啦」和「收到盖被吱」两屏都写着 `对方账户`。
     * 所以它只用来判"**对方是谁**"，判不出方向，也不该拿来判方向。
     */
    data class Sides(
        val from: String?,
        val to: String?,
        /** [to] 来自「收款到X」这种**应用自己写的说明**，而不是人名字段。 */
        val toFromCreditLabel: Boolean,
    )

    fun sides(lines: List<String>): Sides {
        val credited = lines.firstNotNullOfOrNull { CREDIT_TO.find(it)?.groupValues?.get(1) }
        val to = credited
            ?: valueOf(lines, "收款方")
            ?: valueOf(lines, "对方账户")
            ?: valueOf(lines, "还款到")
        return Sides(
            from = valueOf(lines, "付款方式"),
            to = to,
            toFromCreditLabel = credited != null,
        )
    }

    /**
     * 这个名字是不是一个「自己兜里的钱包」。子串匹配。
     *
     * 只用在**账户**字段（`付款方式`）上，那里写的是
     * `账户余额` / `招商银行储蓄卡(1234)` 这种带修饰的完整账户名。
     */
    fun isOwnWallet(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        return WALLET_WORDS.any { n.contains(it) }
    }

    /**
     * 严格版：整个名字**就是**一个钱包，不接受前后缀。
     *
     * 用在**人名**字段（`收款方` / `对方账户`）上，见 [Sides] 的注释。
     */
    fun isOwnWalletExact(name: String?): Boolean {
        val n = name?.trim()?.trimEnd('：', ':', '。', '，', ',').orEmpty()
        return n.isNotEmpty() && n in WALLET_WORDS
    }

    /**
     * 判这一屏是不是自己的钱在挪动。
     *
     * 名词表命中和两端判断**都要算 sides**：2026-10-02 真机上「信用卡还款」
     * 页记成了 `from=null, to=null`——名词路径直接 return，没算两端，日志里
     * 两端全空，对着 dump 才发现屏上明明有 `付款方式 / 余额宝`。先把两端
     * 算出来再下结论，日志才留得住证据。
     *
     * @param lines 读屏行序列（内部会先折叠相邻重复节点）
     */
    fun judge(rawLines: List<String>): Verdict {
        val lines = ScreenFields.collapse(rawLines)
        val s = sides(lines)
        for (w in SELF_TRANSFER_WORDS) {
            if (lines.any { it.contains(w) }) {
                return Verdict.SelfTransfer(s.from, s.to, "交易类型名命中「$w」")
            }
        }

        val toOwn = if (s.toFromCreditLabel) isOwnWallet(s.to) else isOwnWalletExact(s.to)
        return if (isOwnWallet(s.from) && toOwn) {
            Verdict.SelfTransfer(
                s.from, s.to,
                "来处「${s.from}」和去处「${s.to}」都是自己的钱包",
            )
        } else {
            Verdict.NotSelfTransfer
        }
    }
}
