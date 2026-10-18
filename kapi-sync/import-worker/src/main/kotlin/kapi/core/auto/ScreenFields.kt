package kapi.core.auto

/**
 * 从读屏行序列里按「字段标签」取值。
 *
 * ## 为什么不能写死「值在标签前」或「值在标签后」
 *
 * **两种方向在真机上都存在，而且同一台手机同一天都出现。** 2026-10-02 realme
 * 采集（mcode 副手，dump 在 /tmp/kapi-retest/mcode/）：
 *
 * ```
 * 01 收款到余额宝（服务顺序，OwnAccount KDoc 原文）：
 *    转账收款到余额宝 / 商品说明 / 账户余额 / 付款方式 / …   ← 值在标签【前】
 * 03 招行还款（uiautomator 树序）：
 *    付款方式 / 余额宝 / 还款到 / 招商银行（9590）洪佳豪      ← 值在标签【后】
 * ```
 *
 * 节点顺序是对方 App 渲染实现和我们的遍历顺序共同决定的，不是契约。
 * [PaymentId.valueNear] 早就用「同行 → 上一行 → 下一行」兜住了这个坑；
 * 这里把问题再往前推一步：**先用页面自己的日期行探明方向，再按同一方向取所有字段**。
 *
 * 探针：`创建时间` / `支付时间` 标签的邻居里必有一行日期（`2026-10-01 08:54:54`）。
 * 日期在标签前 ⇒ 整页值在前；在后 ⇒ 值在后。支付宝详情页每页都带时间字段，
 * 这个探针几乎不落空；落空时退回「先上一行后下一行」，和 [PaymentId.valueNear] 同款。
 *
 * 只服务 core 内的两处：[OwnAccount]（自转账两端）和 [NoteComposer]（备注合成）。
 */
object ScreenFields {

    /** 字段值的排列方向，由 [orientation] 探出。 */
    enum class Orientation {
        /** 值在标签的上一行（服务遍历顺序，OPPO 时代微信/转账页）。 */
        VALUE_BEFORE_LABEL,

        /** 值在标签的下一行（2026-10-02 realme 支付宝富账单详情页）。 */
        VALUE_AFTER_LABEL,
    }

    /**
     * 已知字段标签。邻居命中这些行说明走错了方向或取到了别的字段的值，
     * 不能当值用（[isUsableValue] 的否定清单）。
     */
    val FIELD_LABELS = listOf(
        "付款方式", "收款方式", "付款账户", "收款账户", "收款方", "对方账户",
        "商品说明", "红包说明", "扣款说明", "转账备注", "还款到", "借还款说明",
        "创建时间", "支付时间", "处理进度", "进度说明", "支付奖励",
        "关联记录", "账单分类", "标签", "当前状态", "备注",
        "订单号", "商家订单号", "交易单号", "商户单号", "商品", "缴费说明",
        "支付方式", "商户全称", "收款方全称", "商户名称", "商家名称", "收单机构", "转账时间", "转账单号", "收款时间",
    )

    /** 界面按钮/入口，出现在字段邻居位置时同样不能当值。 */
    private val CHROME_LINES = listOf(
        "更多", "账单管理", "查看详情", "查看关联记录", "我的消费图鉴",
        "再转一笔", "AA收款", "查看往来记录", "往来流水证明", "申请电子回单",
        "对此订单有疑问", "申请商家售后", "添加", "开关", "请选择", "回首页", "完成",
        "商家小程序", "商家电话", "联系商家", "申请电子凭证", "为您推荐", "计入收支",
    )

    private val DATE_RX = Regex("""\d{4}[-年/.]\s*\d{1,2}[-月/.]\s*\d{1,2}""")

    /** 支付宝商品图的资源 ID 行（`O1CN01…_!!…`），贴在商品名旁边，不能当商品。 */
    private val IMAGE_JUNK_RX = Regex("""O1CN|_!!|https?://""")

