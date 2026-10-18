package kapi.core.auto

import java.math.BigDecimal

/** The native checkout receipt has no bill title or transaction id. Recognize its
 * complete, short field structure; never interpret a success phrase by itself. */
object AlipayPaymentResult {
    const val PACKAGE = "com.eg.android.AlipayGphone"
    private const val NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?"
    private val success = Regex("^(支付成功|付款成功|转账成功|交易成功)(?:\\s*[￥¥]\\s*($NUMBER)\\s*元?)?$")
    private val amount = Regex("^(?:[￥¥]\\s*($NUMBER)|($NUMBER)\\s*元|(\\d{1,8}\\.\\d{2}))$")
    private val method = Regex("银行|储蓄卡|信用卡|银行卡|余额宝|账户余额|支付宝余额|花呗|余额")
    private val pending = listOf("确认付款", "立即付款", "待付款", "支付失败", "付款失败", "转账失败", "付款中", "支付中", "处理中")

    fun canonicalText(text: String): String? {
        val lines = ScreenFields.collapse(text.lines())
        if (lines.size > 20 || lines.sumOf { it.length } > 240) return null
        if (PaymentGate.verdict(text) !is PaymentGate.Verdict.LooksLikePayment) return null
        if (pending.any(text::contains) || PaymentId.authoritative(text) != null) return null
        if (lines.any { it in listOf("账单详情", "交易详情", "转账详情", "退款详情") }) return null
        if ("完成" !in lines || lines.none { it == "回首页" || it == "返回商家" }) return null
        val statuses = lines.mapNotNull(success::matchEntire)
        if (statuses.isEmpty() || statuses.map { it.groupValues[1] }.distinct().size != 1) return null
        val amounts = lines.mapNotNull { line ->
            val statusAmount = success.matchEntire(line)?.groupValues?.get(2)?.takeIf(String::isNotBlank)
            val value = statusAmount ?: amount.matchEntire(line)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
            value?.let { runCatching { BigDecimal(it.replace(",", "")).movePointRight(2).longValueExact() }.getOrNull() }
        }.distinct()
        if (amounts.size != 1 || amounts.single() <= 0) return null
        fun usable(value: String) = ScreenFields.isUsableValue(value) &&
            !PaymentGate.hasStrongVerb(value) && !PaymentGate.hasAmount(value)
        val paymentMethod = listOf("付款方式", "支付方式").firstNotNullOfOrNull { label ->
            ScreenFields.valueNear(lines, label, null) { usable(it) && method.containsMatchIn(it) }
        } ?: return null
        val payee = listOf("收款方", "收款人", "商户全称").firstNotNullOfOrNull { label ->
            ScreenFields.valueNear(lines, label, null) { usable(it) && !method.containsMatchIn(it) }
        } ?: return null
        val value = BigDecimal.valueOf(amounts.single(), 2).toPlainString()
        // Parent content descriptions and child text may repeat the same receipt amount.
        // Preserve one amount and the two actual values, so shape and parser agree.
        return "回首页\n完成\n$paymentMethod\n付款方式\n$payee\n收款方\n${statuses.first().groupValues[1]}￥$value"
    }
}
