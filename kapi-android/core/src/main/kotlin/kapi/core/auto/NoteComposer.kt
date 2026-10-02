package kapi.core.auto

/**
 * 从读屏原文**合成备注**。
 *
 * ## 产品语义（用户 2026-10-02 原话）
 *
 * 「这次支付是淘宝买东西，能备注上淘宝平台消费；如果是餐饮，支付的账单中
 * 有店名，最好也能一起备注。」——备注是**给三个月后的对账看的**：这笔钱
 * 花在哪个平台/谁身上/买了什么。金额、方向、分类都不缺信号，唯独这一块
 * 之前永远是空的（草稿 `note = null`，全靠手填）。
 *
 * ## 规则（按真机 6 页 + 历史页样本钉出，2026-10-02）
 *
 * | 屏 | 证据 | 备注 |
 * |---|---|---|
 * | 转给啦啦啦 -10000 | `对方账户` + `付款方式`都在 | `转给啦啦啦(朱娜娜)` |
 * | 收到盖被吱 +12100 | `对方账户`在、`付款方式`**缺席** | `收到盖被吱转账` |
 * | 先用后付（杭州淘宝通信…） | 商户/原文含「淘宝」+ `商品说明` | `淘宝平台消费·先用后付` |
 * | NYCD 皮带轮 | `交易详情`块里商品名贴在`共1件`前 | 商品名（截 30 字） |
 * | 闲鱼 TP-LINK 摄像头 | 原文含「闲鱼」+ 商品说明 | `闲鱼平台消费·TP-LINK…` |
 * | 好医保保费 | `交易详情`→图片行→商品名（无数量行） | 商品名（截 30 字） |
 * | 退款（学而思 184.47） | `退款成功`，无对象无商品 | `退款` |
 * | 收款到余额宝 / 信用卡还款 | —— | null（OwnAccount 拦截，不进草稿） |
 *
 * 取值一律走 [ScreenFields]（方向探针），不自己扫邻居——01 页值在标签前、
 * 03 页值在标签后，写死任何一侧都会在另一半页面上静默取错。
 *
 * ## 刻意不做的
 *
 * - **不猜**。没有平台词、没有商品行、不是转账的屏（瑞幸扫码付这类）备注
 *   就是 null——编一句「消费」出来是噪音，用户自己一秒能补。
 * - **不用详情页标题行当店名**。标题对淘宝系商户会掩码成 `ny**店`
 *   （2026-10-02 实测），零信息量还误导；店名已经进了商户字段，备注里
 *   只放有增量的信息（平台、商品、对象）。
 * - **不碰「扣款说明」**。真机值是「先用后付订单完成付款，已计入芝麻分评估」
 *   ——是状态说明不是商品描述，进备注全是噪音。
 */
object NoteComposer {

    /**
     * 平台词 → 备注「X平台消费」。只收**平台**名字，不收商户字号——
     * 「兰州拉面」里没有平台；判定是子串包含，出现在原文任何位置都算
     * （商户「杭州淘宝通信技术有限公司」、商品「淘宝心选…」都该命中）。
     */
    private val PLATFORMS = listOf(
        "淘宝", "天猫", "闲鱼", "京东", "拼多多", "美团", "饿了么",
        "抖音", "盒马", "飞猪", "携程", "12306", "小红书",
    )

    /** 微信转账详情页标题里的对象：「转账-转给加油无忧无虑」/「转账-来自view」。 */
    private val TITLE_TO = Regex("""转账-转给(.{1,20})""")
    private val TITLE_FROM = Regex("""转账-来自(.{1,20})""")

    /** 对方账户值尾部的掩码号（`啦啦啦(朱娜娜) 153******10` → 只留名字）。 */
    private val MASK_TAIL = Regex("""\s+[0-9*]{6,}$""")

    private val QTY_MARKER = Regex("""^共\d+件$""")

    /** 说明这笔钱**已经退了**的状态词；入口按钮（申请/查看退款）不算。 */
    private val REFUND_STATUS = listOf("退款成功", "全额退款", "已退款", "退款方式")

    /** 备注上限。再长确认页放不下，也没有对账价值。 */
    private const val MAX_CHARS = 30

