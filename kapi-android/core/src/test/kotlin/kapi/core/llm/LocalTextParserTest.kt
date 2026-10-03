package kapi.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 降级通路的测试。
 *
 * 这条路径**永远可用**——不联网、不烧 token、不看模型脸色。BYOK 产品的命脉就在
 * 这：如果它挂了而用户没有任何退路，用户第一次失败就永久流失。
 */
class LocalTextParserTest {

    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 29, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(s: String?) = LocalTextParser.parse(s, now)

    @Test
    fun `wechat style payment notification`() {
        val r = parse("微信支付\n商户全称：星巴克（国贸店）\n支付金额 ¥23.50\n支付方式 零钱")
        assertNotNull(r)
        assertEquals(2350L, r!!.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun `alipay style notification`() {
        val r = parse("你有一笔支出\n瑞幸咖啡(望京店)\n实付 19.90 元\n付款方式 余额宝")
        assertNotNull(r)
        assertEquals(1990L, r!!.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun `refund is income not expense`() {
        // 方向判断错了就是把退款记成支出，是账本里最误导人的一类错误。
        val r = parse("退款到账\n订单号 1234567890\n退款金额 ¥128.00")
        assertNotNull(r)
        assertEquals(12800L, r!!.amountsMinor.first())
        assertEquals(Direction.INCOME, r.direction)
    }

    @Test
    fun `merchant is guessed from the line holding the amount`() {
        val r = parse("交易成功\n盒马鲜生\n实付 ¥156.80\n时间 12:03")
        assertNotNull(r)
        assertTrue("商户名应被识别", r!!.merchant?.contains("盒马") == true)
    }

    @Test
    fun `merchant is not guessed from digits`() {
        // 订单号、卡号这类不能当商户名，会污染历史统计。
        val r = parse("订单 2026092912345678 已支付\n实付 ¥23.50")
        assertNotNull(r)
        assertNull("含数字的行不该被当成商户名", r!!.merchant)
    }

    /**
     * 置信度上限被刻意压到 0.55。规则匹配经常差一点：把券面额当实付、
     * 把订单号当金额。UI 必须始终要求用户确认。
     */
    @Test
    fun `confidence is capped low so the ui always asks`() {
        val r = parse("支付 ¥23.50")!!
        assertTrue("置信度必须低,UI 应当总是要求确认", r.confidence <= 0.55)
    }

    @Test
    fun `multiple amounts lower the confidence further`() {
        val one = parse("实付 ¥23.50")!!
        val two = parse("原价 ¥30.00 实付 ¥23.50 优惠 ¥6.50")!!
        assertTrue("金额越不确定,信心必须越低", two.confidence < one.confidence)
    }

    @Test
    fun `today and yesterday`() {
        // 这里**不能**再按 12:00 算。早期版本会把读不出时刻的文本一律归到当天中午，
        // 于是同一分钟内的事件时间戳完全相同，而自动记账的跨来源合并判据正是
        // "同额同向 + 120 秒内"——两笔真实消费会被并成一笔，**静默丢账**。
        // 现在的约定是：保留事件发生时刻的时分，只把毫秒清零。
        fun atEventTime(base: Long): Long = Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val r = parse("昨天 12:30\n瑞幸咖啡\n实付 ¥19.90")
        assertNotNull(r)
        assertEquals(atEventTime(now) - 24 * 3600_000L, r!!.occurredAt)
    }

    @Test
    fun `explicit date`() {
        val r = parse("2026-09-15 12:30\n瑞幸咖啡\n实付 ¥19.90")
        assertNotNull(r)
        val c = Calendar.getInstance().apply { timeInMillis = r!!.occurredAt }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, c.get(Calendar.MONTH))
        assertEquals(15, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `no amount means no result rather than zero`() {
        assertNull(parse("您有一条新消息"))
        assertNull(parse(""))
        assertNull(parse(null))
        assertNull(parse("订单已提交,请等待商家确认"))
    }

    @Test
    fun `absurdly long text is rejected outright`() {
        val spam = "微信支付 ".repeat(1000)
        assertNull(parse(spam))
    }

    @Test
    fun `end to end produces a usable draft`() {
        val draft = parse("微信支付\n商户全称：星巴克（国贸店）\n支付金额 ¥23.50")!!.best
        assertNotNull(draft)
        assertTrue(draft!!.isUsable)
        assertEquals(2350L, draft.amountMinor)
        assertEquals(Direction.EXPENSE, draft.direction)
    }

    /** 钉死一个具体时刻，避免测试随运行时刻漂移。 */
    private val FIXED_NOW: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 29, 15, 30, 0)
    }.timeInMillis

    /**
     * 支付成功页的商户不能被猜成"支付成功"。
     *
     * 这条是设备测试 `wechatPaymentBecomesADraftNotATransaction` 逮到的：
     * 纯逻辑测试里没有这段真实版式，所以单测全绿，真库里 merchant 却是
     * "支付成功"。后果是商户一栏搜不到真实商家、历史统计被污染，
     * 而界面上完全看不出来。
     */
    @Test
    fun wechatSuccessPageYieldsTheMerchantNotTheStatusLine() {
        val r = LocalTextParser.parse(
            "支付成功\n¥23.50\n星巴克\n当前状态 支付成功",
            FIXED_NOW,
        )
        assertNotNull(r)
        assertEquals("星巴克", r!!.merchant)
        assertEquals(2350L, r.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun statusOnlyPagesYieldNoMerchantRatherThanAWrongOne() {
        for (text in listOf(
            "支付成功\n¥23.50",
            "交易成功\n¥23.50\n当前状态 交易成功",
            "退款成功 ¥56.00 已退回原路",
        )) {
            val r = LocalTextParser.parse(text, FIXED_NOW)
            assertNotNull("这段应该能解析出金额：$text", r)
            // 猜错商户比不猜更糟：空着用户一秒能补，错了他不会发现
            assertNull("不该把状态行当商户：$text", r!!.merchant)
        }
    }

    /**
     * 读不出时间时必须保留事件发生的时分，不能一律归到中午。
     *
     * 归中午的直接后果是"静默丢账"：15:30 和 15:35 两笔真实消费解析出来的
     * occurredAt 完全相同，自动记账的跨来源合并判据（同额同向 + 120 秒内）
     * 于是把两笔并成一笔。这是设备测试先发现的，纯逻辑测试一次只解析一条，
     * 永远看不见。
     */
    @Test
    fun unstatedTimeKeepsTheEventClockInsteadOfCollapsingToNoon() {
        val a = LocalTextParser.parse("支付成功 ¥23.50 星巴克", FIXED_NOW)!!
        val b = LocalTextParser.parse("支付成功 ¥23.50 星巴克", FIXED_NOW + 5 * 60_000)!!
        assertEquals(
            "两笔真实消费不能解析出同一个时间戳——那会被合并判据当成同一笔",
            5 * 60_000L,
            b.occurredAt - a.occurredAt,
        )
    }

    @Test
    fun yesterdayStillShiftsByADayNotByWholeHours() {
        val r = LocalTextParser.parse("昨天 支付成功 ¥23.50", FIXED_NOW)!!
        val delta = FIXED_NOW - r.occurredAt
        assertTrue("应该落在 23~25 小时之间，实际 ${delta / 3600000.0} 小时", delta in 23 * 3600_000L..25 * 3600_000L)
    }

    @Test
    fun merchantBelowTheAmountLineIsStillFound() {
        val r = LocalTextParser.parse(
            "支付成功\n¥23.50\n星巴克\n当前状态 支付成功",
            FIXED_NOW,
        )!!
        assertEquals("星巴克", r.merchant)
    }

    /**
     * 千分位金额（2026-10-02 realme 真机）。
     *
     * 支付宝账单页对 ≥1000 的金额写 `10,000.00`。修复前这笔**整笔解析失败**
     * （真机日志：「没解析出金额」）——裸金额正则从逗号后只咬出尾段 `000.00`，
     * Money 读成 0，被 `filter { it > 0 }` 过滤，整屏无候选 → 大额转账静默漏记。
     * 原文 = 记账本列表里「啦啦啦(朱娜娜)-转账」详情页的服务读屏原文。
     */
    @Test
    fun alipayCommaAmountsFromTheRealTransferDetail() {
        val r = LocalTextParser.parse(
            "啦啦啦(朱娜娜)-转账\n-10,000.00\n一般\n10月01日 星期四 / 余额宝\n详情\n返回",
            FIXED_NOW,
        )
        assertNotNull("千分位金额必须能解析出来", r)
        assertEquals(10_000_00L, r!!.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    /**
     * 截断是比漏记更危险的兄弟：`\d+(?:[.,]\d{1,2})?` 对 `¥12,100.00` 只捕获
     * 到 `12,1`，Money 把它当**小数逗号**读成 12.1 元——金额错 100 倍还入了账。
     * 三个捕获组模式（¥ 前缀 / 元 后缀 / 字段标签）逐一钉死。
     */
    @Test
    fun commaGroupedAmountsAreCapturedWholeNotTruncated() {
        // ¥ 前缀：修复前捕获 `12,10` → Money 当小数逗号读成 12.1 元
        assertEquals(
            12_100_00L,
            LocalTextParser.parse("支付宝\n¥12,100.00\n余额宝", FIXED_NOW)!!.amountsMinor.first(),
        )
        // 元 后缀：修复前 `\d+` 回退匹配到 `100` → 100 元（错 100 倍）
        assertEquals(
            12_100_00L,
            LocalTextParser.parse("支出12,100元\n余额宝", FIXED_NOW)!!.amountsMinor.first(),
        )
        // 字段标签：修复前捕获 `1,36` → 1.36 元
        assertEquals(
            1_360_11L,
            LocalTextParser.parse("订单金额:1,360.11\n信用卡还款", FIXED_NOW)!!.amountsMinor.first(),
        )
    }

    /** 无单位无符号的千分位裸金额（列表页写法 `12,100.00` / 转账页 `12,100`）。 */
    @Test
    fun bareCommaAmountWithoutUnitOrSymbolStillParses() {
        assertEquals(
            12_100_00L,
            LocalTextParser.parse("转账收款到余额宝\n12,100.00\n2026-10-01", FIXED_NOW)!!.amountsMinor.first(),
        )
        assertEquals(
            12_100_00L,
            LocalTextParser.parse("转账收款到余额宝\n12,100\n余额宝", FIXED_NOW)!!.amountsMinor.first(),
        )
    }

    /** 千分位的尾段不能变成第二笔 0 元候选，半截分组 `1,36` 不能被洗成 1.36。 */
    @Test
    fun commaAmountsLeaveNoPhantomTailCandidates() {
        val r = LocalTextParser.parse("支出:10,030.10\n收入:12,100.00", FIXED_NOW)!!
        assertTrue(
            "候选里不该出现尾段咬出来的 0 元/错值：${r.amountsMinor}",
            r.amountsMinor.all { it in listOf(10_030_10L, 12_100_00L) },
        )
        assertNull("半截分组不是金额，更不能当小数逗号读成 1.36", parse("1,36"))
    }

    /**
     * 收款详情页的方向（2026-10-02 realme 真机，mcode 副手采集的 6 页对照）。
     *
     * 收入的金额节点是**裸的**：屏幕画着 `+12,100.00`，无障碍节点文本只有
     * `12100元`（+号/千分位/小数点被支付宝丢掉），整屏没有收入词。老兜底把
     * 这种页判成支出——真收入记成支出，整本账是歪的。
     *
     * 结构判据：`对方账户` 在而 `付款方式` 不在 ⇒ 钱是进来的。支出页对照样本
     * 全部带付款方式行或「支出」前缀（02/05/03/01 四页对照，见
     * isReceivedMoneyShape 的 KDoc）。
     */
    @Test
    fun receivedTransferDetailIsIncomeEvenWithoutAnyDirectionWord() {
        // 无推荐位的收款详情页（页面变体，「转账红包」推荐块不保证存在）
        val r = LocalTextParser.parse(
            "盖被吱\n12100元\n交易成功\n2026-10-01 08:54:39\n创建时间\n转账\n转账备注\n" +
                "盖被吱 181******39\n对方账户\n更多\n账单管理\n计入收支\n开关\n添加\n备注",
            FIXED_NOW,
        )
        assertNotNull(r)
        assertEquals(12_100_00L, r!!.amountsMinor.first())
        assertEquals("收款页兜底必须是收入", Direction.INCOME, r.direction)
    }

    /** 真机 04 页原样（带「转账红包」推荐位）：收入词命中，结果一致。 */
    @Test
    fun realReceivedTransferPageWithPromoBlockIsStillIncome() {
        val r = LocalTextParser.parse(
            "盖被吱\n12100元\n交易成功\n2026-10-01 08:54:39\n创建时间\n转账\n转账备注\n" +
                "盖被吱 181******39\n对方账户\n更多\n账单管理\n我的消费图鉴\n转账红包\n" +
                "账单分类\n请选择\n标签\n为您推荐\n转账红包\n开关\n计入收支",
            FIXED_NOW,
        )
        assertNotNull(r)
        assertEquals(Direction.INCOME, r!!.direction)
    }

    /** 支出转账页（02 对照）：有付款方式行，结构判据不得生效，「支出」前缀说了算。 */
    @Test
    fun expenseTransferWithPaymentMethodStaysExpense() {
        val r = LocalTextParser.parse(
            "啦啦啦(朱娜娜)\n支出10000元\n交易成功\n2026-10-01 08:54:54\n创建时间\n" +
                "余额宝\n付款方式\n转账\n转账备注\n啦啦啦(朱娜娜) 18******\n对方账户",
            FIXED_NOW,
        )
        assertNotNull(r)
        assertEquals(10_000_00L, r!!.amountsMinor.first())
        assertEquals(Direction.EXPENSE, r.direction)
    }

    /**
     * 优惠/原价行不得抢走实付（2026-10-02 微信「腾讯文档会员」页真机，
     * 用户在悬浮窗上发现：草稿金额成了优惠的 5 元，实付 13 元）。
     * 结构：原价行（划线）→ 优惠行 → 实付在裸金额模式里。
     */
    @Test
    fun discountAndOriginalPriceLinesNeverBeatThePaidAmount() {
        val r = parse(
            "深圳市腾讯计算机系统有限公司\n-13.00\n原价\n¥18.00\n优惠\n" +
                "中国银行首绑有礼优惠￥5.00\n当前状态\n支付成功\n支付时间\n" +
                "商品\n腾讯文档会员\n付款方式\n中国银行储蓄卡(0416)",
        )
        assertNotNull(r)
        assertEquals("实付 13 元，不是原价 18 也不是优惠 5", 1300L, r!!.amountsMinor.first())
    }

    /** 微信转账详情页（文档样本）：没有「对方账户」结构，老兜底照旧判支出。 */
    @Test
    fun wechatTransferDetailWithoutCounterpartyFieldKeepsExpenseFallback() {
        val r = LocalTextParser.parse(
            "等待对方确认收钱\n当前状态\n-0.10\n转账-转给橘生",
            FIXED_NOW,
        )
        assertNotNull(r)
        assertEquals(Direction.EXPENSE, r!!.direction)
    }

    /**
     * 金额行质量排序（2026-10-02 真机话费页，用户现场验证时抓到）。
     *
     * 同一页两次读取建了两条草稿：折叠态选「支出49.5元」✓，展开态的商品名
     * 「云南手机充值**50元**电信话费直充快充」在文本序里先出现，面额 50 被
     * 咬走当金额、商户也锚错成「更多」——同一笔因金额不同不触发合并。
     * 修复：带方向/实付词的行永远优先于长行（商品名）里的数字。
     */
    @Test
    fun phoneCreditPagePicksThePaidAmountNotTheFaceValueInProductName() {
        val collapsed = LocalTextParser.parse(
            """
            账单详情
            返回
            手机充值
            商品说明
            中国银行储蓄卡(0416)
            付款方式
            2026-07-07 15:21:56
            支付时间
            交易成功
            支出49.5元
            中国电信官方旗舰店
            """.trimIndent(),
            FIXED_NOW,
        )
        assertNotNull(collapsed)
        assertEquals(4950L, collapsed!!.amountsMinor.first())
        assertEquals("中国电信官方旗舰店", collapsed.merchant)

        val expanded = LocalTextParser.parse(
            """
            账单详情
            返回
            话费一般30分钟内到账。月初为充值高峰期，会有延迟到账情况。
            话费
            账单分类
            更多
            共1件
            中国电信官方旗舰店 云南手机充值50元电信话费直充快充 电信充值
            O1CN01lfspde22JqfPTX7PP_!!0-item_pic
            交易详情
            中国电信官方
            交易对象
            180 8795 3510
            充值号码
            手机充值
            商品说明
            中国银行储蓄卡(0416)
            付款方式
            2026-07-07 15:21:56
            支付时间
            交易成功
            支出49.5元
            中国电信官方旗舰店
            """.trimIndent(),
            FIXED_NOW,
        )
        assertNotNull(expanded)
        assertEquals(
            "商品名里的面额 50 不能赢过实付行",
            4950L,
            expanded!!.amountsMinor.first(),
        )
        assertEquals("中国电信官方旗舰店", expanded.merchant)
        assertEquals(Direction.EXPENSE, expanded.direction)
    }

    @Test
    fun `mlkit style ocr text of a wechat bill parses into a usable draft`() {
        // 端侧截图识别链（2026-10-04）的后半段：ML Kit 中文 OCR 输出的全文
        // （按版面块序、每块一行）直接交给 LocalTextParser，字段应与读屏
        // 同源解析成功——乳冠 -24.00 那张真机账单的版面。
        val ocrText = """
            本服务由财付通提供
            账单服务
            当前状态 支付成功
            支付时间 2026年10月3日 11:22:50
            商品 云南乳冠-【欧亚】谷雨苑店
            商户全称 云南乳冠乳业有限公司
            收单机构 随行付支付有限公司
            支付方式 招商银行信用卡(9590)
            交易单号 4200003153202610032689963328
            -24.00
            乳冠
            RUIQUAN
        """.trimIndent()
        val r = LocalTextParser.parse(ocrText, FIXED_NOW)
        assertNotNull(r)
        val best = r!!.best
        assertNotNull("OCR 全文应解析出可用草稿", best)
        assertEquals(2400L, best!!.amountMinor)
        assertEquals(Direction.EXPENSE, best.direction)
        assertTrue(best.merchant?.contains("乳冠") == true)
    }
}