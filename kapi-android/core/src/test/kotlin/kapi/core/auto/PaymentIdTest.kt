package kapi.core.auto

import kapi.core.auto.PaymentId.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交易号抽取的测试。
 *
 * ## 样本是 2026-09-30 用户真机录屏里那一屏
 *
 * 原文来自微信「账单详情页」（用户从小红书那一笔点进去的那一屏，逐字照抄）：
 * ```
 * 小红书平台商户
 * -80.00
 * 当前状态  支付成功
 * 支付时间  2026年9月30日 21:14:23
 * 商品      小红书订单：be1ac972-52ec-4290-9e84-4ff71974c41f
 * 商户全称  行吟信息科技(上海)有限公司
 * 收单机构  财付通支付科技有限公司
 * 支付方式  零钱
 * 交易单号  4500000486202609301360845789
 * 商户单号  120000000d0fdfff66e035263c46
 * 对订单有疑惑
 * 发起群收款
 * 在此商户的交易
 * 申请电子凭证
 * ```
 *
 * 之所以拿这一屏当样本而不是编一个：这一屏上有**三个**长得差不多的长串
 * （交易单号 / 商户单号 / 商品订单里的 uuid），而 [PaymentId] 全部要靠标签区分。
 * 编出来的样本很容易只放一个标签，就把"多串并存"这个真实难点漏掉了。
 */
class PaymentIdTest {

    /** 微信账单详情页整屏，逐字照抄真机录屏。 */
    private val wechatBillDetail = """
        小红书平台商户
        -80.00
        当前状态  支付成功
        支付时间  2026年9月30日 21:14:23
        商品  小红书订单：be1ac972-52ec-4290-9e84-4ff71974c41f
        商户全称  行吟信息科技(上海)有限公司
        收单机构  财付通支付科技有限公司
        支付方式  零钱
        交易单号  4500000486202609301360845789
        商户单号  120000000d0fdfff66e035263c46
        对订单有疑惑
        发起群收款
        在此商户的交易
        申请电子凭证
    """.trimIndent()

    @Test
    fun `wechat bill detail exposes the platform transaction id`() {
        val id = PaymentId.extract(wechatBillDetail)
        assertNotNull("账单详情页必须能抠出交易单号", id)
        assertEquals(Kind.WECHAT_TXN, id!!.kind)
        assertEquals("4500000486202609301360845789", id.value)
    }

    @Test
    fun `wechat transaction id is authoritative so it can auto-drop duplicates`() {
        val id = PaymentId.authoritative(wechatBillDetail)
        assertNotNull("平台交易号必须能触发自动丢弃", id)
        assertTrue(id!!.authoritative)
        assertEquals("WECHAT_TXN:4500000486202609301360845789", id.key)
    }

    /**
     * 三选一：平台交易号优先于商户单号。
     *
     * 两个都在的时候拿商户单号去重，撞单号的商户就会导致**静默丢账**。
     * 而 [AutoBook] 的头号原则是"宁重复，不丢失"，所以这条钉死。
     */
    @Test
    fun `platform id wins over merchant order id`() {
        val only = PaymentId.extract("商户单号  120000000d0fdfff66e035263c46")
        assertEquals(Kind.MERCHANT_ORDER, only!!.kind)
        assertTrue("商户单号不能用来自动丢弃，否则会静默丢账", !only.authoritative)
        assertNull("商户单号不足以触发自动丢弃", PaymentId.authoritative("商户单号  120000000d0fdfff66e035263c46"))
    }

    /** 没有标签就不能取值：银行卡号、手机号、日期全是长数字串，不能被当成交易号。 */
    @Test
    fun `long digits without a label are not an id`() {
        assertNull(PaymentId.extract("银行卡号 6222021234567890123"))
        assertNull(PaymentId.extract("2026年9月30日 21:14:23"))
        assertNull(PaymentId.extract("订单：be1ac972-52ec-4290-9e84-4ff71974c41f"))
    }

    /** 支付成功页没有交易号，抽取必须干净地返回 null，让调用方退回文本指纹。 */
    @Test
    fun `payment success page has no id and falls back cleanly`() {
        val success = """
            回首页
            回首页
            完成
            付款方式
            零钱
            收款方
            啦啦啦(朱娜娜)
            支付成功￥23.50
        """.trimIndent()
        assertNull(PaymentId.extract(success))
        assertNull(PaymentId.authoritative(success))
    }

    /**
     * 短于 12 位的值不算 id。
     *
     * 阈值不是随手定的：微信交易单号 28 位、支付宝交易号 28 位，商户单号 32 位。
     * 取 12 是为了把明显更短的编号（订单尾号、门店号）挡在外面——
     * 那些东西跨商户会撞，撞了就丢账。
     */
    @Test
    fun `short values are rejected`() {
        assertNull(PaymentId.extract("交易单号 12345"))
        assertNotNull(PaymentId.extract("交易单号 123456789012"))
    }

    /** 支付宝账单里的字段叫「交易号」，值同样是 28 位数字。 */
    @Test
    fun `alipay transaction id is recognised`() {
        val id = PaymentId.extract("交易号  2026093022001487923401234567")
        assertEquals(Kind.ALIPAY_TXN, id!!.kind)
        assertTrue(id.authoritative)
    }

