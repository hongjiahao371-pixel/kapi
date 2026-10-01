package kapi.core.auto

import kapi.core.auto.PaymentShape.Node
import kapi.core.auto.PaymentShape.Verdict
import kapi.core.llm.Direction
import kapi.core.llm.LocalTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-30 23:12–23:15 真机上**真实发生过**的三次误判。
 *
 * ## 为什么这三个用例最重要
 *
 * 它们不是"设计上的边界情况"，是用户**亲眼看到**的三个现象：
 * 钱包页面凭空多出一笔 1008.30 的支出、支付宝一笔 0.1 元的支出记成了收入、
 * 支付宝首页上一笔真实的账因为旁边有张促销 banner 而没记上。
 *
 * 而且三个都是**不报错**的：控制台干净、流程走完、账本里就是多了或少了那一行。
 * 纯逻辑测试之前全绿，因为用例都是自己编的、每屏只有一两个节点——
 * 真实页面有 27~38 个节点，里面混着余额、促销位、别人的消息。
 */
class RealScreenFalsePositiveTest {

    // ------------------------------------------------------------ 余额被记成支出

    /**
     * 微信钱包页。原文就是日志里那句 `形状像一笔：27 个节点，1 个带金额条目（钱包¥1008.30）`。
     *
     * 那一屏用户**一分钱没花**，但它生成了一条 1008.30 的支出草稿。
     * 危害等级最高：假账用户看不出来，还会污染月度统计。
     */
    private val wechatWallet = listOf(
        "微信",
        "钱包",
        "钱包¥1008.30",
        "零钱",
        "零钱¥0.10",
        "服务",
    ).map { Node(it) }

    @Test
    fun `wallet balance is never counted as a transaction`() {
        val v = PaymentShape.inspect(wechatWallet)
        // 挡不住的还有 PaymentGate 兜一层，这里钉住的是形状闸这道第一道门
        assertTrue(
            "钱包余额必须被形状闸挡下——它不是一笔消费，实际 $v",
            v is Verdict.ManyTransactions,
        )
    }

    @Test
    fun `wallet balance is never parsed as an amount`() {
        val r = LocalTextParser.parse("微信\n钱包\n钱包¥1008.30\n零钱\n零钱¥0.10")
        // 解析器可以给出候选，但金额必须是 0.10（零钱）而不是 1008.30（余额）
        // ——具体由 PaymentShape 挡在前面；这里只保证余额不参与候选。
        if (r != null) {
            assertTrue(
                "余额 1008.30 不该被当成消费金额，实际 ${r.amountsMinor}",
                r.amountsMinor.none { it == 100830L },
            )
        }
    }

    @Test
    fun `wallet balance is wiped from the fingerprint`() {
        val a = AutoBook.normalize("钱包\n钱包¥1008.30\n服务")
        val b = AutoBook.normalize("钱包\n钱包¥9987.65\n服务")
        assertEquals(
            "余额变了指纹不能变——否则同一笔的两次读取会各生成一条",
            a, b,
        )
    }

    // ------------------------------------------------------ 方向被整屏里别的词翻掉

    /**
     * 支付宝一屏 38 个节点，其中这一行是 `支出0.1元`。
     *
     * 实际判成了**收入**（日志：``新草稿 …：10 INCOME``）。原因：整屏任意一个
     * 节点出现「到账 / 退款 / 红包」就先把收入判定了，哪怕「支出」就贴在金额前面。
     */
    @Test
    fun `expense word right next to the amount beats a distant income word`() {
        val screen = buildString {
            append("支付宝\n")
            append("账单\n")
            append("支出0.1元\n")          // ← 真正决定方向的那一行
            append("到账\n")                 // ← 距离 6 个字符
            append("退款记录\n")
            append("红包\n")
        }
        val r = LocalTextParser.parse(screen, 1_700_000_000_000L)
        assertEquals(
            "「支出0.1元」就贴在金额前面，不能被远处的「到账/红包」翻成收入",
            Direction.EXPENSE, r!!.direction,
        )
        assertEquals(10L, r.amountsMinor.first())
    }

