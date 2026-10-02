package kapi.core.auto

import kapi.core.llm.Direction
import kapi.core.llm.LocalTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包详情页（2026-10-02 realme，用户现场翻到「支付宝红包」0.1 元被整屏挡掉）。
 *
 * 根因：「红包」在调整项词表里（为支付页的「红包抵扣 -0.5」设计），而红包页上
 * 「红包」是**交易类型本身**——形状闸把金额当折扣剔掉（plain 归零），支付门的
 * 剔行更狠，连「支出0.1元」这一行本身都会被删（它的下一行含「红包」），
 * 死于"没有金额"。
 *
 * 修法：红包页有**结构性**标志（标题「支付宝红包」/字段「红包说明」/
 * 说明值「发普通红包」），命中的页面整页豁免调整项逻辑。支付页上的
 * 「红包抵扣」负例钉在最后——没有结构标志，折扣语义照旧。
 */
class RedPacketPageTest {

    /** 用户现场那笔的真机 dump 原文（y 序，逐行照抄）。 */
    private val redPacketPage = """
        支付宝红包
        支出0.1元
        领取中
        创建时间
        2026-10-02 15:36:19
        付款方式
        中国银行储蓄卡(0416)
        红包说明
        发普通红包
        发普通红包
        更多
        账单管理
        我的消费图鉴
        账单分类
        转账红包
        标签
        请选择
        为您推荐
        发红包
        计入收支
        开关
        备注
        添加
    """.trimIndent()

    /** 支付页上的红包**抵扣**（没有结构标志）：折扣语义必须保留。行序=真机的值在标签前。 */
    private val paymentWithRedPacketDiscount = """
        微雪**店
        支出244.93元
        等待确认收货
        -0.50
        红包抵扣
        支付时间
        2026-09-29 16:03:25
        付款方式
        余额宝
    """.trimIndent()

    @Test
    fun `red packet page passes the shape gate`() {
        val verdict = PaymentShape.inspect(redPacketPage.lines().map { PaymentShape.Node(it) })
        assertTrue(
            "红包页是一笔，实际 $verdict",
            verdict is PaymentShape.Verdict.OneTransaction,
        )
    }

    /** 金额行不能被剔行删掉——「支出0.1元」的下一行含「红包」。 */
    @Test
    fun `adjustment stripping keeps the amount row on red packet pages`() {
        val stripped = PaymentShape.withoutAdjustmentRows(redPacketPage)
        assertTrue("金额行必须保留：\n$stripped", stripped.contains("支出0.1元"))
    }

    @Test
    fun `red packet page passes the payment gate`() {
        assertEquals(
            PaymentGate.Verdict.LooksLikePayment,
            PaymentGate.verdict(redPacketPage),
        )
    }

    @Test
    fun `red packet parses as expense to rename later`() {
        val r = LocalTextParser.parse(redPacketPage, System.currentTimeMillis())!!
        assertEquals(10L, r.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
        // 「领取中」是 创建时间 的字段值（下一行是纯标签），不能当商户
        assertEquals("支付宝红包", r.merchant)
        // 分类走商户名：支付宝红包 → 人情/红包（merchantOnly 词表）
        assertEquals("人情/红包", CategoryRules.classify(r.merchant, null, income = false)?.text)
    }

    /** 支付页上的「红包抵扣」仍然是调整项：244.93 是那一笔，-0.50 不是第二笔。 */
    @Test
    fun `red packet discount on a payment page stays an adjustment`() {
        val verdict = PaymentShape.inspect(paymentWithRedPacketDiscount.lines().map { PaymentShape.Node(it) })
        assertTrue(
            "红包抵扣页的真实支付应放行，实际 $verdict",
            verdict is PaymentShape.Verdict.OneTransaction,
        )
        assertEquals(
            PaymentGate.Verdict.LooksLikePayment,
            PaymentGate.verdict(paymentWithRedPacketDiscount),
        )
        val r = LocalTextParser.parse(paymentWithRedPacketDiscount, System.currentTimeMillis())!!
        assertEquals(
            "实付是 244.93，不是面额之和也不是抵扣额",
            24493L,
            r.amountsMinor.first(),
        )
    }
}
