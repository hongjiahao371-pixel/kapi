package kapi.core.auto

import java.math.BigDecimal

/** Native WeChat scan/merchant payment result. Unknown extra content stays blocked. */
object WechatMerchantPaymentResult {
    private val money = Regex("^[￥¥]\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)$")
    private val offerFields = setOf("摇一摇有优惠", "点击领取优惠", "领取")
    private val nonMerchantFields = setOf(
        "返回", "返回商家", "回首页", "微信支付", "账单", "全部账单", "查看详情",
        "确认付款", "确认支付", "确认收款", "待确认收款", "支付失败", "付款失败",
        "付款", "支付", "收款方", "付款方", "当前状态", "收款成功", "交易详情",
        "账单详情", "转账详情", "退款详情", "钱包", "零钱", "零钱通", "余额",
        "加入购物车", "商品详情", "查看订单", "收银台", "选择支付方式", "领取优惠",
    )

    data class Receipt(val merchant: String, val amountMinor: Long) {
        fun canonicalText(): String = "支付成功\n$merchant\n￥${BigDecimal.valueOf(amountMinor, 2).toPlainString()}\n完成"
    }

    fun read(text: String): Receipt? {
        if (text.length > 400) return null
        val lines = ScreenFields.collapse(text.lines().map(WechatCompletionFields::normalize))
        if (lines.size > 20) return null
        // Accessibility may repeat children in a comma-separated parent description.
        // Remove only the observed offer block, before the generic adjustment filter
        // can mistake its adjacent payment amount for a discount.
        val fields = lines.distinct().filterNot { line ->
            WechatCompletionFields.isNativeOffer(line) || line.split(',', '，').map(String::trim).all { it in offerFields }
        }
        if (fields.size != 4 || "支付成功" !in fields || "完成" !in fields) return null
        val amountField = fields.singleOrNull { money.matches(it) } ?: return null
        val merchant = fields.singleOrNull { it != "支付成功" && it != "完成" && it != amountField }
            ?.takeIf { candidate ->
                candidate.length in 1..60 && candidate !in nonMerchantFields &&
                    ScreenFields.isUsableValue(candidate) && candidate.any(Char::isLetter) &&
                    !candidate.contains("确认收款") && !candidate.contains("http", ignoreCase = true) &&
                    !PaymentGate.hasStrongVerb(candidate) && !PaymentGate.hasAmount(candidate)
            } ?: return null
        val cents = runCatching {
            BigDecimal(money.matchEntire(amountField)!!.groupValues[1].replace(",", ""))
                .movePointRight(2).longValueExact()
        }.getOrNull()?.takeIf { it > 0 } ?: return null
        val receipt = Receipt(merchant, cents)
        if (PaymentGate.verdict(receipt.canonicalText()) !is PaymentGate.Verdict.LooksLikePayment) return null
        return receipt
    }

    fun canonicalText(text: String): String? = read(text)?.canonicalText()
}
