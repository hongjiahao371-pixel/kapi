package kapi.core.auto

import kapi.core.llm.LocalTextParser

/** Identity for the current page session, never a persistent transaction deduplication key. */
object PaymentPageIdentity {
    fun key(raw: String, now: Long): String {
        val text=PeerPaymentReceipt.read(raw)?.text ?: raw
        PaymentId.authoritative(text)?.let { return it.key + if(LocalTextParser.parse(text,now)?.direction?.name=="INCOME") "/INCOME" else "" }
        val lines = ScreenFields.collapse(text.lines())
        // Without an explicit timestamp the parser uses the event clock, which changes on redraw.
        val date = Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}""")
        val orientation = ScreenFields.orientation(lines)
        val dated = listOf("支付时间", "付款时间", "收款时间", "到账时间", "退款时间", "转账时间", "创建时间")
            .any { label -> ScreenFields.valueNear(lines,label,orientation) { date.containsMatchIn(it) } != null }
        if (dated) {
            LocalTextParser.parse(text,now)?.let { p ->
                val merchant = MerchantLearning.key(p.merchant) ?: p.merchant.orEmpty()
                // Amount + direction + merchant + actual event time ignores recommendation/menu redraws.
                return "page/${p.direction}/${p.amountsMinor.first()}/$merchant/${p.occurredAt}"
            }
        }
        return AutoBook.exactFingerprint(AutoBook.CaptureChannel.A11Y,text)
    }

    fun isDetail(text: String): Boolean = ScreenFields.collapse(text.lines()).any {
        it in setOf("账单详情","交易详情","转账详情","退款详情","红包详情","收款详情")
    }

    /** Partial wallet trees and covering cards are not evidence of leaving a receipt. */
    fun keepsReceiptSession(text:String,pkg:String):Boolean = pkg in WalletOcrPolicy.packages && !isKnownNonDetail(text)

    /** A half-loaded detail tree is not proof that the user navigated away. */
    fun isKnownNonDetail(text: String): Boolean {
        val lines=ScreenFields.collapse(text.lines()).toSet()
        if (isDetail(text)) return false
        if ("搜索我的订单" in lines && "全部" in lines && "待付款" in lines) return true
        return ("全部" in lines && "支出" in lines && ("转账" in lines || "收入" in lines)) ||
            ("账单" in lines && ("全部" in lines || "全部账单" in lines || "搜索交易记录" in lines)) ||
            ("钱包" in lines && "零钱" in lines) ||
            ("我的" in lines && "首页" in lines) ||
            ("我" in lines && "通讯录" in lines) ||
            ("总资产" in lines && "余额" in lines)
    }
}