    @Test
    fun `a distant expense word does not beat an income word next to the amount`() {
        val screen = "到账100.00元\n退款说明\n"
        val r = LocalTextParser.parse(screen, 1_700_000_000_000L)
        assertEquals(
            "「到账」离金额最近就该判收入，哪怕别处有「退款」这种支出向的词",
            Direction.INCOME, r!!.direction,
        )
    }

    // ------------------------------------------------- 促销 banner 挤掉了真实的那一笔

    /**
     * 支付宝首页。日志原文：
     * `整屏有 2 个带金额的独立条目（闲鱼100元以下免密支付 / 支出55元）`
     *
     * 「闲鱼100元以下免密支付」是一张运营 banner，不是消费。它被数成一个独立条目后，
     * 加上「支出55元」正好凑够阈值，这一屏被判成"一屏记录"整个挡掉——
     * **该记的没记**，界面上什么都不出现，比假账更难发现。
     */
    private val alipayHome = listOf(
        "支付宝",
        "闲鱼100元以下免密支付",
        "支出55元",
        "扫一扫",
        "收付款",
    ).map { Node(it) }

    @Test
    fun `promo banner is not a transaction entry`() {
        val v = PaymentShape.inspect(alipayHome)
        assertTrue(
            "促销 banner 不能被数成独立条目，否则真实那笔被挡掉——实际 $v",
            v is Verdict.OneTransaction,
        )
    }

    @Test
    fun `promo banner does not become the amount either`() {
        val r = LocalTextParser.parse("闲鱼100元以下免密支付\n支出55元", 1_700_000_000_000L)
        // 「100元」是免密门槛不是金额；第一候选必须是 55 元
        assertEquals(5500L, r!!.amountsMinor.first())
    }

    // ---------------------------------------------- 反向：别把修假账修成全面漏记

    /**
     * 银行交易详情页。这是**最容易被我改过头**的一屏。
     *
     * 它同时有两个金额，「余额」在账户状态词表里：
     * ```
     * 消费支出23.50元
     * 余额1234.56元
     * ```
     * 如果"含账户状态词 → 整屏否掉"，这笔 23.50 的真实消费就没了。
     * 正确做法是**逐个节点判**——23.50 那个没有否定词，1234.56 那个有。
     *
     * 这条用例在 2026-09-30 加进来时立刻有用：它是把 wallet 误判修好之后，
     * 用来证明那笔修法没有顺手把银行消费一起打死。
     */
    @Test
    fun `bank detail keeps the spend and drops the balance beside it`() {
        val nodes = listOf("消费支出23.50元", "余额1234.56元", "中国银行储蓄卡(0416)", "完成")
            .map { Node(it) }
        val v = PaymentShape.inspect(nodes)
        assertTrue("有真实消费时不能整屏挡掉，实际 $v", v is Verdict.OneTransaction)

        val r = LocalTextParser.parse("消费支出23.50元\n余额1234.56元", 1_700_000_000_000L)
        assertEquals("23.50 是消费，1234.56 是余额", 2350L, r!!.amountsMinor.first())
    }

    /**
     * 同一个否定不能连坐：**这一屏有一笔真消费时，旁边的余额和促销位都得让路**。
     *
     * 对应支付宝首页那一屏：banner 和余额都在，只有「支出55元」是账。
     */
    @Test
    fun `a real amount on the screen wins over a balance next to it`() {
        val nodes = listOf("钱包¥1008.30", "支出55元", "闲鱼100元以下免密支付").map { Node(it) }
        val v = PaymentShape.inspect(nodes)
        assertTrue("真消费优先于同屏的余额和促销，实际 $v", v is Verdict.OneTransaction)

        val r = LocalTextParser.parse("钱包¥1008.30\n支出55元", 1_700_000_000_000L)
        assertEquals(5500L, r!!.amountsMinor.first())
    }
}
