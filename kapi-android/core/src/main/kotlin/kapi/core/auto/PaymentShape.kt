package kapi.core.auto

/**
 * 读屏的**形状闸**：在把整屏文字交给解析器之前，先判断"这屏是一笔支付还是一屏记录"。
 *
 * ## 为什么需要它
 *
 * 通知栏那条路不需要：一条通知本来就是一条支付，文本短、格式固定。
 *
 * 但无障碍读屏看到的是**当前屏幕上的一切**。2026-09-30 真机实测，支付宝消息列表页
 * 每一条聊天消息都自带完整金额和"成功"字样——
 * 「闲鱼 | 自动扣款成功￥55.00」、「\*\*奇向你转账7000.00元」。原样喂给解析器，
 * 就在用户账本里生成了 55 元收入和 7000 元支出两条**根本不存在的**消费。
 *
 * ## 判据：数「带金额的短节点」
 *
 * 2026-09-30 真机原始节点（uiautomator dump 那个不能信，见下面的坑）：
 * ```
 * 转账成功页：回首页 / 回首页 / 完成 / 中国银行储蓄卡(0416) / 付款方式 /
 *            啦啦啦(朱娜娜) / 收款方 / 转账成功￥1.00          → 1 个
 * 消息列表页：…闲鱼 | 自动扣款成功￥55.00 / [转账]**奇向你转账7000.00元…  → 2 个
 * 资金往来页：¥3000.00 / ¥1500.00 / ¥1000.00                     → 3 个
 * 订单详情页：商品金额 ¥100.00 / 优惠 ¥20.00 / 运费 ¥8.00 / 实付 ¥88.00 → 0 个
 * ```
 *
 * 阈值取 2：结果页 1 个放行，两种列表页 2~3 个挡下。
 *
 * ## 两个设计上的坑（都踩过）
 *
 * 1. **不能只数「整段就是一个金额」的节点。** 首版就是这么写的，结果真机上
 *    「转账成功￥1.00」这种**状态词和金额长在同一个节点**的写法一个都不算，
 *    闸门永远不触发（2026-09-30 实测：8 个节点、0 个独立金额）。
 *    所以判据必须是「短节点里含金额」。
 *
 * 2. **必须排除带字段名的节点。** 订单详情页的四个金额都是「实付 ¥88.00」
 *    这种构成项，它们属于**同一笔**，数成 4 笔会把正常支付挡掉。
 *
 * 顺带一条：`uiautomator dump` 出来的节点划分**和无障碍原始树不一样**
 * ——它把「转账成功」和「1.00」拆成两个节点，原始树里是合在一起的。
 * 拿 dump 的坐标设计算法，调出来的阈值在真机上完全不成立。真实形状只能
 * 从服务自己的日志里读。
 */
object PaymentShape {

    /** 一个屏幕节点。只留文本——这个闸不看位置。 */
    data class Node(val text: String)

    sealed interface Verdict {
        /** 形状上像"一笔"。文字照原样交给 [PaymentGate] 判语义。 */
        data class OneTransaction(val text: String, val why: String) : Verdict

        /** 形状上就是"一屏记录"，不用往下走了。 */
        data class ManyTransactions(val why: String) : Verdict
    }

    /** 含金额形态。宽松即可，这里只用来数个数。 */
    private val AMOUNT = Regex(
        """[¥￥$]\s*\d+(?:[.,]\d{1,2})?""" +
            """|\d+(?:[.,]\d{1,2})?\s*元""" +
            """|(?i)(?:实付|实收|付款金额|支付金额|交易金额|消费金额|金额|合计|总计|订单金额)\s*[:：]?\s*\d+(?:[.,]\d{1,2})?""" +
            // 裸金额：微信账单详情页写的是 `-80.00`，没有 ¥ 也没有元（2026-09-30 真机录屏）。
            // 少了这一段，那一屏会被判成"整屏没有任何金额"直接丢掉——而它恰恰是
            // 现在最稳的入口。环视防的是把 `8.0.55` 这类版本号啃一段当金额。
            """|(?<![\d.])\d{1,8}\.\d{2}(?![\d.])""",
    )

