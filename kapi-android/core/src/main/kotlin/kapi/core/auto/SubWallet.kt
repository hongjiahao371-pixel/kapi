package kapi.core.auto

/**
 * 平台**子钱包**目录：读屏原文里的钱包名 → 账本里的账户行。
 *
 * ## 为什么要有它
 *
 * [OwnAccount] 能判出「账户余额 → 余额宝是自己的钱在挪」，但自转账要真实入账，
 * 两端必须对应到 `account` 表里的**不同行**。种子账户只有 4 个平台级
 * （现金/银行卡/支付宝/微信），余额宝、花呗这些子钱包没有行——于是
 * `账户余额→余额宝` 在管线里落到同一个「支付宝」上，只能记日志。
 * 2026-10-02 起子钱包进种子（A5…A10），本目录是**读屏名 → 账户行**的唯一映射。
 *
 * ## 目录是封闭的
 *
 * 只收「产品里固定存在的入口」，和 [OwnAccount.WALLET_WORDS] 同一套收敛标准——
 * 这张表判错的代价是把花呗消费记到别家，而人名、商户名在支付页里遍地都是。
 * 银行卡**不在表里**：「招商银行储蓄卡(0416)」对不上任何一张具体的卡，
 * 映射到笼统的「银行卡」行会把不同卡的流水搅在一起；要精确到卡，
 * 等账户管理里用户自己加卡再说。
 *
 * ## 匹配：长词优先
 *
 * 「零钱通」包含「零钱」，顺序错了微信的零钱通会被记到零钱头上。
 * 目录按词长降序匹配（[match]），**账户行本身用固定 id**（[Wallet.accountId]），
 * 种子（[kapi.ledger.data.LedgerRepository] 的 DEFAULT_ACCOUNTS）与这里的 id
 * 必须逐字一致——两处各写一份是刻意为之（core 不能依赖 app 的 Room 实体），
 * 但改任何一边都要同步另一边，`SubWalletTest` 里有 id 对照测试钉着。
 */
object SubWallet {

    /** 一个子钱包：读屏词、账本账户行（固定 id/名/类型）。 */
    data class Wallet(
        /** 读屏匹配词。匹配按词长降序，「零钱通」必须先于「零钱」。 */
        val word: String,
        /** account 表里的固定 id，与 LedgerRepository.DEFAULT_ACCOUNTS 一致。 */
        val accountId: String,
        /** 账户显示名。 */
        val name: String,
        /** 账户类型：余额宝/零钱通/存钱罐是货基 → INVESTMENT；花呗 → CREDIT。 */
        val kind: String,
    )

    private val WALLETS = listOf(
        Wallet("账户余额", "A9", "账户余额", "DEBIT"),
        Wallet("余额宝", "A5", "余额宝", "INVESTMENT"),
        Wallet("零钱通", "A6", "零钱通", "INVESTMENT"),
        Wallet("存钱罐", "A8", "存钱罐", "INVESTMENT"),
        Wallet("零钱", "A7", "零钱", "DEBIT"),
        Wallet("花呗", "A10", "花呗", "CREDIT"),
    )

    /** 按词长降序的匹配序——「零钱通」必须先于「零钱」被尝试。 */
    private val MATCH_ORDER = WALLETS.sortedByDescending { it.word.length }

    /**
     * 子钱包占据的 account 行 id（A5…A10）。手动记账的账户选择器用它们
     * 把平台子钱包从默认列表里摘出去——这六行是**自动记账**为了自转账
     * 两端入账铺的，手动记一笔的人 99% 只在 现金/银行卡/支付宝/微信 里挑；
     * 全部平铺出来是十个芯片的噪音（2026-10-03 用户反馈）。
     */
    val ACCOUNT_IDS: Set<String> = WALLETS.map { it.accountId }.toSet()

    /**
     * 读屏账户名 → 子钱包。找不到（银行卡、人名、空）返回 null，调用方
     * 走各自的降级路（管线记日志、消费落平台级账户）。
     *
     * 子串匹配是刻意的：调用方只把它用在**账户字段**上（`付款方式`、
     * OwnAccount 判完的 from/to），那里的值是「账户余额」这种带修饰的
     * 全名——和 [OwnAccount.isOwnWallet] 的语义一致；人名字段的精确匹配
     * 在 OwnAccount 那一层已经做完了，走到这里的都是它放行的名字。
     */
    fun match(name: String?): Wallet? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        return MATCH_ORDER.firstOrNull { n.contains(it.word) }
    }

    /** 固定 id 反查（管线拿到 A5…A10 后要回读类型/名字做日志）。 */
    fun byId(accountId: String): Wallet? = WALLETS.firstOrNull { it.accountId == accountId }
}
