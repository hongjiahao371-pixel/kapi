package kapi.core.auto

import kapi.core.llm.Direction
import kapi.core.llm.LocalTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 微信样本轮（2026-10-02 OPPO PMA110，mcode 视觉扫描 22 类）钉出的回归。
 * 全部夹具来自真机截图逐字转写。
 */
class WechatSampleRoundTest {

    /** 微信转账给个人（截图 13）：「加油无忧无虑」是微信昵称，不是加油消费。 */
    private val transferToJiayou = """
        转账-转给加油无忧无虑
        -500.00
        当前状态
        对方已收钱
        转账说明
        w20结算
        转账时间
        2026年9月30日 16:04:57
        收款时间
        2026年9月30日 16:05:52
        支付方式
        零钱
        转账单号
        1000050001202609300629488484283
        账单服务
        对订单有疑惑
        定位到聊天位置
        申请转账电子凭证
        查看往来转账
        本服务由财付通提供
    """.trimIndent()

    /** 理财通赎回（截图 14）：收入，落理财。 */
    private val lctRedemption = """
        理财通赎回-活期 +
        800.00
        当前状态
        已到账
        收款方式
        中国银行储蓄卡(0416)
        取出产品
        活期 +
        到账时间
        2026年9月28日 12:09:09
        交易单号
        180000782411260928040071188737 00
    """.trimIndent()

    /** 小红书平台商户（截图 06）：平台消费。 */
    private val xhsMerchant = """
        小红书平台商户
        支出100元
        交易成功
        当前状态
        交易成功
        支付时间
        2026年10月1日 21:54:12
        商品说明
        小红书商品订单
        付款方式
        零钱
    """.trimIndent()

    /** 分类：微信名含「加油」不能抢成交通——转账豁免的「转给」扩展。 */
    @Test
    fun `transfer to a person named jiayou is not fuel`() {
        assertNull(
            "转账给个人不猜品类",
            CategoryRules.classify("转账-转给加油无忧无虑", transferToJiayou, income = false),
        )
    }

    /** 理财通赎回是收入，落理财（「赎回」「理财通」进词表）。 */
    @Test
    fun `licaitong redemption goes to investment income`() {
        assertEquals(
            "理财",
            CategoryRules.classify(null, lctRedemption, income = true)?.major,
        )
    }

    /** 小红书是平台（rawOnly 兜底）：平台商户页归购物，备注带平台名。 */
    @Test
    fun `xiaohongshu merchant page is shopping with platform note`() {
        assertEquals("购物", CategoryRules.classify("小红书平台商户", xhsMerchant, income = false)?.major)
        val note = NoteComposer.compose(xhsMerchant)!!
        assertEquals("小红书平台消费·小红书商品订单", note)
    }

    /** luckin coffee（截图 07）归咖啡茶饮。 */
    @Test
    fun `luckin coffee goes to coffee tea`() {
        assertEquals(
            "餐饮/咖啡茶饮",
            CategoryRules.classify("luckin coffee", "支出11.90元\n支付成功", income = false)?.text,
        )
    }

    /** 微信转账页没有对方账户字段，备注从**标题行**取对象。 */
    @Test
    fun `wechat transfer note comes from the title line`() {
        assertEquals("转给加油无忧无虑", NoteComposer.compose(transferToJiayou))
        assertEquals(
            "收到view转账",
            NoteComposer.compose(
                "转账-来自view\n+100.00\n已收款\n2026年9月20日 16:04\n收款时间\n零钱\n付款方式",
            ),
        )
    }

    /** 方向与金额整链（13 页原文）：-500 支出。 */
    @Test
    fun `wechat transfer parses as expense with correct amount`() {
        val r = LocalTextParser.parse(transferToJiayou, System.currentTimeMillis())!!
        assertEquals(500_00L, r.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    /** 麦当劳（OPPO 库真机草稿）：商户全称「云南滇美餐饮」毫无品类信息，靠品牌词。 */
    @Test
    fun `mcdonald goes to fast food`() {
        assertEquals(
            "餐饮/正餐",
            CategoryRules.classify(
                "麦当劳",
                "商家小程序 麦当劳\n商户全称\n云南滇美餐饮有限公司\n商品\n麦当劳\n支付成功",
                income = false,
            )?.text,
        )
    }

    /** 象子茶铺茶（OPPO 库真机草稿）：「茶铺」进茶饮词表。 */
    @Test
    fun `teahouse goes to coffee tea`() {
        assertEquals(
            "餐饮/咖啡茶饮",
            CategoryRules.classify("象子茶铺茶", "支出13元\n交易成功", income = false)?.text,
        )
    }

    /** 微信的商品字段名是「商品」，停车页备注=车牌照+停车场。 */
    @Test
    fun `wechat product field feeds the note`() {
        val note = NoteComposer.compose(
            """
            速停车
            支出2元
            支付成功
            云AFM6585-奥斯迪商务中心A座（沃尔玛）
            商品
            银联商务支付股份有限公司
            收单机构
            厦门泊云速停车科技有限公司
            商户全称
            零钱通
            支付方式
            """.trimIndent(),
        )
        assertEquals("云AFM6585-奥斯迪商务中心A座（沃尔玛）", note)
    }
}
