package kapi.core.auto

import java.math.BigDecimal

/** 美团已支付订单和完成退款的窄入口。商品页、列表和未支付订单不捕获。 */
object MeituanOrder {
    const val PACKAGE = "com.sankuai.meituan"
    data class Detail(val id: String?, val merchant: String, val amountMinor: Long,
        val time: String, val timeSource: String, val product: String, val status: String,
        val paymentMethod: String?, val rideStartedAt: String? = null, val rideDuration: String? = null,
        val income: Boolean = false) {
        fun canonicalText(): String = buildString {
            appendLine("账单详情")
            appendLine("订单信息")
            if (id != null) { appendLine("美团订单号"); appendLine(id) }
            if (rideStartedAt != null) {
                appendLine("订单详情"); appendLine("本次骑行")
                appendLine("骑行开始时间"); appendLine(rideStartedAt)
                appendLine(rideDuration)
            }
            appendLine("当前状态")
            appendLine(if (income) "退款成功" else "交易成功")
            appendLine("订单状态")
            appendLine(status)
            appendLine("${if (income) "收入" else "支出"}${BigDecimal.valueOf(amountMinor, 2).toPlainString()}元")
            appendLine(if (income) "退款金额" else "实付金额")
            appendLine("¥${BigDecimal.valueOf(amountMinor, 2).toPlainString()}")
            appendLine("商户全称")
            appendLine(merchant)
            appendLine("商品说明")
            appendLine(product)
            appendLine(if (income) "退款时间" else if (timeSource == "付款时间") "支付时间" else "创建时间")
            appendLine(time)
            appendLine("时间来源")
            appendLine(timeSource)
            paymentMethod?.let { appendLine(if (income) "退款方式" else "付款方式"); appendLine(it) }
            if (income) {
                appendLine("退款进度"); appendLine("退款完成"); appendLine(time)
                appendLine("退款至"); appendLine(paymentMethod)
            }
        }.trim()
    }

    private val settled = setOf("已使用", "已消费", "订单已完成", "已完成", "放映结束", "出票成功", "已支付", "支付成功", "待使用", "交易成功")
    private val blocked = setOf("待付款", "待支付", "未支付", "订单已取消", "已取消", "交易关闭", "已退款", "退款成功", "退款完成", "退款中")
    private val date = Regex("""^\d{4}[-/]\d{2}[-/]\d{2}\s+\d{2}:\d{2}:\d{2}$""")
    private val orderId = Regex("""^\d{10,25}$""")
    private val money = Regex("""^[¥￥]?\s*(\d+(?:\.\d{1,2})?)(?:\s|$)""")
    private fun minor(value: String?): Long? = runCatching {
        value?.toBigDecimalOrNull()?.multiply(BigDecimal(100))?.longValueExact()?.takeIf { it > 0 }
    }.getOrNull()
    private val labels = listOf("美团订单号", "订单号", "订单编号", "订单号码", "付款时间", "支付时间", "下单时间", "订单时间", "创建时间", "实付金额", "实付", "商户全称", "商品说明", "交易方式", "付款方式", "时间来源")

    /** 同时支持正常行序和服务末子节点优先的行序；单字字段只在连续单字行中拼合。 */
    fun read(text: String): Detail? {
        val raw = text.lines().map(String::trim).filter(String::isNotEmpty)
        fun fold(input: List<String>): List<String> {
            val out = mutableListOf<String>(); var i = 0
            while (i < input.size) {
                if (input[i].length == 1 && input[i][0] in '\u4e00'..'\u9fff') {
                    val start = i
                    while (i < input.size && input[i].length == 1 && input[i][0] in '\u4e00'..'\u9fff') i++
                    val joined = input.subList(start, i).joinToString("")
                    if (joined in labels) out += joined else out += input.subList(start, i)
                } else { out += input[i]; i++ }
            }
            return ScreenFields.collapse(out)
        }
        val candidates = listOf(fold(raw), fold(raw.reversed()))
        return candidates.firstNotNullOfOrNull { readRefund(it) ?: readRide(it) ?: readForward(it) }
    }