    /**
     * 合成备注；没有把握时返回 null（调用方照旧存空备注）。
     *
     * @param rawText 无障碍服务的整屏文字（行序 = 树序，可能带重复节点）
     */
    fun compose(rawText: String): String? {
        val lines = ScreenFields.collapse(rawText.lines())
        if (lines.isEmpty()) return null
        val orientation = ScreenFields.orientation(lines)
        fun field(label: String) = ScreenFields.valueNear(lines, label, orientation)

        // 1. 转账：对方是谁，比平台/商品优先——转账页的「商品说明」可能是
        //    「转账收款到余额宝」这类功能描述，不是商品。
        //    微信转账详情页没有「对方账户」字段，对象只在**标题行**里
        //    （2026-10-02 OPPO 真机：「转账-转给加油无忧无虑」「转账-来自view」）。
        val counterparty = (field("对方账户") ?: field("收款方"))?.replace(MASK_TAIL, "")?.trim()
        if (!counterparty.isNullOrEmpty()) {
            val paidByMe = field("付款方式") != null
            return if (paidByMe) "转给$counterparty" else "收到${counterparty}转账"
        }
        TITLE_TO.find(rawText)?.let { return "转给${it.groupValues[1].trim()}" }
        TITLE_FROM.find(rawText)?.let { return "收到${it.groupValues[1].trim()}转账" }

        // 2. 退款页（2026-10-02 真机样本：`退款成功 / 退款方式 / 查看原账单`，
        //    没有商品说明也没有对方账户）。原单商户拿不到，只标「退款」——
        //    方向和金额已经说明这是钱回来了，备注点到为止。
        //    **判据必须是状态词**：普通支出页上长着「申请退款/查看退款」入口
        //    按钮（2026-10-02 审计，燃气/药店/餐饮页），单词「退款」会把它们
        //    全部误标。
        val refunded = REFUND_STATUS.any { rawText.contains(it) }
        if (refunded) return "退款"

        // 3. 平台消费：淘宝买东西 → 「淘宝平台消费」，带商品说明就续在后面。
        val product = productOf(lines, ::field)
        val platform = PLATFORMS.firstOrNull { rawText.contains(it) }
        val base = when {
            platform != null -> product?.let { "${platform}平台消费·${shorten(it)}" } ?: "${platform}平台消费"
            product != null -> shorten(product)
            else -> null
        }

        // 4. 自动续费前缀：周期性扣款是对账时最想先看到的属性
        // （2026-10-02 用户口头描述就是「小佩自动续费」）。页面证据：
        // `管理自动续费` / `XXX自动续费`（状态词 自动扣款成功 的页不含它，
        // 别拿状态词判）。没有商品/平台时单独「自动续费」也比空着有用。
        return when {
            !rawText.contains("自动续费") -> base
            base != null -> "自动续费·$base"
            else -> "自动续费"
        }
    }

    /**
     * 商品名的来源，按可信度：
     * 1. `商品说明` 字段的值（绝大多数订单页有）
     * 2. `缴费说明` 字段的值——**缴费类页面没有商品说明**，这一栏是它的对应物
     *    （2026-10-02 真机水费页：`缴费说明 = 水费-碧桂****务集团股份有限公司…`；
     *    燃气小程序页有商品说明，水费老页面只有缴费说明，备注因此空了一笔）
     * 3. `共N件` 数量行的**上一行**（收起态订单页的「交易详情」块）
     * 4. `交易详情` 标签 → 商品图资源 ID → **商品名**（保险页没有数量行标记；
     *    图片行本身已被 [ScreenFields.isUsableValue] 拒掉，所以直接找图片行的下一行）
     */
    private fun productOf(lines: List<String>, field: (String) -> String?): String? {
        field("商品说明")?.let { return it }
        // 微信的字段名是「商品」（2026-10-02 OPPO 真机：商品=云AFM6585-奥斯迪
        // 商务中心A座（沃尔玛），停车场的车牌照+位置，进备注正合适）
        field("商品")?.let { return it }
        field("缴费说明")?.let { return it }
        for (i in lines.indices) {
            if (QTY_MARKER.matches(lines[i])) {
                val prev = lines.getOrNull(i - 1)?.trim().orEmpty()
                if (ScreenFields.isUsableValue(prev)) return prev
                return null
            }
        }
        for (i in 1 until lines.size - 1) {
            if (!IMAGE_JUNK.containsMatchIn(lines[i])) continue
            if (lines[i - 1] != "交易详情") continue
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (ScreenFields.isUsableValue(next)) return next
            return null
        }
        return null
    }

    private val IMAGE_JUNK = Regex("""O1CN|_!!|https?://""")

    private fun shorten(s: String): String =
        if (s.length <= MAX_CHARS) s else s.take(MAX_CHARS) + "…"
}
