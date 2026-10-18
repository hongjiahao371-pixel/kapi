package kapi.core.auto

import java.math.BigDecimal

/** Native outgoing transfer receipt, not the recipient's acceptance page or a chat preview. */
object WechatPaymentResult {
    const val PACKAGE = "com.tencent.mm"
    private val pendingRecipient = Regex("^待(.{1,60})确认收款$")
    private val money = Regex("^[￥¥]\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)$")

    data class Receipt(val recipient: String, val amountMinor: Long) {
        val merchant: String get() = "转账-转给$recipient"
        fun canonicalText(): String = "支付成功\n待${recipient}确认收款\n￥${BigDecimal.valueOf(amountMinor,2).toPlainString()}\n完成"
    }

    fun read(text: String): Receipt? {
        val lines = ScreenFields.collapse(text.lines().map(WechatCompletionFields::normalize))
        // Parent descriptions may repeat children; only these four distinct fields are allowed.
        if (lines.size > 8 || text.length > 200) return null
        val fields = lines.distinct()
        if (fields.size != 4 || "支付成功" !in fields || "完成" !in fields) return null
        if (PaymentGate.verdict(text) !is PaymentGate.Verdict.LooksLikePayment) return null
        val recipient = fields.mapNotNull(pendingRecipient::matchEntire).singleOrNull()
            ?.groupValues?.get(1)?.trim()?.takeIf {
                it.isNotEmpty() && it !in setOf("你", "您", "本人", "对方") &&
                    !PaymentGate.hasStrongVerb(it) && !PaymentGate.hasAmount(it)
            } ?: return null
        val value = fields.mapNotNull(money::matchEntire).singleOrNull()?.groupValues?.get(1) ?: return null
        val cents = runCatching { BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { it > 0 } ?: return null
        return Receipt(recipient,cents)
    }

    fun canonicalText(text: String): String? = read(text)?.canonicalText()
}
