package kapi.core.auto

/**
 * 从屏幕文字里抠出**平台交易号**。
 *
 * ## 为什么需要它
 *
 * 现在的去重靠 [AutoBook.exactFingerprint]——把整屏文字归一化之后算哈希。这个做法
 * 有一个先天缺陷：归一化会把「8 位以上的连续数字」抹成 `#`（见 [AutoBook.normalize]，
 * 抹掉是为了让同一笔支付的第 N 次重绘得到同一个指纹）。而**交易号恰恰就是 8 位以上的
 * 连续数字**，于是最有区分度的那一段被亲手抹掉了。
 *
 * 后果在账单详情页上是致命的。用户上周点开一笔 80 元的账单，今天又点开同一笔：
 * ```
 * 小红书平台商户 / -80.00 / 当前状态 支付成功 / 支付方式 零钱 / 交易单号 <被抹成 #>
 * ```
 * 两次读到的归一化文本**完全一样**，指纹一样。3 分钟去重窗口一过，第二笔就成了一条
 * 新草稿。用户在微信里翻账单是常事，账本里于是堆满同一条消费的重复行。
 *
 * ## 为什么「账单详情页」才是主战场
 *
 * 2026-09-30 用户实测录屏（OPPO PMA110，小布记忆）显示：稳定的那条通路抓的**不是**
 * 付款成功页，而是用户主动点进历史流水的**账单详情页**。原因不难理解：
 *  - 付款成功页只存在一两秒，是瞬态 Activity，最容易被支付保护和动画吃掉；
 *  - 账单详情页是稳定页面，用户可以随时回来看，信息还更全（含交易号、支付时间）。
 *
 * 换句话说，我们之前把宝押在了最不稳的那一屏上。而账单详情页稳，代价是**必须能认得出
 * 「这笔我见过了」**——那正是 [Id.authoritative] 存在的理由。
 *
 * ## 为什么不直接用商户单号
 *
 * 商户单号只在**该商户内部**唯一，不同商户可以撞。而且现实里重复用单号的商户不少。
 * 拿它自动丢弃，等于赌「这个商户不会复用单号」——赌输了就是**静默丢账**，
 * 比多记严重得多（见 [AutoBook] 的类注释：宁重复，不丢失）。
 *
 * 所以 [Id.authoritative] 只有平台交易号为真。商户单号照样抽出来，留给将来做
 * 「疑似同一笔」的提示，但**不参与自动丢弃**。
 */
object PaymentId {
    /** Preserve the wallet payment identity when commerce and payment order IDs coexist. */
    fun importIdentity(text:String):String? = ofKind(text,Kind.WECHAT_TXN)?.key ?: ofKind(text,Kind.ALIPAY_TXN)?.key ?: ofKind(text,Kind.PDD_TXN)?.key ?: authoritative(text)?.key


    enum class Kind(val label: String) {
        WECHAT_TXN("微信支付交易单号"),
        ALIPAY_TXN("支付宝交易号"),
        MEITUAN_ORDER("美团订单号"),
        JD_ORDER("京东订单号"),
        TAOBAO_ORDER("淘宝订单号"),
        PDD_ORDER("拼多多订单号"),
        PDD_TXN("多多支付交易单号"),
        MERCHANT_ORDER("商户单号");

        /** 只有平台交易号能自动丢弃。商户单号不参与，见类注释。 */
        val authoritative: Boolean get() = this != MERCHANT_ORDER
    }

    data class Id(val kind: Kind, val value: String) {
        val key: String get() = kind.name + ":" + value
        val authoritative: Boolean get() = kind.authoritative
    }

    /**
     * 字段名 → 种类。**必须先有标签再取值**，不能只找长数字串。
     *
     * 只找长数字会命中一堆不该命中的东西：银行卡号、手机号、验证码、
     * 「2026年9月30日」这种日期、订单号。2026-09-30 微信账单详情页整屏就有
     * 交易单号、商户单号、订单里的 uuid、商品订单号好几个长串，没有标签根本分不出谁是谁。
     *
     * 顺序即优先级：[extract] 按这个顺序返回第一个命中的。
     * 平台交易号必须排在商户单号前面——微信一屏里两者同时出现，
     * 排错了就会拿交易号去当商户单号，然后因为"非权威"而放弃去重。
     */
    private val LABELS: List<Pair<String, Kind>> = listOf(
        "美团订单号" to Kind.MEITUAN_ORDER,
        "京东订单号" to Kind.JD_ORDER,
        "淘宝订单号" to Kind.TAOBAO_ORDER,
        "拼多多订单号" to Kind.PDD_ORDER,
        "多多支付交易单号" to Kind.PDD_TXN,
        "支付宝交易号" to Kind.ALIPAY_TXN,
        "微信支付交易单号" to Kind.WECHAT_TXN,
        "交易单号" to Kind.WECHAT_TXN,
        "转账单号" to Kind.WECHAT_TXN,
        "交易号" to Kind.ALIPAY_TXN,
        "商户单号" to Kind.MERCHANT_ORDER,
        "商家订单号" to Kind.MERCHANT_ORDER,
        "订单号" to Kind.MERCHANT_ORDER,
    )

