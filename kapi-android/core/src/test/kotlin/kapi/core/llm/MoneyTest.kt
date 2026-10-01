package kapi.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    private fun minor(s: String?): Long? = Money.parse(s)?.minor

    @Test
    fun `plain decimals`() {
        assertEquals(2300L, minor("23"))
        assertEquals(2350L, minor("23.5"))
        assertEquals(2350L, minor("23.50"))
        assertEquals(2350L, minor("23.500"))
        assertEquals(5L, minor("0.05"))
        assertEquals(100L, minor("1"))
    }

    @Test
    fun `currency noise is stripped`() {
        assertEquals(2350L, minor("¥23.50"))
        assertEquals(2350L, minor("￥23.50"))
        assertEquals(2350L, minor("23.50元"))
        assertEquals(2350L, minor("  23.50 元  "))
        assertEquals(2350L, minor("RMB 23.50"))
        assertEquals(2350L, minor("CNY23.50"))
    }

    @Test
    fun `amount embedded in a sentence`() {
        assertEquals(2350L, minor("订单23.50元已支付"))
        assertEquals(2350L, minor("实付: 23.50"))
        // 1,234.56 元 = 123456 分。写 2350 是我一开始的笔误。
        assertEquals(123456L, minor("共 1,234.56 元"))
    }

    @Test
    fun `thousands separators`() {
        assertEquals(123456L, minor("1,234.56"))
        assertEquals(123400L, minor("1,234"))
        assertEquals(123456L, minor("1.234,56"))
        assertEquals(100000000L, minor("1,000,000"))
    }

    @Test
    fun `decimal comma is not a thousands separator`() {
        // 逗号后不是 3 位（或太短），按小数点处理。
        assertEquals(2350L, minor("23,50"))
        assertEquals(235L, minor("2,35"))
    }

    /**
     * 金额恒为正数，符号被单独标记。
     *
     * 账本最严重的 bug 是「支出被存成收入」。如果金额自带符号，「模型写了 -23.50
     * 且说这是支出」和「模型写了 -23.50 且说这是收入」就无法区分。拆成
     * 量+方向之后这种错误在结构上不可能发生，符号异常只会变成一个可提示的标记。
     */
    @Test
    fun `sign is separated from magnitude`() {
        val expense = Money.parse("-23.50")!!
        assertEquals(2350L, expense.minor)
        assertTrue("负号必须被记录成异常", expense.negative)

        val positive = Money.parse("23.50")!!
        assertFalse(positive.negative)
    }

    @Test
    fun `over-precise amounts are rounded and flagged`() {
        val r = Money.parse("23.9999")!!
        assertEquals(2400L, r.minor)
        assertTrue("小数位过多必须告知调用方", r.rounded)

        assertFalse(Money.parse("23.99")!!.rounded)
    }

    @Test
    fun `garbage returns null rather than zero`() {
        // 这条是刻意的：识别失败绝不能变成 0 元，用户会以为今天花了 0。
        assertNull(Money.parse(null))
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse("无法识别"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("¥¥¥"))
        assertNull(Money.parse("1.2.3"))
    }

    @Test
    fun `display is only for ui and never feeds back into storage`() {
        assertEquals("23.50", Money.toYuanDisplay(2350))
        assertEquals("0.05", Money.toYuanDisplay(5))
        assertEquals("1234.56", Money.toYuanDisplay(123456))
    }
}
