package kapi.core.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备注合成的回归。全部夹具来自真机原文：
 * - 转账两屏 = 2026-10-01 realme 落库 `rawText`（与 [OwnAccountTest] 同源）
 * - 淘宝先用后付 = 2026-10-01 富账单详情页
 * - NYCD 皮带轮 = 2026-10-02 mcode 副手 dump（05/06，树序逐行照抄）
 */
class NoteComposerTest {

    /** 与 [OwnAccountTest] 同源的真机 rawText：10000.00 转给啦啦啦(朱娜娜)。 */
    private val transferOutToHer = """
        账单详情
        返回
        再转一笔
        转账凭证
        对此订单有疑问
        申请电子回单
        往来流水证明
        AA收款
        查看往来记录
        添加
        备注
        开关
        计入收支
        转账红包
        为您推荐
        请选择
        标签
        转账红包
        账单分类
        账单管理
        更多
        立即领取1积分
        支付奖励
        啦啦啦(朱娜娜) 153******10
        啦啦啦(朱娜娜) 153******10
        对方账户
        转账
        转账
        转账备注
        余额宝
        付款方式
        2026-10-01 11:31:39
        2026-10-01 11:31:39
        创建时间
        交易成功
        支出10000元
        啦啦啦(朱娜娜)
    """.trimIndent()

    /** 真机 rawText：12100.00 收到盖被吱的转账（屏上没有付款方式行）。 */
    private val transferInFromHim = """
        账单详情
        返回
        对此订单有疑问
        申请电子回单
        往来流水证明
        查看往来记录
        添加
        备注
        开关
        计入收支
        转账红包
        为您推荐
        请选择
        标签
        转账红包
        账单分类
        账单管理
        更多
        盖被吱 181******39
        盖被吱 181******39
        对方账户
        转账
        转账
        转账备注
        2026-10-01 08:54:39
        2026-10-01 08:54:39
        创建时间
        交易成功
        12100元
        盖被吱
    """.trimIndent()

    /** 2026-10-02 mcode dump `05-nycd-detail`，树序逐行照抄（收起态，商品贴在「共1件」前）。 */
    private val nycdCollapsed = """
        ny**店
        支出20元
        支付成功
        关联记录
        查看关联记录
        支付时间
        2026-10-01 08:28:01
        付款方式
        交通银行信用卡(5894) 查看扣款顺序
        扣款说明
        先用后付订单完成付款，已计入芝麻分评估
        支付奖励
        已领取3积分
        交易详情
        O1CN01PTl0Wq2MbWoe3vlJZ_!!4611686018427384790-0-item_pic
        NYCD非标同步轮定制3m5m8m现货皮带轮AF/AS/BF同步带轮定做铝合金
        共1件
        更多
        账单管理
        账单分类
        家居家装
        标签
        请选择
        为您推荐
        五金
        计入收支
        开关
        备注
        添加
    """.trimIndent()

    /** 2026-10-01 富账单详情页：杭州淘宝通信技术有限公司的先用后付。 */
    private val taobaoPayAfter = """
        杭州淘宝通信技术有限公司
        支出10.1元
        交易成功
        关联记录
        查看关联记录
        支付时间
        2026-10-01 13:48:06
        付款方式
        交通银行信用卡(5894) 查看扣款顺序
        商品说明
        先用后付
        支付奖励
        已领取2积分
        更多
        账单管理
        我的消费图鉴
        账单分类
        商业服务
        标签
        请选择
        计入收支
        开关
        备注
        添加
    """.trimIndent()

    @Test
    fun `transfer out names the counterparty`() {
        assertEquals("转给啦啦啦(朱娜娜)", NoteComposer.compose(transferOutToHer))
    }

    @Test
    fun `received transfer names the sender`() {
        assertEquals("收到盖被吱转账", NoteComposer.compose(transferInFromHim))
    }

    @Test
    fun `taobao purchase carries platform and product`() {
        assertEquals("淘宝平台消费·先用后付", NoteComposer.compose(taobaoPayAfter))
    }

    @Test
    fun `merchant order page notes the product beside the qty marker`() {
        val note = NoteComposer.compose(nycdCollapsed)!!
        assertTrue("应取商品名而不是图片资源 ID：$note", note.startsWith("NYCD非标同步轮"))
        assertTrue("超长商品名要截断：$note", note.endsWith("…"))
        assertTrue("备注要短：$note", note.length <= 31)
    }

    /** 值在标签前的页面（服务遍历顺序）同样取得到——方向探针的反向验证。 */
    @Test
    fun `value-before page still extracts the product description`() {
        val text = """
            转账收款到余额宝
            商品说明
            账户余额
            付款方式
            2026-10-01 08:54:54
            创建时间
            交易成功
            12100元
            余额宝
        """.trimIndent()
        assertEquals("转账收款到余额宝", NoteComposer.compose(text))
    }

    /** 还款页没有可备注的对象（OwnAccount 会拦下它），备注宁可空着。 */
    @Test
    fun `repayment page with nothing to say stays null`() {
        val text = """
            招商银行
            1360.11元
            还款成功
            创建时间
            2026-10-01 11:26:54
            付款方式
            余额宝
            还款到
            招商银行（9590）洪佳豪
            处理进度
            提交银行处理
            10-01 11:26
            银行处理中
            10-01 11:27
            银行入账成功
            进度说明
            银行反馈已入账，如有疑问请联系银行客服
            借还款说明
            信用卡还款
            支付奖励
            已领取5积分
            更多
        """.trimIndent()
        assertNull(NoteComposer.compose(text))
    }

