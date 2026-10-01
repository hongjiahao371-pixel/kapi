package kapi.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 2026-09-30 真机实测回归：**收支方向**。
 *
 * ## 这组测试为什么单独一个文件
 *
 * 那天用户在支付宝真付了 1.00 元，结果：
 *  - 真实那笔的金额对了，方向记成了**收入**（用户是转给别人，本该是支出）
 *  - 同时从消息列表里凭空生成了 3 条假草稿，其中一条 7000 元支出
 *    方向也是反的（那是别人转给他的）
 *
 * 方向记反和金额解析错不是一个量级的错。金额错了一眼能看出来，方向反了
 * 这本账从头到尾是歪的，而用户未必看得出"我明明在付钱怎么变成收入了"——
 * 尤其当他按分类统计的时候。
 *
 * 原文全部照抄自 `auto_draft.rawText`（见 tools/dumpdrafts.py），
 * 不做改写：构造样本会不自觉地把真正的干扰项抹掉。
 */
class TransferDirectionRealCaptureTest {

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 30, 17, 27, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(s: String) = LocalTextParser.parse(s, now)

    // ------------------------------------------------------- 转出 = 支出

    @Test
    fun `alipay transfer out success page is an expense`() {
        // 真机原文。页面结构：
        //   付款方式 = 我用的卡    收款方 = 对方    → 用户是付款人
        // 旧实现里 INCOME_HINTS 含「收款」，于是这个"收款方"把它判成了收入。
        val r = parse(
            """
            回首页
            回首页
            完成
            中国银行储蓄卡(0416)
            付款方式
            啦啦啦(朱娜娜)
            收款方
            转账成功￥1.00
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals(100L, r!!.amountsMinor.first())
        assertEquals(
            "「收款方」指的是对方，这笔是转出去的钱，必须是支出",
            Direction.EXPENSE,
            r.direction,
        )
    }

    @Test
    fun `alipay confirm payment page is an expense`() {
        val r = parse(
            """
            付款,确认付款，向啦啦啦(朱娜娜)转账，付款金额1.00元人民币
            付款
            查看全部
            未选中,工商银行储蓄卡,尾号(1353),支付渠道说明内容: 银行卡渠道
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals(Direction.EXPENSE, r!!.direction)
    }

    // ------------------------------------------------------- 转入 = 收入

    @Test
    fun `someone transferred money to me is an income`() {
        // 旧实现里「向你转账」不在任何一侧的关键词表里，走到 else 分支被默认成支出。
        val r = parse("[转账]**奇向你转账7000.00元")
        assertNotNull(r)
        assertEquals(700000L, r!!.amountsMinor.first())
        assertEquals(
            "别人转给我的钱是收入",
            Direction.INCOME,
            r.direction,
        )
    }

    @Test
    fun `received transfer page with payer field is an income`() {
        // 收到转账时页面写的是反过来的一对字段：付款方=对方，收款方式=我
        val r = parse(
            """
            转账成功
            付款方
            张三
            收款方式
            中国银行储蓄卡(0416)
            已到账
            ¥200.00
            """.trimIndent(),
        )
        assertNotNull(r)
        assertEquals(Direction.INCOME, r!!.direction)
    }

    // ------------------------------------------------- 传统场景不能被带歪

    @Test
    fun `wechat payment notification is still an expense`() {
        val r = parse("微信支付\n商户全称：星巴克（国贸店）\n支付金额 ¥23.50\n支付方式 零钱")
        assertNotNull(r)
        assertEquals(Direction.EXPENSE, r!!.direction)
    }

    @Test
    fun `refund is still an income`() {
        val r = parse("退款成功，¥56.00 已退回原路")
        assertNotNull(r)
        assertEquals(Direction.INCOME, r!!.direction)
    }

    @Test
    fun `bank expense push is still an expense`() {
        val r = parse("【工商银行】您尾号1234的储蓄卡9月29日15:30消费支出人民币23.50元，余额1234.56元。")
        assertNotNull(r)
        assertEquals(Direction.EXPENSE, r!!.direction)
    }

    // ------------------------------------------------------------ 商户名

    @Test
    fun `field labels are never used as merchant`() {
        // 真机上 merchant 被猜成了"收款方"——一眼假的值，会污染历史统计。
        val r = parse(
            """
            回首页
            完成
            中国银行储蓄卡(0416)
            付款方式
            啦啦啦(朱娜娜)
            收款方
            转账成功￥1.00
            """.trimIndent(),
        )
        assertNotNull(r)
        val m = r!!.merchant
        assertNotEquals("「收款方」是指代对方的字段名，不能当商户", "收款方", m)
        assertNull("「回首页」是导航按钮", m?.takeIf { it == "回首页" })
        // 宁可不猜，也不要给一个假的。空着用户一秒能补。
        assertTrue("商户名不该是界面文案，实际=$m", m == null || m.length in 2..24)
    }
}
