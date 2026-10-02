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

    /**
     * 这个金额是不是某个**构成项标签**的值。
     *
     * 2026-10-01 realme 支付宝订阅订单详情页（`TokenPlanMax-年度会员`，
     * 上海稀宇科技有限公司）被整屏挡掉：
     * ```
     * 整屏有 3 个带金额的独立条目（508.61 / 508.61 / 支出507.79元）
     * ```
     * `交通银行立减金 -0.82` 已经被调整项那条规则正确排除了，剩下这三个才是问题：
     * `订单金额` 自己命中 [COMPONENT_LABELS] 进了 component，可它的**值**
     * `508.61` 被拆到了另一个节点上、自身不带任何标签，[noLabelIn] 判它"无标签"，
     * 于是被当成**独立的一笔**。结构上这是 [isAdjustmentAmount] 的翻版：
     * 标签和值落在了两个节点。
     *
     * 方向不一样：调整项是**值在前、标签在后**（[isAdjustmentAmount]），
     * 构成项是**标签在前、值在后**。两边都得判，缺一个就漏一种写法。
     */
    private fun isComponentValue(idx: Int, texts: List<String>): Boolean {
        val prev = texts.getOrNull(idx - 1) ?: return false
        if (COMPONENT_LABELS.any { texts[idx].contains(it) }) return false
        return COMPONENT_LABELS.any { prev.contains(it) }
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

    /**
     * **金额调整项**标签。带这些的金额是**这一笔自己的加减法**，不是另一笔记录。
     *
     * 2026-10-01 realme 真机：支付宝支付详情页上并排两个金额——
     * ```
     * 微雪**店
     * 支出244.93元        ← 订单金额
     * 等待确认收货
     * 交通银行立减金        ← 这一行是**标签**
     * -1.98               ← 金额在**下一个节点**，自己不带任何标签
     * 支付时间
     * ```
     * `交通银行立减金` 本身因为命中 [PROMO_LABELS] 的「立减」被排除掉了，
     * 而 `-1.98` 是**另一个节点**、不含任何词，`noLabelIn` 判它"无标签"，
     * 于是 plain 数到 2，撞上 [MANY_THRESHOLD]，**一笔真实的支付被当成一屏记录挡掉**。
     *
     * 金额和对方怎么拆分节点有关，而那是渲染实现细节——我们不能假设
     * 「立减金」和「1.98」一定在同一个节点里。
     *
     * ## 为什么单独一档，不并进 [COMPONENT_LABELS] 或 [PROMO_LABELS]
     *
     * 构成项（实付/运费/合计）带在**金额自己那个节点**上；
     * 促销位（免密/首单/秒杀）是**营销文案**，它的数字不属于任何一笔账。
     * 而调整项是第三种：**它属于这一笔，但标签和值被拆到了两个节点**。
     * 三者判定方式不同，混在一起就会出现「支付宝首页那笔 55 元被旁边的
     * 免密 banner 连坐」这种反向误杀（见 RealScreenFalsePositiveTest）。
     */
    private val ADJUSTMENT_LABELS = listOf(
        "立减金", "立减", "优惠", "红包", "优惠券", "折扣", "津贴", "返现", "抵扣",
    )

    /**
     * 这个金额是不是某个调整项的值。
     *
     * **只看后一行**（值在前、标签在后），这是本树的真实形状。
     * 2026-10-01 realme 支付宝支付详情页，服务自己打出来的原始行序：
     * ```
     * -1.98 │ 交通银行立减金 │ 极速付款 │ 管理极速付款 │ 等待确认收货 │
     * 支出244.93元 │ 微雪**店
     * ```
     * 和 [PaymentId] 类注释里记的一致：**值在标签前面**。
     *
     * ## 这里的坑：不能用 `uiautomator dump` 的顺序
     *
     * 同一屏，`uiautomator dump` 给的顺序是**反的**（它按布局从上到下排，
     * 原始树按 DOM）。照着 dump 的顺序写单测，测试会绿，真机不生效——
     * 2026-10-01 第一次修就是这么白跑一轮。判邻接关系只能用服务自己打的
     * `原始行序` 那行日志。
     *
     * 前一行那个方向**不能**判，否则会连坐：
     * ```
     * 支出55元 │ 交通银行立减金 │ -1.98
     * ```
     * `支出55元` 的下一行是调整项标签，它会被一起摘出去，整屏就只剩一个
     * 「立减金」，落到"没有任何一笔消费"那一档。
     * `RealScreenFalsePositiveTest` 里 `a discount label does not swallow the
     * neighbouring real spend` 钉的就是这一屏。
     */
    private fun isAdjustmentAmount(idx: Int, texts: List<String>): Boolean {
        val next = texts.getOrNull(idx + 1) ?: return false
        return ADJUSTMENT_LABELS.any { next.contains(it) }
    }

    /**
     * 某一行的下一行是不是调整项标签——用来把「标签行」也一起摘掉。
     *
     * 只判「这一行本身是调整项标签」，不判金额：金额那一行由
     * [isAdjustmentAmount] 负责。两者合起来才是一整条调整项记录。
     */
    private fun isAdjustmentLabel(idx: Int, texts: List<String>): Boolean =
        ADJUSTMENT_LABELS.any { texts[idx].contains(it) }

    /**
     * 红包**页**的结构性标志。
     *
     * 2026-10-02 真机（用户现场翻到「支付宝红包」详情页）：整页被形状闸挡掉——
     * 「支出0.1元」的邻居行含「红包」，被 [isAdjustmentAmount] 当成
     * "这笔交易的红包抵扣"剔掉，plain 归零。但在这类页上**红包是交易类型
     * 本身**（标题 支付宝红包 / 字段 红包说明 / 说明值 发普通红包），
     * 不是哪一笔的折扣明细。
     *
     * 只收**结构性**的字样（标题/专属字段/说明值），不收「红包」单词——
     * 支付页上的「红包抵扣 -0.5」仍然要按调整项剔（那是这笔的加减法）。
     * 两者的区别是页面上有没有这些专属结构，而不是红包二字出没出现。
     */
    private val RED_PACKET_PAGE_WORDS = listOf(
        "支付宝红包", "红包说明", "发普通红包", "发口令红包",
    )

    /** 这一屏是不是红包的详情/结果页（两个消费方：[inspect] 与 [withoutAdjustmentRows]）。 */
    fun isRedPacketPage(text: String): Boolean =
        RED_PACKET_PAGE_WORDS.any { text.contains(it) }

    /**
     * 剔掉调整项行（标签 + 它那个金额），剩下的才是"页面本身在讲什么"。
     *
     * ## 支付门为什么也需要它
     *
     * 形状闸数的是"有几个独立记录"，剔掉调整项就够。但 [PaymentGate] 的促销词
     * 检查是**对整屏做子串匹配**的，于是支付详情页上那一行
     * `交通银行立减金` 会把整屏判成促销页：
     * ```
     * 丢弃[A11Y/com.eg.android.AlipayGphone]：出现了促销词「立减」
     * ```
     * 而「这一笔减了 1.98」是**这一笔的明细**，它不说明页面性质。
     *
     * 所以同一份"哪些行属于调整项"的判断要给两道门共用——它们问的问题不同
     * （一个问"有几笔"，一个问"这是不是促销页"），但前提是同一件事：
     * **立减金是这一笔的一部分，不是另一个页面。**
     *
     * 红包页整页豁免（[isRedPacketPage]）：剔行会把金额行本身删掉
     * （「支出0.1元」的下一行含「红包」），门直接死于"没有金额"。
     */
    fun withoutAdjustmentRows(text: String): String {
        if (isRedPacketPage(text)) return text
        val lines = text.lines()
        if (lines.size < 2) return text
        val drop = HashSet<Int>()
        for (i in lines.indices) {
            if (isAdjustmentLabel(i, lines)) drop.add(i)
            if (isAdjustmentAmount(i, lines)) drop.add(i)
        }
        if (drop.isEmpty()) return text
        return lines.filterIndexed { i, _ -> i !in drop }.joinToString("\n")
    }

    /** 超过这个长度的节点是整条消息/整段描述，不是一笔。 */
    private const val MAX_SHORT_CHARS = 24

    /** 带金额的短节点多到这个数，就不是"一笔"了。 */
    private const val MANY_THRESHOLD = 2

    /** 含金额的**短节点**里，一个都没被任何标签解释掉——这才是"一笔"的实体。 */
    private fun List<String>.noLabelIn() = filter { t ->
        COMPONENT_LABELS.none { t.contains(it) } &&
            ACCOUNT_LABELS.none { t.contains(it) } &&
            PROMO_LABELS.none { t.contains(it) }
    }

    /**
     * 折叠**相邻且完全相同**的重复行。
     *
     * 无障碍的原始树会把一部分节点报**两遍**（父容器和它自己的 TextView 各报一次），
     * 2026-10-01 realme 上抓到的支付宝转账详情页原样长这样：
     * ```
     * 盖被吱 181\*\*\*\*\*\*39
     * 盖被吱 181\*\*\*\*\*\*39     ← 同一个节点
     * 对方账户
     * 转账
     * 转账                     ← 同一个节点
     * 2026-10-01 08:54:39
     * 2026-10-01 08:54:39       ← 同一个节点
     * ```
     * 数记录的时候它们是**同一个**东西，数成两笔就撞上 [MANY_THRESHOLD]。
     *
     * 只折叠**相邻**的：账单列表里两笔同额消费（比如两笔 10.00）中间隔着商户名，
     * 不相邻。而"紧挨着的两个一模一样的节点"在真机上只有一个解释——报了两遍。
     *
     * 这一步放在**最前面**，后面的邻接判断（[isAdjustmentAmount]、
     * [isComponentValue]）拿到的才是干净的相邻关系。
     */
    private fun collapseConsecutiveDuplicates(texts: List<String>): List<String> {
        if (texts.size < 2) return texts
        val out = ArrayList<String>(texts.size)
        for (t in texts) {
            if (out.lastOrNull() != t) out.add(t)
        }
        return out
    }

    /**
     * 金额出现的位置是不是**散文里的一部分**。
     *
     * 2026-10-01 realme 支付宝消息列表页的假账就是这么来的：
     * ```
     * 我的 / 消息 / 9 / 视频 / 理财 / 首页 / 关闭
     * 1074 / 小李搞钱进行时 / 以后还是老老实实用苹果吧
     * 明明白白胡萝卜
     * 突然到账42876元，差点以为是诈骗😅     ← 这一行
     * 7347 / 旅行收藏夹 / 据说这里是二次元的圣地，南宁最潮的楼…
     * ```
     * `突然到账42876元` 被当成一笔收入，而 `到账` 又是收入词词表里的强信号，
     * 两道门一起放行，账本里多出 **42876 元**的收入。
     *
     * 那一屏是**消息列表**——每行是「未读数 / 发送者 / 预览 / 时间」，
     * 金额只是别人消息里提到的一个数字。
     *
     * 真正的交易行长什么样，逐条核过真机样本，**没有一个带散文标点**：
     * ```
     * 支出244.93元        支付宝支付详情
     * 支出10.1元          支付宝账单详情
     * -18.00              微信商家账单详情
     * -0.10               微信转账详情
     * 待橘生收款¥0.10      微信转账摘要
     * ```
     * 「标签 值」是标签在前，值在分隔符后面，不会有第二个逗号。
     * 所以：**节点里同时出现金额和全角逗号/句号 ⇒ 它是一句话，不是一行账。**
     */
    private fun isProseAmount(text: String): Boolean =
        AMOUNT.containsMatchIn(text) && PROSE_PUNCT.any { text.contains(it) }

    private val PROSE_PUNCT = listOf('，', '。', '！', '？', '；', '、')

    fun inspect(nodes: List<Node>): Verdict {
        val texts = collapseConsecutiveDuplicates(
            nodes.map { it.text.trim() }.filter { it.isNotEmpty() },
        )
        if (texts.isEmpty()) return Verdict.ManyTransactions("屏幕上没有可读文字")

        // 只看短节点里的金额。长的多半是整条消息/整段描述。
        // **带下标**：调整项要靠相邻行判定（见 isAdjustmentAmount）。
        val amountIdx = texts.withIndex()
            .filter { (_, t) ->
                t.length <= MAX_SHORT_CHARS && t.any { it.isDigit() } && AMOUNT.containsMatchIn(t)
            }
            .map { it.index to it.value }
        // 散文里的金额（消息预览、推送文案）不是"一行账"，先摘出去。
        val amountNodes = amountIdx.filterNot { (_, t) -> isProseAmount(t) }.map { it.second }

        // 调整项（立减金/优惠/红包…）的金额先摘出去——它是这一笔的加减法，
        // 留在里面会被数成第二笔记录，把真实的支付整屏挡掉。
        // 构成项（订单金额/合计…）的值同理：标签在上一行、值在下一行，
        // 那个值自己不带任何词，不摘就会被当独立一笔（见 isComponentValue）。
        // **红包页豁免**：红包页上「红包」是交易类型，金额就是这一笔——
        // 见 [isRedPacketPage] 的 KDoc（2026-10-02 真机，用户现场翻到）。
        val redPacketPage = isRedPacketPage(texts.joinToString("\n"))
        val explained = if (redPacketPage) {
            emptySet()
        } else {
            amountIdx
                .filter { (i, _) -> isAdjustmentAmount(i, texts) || isComponentValue(i, texts) }
                .map { it.second }.toSet()
        }
        val plain = amountNodes.filterNot { it in explained }.noLabelIn()
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

        // 全文里确实有金额，但**每一处都在句子里**（消息预览、推送文案）。
        // 下面那个"金额只在长句里"的兜底就是为这一屏写的，可它分不清
        // 「一句描述里提到了金额的真交易」和「消息列表里别人提到的一个数字」——
        // 2026-10-01 realme 支付宝消息列表就是这么被记成了 42876 元收入。
        //
        // 真机五个真实交易样本的金额节点没有一个带散文标点，所以这里直接挡。
        if (amountNodes.isEmpty() && texts.any { isProseAmount(it) }) {
            return Verdict.ManyTransactions(
                "整屏的金额全都出现在句子里（消息预览/推送文案），没有一行是账",
            )
        }

        return Verdict.OneTransaction(
            joined,
            "形状像一笔：${texts.size} 个节点，金额只在长句里（无独立短节点）",
        )
    }
}
