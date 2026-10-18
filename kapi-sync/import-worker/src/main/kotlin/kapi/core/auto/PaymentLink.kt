package kapi.core.auto

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** 美团订单与微信扣款的证据关联。调用方另外核对实付金额、支出方向和记录存活状态。 */
object PaymentLink {
    const val REVIEW_PREFIX = "跨应用付款待核对\n"
    fun needsReview(text: String?) = text?.startsWith(REVIEW_PREFIX) == true
    /** 仅在真实付款会话里使用；不能把聊天中的“支付成功”文本放宽为账单。 */
    fun isCheckoutResult(text: String): Boolean {
        val lines = ScreenFields.collapse(text.lines())
        if (lines.size !in 2..6 || text.length > 160 || lines.none { it == "支付成功" || it == "付款成功" }) return false
        val money = Regex("""^[¥￥]\s*(\d+(?:\.\d{1,2})?)$""")
        if (lines.count(money::matches) != 1) return false
        if (lines.any { !money.matches(it) && it !in setOf("支付成功","付款成功","完成","返回商家","返回美团","返回","微信支付") }) return false
        return money.find(lines.first(money::matches))!!.groupValues[1].toBigDecimalOrNull()?.signum() == 1
    }

    private val date = Regex("""^(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?\s+(\d{1,2}):(\d{2}):(\d{2})$""")
    fun timestamp(value: String): Long? = TransactionTime.timestamp(value)
    fun paidTime(text: String): Long? {
        return TransactionTime.resolve(text,System.currentTimeMillis()).takeIf { !it.requiresReview && it.precision=="SECOND" && it.source=="PAYMENT" }?.paidAt
    }
    fun matches(wechat: String, meituan: String, sameCheckoutFlow: Boolean,
        wechatCapturedAt: Long, now: Long): Boolean {
        val detail = MeituanOrder.read(meituan) ?: return false
        if (detail.income || detail.paymentMethod != "微信支付") return false
        // 微信转账/红包不能与商品消费合并。
        if (wechat.contains("转账") || wechat.contains("红包") || wechat.contains("退款")) return false
        val wxOrder = PaymentId.ofKind(wechat, PaymentId.Kind.MERCHANT_ORDER)?.value
        val sharedOrder = detail.id != null && wxOrder == detail.id && wechat.contains("美团")
        val wxTime = paidTime(wechat)
        val mtTime = if (detail.timeSource == "付款时间") timestamp(detail.time) else null
        if (sharedOrder) return wxTime == null || mtTime == null || abs(wxTime - mtTime) <= 120_000
        // 没有共享订单号时，必须有完整付款跳转会话及真正的付款时间，不能拿下单时间凑。
        if (!sameCheckoutFlow || mtTime == null || now < wechatCapturedAt || now - wechatCapturedAt > 120_000) return false
        if (abs(mtTime - now) > 120_000) return false
        return if (wxTime != null) abs(wxTime - mtTime) <= 5_000 && abs(wxTime - wechatCapturedAt) <= 120_000
            else abs(mtTime - wechatCapturedAt) <= 10_000 // 短付款成功页没有日期，使用本次读屏时刻。
    }
}