    /** 值的最短长度。短于此的多半是日期、金额或验证码。 */
    private const val MIN_VALUE = 12

    /**
     * 一整行是不是**干净的标签行**。
     *
     * 允许两种形态：
     * ```
     * 交易单号                       <- 值在上一行（微信）
     * 交易单号：45000004862026...     <- 值在同一行（银行 App）
     * ```
     * 其余一律不算——「小红书订单: be1ac972-…」这种虽然含「订单」，
     * 但它不是标签行，里面那个 uuid 是商品 id，拿它去重会静默丢账。
     */
    private fun valueOnSameLine(line: String, label: String): String? {
        val head = line.trim()
        val rest = when {
            head == label -> ""
            head.startsWith(label) -> head.removePrefix(label)
            else -> return null
        }
        return clean(rest, if (label in setOf("美团订单号","京东订单号","淘宝订单号")) 10 else MIN_VALUE)
    }

    /** 值本身：去掉分隔符后，必须是一整串字母数字。 */
    private fun clean(raw: String, minValue: Int = MIN_VALUE): String? {
        val v = raw.trim().trimStart('：', ':', '=', ' ', '　').trim()
        if (v.length < minValue) return null
        if (!v.all { it.isLetterOrDigit() } && !Regex("\\d{6}-\\d{9,18}").matches(v)) return null
        return v
    }

    /**
     * 取标签所在行**附近**的号。同行没有就往上一行找，再没有才往下一行。
     *
     * ## 为什么是「上一行优先」而不是「下一行优先」
     *
     * 2026-09-30 23:34 真机读到的微信账单详情页，节点顺序是**值在标签前面**：
     * ```
     * T1312174297162407936 | 商户单号 | 4500000488202609303522863079 | 交易单号 | 支付方式
     * ```
     * 旧写法只认「标签后面紧跟数字」，于是：
     *  - `交易单号` 后面是「支付方式」→ 匹配不上，**权威号没抽到**；
     *  - `商户单号` 后面是 `4500000488…` → 匹配上了，**把交易号当成了商户单号**。
     *
     * 第二个错更隐蔽：它不是没抽到，是抽到了一个**贴错标签**的号。
     * 商户单号不参与自动丢弃，所以结果就是这一屏悄悄退回文本指纹，
     * 90 天的硬去重退化成 3 分钟——日志里只会看到「指纹 … 丢弃」，
     * 看起来像正常工作，实际上第 3 关从来没生效过。
     */
    private fun valueNear(lines: List<String>, index: Int, label: String): String? {
        valueOnSameLine(lines[index], label)?.let { return it }
        val minValue = if (label in setOf("美团订单号","京东订单号","淘宝订单号")) 10 else MIN_VALUE
        if (index > 0) clean(lines[index - 1], minValue)?.let { return it }
        if (index + 1 < lines.size) clean(lines[index + 1], minValue)?.let { return it }
        return null
    }

    /**
     * 从整屏文字里抠交易号。
     *
     * 返回**权威度最高的那一个**：平台交易号 > 商户单号。一屏里同时出现三个号很常见。
     */
    fun extract(text: String?): Id? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        for ((label, kind) in LABELS) {
            for (i in lines.indices) {
                // 先确认这一行确实是标签行，再去邻域取值。
                // 反过来做会在一屏里到处抓到不相干的数字。
                if (valueOnSameLine(lines[i], label) == null &&
                    !lines[i].trim().equals(label, ignoreCase = false)
                ) {
                    continue
                }
                val v = valueNear(lines, i, label) ?: continue
                return Id(kind, v)
            }
        }
        return null
    }

    /** 指定类型的有标签编号；跨平台关联不能把微信交易号当成商户订单号。 */
    fun ofKind(text: String?, kind: Kind): Id? {
        if (text.isNullOrBlank()) return null
        val lines = ScreenFields.collapse(text.lines())
        for ((label, candidate) in LABELS.filter { it.second == kind }) {
            val min = if (label in setOf("美团订单号","京东订单号","淘宝订单号") || kind == Kind.MERCHANT_ORDER) 10 else MIN_VALUE
            val value = ScreenFields.valueNear(lines, label, ScreenFields.orientation(lines)) {
                clean(it, min) != null
            }?.let { clean(it, min) } ?: continue
            return Id(candidate, value)
        }
        return null
    }

    /** 只要能自动丢弃的那个，没有就返回 null。调用点据此决定要不要换去重策略。 */
    fun authoritative(text: String?): Id? = extract(text)?.takeIf { it.authoritative }
}
