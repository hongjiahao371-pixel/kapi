package kapi.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 降级通路的测试。
 *
 * 这条路径**永远可用**——不联网、不烧 token、不看模型脸色。BYOK 产品的命脉就在
 * 这：如果它挂了而用户没有任何退路，用户第一次失败就永久流失。
 */
class LocalTextParserTest {

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 29, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(s: String?) = LocalTextParser.parse(s, now)

    @Test
    fun `wechat style payment notification`() {
        val r = parse("微信支付\n商户全称：星巴克（国贸店）\n支付金额 ¥23.50\n支付方式 零钱")
        assertNotNull(r)
        assertEquals(2350L, r!!.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun `alipay style notification`() {
        val r = parse("你有一笔支出\n瑞幸咖啡(望京店)\n实付 19.90 元\n付款方式 余额宝")
        assertNotNull(r)
        assertEquals(1990L, r!!.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun `refund is income not expense`() {
        // 方向判断错了就是把退款记成支出，是账本里最误导人的一类错误。
        val r = parse("退款到账\n订单号 1234567890\n退款金额 ¥128.00")
        assertNotNull(r)
        assertEquals(12800L, r!!.amountsMinor.first())
        assertEquals(Direction.INCOME, r.direction)
    }

    @Test
    fun `merchant is guessed from the line holding the amount`() {
        val r = parse("交易成功\n盒马鲜生\n实付 ¥156.80\n时间 12:03")
        assertNotNull(r)
        assertTrue("商户名应被识别", r!!.merchant?.contains("盒马") == true)
    }

    @Test
    fun `merchant is not guessed from digits`() {
        // 订单号、卡号这类不能当商户名，会污染历史统计。
        val r = parse("订单 2026092912345678 已支付\n实付 ¥23.50")
        assertNotNull(r)
        assertNull("含数字的行不该被当成商户名", r!!.merchant)
    }

    /**
     * 置信度上限被刻意压到 0.55。规则匹配经常差一点：把券面额当实付、
     * 把订单号当金额。UI 必须始终要求用户确认。
     */
    @Test
    fun `confidence is capped low so the ui always asks`() {
        val r = parse("支付 ¥23.50")!!
        assertTrue("置信度必须低,UI 应当总是要求确认", r.confidence <= 0.55)
    }

    @Test
    fun `multiple amounts lower the confidence further`() {
        val one = parse("实付 ¥23.50")!!
        val two = parse("原价 ¥30.00 实付 ¥23.50 优惠 ¥6.50")!!
        assertTrue("金额越不确定,信心必须越低", two.confidence < one.confidence)
    }

    @Test
    fun `today and yesterday`() {
        // 这里**不能**再按 12:00 算。早期版本会把读不出时刻的文本一律归到当天中午，
        // 于是同一分钟内的事件时间戳完全相同，而自动记账的跨来源合并判据正是
        // "同额同向 + 120 秒内"——两笔真实消费会被并成一笔，**静默丢账**。
        // 现在的约定是：保留事件发生时刻的时分，只把毫秒清零。
        fun atEventTime(base: Long): Long = Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val r = parse("昨天 12:30\n瑞幸咖啡\n实付 ¥19.90")
        assertNotNull(r)
        assertEquals(atEventTime(now) - 24 * 3600_000L, r!!.occurredAt)
    }

    @Test
    fun `explicit date`() {
        val r = parse("2026-09-15 12:30\n瑞幸咖啡\n实付 ¥19.90")
        assertNotNull(r)
        val c = Calendar.getInstance().apply { timeInMillis = r!!.occurredAt }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, c.get(Calendar.MONTH))
        assertEquals(15, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `no amount means no result rather than zero`() {
        assertNull(parse("您有一条新消息"))
        assertNull(parse(""))
        assertNull(parse(null))
        assertNull(parse("订单已提交,请等待商家确认"))
    }

    @Test
    fun `absurdly long text is rejected outright`() {
        val spam = "微信支付 ".repeat(1000)
        assertNull(parse(spam))
    }

    @Test
    fun `end to end produces a usable draft`() {
        val draft = parse("微信支付\n商户全称：星巴克（国贸店）\n支付金额 ¥23.50")!!.best
        assertNotNull(draft)
        assertTrue(draft!!.isUsable)
        assertEquals(2350L, draft.amountMinor)
        assertEquals(Direction.EXPENSE, draft.direction)
    }

    /** 钉死一个具体时刻，避免测试随运行时刻漂移。 */
    private val FIXED_NOW: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 15, 30, 0)
    }.timeInMillis

    /**
     * 支付成功页的商户不能被猜成"支付成功"。
     *
     * 这条是设备测试 `wechatPaymentBecomesADraftNotATransaction` 逮到的：
     * 纯逻辑测试里没有这段真实版式，所以单测全绿，真库里 merchant 却是
     * "支付成功"。后果是商户一栏搜不到真实商家、历史统计被污染，
     * 而界面上完全看不出来。
     */
    @Test
    fun wechatSuccessPageYieldsTheMerchantNotTheStatusLine() {
        val r = LocalTextParser.parse(
            "支付成功\n¥23.50\n星巴克\n当前状态 支付成功",
            FIXED_NOW,
        )
        assertNotNull(r)
        assertEquals("星巴克", r!!.merchant)
        assertEquals(2350L, r.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun statusOnlyPagesYieldNoMerchantRatherThanAWrongOne() {
        for (text in listOf(
            "支付成功\n¥23.50",
            "交易成功\n¥23.50\n当前状态 交易成功",
            "退款成功 ¥56.00 已退回原路",
        )) {
            val r = LocalTextParser.parse(text, FIXED_NOW)
            assertNotNull("这段应该能解析出金额：$text", r)
            // 猜错商户比不猜更糟：空着用户一秒能补，错了他不会发现
            assertNull("不该把状态行当商户：$text", r!!.merchant)
        }
    }

    /**
     * 读不出时间时必须保留事件发生的时分，不能一律归到中午。
     *
     * 归中午的直接后果是"静默丢账"：15:30 和 15:35 两笔真实消费解析出来的
     * occurredAt 完全相同，自动记账的跨来源合并判据（同额同向 + 120 秒内）
     * 于是把两笔并成一笔。这是设备测试先发现的，纯逻辑测试一次只解析一条，
     * 永远看不见。
     */
    @Test
    fun unstatedTimeKeepsTheEventClockInsteadOfCollapsingToNoon() {
        val a = LocalTextParser.parse("支付成功 ¥23.50 星巴克", FIXED_NOW)!!
        val b = LocalTextParser.parse("支付成功 ¥23.50 星巴克", FIXED_NOW + 5 * 60_000)!!
        assertEquals(
            "两笔真实消费不能解析出同一个时间戳——那会被合并判据当成同一笔",
            5 * 60_000L,
            b.occurredAt - a.occurredAt,
        )
    }

    @Test
    fun yesterdayStillShiftsByADayNotByWholeHours() {
        val r = LocalTextParser.parse("昨天 支付成功 ¥23.50", FIXED_NOW)!!
        val delta = FIXED_NOW - r.occurredAt
        assertTrue("应该落在 23~25 小时之间，实际 ${delta / 3600000.0} 小时", delta in 23 * 3600_000L..25 * 3600_000L)
    }

    @Test
    fun merchantBelowTheAmountLineIsStillFound() {
        val r = LocalTextParser.parse(
            "支付成功\n¥23.50\n星巴克\n当前状态 支付成功",
            FIXED_NOW,
        )!!
        assertEquals("星巴克", r.merchant)
    }
}
