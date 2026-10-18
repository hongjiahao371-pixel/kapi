package kapi.core.auto

import kotlin.math.abs

/** 只保存编号、金额和付款时刻；弱证据只能提示核对，不能自动合账。 */
object CommercePaymentLink {
    const val ALIPAY = "com.eg.android.AlipayGphone"
    val WALLETS = setOf(CheckoutFlow.WECHAT, ALIPAY, CommerceOrder.PDD)
    const val PREFIX = "C_LINK/"
    data class Evidence(val orderKind: String?, val orderId: String?, val wallet: String,
        val amount: Long, val paidAt: Long?, val providerId: String?, val merchantOrder: String?, val createdAt:Long?=null) {
        val isOrder get() = orderId != null
        val key get() = PREFIX + listOf(orderKind,orderId,wallet,amount.toString(),paidAt?.toString(),providerId,merchantOrder,createdAt?.toString())
            .joinToString("/") { it ?: "-" }
    }
    fun readKey(key: String): Evidence? {
        if (!key.startsWith(PREFIX)) return null
        val p=key.removePrefix(PREFIX).split('/')
        if(p.size !in 7..8 || p[2] !in WALLETS) return null
        return Evidence(p[0].takeUnless { it=="-" },p[1].takeUnless { it=="-" },p[2],
            p[3].toLongOrNull() ?: return null,p[4].toLongOrNull(),p[5].takeUnless { it=="-" },p[6].takeUnless { it=="-" },p.getOrNull(7)?.toLongOrNull())
    }
    fun evidence(pkg: String, text: String, amount: Long): Evidence? {
        if(pkg in CommerceOrder.PACKAGES) {
            val order=CommerceOrder.read(pkg,text) ?: return null
            if(order.kind==PaymentId.Kind.PDD_TXN) return Evidence("PDD_ORDER",null,CommerceOrder.PDD,amount,order.time?.let(PaymentLink::timestamp),order.id,null)
            val wallet=when(order.method) { "微信支付" -> CheckoutFlow.WECHAT; "支付宝", "支付宝支付" -> ALIPAY; "多多支付" -> CommerceOrder.PDD; else -> return null }
            return Evidence(order.kind.name,order.id,wallet,
                amount,if(order.timeSource=="付款时间")order.time?.let(PaymentLink::timestamp) else null,order.providerId?.value,null,
                if(order.timeSource=="下单时间")order.time?.let(PaymentLink::timestamp) else null)
        }
        if(pkg !in WALLETS || listOf("退款","转账","红包").any(text::contains)) return null
        val kind=when { text.contains("拼多多") || text.contains("多多支付") -> "PDD_ORDER"; text.contains("京东") -> "JD_ORDER"; text.contains("淘宝") || text.contains("天猫") -> "TAOBAO_ORDER"; else -> null }
        val provider=PaymentId.ofKind(text,if(pkg==ALIPAY) PaymentId.Kind.ALIPAY_TXN else PaymentId.Kind.WECHAT_TXN)
        return Evidence(kind,null,pkg,amount,PaymentLink.paidTime(text),provider?.value,
            PaymentId.ofKind(text,PaymentId.Kind.MERCHANT_ORDER)?.value)
    }
    enum class Match { NONE, STRONG, REVIEW }
    fun match(a: Evidence, b: Evidence, capturedAt: Long, now: Long): Match {
        if(a.isOrder==b.isOrder || a.wallet!=b.wallet || a.amount!=b.amount) return Match.NONE
        val order=if(a.isOrder) a else b
        val wallet=if(a.isOrder) b else a
        if(order.providerId!=null && wallet.providerId!=null && order.providerId!=wallet.providerId) return Match.NONE
        // 商户单号可能属于付款单的编号空间；不同于商品订单号时不能自动合账，仍可人工核对。
        val sharedProvider=order.providerId!=null && order.providerId==wallet.providerId
        val sharedOrder=order.orderKind==wallet.orderKind && order.orderId==wallet.merchantOrder
        if(sharedProvider || sharedOrder) {
            if(a.paidAt!=null && b.paidAt!=null && abs(a.paidAt-b.paidAt)>120_000) return Match.NONE
            return Match.STRONG
        }
        if(now<capturedAt || now-capturedAt>120_000) return Match.NONE
        if(order.paidAt!=null && wallet.paidAt!=null && abs(order.paidAt-wallet.paidAt)<=5_000) return Match.REVIEW
        // 下单时刻只允许人工核对，绝不伪装成扣款时间自动合账。
        return if(order.createdAt!=null && wallet.paidAt!=null && wallet.paidAt-order.createdAt in 0..180_000) Match.REVIEW else Match.NONE
    }
}
