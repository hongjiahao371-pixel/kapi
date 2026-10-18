package kapi.core.auto

/** Explicit recipient in a complete WeChat QR bill, independent of nickname decoration. */
object WechatQrCounterparty {
    fun read(text: String): String? {
        val lines = ScreenFields.collapse(text.lines())
        if (lines.none { it == "交易详情" || it == "账单详情" } ||
            PaymentId.ofKind(text, PaymentId.Kind.WECHAT_TXN) == null ||
            lines.none { it == "支付成功" || it.matches(Regex("^当前状态[ :：]+支付成功$")) } ||
            !text.contains("支付方式") ||
            listOf("按住说话", "发送给朋友", "选择聊天", "账单列表").any(text::contains)) return null
        val recipients = lines.mapNotNull { line ->
            listOf("扫二维码付款-给", "扫二维码付款－给", "扫二维码付款—给").firstOrNull(line::startsWith)
                ?.let { line.removePrefix(it).trim() }
        }.distinct()
        // Digits are legal in explicitly named counterparties (including Unicode nickname glyphs).
        // They remain disallowed in generic amount-neighbour guessing.
        return recipients.singleOrNull()?.takeIf {
            it.length in 1..60 && it.any(Char::isLetter) &&
                !MerchantName.isInterfaceLabel(it) && !it.contains("http", ignoreCase = true) &&
                it.none { c -> c.isISOControl() || c in "¥￥$" } &&
                !PaymentGate.hasAmount(it) && !PaymentGate.hasStrongVerb(it)
        }
    }
}
