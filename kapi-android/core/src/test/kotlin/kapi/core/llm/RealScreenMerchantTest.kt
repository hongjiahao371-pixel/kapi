package kapi.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机读屏样本的回归：微信转账详情页、支付宝账单详情页。
 *
 * ## 这两屏为什么难
 *
 * 它们的节点顺序都是**值在标签前面**（见 [PaymentId] 的类注释），而且**抽屉菜单
 * 的噪音排在真正数据的前面**。金额之前可能隔着二三十行菜单项。
 *
 * 原来的 [LocalTextParser.guessMerchant] 是"从金额行向上逐行找第一个看起来像名字的"，
 * 于是在这两屏上分别抓到了：
 *  - 微信：`等待对方确认收钱`（这是「当前状态」这个字段的**值**，不是商户）
 *  - 支付宝：`账单分类`（这是侧边抽屉的一个菜单项）
 *
 * 金额和方向一直是对的，所以这个错很隐蔽：账目金额没错，只有商户栏是脏的，
 * 而商户栏正是历史统计和搜索要用��字段。
 */
class RealScreenMerchantTest {

    /**
     * 2026-10-01 realme RMX3852 / 微信 8.0.78 / 转账详情页。
     * 节点顺序原样，未做任何重排。
     */
    private val wechatTransfer = """
        加载中
        本服务由财付通提供
        查看往来转账
        定位到聊天位置
        对订单有疑惑
        账单服务
        1000050001202610010626797537039
        转账单号
        中国银行储蓄卡(0416)
        支付方式
        2026年10月1日 14:45:40
        转账时间
        微信转账
        转账说明
        提醒对方收款
        等待对方确认收钱
        当前状态
        -0.10
        转账-转给橘生
    """.trimIndent()

    /**
     * 2026-10-01 realme RMX3852 / 支付宝 12.12.30.8000 / 账单详情页。
     * 前 20 行全是侧边抽屉菜单，噪音比微信那屏更重。
     */
    private val alipayBill = """
        账单详情
        返回
        对此订单有疑问
        往来流水证明
        查看往来记录
        添加
        备注
        开关
        计入收支
        使用记账本，查看自定义分类、标签统计
        请选择
        标签
        商业服务
        账单分类
        账单管理
        更多
        立即领取2积分
        支付奖励
        先用后付
        先用后付
        商品说明
        交通银行信用卡(5894)
        付款方式
        2026-10-01 13:48:06
        2026-10-01 13:48:06
        支付时间
        查看关联记录
        关联记录
        交易成功
        支出10.1元
        杭州淘宝通信技术有限公司
    """.trimIndent()

    // ---------------------------------------------------------------- 微信

    @Test
    fun `微信转账详情页：金额与方向本来就对`() {
        val r = LocalTextParser.parse(wechatTransfer)
        assertNotNull("整屏应该解析得出来", r)
        r!!
        assertEquals(10L, r.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun `微信转账详情页：商户是对面的人，不是状态行`() {
        val m = LocalTextParser.parse(wechatTransfer)!!.merchant
        assertTrue(
            "商户应该是「转账-转给橘生」，实际是 $m",
            m != null && m.contains("橘生"),
        )
    }

    @Test
    fun `微信转账详情页：不能把「当前状态」的值当商户`() {
        val m = LocalTextParser.parse(wechatTransfer)!!.merchant
        assertTrue(
            "「等待对方确认收钱」是状态字段的值，不能当商户，实际是 $m",
            m == null || !m.contains("等待对方确认收钱"),
        )
    }

    // -------------------------------------------------------------- 支付宝

    @Test
    fun `支付宝账单详情页：金额与方向本来就对`() {
        val r = LocalTextParser.parse(alipayBill)
        assertNotNull("整屏应该解析得出来", r)
        r!!
        assertEquals(1010L, r.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun `支付宝账单详情页：商户是商户全称，不是抽屉菜单项`() {
        val m = LocalTextParser.parse(alipayBill)!!.merchant
        assertTrue(
            "商户应该是「杭州淘宝通信技术有限公司」，实际是 $m",
            m != null && m.contains("淘宝通信"),
        )
    }

    @Test
    fun `支付宝账单详情页：不能把抽屉菜单项当商户`() {
        val m = LocalTextParser.parse(alipayBill)!!.merchant
        for (noise in listOf("账单分类", "账单管理", "商业服务", "更多", "标签")) {
            assertTrue(
                "抽屉菜单项「$noise」不能当商户，实际是 $m",
                m == null || m != noise,
            )
        }
    }

    // -------------------------------------------------------------- 负例

    /**
     * 商户紧贴在金额**上面**的老形状必须继续能用。
     *
     * 这是加"只在金额附近找"这条限制时最容易打破的一类：
     * 通知文本里商户在金额行上一行是常态，不能为了修真机读屏把它改坏。
     */
    @Test
    fun `商户在金额上一行的老形状不受影响`() {
        val r = LocalTextParser.parse(
            """
            交易成功
            盒马鲜生
            实付 ¥156.80
            时间 12:03
            """.trimIndent()
        )
        assertNotNull(r)
        assertEquals("盒马鲜生", r!!.merchant)
    }

    /** 微信支付成功页：商户在金额**下面**一行，也不能被改坏。 */
    @Test
    fun `商户在金额下一行的支付成功页不受影响`() {
        val r = LocalTextParser.parse(
            """
            支付成功
            ¥23.50
            星巴克
            当前状态 支付成功
            """.trimIndent()
        )
        assertNotNull(r)
        assertEquals("星巴克", r!!.merchant)
    }

    /** 整屏没有任何像名字的东西时，宁可空着。 */
    @Test
    fun `没有可信候选时返回空而不是硬猜`() {
        val r = LocalTextParser.parse(
            """
            支付成功
            ¥23.50
            当前状态 支付成功
            """.trimIndent()
        )
        assertNotNull(r)
        assertEquals(null, r!!.merchant)
    }
}