    private fun readRefund(lines: List<String>): Detail? {
        if ("账单详情" !in lines || "退款进度" !in lines || "退款成功" !in lines || "退款完成" !in lines) return null
        fun field(name: String): String? = lines.indexOf(name).takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }
        val method = field("退款至")?.takeIf { it in setOf("微信支付", "支付宝", "支付宝支付", "美团支付", "银行卡") } ?: return null
        val completedTime = field("退款完成")?.takeIf(date::matches) ?: return null
        val amountText = lines.firstOrNull { Regex("\\+\\d+(?:\\.\\d{1,2})?").matches(it) }?.removePrefix("+")
            ?: if (field("当前状态") == "退款成功") field("退款金额")?.removePrefix("¥") else null
        val amount = minor(amountText) ?: return null
        val merchant = lines.firstOrNull { it.startsWith("退款-") }?.removePrefix("退款-")
            ?: field("商户全称") ?: return null
        if (merchant.length !in 2..100) return null
        val product = field("商品说明") ?: lines.firstOrNull { it.startsWith("$merchant-") }?.removePrefix("$merchant-") ?: "美团订单退款"
        return Detail(null,merchant,amount,completedTime,"退款完成时间",product,"退款成功",method,income=true)
    }

    private fun readRide(lines: List<String>): Detail? {
        if ("订单详情" !in lines || "本次骑行" !in lines || lines.any { it in blocked }) return null
        val duration = lines.firstOrNull { Regex("骑行\\d+分\\d+秒").matches(it) } ?: return null
        fun field(name: String): String? = lines.indexOf(name).takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }
        val isCanonical = field("商户全称") == "美团电单车" && field("当前状态") == "交易成功"
        val paidAmount = lines.firstOrNull { Regex("已支付\\d+(?:\\.\\d{1,2})?元").matches(it) }
            ?.removePrefix("已支付")?.removeSuffix("元")
            ?: if (isCanonical) field("实付金额")?.removePrefix("¥") else null
        val amount = minor(paidAmount) ?: return null
        val payment = lines.firstOrNull { Regex("(?:美团支付|微信支付|支付宝支付) \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(it) }
        val paidTime = payment?.substringAfter(' ') ?: if (isCanonical) field("支付时间") else null
        if (paidTime == null || !date.matches(paidTime)) return null
        val startedAt = field("骑行开始时间") ?: lines.firstOrNull(date::matches) ?: return null
        if ("支付方式" !in lines && !isCanonical) return null
        return Detail(null, "美团电单车", amount, paidTime, "付款时间", "美团电单车·$duration", "交易成功",
            payment?.substringBefore(' ') ?: field("付款方式"), startedAt, duration)
    }

    private fun readForward(lines: List<String>): Detail? {
        if (lines.none { it == "订单信息" || it == "订单详情" }) return null
        if (lines.any { it.trimEnd('：', ':') in blocked }) return null
        fun value(vararg names: String, accepts: (String) -> Boolean = { it.isNotBlank() }): String? {
            for (name in names) for (i in lines.indices) {
                val line = lines[i]
                val inline = if (line.startsWith("$name：") || line.startsWith("$name:")) line.substring(name.length + 1).trim() else null
                if (inline != null && accepts(inline)) return inline
                if (line.trimEnd('：', ':') == name) lines.getOrNull(i + 1)?.takeIf(accepts)?.let { return it }
            }
            return null
        }
        val id = value("美团订单号", "订单号", "订单编号", "订单号码", accepts = orderId::matches) ?: return null
        val status = value("订单状态", accepts = { it in settled }) ?: lines.firstOrNull { it in settled } ?: return null
        val amount = value("实付金额", "实付", accepts = { money.containsMatchIn(it) })?.let {
            minor(money.find(it)?.groupValues?.get(1))
        }?.takeIf { it > 0 } ?: return null
        val paidTime = value("付款时间", "支付时间", accepts = date::matches)
        val createdTime = value("下单时间", "订单时间", "创建时间", accepts = date::matches)
        val time = paidTime ?: createdTime ?: return null
        val timeSource = value("时间来源") ?: if (paidTime != null) "付款时间" else "下单时间"
        fun before(anchor: (String) -> Boolean): String? = lines.indexOfFirst(anchor).takeIf { it > 0 }?.let { lines[it - 1] }
        val merchant = value("商户全称") ?: before { it.startsWith("营业中") || it.startsWith("休息中") }
            ?: before { it == "查看所有适用门店" }
            ?: before { Regex("第\\d+次下单").matches(it) }
            ?: lines.firstOrNull { "影城" in it && it.length in 3..80 && !it.first().isDigit() }
            ?: return null
        if (merchant.length !in 2..100 || merchant in labels || merchant in settled || merchant.contains('¥') || merchant.contains('￥')) return null
        val product = value("商品说明") ?: lines.firstOrNull { it.startsWith("【") && it.length > 5 }
            ?: lines.firstOrNull { "猫洗澡" in it || "狗洗澡" in it || "宠物洗护" in it }
            ?: before { Regex(".*(?:2D|3D).*\\d+张.*").matches(it) }
            ?: lines.indexOfFirst { Regex("第\\d+次下单").matches(it) }.takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }
            ?: merchant
        val method = value("交易方式", "付款方式", accepts = { it in setOf("微信支付", "支付宝", "支付宝支付", "银行卡", "美团支付") })
        return Detail(id, merchant, amount, time, timeSource, product, status, method)
    }
}