    /** 没有平台词、没有商品行、不是转账——不编一句出来。 */
    @Test
    fun `no signal means no note`() {
        assertNull(NoteComposer.compose("支付成功\n¥23.50\n星巴克\n当前状态 支付成功"))
        assertNull(NoteComposer.compose(""))
    }

    /** 「先用后付订单完成付款」是状态说明不是商品，不能因为含平台词外的关键词就混进来。 */
    @Test
    fun `deduction status line is never used as product`() {
        // NYCD 屏上没有商品说明字段时取的是共N件前的商品名；
        // 若哪天把扣款说明误当商品源，这条会抓到「已计入芝麻分评估」进备注。
        val note = NoteComposer.compose(nycdCollapsed)!!
        assertTrue(!note.contains("芝麻分") && !note.contains("扣款"))
    }

    // ------------------------------------------------ 2026-10-02 mcode 二轮样本

    /** 闲鱼 TP-LINK 摄像头页（dump 17 树序）：平台词 + 商品说明 → 平台消费备注。 */
    @Test
    fun `xianyu purchase carries platform and product`() {
        val note = NoteComposer.compose(
            """
            x***7
            支出55元
            等待确认收货
            闲鱼100元以下免密支付
            管理免密支付
            2026-09-30 15:49:12
            支付时间
            花呗
            付款方式
            TP-LINK TL-IPC43AN-4 300万云台无线网
            TP-LINK TL-IPC43AN-4 300万云台无线网
            商品说明
            已领取4积分
            支付奖励
            推荐服务
            你有1个花呗红包可领取
            """.trimIndent(),
        )!!
        assertTrue("应带平台前缀：$note", note.startsWith("闲鱼平台消费·"))
        assertTrue("应带商品名：$note", note.contains("TP-LINK"))
    }

    /** 好医保页（dump 20 树序）：无商品说明无共N件，商品名在「交易详情」图片行的下一行。 */
    @Test
    fun `insurance product hides behind the image junk line`() {
        val note = NoteComposer.compose(
            """
            中国人民健康保险股份有限公司
            支出34.97元
            交易成功
            2026-09-23 11:24:02
            创建时间
            中国银行储蓄卡(0416) 查看扣款顺序
            付款方式
            交易详情
            O1CN01NfQIJ31f7GhvZuRwd_!!6000000003959-2-tps-140-140
            2026.9月保费缴清-好医保·长期医疗(旗舰版2026)-**娜
            推荐服务
            你的专属保障方案已生成
            去看看
            更多
            账单管理
            保险
            """.trimIndent(),
        )!!
        assertTrue("应取保费商品名：$note", note.startsWith("2026.9月保费缴清"))
    }

    /** 自动续费页（dump 19）：套餐名备注 + 自动续费前缀（用户口径「小佩自动续费」）。 */
    @Test
    fun `auto renew note is prefixed and carries the plan name`() {
        assertEquals(
            "自动续费·基础套餐连续包月",
            NoteComposer.compose(
                """
                小佩网络科技（上海）有限公司
                支出3.99元
                自动扣款成功
                小佩网络科技（上海）有限公司自动续费
                管理自动续费
                2026-09-23 08:41:41
                支付时间
                中国银行储蓄卡(0416) 查看扣款顺序
                付款方式
                基础套餐连续包月
                基础套餐连续包月
                商品说明
                """.trimIndent(),
            ),
        )
    }

    /** 只有「管理自动续费」、没有商品说明的订阅页：单独「自动续费」也比空着有用。 */
    @Test
    fun `auto renew without product still notes the recurrence`() {
        assertEquals(
            "自动续费",
            NoteComposer.compose(
                "某服务有限公司\n支出15.00元\n支付成功\n管理自动续费\n付款方式\n余额宝",
            ),
        )
    }

    /**
     * 退款判据必须是**状态词**（2026-10-02 全量审计）：普通支出页上长着
     * 「申请退款/查看退款」入口按钮——燃气/药店/餐饮三页都只有裸「退款」
     * 二字，不能因此把支出标成退款。
     */
    @Test
    fun `refund entry button is not a refund`() {
        val note = NoteComposer.compose(
            "某燃气公司\n支出100元\n交易成功\n支付宝小程序燃气缴费\n商品说明\n查看退款\n更多",
        )
        assertEquals("支付宝小程序燃气缴费", note)
    }

    /** 退款页（dump 13 树序）：无对象无商品，只标「退款」。 */
    @Test
    fun `refund page notes just the refund`() {
        assertEquals(
            "退款",
            NoteComposer.compose(
                """
                微雪**店
                184.47元
                退款成功
                查看原账单
                关联记录
                2026-09-29 16:47:45
                创建时间
                招商银行信用卡(9590)
                退款方式
                提交银行处理
                退款进度
                09-29 16:47
                银行处理中
                09-29 16:47
                银行处理成功
                """.trimIndent(),
            ),
        )
    }
}