    /**
     * **构成项**标签。带这些的节点是"同一笔的组成部分"，不是独立的一笔。
     *
     * 词表对齐 [kapi.core.llm.LocalTextParser] 的 FIELD_LABELS —— 那边用它们
     * 区分「字段名」和「值」，这里要排除的是同一批。抄一份不完整的会出事：
     * 漏了「余额」，银行交易详情页的「消费支出23.50元 / 余额1234.56元」
     * 就会被数成两笔，把一笔正常消费拦掉（2026-09-30 单测抓到）。
     *
     * ## 2026-09-30 23:12 之后为什么必须拆成两组
     *
     * 原来是一张 `FIELD_LABELS`，「实付」和「余额」放在一排。修钱包误判时发现
     * 这两类的**结论完全相反**：
     *
     * | 屏 | 命中的标签组 | 形状上是什么 | 该不该放行 |
     * |---|---|---|---|
     * | 订单详情 `实付¥88 / 优惠¥20 / 运费¥8` | 构成项 | **就是一笔** | 放行 |
     * | 微信钱包 `钱包¥1008.30` | 账户状态 | **一笔也没有** | 挡掉 |
     *
     * 合在一张表里就没法同时满足这两条：全排除会把订单详情页挡掉，
     * 全放行会让钱包余额变成 1008.30 的假账。所以拆成 [COMPONENT_LABELS]
     * 和 [ACCOUNT_LABELS]，并把「全是账户状态」当作**否定证据**用。
     */
    private val COMPONENT_LABELS = listOf(
        "实付", "实收", "付款金额", "支付金额", "交易金额", "消费金额",
        "金额", "合计", "总计", "订单金额", "商品金额",
        "原价", "运费", "配送费", "邮费", "手续费", "优惠",
    )

    /**
     * **账户状态**标签。带这些的节点说的是"账户里有多少钱"，不是"花了多少钱"。
     *
     * 2026-09-30 23:12 微信钱包页实测，整屏 27 个节点里带数字的只有这几个：
     * ```
     * 钱包¥1008.30
     * 零钱¥0.10
     * ```
     * 老词表里有「余额」但**没有「钱包」**，于是这一屏被判成"一笔 1008.30 的支出"，
     * 直接生成了一条草稿——用户的钱一分没花，账本里凭空多出 1008.30。
     *
     * 这类词的危害比漏判大得多：漏判只是少记一笔（用户会自己补），
     * 误判是**假账**（用户看不出，还会污染月度统计）。
     *
     * 词表的判据是「这个词出现时，后面的数字是账户里有多少钱，而不是花了多少钱」。
     * 「零钱」也在列：它作为支付方式出现时本身不带金额，作为余额出现时（零钱¥0.10）
     * 正是要挡掉的那一种。
     */
    private val ACCOUNT_LABELS = listOf(
        "余额", "钱包", "零钱", "零钱通", "余额宝", "资产", "总额", "可用",
        "信用额度", "额度", "授信", "待还", "欠款",
    )

    /**
     * 营销文案里带数字，但不是一笔消费。
     *
     * 2026-09-30 23:15 支付宝首页实测：
     * ```
     * 整屏有 2 个带金额的独立条目（闲鱼100元以下免密支付 / 支出55元）
     * ```
     * 「闲鱼100元以下免密支付」是首页的一张运营 banner，它把**真实的那一笔**
     * 挤过了 [MANY_THRESHOLD] 的判定线，于是这一屏被当成"一屏记录"整个挡掉。
     * 结果是**该记的没记**——比假账更难发现，因为界面上什么都不出现。
     *
     * 词表只收**只可能出现在营销位**的词，不收「优惠」「实付」这类
     * 在真实交易页里也出现的（那属于 [FIELD_LABELS] 的"构成项"）。
     */
    private val PROMO_LABELS = listOf(
        "免密支付", "免密", "立减", "满减", "领券", "券后", "限时", "秒杀",
        "拼团", "首单", "新人专享", "最高可省", "随机立减", "开卡", "抽红包",
    )

