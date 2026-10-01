package kapi.core.auto

import kapi.core.auto.PaymentShape.Node
import kapi.core.auto.PaymentShape.Verdict
import kapi.core.llm.LocalTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 微信「账单详情页」这条通路的整条链路测试。
 *
 * ## 为什么单独一个文件
 *
 * 2026-09-30 用户实测录屏显示，稳定的那条自动记账通路抓的**不是**付款成功页，
 * 而是用户主动点进历史流水的**账单详情页**。之前我们把判据全押在付款成功页上，
 * 形状闸、支付门、解析器都是照着那一屏写的。
 *
 * 这一屏必须单独钉一遍，因为它同时踩到四个坑，任何一个都可能把整条路掐断：
 *
 * | 关卡 | 账单详情页的实际情况 |
 * |---|---|
 * | [PaymentShape] | 只有 1 个带金额节点（`-80.00`），交易单号/订单号都不算金额 —— 低于阈值，放行 |
 * | [PaymentGate] | 「当前状态 支付成功」命中强动词 —— 放行（不靠 `LIST_HINTS`，那一栏没有"账单"） |
 * | [PaymentId] | 有平台交易号 —— 可用硬 ID 去重 |
 * | [LocalTextParser] | 「支付时间 2026年9月30日 21:14:23」—— 时分秒**必须**被取到 |
 *
 * 最后一条是本轮新修的：旧实现 `clear()` 之后只 set 年月日，时分秒直接丢成 00:00。
 * 丢成 00:00 的后果不是"时间显示不好看"，而是同一天的两笔账单详情页解析出
 * **完全相同的时间戳**，近重复合并判据（同额同向 + 20 秒）会把它们并成一笔，
 * 也就是真金白银的一笔账凭空消失。
 */
class WechatBillDetailTest {

    /**
     * 真机录屏那一屏，逐字照抄。
     * 节点是按读屏服务 `collectTexts()` 拿到的方式一行一个写的，
     * **不是** `uiautomator dump` 的划分——两者形状不同，别混用。
     */
    private val billDetail = listOf(
        "小红书平台商户",
        "-80.00",
        "当前状态  支付成功",
        "支付时间  2026年9月30日 21:14:23",
        "商品  小红书订单：be1ac972-52ec-4290-9e84-4ff71974c41f",
        "商户全称  行吟信息科技(上海)有限公司",
        "收单机构  财付通支付科技有限公司",
        "支付方式  零钱",
        "交易单号  4500000486202609301360845789",
        "商户单号  120000000d0fdfff66e035263c46",
        "对订单有疑惑",
        "发起群收款",
        "在此商户的交易",
        "申请电子凭证",
    )

    private val nodes = billDetail.map { Node(it) }
    private val joined = billDetail.joinToString("\n")

    @Test
    fun `bill detail page passes the shape gate as one transaction`() {
        val v = PaymentShape.inspect(nodes)
        assertTrue("账单详情页必须判成一笔，实际 $v", v is Verdict.OneTransaction)
    }

    @Test
    fun `bill detail page passes the payment gate`() {
        val v = PaymentGate.verdict(joined)
        assertTrue(
            "账单详情页必须过支付门，实际 $v —— 它是当前最稳的一条通路，挡掉等于没有自动记账",
            v is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    @Test
    fun `bill detail page yields an authoritative transaction id`() {
        val id = PaymentId.authoritative(joined)
        assertNotNull("必须抠出平台交易号", id)
        assertEquals("WECHAT_TXN:4500000486202609301360845789", id!!.key)
    }

    @Test
    fun `bill detail page parses 80 yuan as an expense`() {
        val r = LocalTextParser.parse(joined, FIXED_NOW)
        assertNotNull("必须解析出金额", r)
        assertEquals(8000L, r!!.amountsMinor.first())
        assertEquals(kapi.core.llm.Direction.EXPENSE, r.direction)
    }

    /**
     * 交易时刻必须是 **21:14:23**，不能是 00:00 也不能是"现在"。
     *
     * 用户是 21:16 录的屏，账单上的支付时间是 21:14:23。旧实现把时分秒丢掉，
     * 解析出来落在当天 00:00:00。
     */
    @Test
    fun `bill detail page keeps the payment clock not midnight`() {
        val r = LocalTextParser.parse(joined, FIXED_NOW)!!
        val c = Calendar.getInstance().apply { timeInMillis = r.occurredAt }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, c.get(Calendar.MONTH))
        assertEquals(30, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(
            "时分秒被丢了——这会让同一天的两笔账单解析出同一个时间戳",
            21, c.get(Calendar.HOUR_OF_DAY),
        )
        assertEquals(14, c.get(Calendar.MINUTE))
        assertEquals(23, c.get(Calendar.SECOND))
    }

    /**
     * 同一笔的两次读取（用户上下滑动、页面重绘）必须得到**同一个**交易号。
     *
     * 这是让账单详情页能当主通路的前提：用户回来看第二遍，账本里不能多一行。
     *
     * ## 顺带记一笔：这里原来写的是 `nodes.take(9).joinToString("\n")`
     *
     * `nodes` 是 `List<Node>`，`joinToString` 调的是**每个 Node 的 toString()**，
     * 拼出来的是 `Node(text=交易单号  4500…)` 这种包装串，不是屏幕上的文字。
     * 旧的宽松正则能在 `Node(text=` 里面照样搜到交易号，所以这个错一直没被发现；
     * 2026-09-30 把 [PaymentId] 改成"整行必须是标签行"之后，它才暴露成一条 NPE。
     *
     * 教训不是新代码太严，是**测试一直在拿一份和真实输入不同的数据跑**。
     * 真实输入永远是 [PaymentShape] 拼出来的 `texts.joinToString("\n")`。
     */
    @Test
    fun `revisiting the same bill keeps the same id`() {
        val a = PaymentId.authoritative(joined)!!.key
        // 重绘时少一个次要节点：多一屏少一屏是读屏的常态
        val b = PaymentId.authoritative(nodes.take(9).joinToString("\n") { it.text })!!.key
        assertEquals("同一笔账单两次读取的交易号必须一致", a, b)
    }

    /**
     * 交易记录列表页必须被形状闸挡下——它是"一屏记录"，不是"一笔"。
     *
     * 这一屏和上面的账单详情页是**同一个录屏里的前后两屏**，
     * 放在一起测才说明闸门真的在区分它们，而不是碰巧都放行。
     */
    @Test
    fun `transaction list page is blocked by the shape gate`() {
        val list = listOf(
            "账单",
            "交易记录",
            "云南超能云电科技有限公司",
            "14:47",
            "-10.00",
            "小红书平台商户",
            "21:14",
            "-80.00",
        ).map { Node(it) }
        val v = PaymentShape.inspect(list)
        assertTrue(
            "交易记录列表页必须判成一屏记录，实际 $v",
            v is Verdict.ManyTransactions,
        )
    }

    private companion object {
        /** 录屏时间是 2026-09-30 21:16:17。用它当 now，才能验出"没取页面时间"这种错。 */
        val FIXED_NOW: Long = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 30, 21, 16, 17)
        }.timeInMillis
    }
}