    /** 折叠相邻且完全相同的行——无障碍树会把一部分节点报两遍（见 [PaymentShape]）。 */
    fun collapse(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (out.lastOrNull() == t) continue
            out.add(t)
        }
        return out
    }

    /**
     * 探这一页的值方向。探不到（屏上没有带日期的时间字段）返回 null，
     * 调用方退回「先上一行后下一行」。
     */
    fun orientation(lines: List<String>): Orientation? {
        for (i in lines.indices) {
            val l = lines[i]
            if (l !in setOf("创建时间", "支付时间", "付款时间", "转账时间", "退款时间", "退款成功时间", "到账时间", "收款时间")) continue
            val prev = lines.getOrNull(i - 1).orEmpty()
            val next = lines.getOrNull(i + 1).orEmpty()
            return when {
                DATE_RX.containsMatchIn(prev) -> Orientation.VALUE_BEFORE_LABEL
                DATE_RX.containsMatchIn(next) -> Orientation.VALUE_AFTER_LABEL
                else -> null
            }
        }
        return null
    }

    /**
     * 取 [label] 字段的值。
     *
     * 三种写法按序支持：同行（`交易单号：xxx`，银行 App 常见）、
     * 方向邻行（[orientation] 探出的那侧优先）、反方向兜底。
     * 候选必须过 [isUsableValue]；不可用就试反方向，两边都不行返回 null。
     */
    fun valueNear(lines: List<String>, label: String, orientation: Orientation?, acceptValue: (String) -> Boolean = ::isUsableValue): String? {
        for (i in lines.indices) {
            val l = lines[i]
            // 同行写法（`交易单号：xxx` / `付款方式 零钱`）必须带分隔符——
            // 「收款方全称」这种**连写的另一个标签**不算（2026-10-02 审计抓到：
            // 前缀匹配把「全称」咬出来当成了对方名字，备注成了「转给全称」）。
            if (l.startsWith(label) && l.length > label.length) {
                val sep = l[label.length]
                if (sep == '：' || sep == ':' || sep == ' ') {
                    val rest = l.substring(label.length + 1).trim()
                    if (acceptValue(rest)) return rest
                }
            }
            if (l != label) continue
            val first = when (orientation) {
                Orientation.VALUE_BEFORE_LABEL -> i - 1
                Orientation.VALUE_AFTER_LABEL -> i + 1
                null -> i - 1
            }
            for (j in intArrayOf(first, 2 * i - first)) {
                val c = lines.getOrNull(j)?.trim().orEmpty()
                if (acceptValue(c)) return c
            }
        }
        return null
    }

    /** Keep both the visible store and original legal name when one label occurs twice. */
    fun valuesNear(lines:List<String>,label:String,orientation:Orientation?):List<String> = lines.indices.mapNotNull { i ->
        val line=lines[i]
        if(line.startsWith(label) && line.length>label.length && line[label.length] in " :：") {
            line.substring(label.length+1).trim().takeIf(::isUsableValue)
        } else if(line==label) {
            val first=if(orientation==Orientation.VALUE_AFTER_LABEL)i+1 else i-1
            listOf(first,2*i-first).firstNotNullOfOrNull { j -> lines.getOrNull(j)?.trim()?.takeIf(::isUsableValue) }
        } else null
    }.distinct()

    /**
     * 一行能不能当字段值。否定清单制：标签行、按钮行、日期行、图片资源行不行，
     * 其余（含掩码账户名 `盖被吱 181******39`）都行——掩码正是我们要进备注的名字。
     *
     * 日期守卫只拒**短行**（≤24 字）：真机时间戳 `2026-10-01 08:54:54` 是 19 字，
     * 而好医保的商品名 `2026.9月保费缴清-好医保·长期医疗…` 以日期开头却长 30+ 字
     * （2026-10-02 真机）——按行长区分，长的当内容放行。
     */
    fun isUsableValue(line: String): Boolean {
        if (line.isEmpty()) return false
        if (FIELD_LABELS.any { line == it }) return false
        if (CHROME_LINES.any { line == it }) return false
        if (line.length <= 24 && DATE_RX.containsMatchIn(line)) return false
        if (IMAGE_JUNK_RX.containsMatchIn(line)) return false
        return true
    }
}
