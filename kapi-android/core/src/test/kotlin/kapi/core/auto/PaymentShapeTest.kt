package kapi.core.auto

import kapi.core.auto.PaymentShape.Node
import kapi.core.auto.PaymentShape.Verdict
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏形状闸的测试。
 *
 * ## 样本全部是 2026-09-30 真机抓的原始节点
 *
 * 不是 `uiautomator dump`，是服务自己 `collectTexts()` 收上来的：
 *
 * 转账成功页（8 个节点）
 * ```
 * 回首页 / 回首页 / 完成 / 中国银行储蓄卡(0416) / 付款方式 /
 * 啦啦啦(朱娜娜) / 收款方 / 转账成功￥1.00
 * ```
 *
 * 这一条特别重要：**「转账成功￥1.00」是状态词和金额合在一个节点里**。
 * 首版按"整段就是一个金额"去数独立节点，真机数出来是 0，闸门一次都没触发。
 * 拿 `uiautomator dump` 抄的坐标设计的算法同样落空——dump 会把
 * 「转账成功」和「1.00」拆成两个节点，和原始树不是一个形状。
 */
class PaymentShapeTest {

    private fun n(vararg t: String) = t.map { Node(it) }

    private val alipayTransferSuccess = n(
        "回首页", "回首页", "完成", "中国银行储蓄卡(0416)", "付款方式",
        "啦啦啦(朱娜娜)", "收款方", "转账成功￥1.00",
    )

    @Test
    fun `transfer result page is one transaction`() {
        val v = PaymentShape.inspect(alipayTransferSuccess)
        assertTrue("转账成功页应判成一笔，实际 $v", v is Verdict.OneTransaction)
        val text = (v as Verdict.OneTransaction).text
        assertTrue("金额必须在：$text", text.contains("1.00"))
        assertTrue("对方昵称必须在（商户靠它）：$text", text.contains("啦啦啦(朱娜娜)"))
    }

    @Test
    fun `the verb and the amount share one node`() {
        // 这条是首版的 bug 本身：按"整段是金额"数，真机一个都数不出来。
        val v = PaymentShape.inspect(alipayTransferSuccess) as Verdict.OneTransaction
        assertTrue("必须数出 1 个带金额条目：${v.why}", v.why.contains("1 个带金额条目"))
    }

    // ------------------------------------------------ 列表页：一屏记录

    @Test
    fun `message list with two money rows is many transactions`() {
        // 真机原文（17:27 抓到的那条假草稿）。
        val messageList = n(
            "我的", "消息", "12", "视频", "理财", "首页",
            "[转账]**奇向你转账7000.00元",
            "9月9日", "陌生人", "盖被吱", "2",
            "闲鱼 | 自动扣款成功￥55.00",
            "15:49", "通知消息", "群福利",
            "全部会话", "关注动态", "通讯录", "搜索好友等", "清除", "消息(12)",
        )
        val v = PaymentShape.inspect(messageList)
        assertTrue("两个带金额条目必须判成一屏记录，实际 $v", v is Verdict.ManyTransactions)
        val why = (v as Verdict.ManyTransactions).why
        assertTrue("理由要写明数了几个：$why", why.contains("2 个带金额"))
    }

    @Test
    fun `fund history page with three amounts is many transactions`() {
        val history = n(
            "转账", "红包", "灵感回复", "图片", "更多", "表情", "发送消息...",
            "出借", "¥3000.00", "啦啦啦", "9月10日 16:00", "转账成功，马上通知TA", "橘生",
            "转账给朱娜娜", "¥1500.00", "8月21日 13:32", "转账成功，马上通知TA",
            "资金往来记录", "啦啦啦", "返回",
        )
        assertTrue(PaymentShape.inspect(history) is Verdict.ManyTransactions)
    }

    // ------------------------------------------------ 不会误伤正常支付

    @Test
    fun `order detail page is not mistaken for a list`() {
        // 四个金额都是"实付 ¥88.00"这种**构成项**，属于同一笔。
        // 不排除字段标签的话这里会数成 4 笔，把正常支付挡掉。
        val detail = n(
            "订单详情", "商品金额 ¥100.00", "优惠 ¥20.00", "运费 ¥8.00",
            "实付 ¥88.00", "支付成功",
        )
        val v = PaymentShape.inspect(detail)
        assertTrue("订单详情页必须照常通过，实际 $v", v is Verdict.OneTransaction)
        assertTrue(PaymentGate.verdict((v as Verdict.OneTransaction).text) is PaymentGate.Verdict.LooksLikePayment)
    }

    @Test
    fun `wechat payment notification node still passes`() {
        val wx = n("微信支付", "商户全称：星巴克（国贸店）", "支付金额 ¥23.50", "支付方式 零钱")
        val v = PaymentShape.inspect(wx)
        assertTrue("微信支付通知必须放行，实际 $v", v is Verdict.OneTransaction)
    }

    @Test
    fun `bank push with date and time still passes`() {
        val bank = n("【工商银行】您尾号1234的储蓄卡", "9月29日15:30", "消费支出人民币23.50元", "余额1234.56元")
        val v = PaymentShape.inspect(bank)
        assertTrue("银行推送必须放行，实际 $v", v is Verdict.OneTransaction)
    }

    // ------------------------------------------------------------ 退化输入

    @Test
    fun `blank screen is rejected rather than throwing`() {
        for (empty in listOf(emptyList(), n(""), n("   "))) {
            val v = PaymentShape.inspect(empty)
            assertTrue("空屏必须挡下且不抛异常，实际 $v", v is Verdict.ManyTransactions)
        }
    }

    @Test
    fun `screen without any amount is rejected`() {
        val v = PaymentShape.inspect(n("我的", "首页", "消息", "视频", "理财"))
        assertTrue("没有金额的屏不该进管线，实际 $v", v is Verdict.ManyTransactions)
    }

    @Test
    fun `long message rows are not counted as money items`() {
        // 超长的整段描述即使含金额也不算一笔，否则一条长消息会触发拦截。
        val long = "这是一段很长的消息内容里面提到了￥99.99这个数字还有一些其他无关的描述文字继续延伸"
        assertTrue(long.length > 24)
        val v = PaymentShape.inspect(n("会话", long))
        assertTrue("长消息不该被算成金额条目，实际 $v", v is Verdict.OneTransaction)
    }
}
