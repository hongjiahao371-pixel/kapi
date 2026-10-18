package kapi.core.auto

/** 只读的付款跳转会话。仅在真实美团待支付页启动；普通浏览和历史订单不启动。 */
class CheckoutFlow {
    private data class Session(val id: String, val startedAt: Long, var enteredWechat: Boolean = false,
        var returned: Boolean = false)
    private var session: Session? = null
    private var sequence = 0L
    private var lastCheckout: String? = null

    @Synchronized fun observe(pkg: String, text: String, now: Long): String? {
        if (session?.let { now < it.startedAt || now - it.startedAt > WINDOW_MS } == true) {
            session = null; lastCheckout = null
        }
        if (pkg == MeituanOrder.PACKAGE) {
            val checkout = isCheckout(text)
            if (checkout && (session == null || session!!.enteredWechat || lastCheckout != text)) {
                session = Session("$now/${++sequence}", now)
                lastCheckout = text
            } else if (!checkout && session?.enteredWechat == true) session?.returned = true
            return session?.takeIf { it.returned }?.id
        }
        if (pkg == WECHAT) {
            session?.enteredWechat = true
            return session?.takeIf { !it.returned }?.id
        }
        // 系统付款保护、键盘和本应用悬浮窗不结束会话。
        if (!pkg.startsWith("com.android.") && !pkg.startsWith("com.coloros.") &&
            !pkg.startsWith("com.oplus.") && !pkg.startsWith("kapi.ledger")) {
            session = null; lastCheckout = null
        }
        return null
    }

    companion object {
        const val WECHAT = "com.tencent.mm"
        const val WINDOW_MS = 180_000L
        fun isCheckout(text: String): Boolean {
            val lines = text.lines().map(String::trim)
            return lines.any { it in setOf("待付款", "待支付", "确认支付", "立即支付", "去支付", "提交订单") } &&
                text.contains("微信支付") && PaymentGate.hasAmount(text) &&
                !lines.any { it in setOf("订单列表", "搜索我的订单", "退款成功", "支付成功", "已完成", "已支付", "已使用", "已消费", "订单已完成", "放映结束", "出票成功", "交易成功") }
        }
    }
}
