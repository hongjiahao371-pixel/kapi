package kapi.core.auto

/** Normalize whole status labels, never arbitrary merchant names or payment content. */
internal object WechatCompletionFields {
    private val success = setOf("支付成功", "付款成功", "交易成功", "支付完成")
    fun normalize(line: String): String {
        val value = line.trim()
        val status = value.removeSuffix("。").removeSuffix(".")
        return if (status in success) "支付成功" else value
    }

    fun normalizeOcr(line: String): String {
        val value = normalize(line)
        // The round success icon can become one punctuation/circle glyph.
        val withoutIcon = value.drop(1).trimStart()
        return if (value.firstOrNull() in setOf('。', '〇', '○', '◯', 'O', '0') &&
            normalize(withoutIcon) == "支付成功") "支付成功" else value
    }

    fun isNativeOffer(line: String): Boolean = line.trim().replace(Regex("[,，\\s]"), "") == "摇一摇有优惠"

    /** OCR-only: one substituted Han character in the known, non-monetary offer row.
     * The caller must still require the complete four-field payment result template. */
    fun isOcrOffer(line: String): Boolean {
        val value = line.trim().removePrefix("公").replace(Regex("[,，\\s]"), "")
        val expected = "摇一摇有优惠"
        return value.length == expected.length && value.startsWith("摇一摇有") &&
            value.all { it in '\u4e00'..'\u9fff' } &&
            value.zip(expected).count { (a, b) -> a != b } <= 1
    }
}
