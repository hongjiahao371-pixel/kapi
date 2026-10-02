package kapi.core.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-01 realme 支付宝，**同一批**真机原文。
 *
 * 三屏放在一起才有意义——它们共用同一套界面结构（`对方账户` / `付款方式` / `转账`），
 * 方向各不相同。要判据只认其中一种，测试就会绿而真机照样错。
 */
class OwnAccountTest {

    /**
     * 真机落库的 `rawText`，逐行照抄。
     * 金额 121.00，`账户余额 → 余额宝`，支付宝把「计入收支」关着。
     */
    private val yuEbaoInternal = """
        转账收款到余额宝
        商品说明
        账户余额
        付款方式
        2026-10-01 08:54:54
        2026-10-01 08:54:54
        创建时间
        交易成功
        12100元
        余额宝
    """.trimIndent().lines()

    /** 真机落库的 `rawText`，逐行照抄。10000.00 转给啦啦啦(朱娜娜)。 */
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
    """.trimIndent().lines()

    /** 真机落库的 `rawText`，逐行照抄。121.00 收到盖被吱的转账。 */
    private val transferInFromHer = """
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
    """.trimIndent().lines()

    @Test
    fun `moving money between own wallets is not a purchase`() {
        val v = OwnAccount.judge(yuEbaoInternal)
        assertTrue(
            "账户余额 → 余额宝 是内部划转，实际 $v",
            v is OwnAccount.Verdict.SelfTransfer,
        )
        val t = v as OwnAccount.Verdict.SelfTransfer
        assertEquals("账户余额", t.from)
        assertEquals("余额宝", t.to)
    }

    /**
     * 最关键的一条：**两端都写 `对方账户`**，来处也确实是自己的余额宝。
     * 只判"有一端是钱包"就会把这笔真支出吃掉。
     */
    @Test
    fun `paying a person is never a self transfer`() {
        assertEquals(
            "转给啦啦啦(朱娜娜) 是真支出，实际 ${OwnAccount.judge(transferOutToHer)}",
            OwnAccount.Verdict.NotSelfTransfer,
            OwnAccount.judge(transferOutToHer),
        )
    }

    @Test
    fun `receiving from a person is never a self transfer`() {
        assertEquals(
            "收到盖被吱的转账是真收入，实际 ${OwnAccount.judge(transferInFromHer)}",
            OwnAccount.Verdict.NotSelfTransfer,
            OwnAccount.judge(transferInFromHer),
        )
    }

    /**
     * 还自己的信用卡：这一屏**根本没有付款账户**可读，两端比不出来。
     * 只能靠交易类型名。
     */
    @Test
    fun `a credit card repayment is a self transfer even with no funding account on screen`() {
        val v = OwnAccount.judge(
            listOf(
                "账单详情", "返回", "对此订单有疑问", "申请电子回单", "查看往来记录",
                "添加", "备注", "开关", "计入收支", "还款", "为您推荐", "请选择",
                "标签", "信用借还", "账单分类", "账单管理", "更多",
                "立即领取5积分", "支付奖励", "信用卡还款", "信用卡还款",
                "借还款说明", "银行反馈已入账，如有疑问",
                "739.9元", "还款成功", "处理进度", "银行处理中",
            ),
        )
        assertTrue("信用卡还款是自转账，实际 $v", v is OwnAccount.Verdict.SelfTransfer)
    }

    /**
     * 还**别人的**钱是真支出。裸的「还款」不收进词表就是为了这一条。
     */
    @Test
    fun `repaying a lender is a real expense`() {
        val v = OwnAccount.judge(
            listOf("还款", "XX小额贷款有限公司", "对方账户", "2000元", "转账成功"),
        )
        assertEquals(
            "还贷是支出，不能被当成自转账",
            OwnAccount.Verdict.NotSelfTransfer,
            v,
        )
    }

    /**
     * 相邻重复节点会干扰取值：`余额宝 / 付款方式` 若变成
     * `余额宝 / 余额宝 / 付款方式`，取到的还是余额宝——但**顺序反过来**就不一定。
     * 这里钉的是折叠前后结论一致。
     */
    @Test
    fun `duplicated nodes do not change the verdict`() {
        val doubled = transferOutToHer.flatMap { listOf(it, it) }
        assertEquals(
            "重复节点前后结论必须一致",
            OwnAccount.judge(transferOutToHer),
            OwnAccount.judge(doubled),
        )
    }

    /**
     * 两侧的匹配规则**本来就不该一样**：
     * `付款方式` 是账户字段（带修饰），`收款方`/`对方账户` 是人名字段。
     * 有一家便利店就叫「余额宝便利店」——按子串去判，往那儿付的钱会被静默吃掉。
     */
    @Test
    fun `a shop whose name contains a wallet word is not a wallet`() {
        assertTrue(
            "「余额宝便利店」是店名，人名侧只认全等",
            !OwnAccount.isOwnWalletExact("余额宝便利店"),
        )
        assertTrue(
            "但账户字段仍按子串认：付款方式写的就是「账户余额」这种带修饰的名字",
            OwnAccount.isOwnWallet("招商银行储蓄卡(余额宝)"),
        )
        assertTrue("「零钱」是钱包", OwnAccount.isOwnWalletExact("零钱"))
        assertTrue("null 不是钱包", !OwnAccount.isOwnWallet(null))
        assertTrue("空串不是钱包", !OwnAccount.isOwnWallet("   "))
    }

    /**
     * 走人名侧时，**整笔仍然不是自转账**——这是一条真的假账防线。
     */
    @Test
    fun `paying a shop named after a wallet is a real expense`() {
        val v = OwnAccount.judge(
            listOf(
                "账单详情", "返回",
                "余额宝", "付款方式",
                "余额宝便利店", "对方账户",
                "转账", "转账备注",
                "2026-10-02 12:00:00", "交易成功", "支出20元", "余额宝便利店",
            ),
        )
        assertEquals(
            "往「余额宝便利店」付的 20 块是真消费，不能被吃掉",
            OwnAccount.Verdict.NotSelfTransfer,
            v,
        )
    }

    /**
     * 名词命中路径也必须把两端算出来（2026-10-02 真机缺陷）：
     * 「信用卡还款」页当天日志是 `from=null, to=null`——屏上明明有
     * `付款方式 / 余额宝`。原因是名词表命中直接 return，没算 sides。
     *
     * 这一屏还是**值在标签后**的页面（mcode dump 树序，与上面夹具相反），
     * 靠日期探针取值——两类方向都要能读。
     */
    @Test
    fun `repayment page with value-after layout records both ends`() {
        val v = OwnAccount.judge(
            listOf(
                "招商银行", "1360.11元", "还款成功",
                "创建时间", "2026-10-01 11:26:54",
                "付款方式", "余额宝",
                "还款到", "招商银行（9590）洪佳豪",
                "处理进度", "提交银行处理", "10-01 11:26", "银行处理中",
                "10-01 11:27", "银行入账成功",
                "进度说明", "银行反馈已入账，如有疑问请联系银行客服",
                "借还款说明", "信用卡还款",
                "支付奖励", "已领取5积分", "更多",
            ),
        )
        assertTrue("信用卡还款是自转账，实际 $v", v is OwnAccount.Verdict.SelfTransfer)
        val t = v as OwnAccount.Verdict.SelfTransfer
        assertEquals("余额宝", t.from)
        assertEquals("招商银行（9590）洪佳豪", t.to)
    }
}