    @Test
    fun `blank input yields null`() {
        assertNull(PaymentId.extract(null))
        assertNull(PaymentId.extract(""))
        assertNull(PaymentId.extract("   \n  "))
    }

    // ============================================================ 2026-09-30 23:34 补
    //
    // 上面 8 条用的样本是**照着截图抄的**：标签和值在同一行、用空格隔开
    // （`交易单号  4500000486…`）。真机跑起来才发现不是这样——

    /** 微信账单详情页，**无障碍树里的真实节点顺序**（日志 23:34:10 原文，`|` 是换行）。 */
    private val wechatNodesValueFirst = """
        本服务由财付通提供
        商家电话
        联系商家
        申请电子凭证
        在此商户的交易
        发起群收款
        对订单有疑惑
        账单服务
        商家小程序 象子茶铺
        T1312174297162407936
        商户单号
        4500000488202609303522863079
        交易单号
        支付方式
        零钱
        当前状态
        支付成功
        支付时间
        2026年9月30日 21:52:16
        -13.00
    """.trimIndent()

    /**
     * 节点顺序是「**值 → 标签**」，不是「标签 → 值」。
     *
     * 截图上明明是「交易单号 | 4500…」这种左标签右值的样子，但无障碍树遍历出来
     * 的顺序是反的。所以按标签找"后面紧跟的数字"必然落空：
     * `交易单号` 后面跟的是「支付方式」，压根不是号。
     *
     * 旧实现在真机上的表现是这一整条用例挂掉，而且挂得很难看——
     * 见下一条。
     */
    @Test
    fun `value before label is still found`() {
        val id = PaymentId.extract(wechatNodesValueFirst)
        assertNotNull("真实节点顺序下也必须抠出交易号", id)
        assertEquals(Kind.WECHAT_TXN, id!!.kind)
        assertEquals("4500000488202609303522863079", id.value)
    }

    /**
     * 最阴的一条：旧实现**不是没抽到**，是抽到了一个**贴错标签**的号。
     *
     * `商户单号` 的下一行恰好是交易号，于是旧正则把 `4500…` 当成了商户单号。
     * 后果是判成 `MERCHANT_ORDER` → 非权威 → 退回文本指纹，
     * 90 天的硬去重悄悄退化成 3 分钟。
     *
     * 而日志里只留下一句「指纹 8a8b9c5a… 在去重窗口里，丢弃」，
     * 看起来完全正常——第 3 关从来没生效过，却没有任何一处会报错。
     * 这条用例钉的就是"必须能触发自动丢弃"，而不只是"能抽到东西"。
     */
    @Test
    fun `the platform id is still authoritative with value-first node order`() {
        val id = PaymentId.authoritative(wechatNodesValueFirst)
        assertNotNull("这一屏必须走硬 ID 去重，不能退回指纹", id)
        assertEquals("WECHAT_TXN:4500000488202609303522863079", id!!.key)
    }

    /** 同一个号不能因为挨着「商户单号」就被贴上商户单号的标签。 */
    @Test
    fun `the merchant label keeps its own value`() {
        val only = PaymentId.extract("T1312174297162407936\n商户单号")
        assertEquals(Kind.MERCHANT_ORDER, only!!.kind)
        assertEquals("T1312174297162407936", only.value)
    }

    /**
     * 微信「转账详情页」的真实节点顺序，也是值在标签前。
     *
     * 这一屏的单号叫「转账单号」，是微信平台自己发的、每笔唯一，
     * 完全可以当硬去重键。旧词表里没有它，于是这一类只能退回指纹。
     */
    @Test
    fun `wechat transfer order number is an authoritative id`() {
        val nodes = """
            本服务由财付通提供
            查看往来转账
            定位到聊天位置
            对订单有疑惑
            账单服务
            1000050001202609300029185285412
            转账单号
            零钱
            支付方式
            2026年9月30日 17:17:49
            转账时间
            微信转账
            转账说明
            -1.00
        """.trimIndent()
        val id = PaymentId.authoritative(nodes)
        assertNotNull("转账单号是平台发的唯一号，应当能自动去重", id)
        assertEquals(Kind.WECHAT_TXN, id!!.kind)
        assertEquals("1000050001202609300029185285412", id.value)
    }

    /**
     * 同一行写法（银行 App 常见）必须继续支持，不能为了修微信把它改坏。
     */
    @Test
    fun `label and value on the same line still work`() {
        val id = PaymentId.extract("交易单号：4500000486202609301360845789")
        assertEquals(Kind.WECHAT_TXN, id!!.kind)
        assertEquals("4500000486202609301360845789", id.value)
    }

    /** 「小红书订单: be1ac972-…」不是标签行，里面的 uuid 是商品 id，拿它去重会静默丢账。 */
    @Test
    fun `a product order line is not an id label`() {
        val nodes = """
            -13.00
            商品
            小红书订单: be1ac972-52ec-4290-9e84-4ff71974c41f
            商户全称
            行吟信息科技(上海)有限公司
        """.trimIndent()
        assertNull(PaymentId.authoritative(nodes))
    }
}