    /** 超过这个长度的节点是整条消息/整段描述，不是一笔。 */
    private const val MAX_SHORT_CHARS = 24

    /** 带金额的短节点多到这个数，就不是"一笔"了。 */
    private const val MANY_THRESHOLD = 2

    /** 带金额的短节点里，一个都没被任何标签解释掉——这才是"一笔"的实体。 */
    private fun List<String>.noLabelIn() = filter { t ->
        COMPONENT_LABELS.none { t.contains(it) } &&
            ACCOUNT_LABELS.none { t.contains(it) } &&
            PROMO_LABELS.none { t.contains(it) }
    }

    fun inspect(nodes: List<Node>): Verdict {
        val texts = nodes.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return Verdict.ManyTransactions("屏幕上没有可读文字")

        // 只看短节点里的金额。长的多半是整条消息/整段描述。
        val amountNodes = texts.filter { t ->
            t.length <= MAX_SHORT_CHARS && t.any { it.isDigit() } && AMOUNT.containsMatchIn(t)
        }
        val plain = amountNodes.noLabelIn()
        val component = amountNodes.filter { node -> COMPONENT_LABELS.any { node.contains(it) } }

        if (plain.size >= MANY_THRESHOLD) {
            return Verdict.ManyTransactions(
                "整屏有 ${plain.size} 个带金额的独立条目（" +
                    plain.joinToString(" / ") { it.take(16) } +
                    "），这是一屏记录不是一笔支付",
            )
        }

        if (plain.size == 1) {
            return Verdict.OneTransaction(
                texts.joinToString("\n"),
                "形状像一笔：${texts.size} 个节点，${plain.size} 个带金额条目" +
                    "（${plain.first().take(14)}）",
            )
        }

        // 一个"不带任何标签的金额节点"都没有。分两种情况，结论相反。
        if (component.isNotEmpty()) {
            // 订单/账单详情页：实付、优惠、运费、合计是**同一笔**的构成项。
            return Verdict.OneTransaction(
                texts.joinToString("\n"),
                "形状像一笔：${texts.size} 个节点，金额是 ${component.size} 个构成项" +
                    "（${component.joinToString("/") { it.take(14) }}）",
            )
        }

        if (amountNodes.isNotEmpty()) {
            // 屏上确实有金额，但每一个都落在「账户状态」或「促销位」上：
            // 微信钱包页的 `钱包¥1008.30` / `零钱¥0.10`、支付宝的 `闲鱼100元以下免密支付`。
            // 这一屏**一笔也没有**，放行就是把余额记成了假账。
            //
            // 早先的写法是 `money.isEmpty() && !hasAmount(joined)` 才挡，
            // 而 [PaymentGate.hasAmount] 只认金额形态、不认标签，于是
            // `钱包¥1008.30` 被放行成了 OneTransaction —— 词表加对了，
            // 兜底那道门却又把它放了回来。这是 2026-09-30 23:12 真实发生的假账。
            return Verdict.ManyTransactions(
                "整屏 ${amountNodes.size} 个金额都落在账户状态或促销位上" +
                    "（${amountNodes.joinToString("/") { it.take(16) }}），屏上没有任何一笔消费",
            )
        }

        val joined = texts.joinToString("\n")
        if (!PaymentGate.hasAmount(joined)) {
            return Verdict.ManyTransactions("整屏 ${texts.size} 个节点，没有任何金额")
        }

        return Verdict.OneTransaction(
            joined,
            "形状像一笔：${texts.size} 个节点，金额只在长句里（无独立短节点）",
        )
    }
}
