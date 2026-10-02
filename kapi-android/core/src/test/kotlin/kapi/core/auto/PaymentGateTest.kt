package kapi.core.auto

import kapi.core.auto.PaymentGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentGateTest {

    private fun accept(text: String?) {
        val v = PaymentGate.verdict(text)
        assertTrue("本该放行，实际 $v：$text", v is Verdict.LooksLikePayment)
    }

    private fun reject(text: String?, because: String) {
        val v = PaymentGate.verdict(text)
        assertTrue("本该挡下（$because），实际放行了：$text", v is Verdict.NotPayment)
    }

    /** 微信商家账单详情页 + 常驻「加载中」哨兵（2026-10-02 realme 微信，mcode 扫描报告）。 */
    private val wechatDetailWithSentinel = """
        加载中
        本服务由财付通提供
        查看往来转账
        申请转账电子凭证
        定位到聊天位置
        对订单有疑惑
        账单服务
        product_SZ_20250908128421809
        商户单号
        4200002542202509081739157531
        交易单号
        零钱
        付款方式
        2025年9月8日 09:24:15
        收款时间
        交易成功
        -18.00
        深圳市腾讯计算机系统有限公司
        当前状态
        支付成功
    """.trimIndent()

    // ---------------------------------------------------- 真实支付通知

    @Test
    fun wechatPaySuccessPageIsAccepted() {
        accept("支付成功\n¥23.50\n星巴克\n当前状态 支付成功")
    }

    @Test
    fun alipayNotificationIsAccepted() {
        accept("你有一笔支出\n支付宝\n实付款 ¥23.50\n交易成功\n付款方式 余额宝")
    }

    @Test
    fun bankPushNotificationIsAccepted() {
        accept("【工商银行】您尾号1234的储蓄卡9月29日15:30消费支出人民币23.50元，余额1234.56元。")
    }

    @Test
    fun refundNotificationIsAccepted() {
        accept("退款成功，¥56.00 已退回原路")
    }

    @Test
    fun paymentDetailPageWithStrongVerbIsAcceptedEvenWithOtherAmounts() {
        // 详情页带原价/优惠/运费/实付四个金额，超过列表阈值，但有"支付成功"就该放行
        accept("订单详情\n商品金额 ¥100.00\n优惠 ¥20.00\n运费 ¥8.00\n实付 ¥88.00\n支付成功")
    }

    @Test
    fun englishNotificationIsAcceptedWhenAmountIsDirectlyReadable() {
        // 只收金额紧跟在字段名后面的写法。LocalTextParser 的英文正则是
        // `(?:paid|total|amount)\s*[:：]?\s*(\d+...)`，数字必须贴着 paid/total/amount。
        accept("Amount: 12.34 paid to Starbucks")
    }

    @Test
    fun englishWithCurrencyCodeInBetweenIsRejectedBecauseParserCannotReadIt() {
        // 这条是**故意**挡下来的。门放行一段后面根本解析不出的文本毫无价值：
        // 产出的草稿没有金额，用户只会看到一个空条目然后困惑。
        // 门和解析器必须口径一致——这条测试把这个契约钉死。
        //
        // 金额必须是**没有小数点**的整数：`1200` 读不出来，解析器直接返回 null。
        // 别改回 `12.34`——那个例子 2026-09-30 起已经失效了：解析器的兜底形态
        // 认得「独立的两位小数」，`12.34` 两边都是空格所以读得出来，门也就该放行。
        // 拿一个解析器其实读得懂的例子当反例，是在给门立一条错的规矩。
        reject("Payment successful. You paid USD 1200 to Starbucks.", "解析器读不出 USD 1200")
    }

    /**
     * 口径的另一半：门**认得**的东西，解析器也必须认得。
     *
     * 2026-09-30 之前这条是反的——解析器的兜底认 `12.34`，门的三种金额形态一个都不认，
     * 于是门把一段能解析出金额的文本判成"没有金额"。两边的口径必须同时改。
     */
    @Test
    fun bareDecimalIsAcceptedByBothGateAndParser() {
        val text = "Payment successful. You paid USD 12.34 to Starbucks."
        assertEquals(
            "门认了裸金额，解析器就必须也认得，否则草稿是空的",
            listOf(1234L),
            kapi.core.llm.LocalTextParser.parse(text)!!.amountsMinor,
        )
        assertTrue(
            "裸金额是合法形态，门不能挡",
            PaymentGate.verdict(text) is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    @Test
    fun singleAmountWithListWordIsStillAccepted() {
        // 只有一个金额时，"账单"可能只是标题而不是列表标志
        accept("账单提醒：您昨日消费 ¥23.50")
    }

    // ---------------------------------------------------- 账单列表页

    @Test
    fun fullScreenTransactionListIsRejected() {
        // 用户只是打开账单看了一眼——不挡下来就会凭空多出一笔三天前的消费
        reject(
            """
            账单
            全部  支出  收入
            今天
            -23.50  星巴克  餐饮
            -8.00   地铁    交通
            -156.80 盒马    购物
            -42.00  京东    购物
            """.trimIndent(),
            "整屏历史流水",
        )
    }

    @Test
    fun bankStatementListIsRejectedEvenWhenItMentionsPayment() {
        // 银行 App 的流水页每行都带"支付"两个字，但那是**过去**的钱
        reject(
            """
            交易明细  筛选
            2026-09-28 支付  星巴克  -23.50
            2026-09-27 支付  地铁    -8.00
            2026-09-26 支付  盒马    -156.80
            2026-09-25 支付  京东    -42.00
            """.trimIndent(),
            "每行都写了支付",
        )
    }

    @Test
    fun statementHeaderWithAmountButNoVerbIsRejected() {
        reject("9月支出合计 ¥3,280.00 收入 ¥12,000.00", "只有合计数")
    }

    @Test
    fun promoPageWithBalanceAndAmountIsRejected() {
        // 活动页：有钱、有"支付"字样，但不是刚发生的一笔
        reject("支付立减 20 元\n下单满 200 可用\n余额 ¥45.00", "促销页")
    }

    @Test
    fun amountWithoutAnyPaymentWordIsRejected() {
        reject("23.50 星巴克 餐饮", "只有金额和商户")
    }

    // ------------------------------------------- 2026-09-30 真机实测回归
    //
    // 下面 5 条**全部是支付宝真机抓到的原文**，不是构造的样本。
    // 那天用户付了一笔 1.00 元，结果：3 条假草稿 + 1 笔真实草稿方向记反 +
    // 真实确认页被误杀。原文照抄进来，因为构造样本会不自觉地把真正的
    // 干扰项（会话列表的"通讯录/搜索好友"、确认页的"查看全部"）抹掉。
    // 参见 tools/dumpdrafts.py 的 auto_draft.rawText。

    @Test
    fun alipayTransferSuccessPageIsAccepted() {
        // 真实转账成功页。金额 1.00、商户"啦啦啦(朱娜娜)"，必须放行。
        accept(
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
    }

    @Test
    fun alipayConfirmPaymentPageSaysWhyItWasHeldBack() {
        // 这条最早写的时候断言的是"应该放行"——**那个期望值本身是错的**。
        // 「查看全部」里的「全部」把它误杀是个真 bug，但**修掉那个 bug 之后**
        // 它会开始放行，于是真机上一笔 1.00 元弹了两条草稿：
        // 确认页 + 结果页。同源不合并（合并只对不同通路生效），所以成了两条。
        //
        // 正确判据是语义：确认页证的是"用户打算付"，不是"钱已经出去了"。
        // 拦它的理由会写进日志并显示给用户，所以理由本身要能被看懂。
        val v = PaymentGate.verdict(
            """
            付款,确认付款，向啦啦啦(朱娜娜)转账，付款金额1.00元人民币
            付款
            查看全部
            未选中,工商银行储蓄卡,尾号(1353),支付渠道说明内容: 银行卡渠道
            银行卡
            (1353)
            工商银行储蓄卡
            已选中,中国银行储蓄卡,尾号(0416)
            """.trimIndent(),
        )
        assertTrue("付款前的确认页不该被当成已支付，实际 $v", v is Verdict.NotPayment)
        val why = (v as Verdict.NotPayment).why
        assertTrue("理由要指向「确认付款」这四个字，实际：$why", why.contains("确认付款"))
        assertTrue("理由要说明钱还没出去，实际：$why", why.contains("没付"))
    }

    @Test
    fun alipayMessageListWithTransferCardsIsRejected() {
        // 假草稿 #1：9 月 9 日别人给我的 7000 元转账，被记成了今天的支出。
        // 每条消息都自带金额和"向你转账"，靠强动词分辨不出来。
        reject(
            """
            我的
            消息
            12
            视频
            理财
            首页
            [转账]**奇向你转账7000.00元
            9月9日
            陌生人
            盖被吱
            2
            我帮你找回了125g已过期的能量，快来收
            [转账]朱娜娜向你转账3000.00元
            9月10日
            啦啦啦
            4
            本周有Tony学友等好友过生日，送上祝福吧
            9月14日
            生日提醒
            1
            支付宝会员 | 你有28积分待领取，解锁飞天小女警皮肤
            更多
            通讯录
            搜索好友等
            清除
            消息(12)
            """.trimIndent(),
            "会话列表里全是别人的转账消息",
        )
    }

    @Test
    fun alipayConversationListWithForeignPaymentNoticeIsRejected() {
        // 假草稿 #2：闲鱼的自动扣款通知，出现在支付宝消息列表里，
        // 被记成了用户自己的 55.00 元收入。
        reject(
            """
            我的
            消息
            6
            视频
            理财
            首页
            4
            本周有Tony学友等好友过生日，送上祝福吧
            9月14日
            生日提醒
            1
            我帮你找回了105g已过期的能量，快来收
            美式燕麦奶
            2周前的消息
            你有一个待完成的服务评价，期待你的反馈
            9月17日
            我的客服
            3
            [蚂蚁庄园]你的小鸡被我带走了
            昨天
            闲鱼 | 自动扣款成功￥55.00
            15:49
            通知消息
            群福利
            全部会话
            关注动态
            通讯录
            搜索好友等
            清除
            消息(6)
            """.trimIndent(),
            "别人的通知消息，不是用户自己的消费",
        )
    }

    @Test
    fun alipayFundHistoryPageIsRejected() {
        // 假草稿 #3：聊天页里的历史转账记录（8 月 21 日、9 月 10 日…）
        reject(
            """
            转账
            红包
            灵感回复
            图片
            更多
            表情
            发送消息...
            语音
            转账
            出借
            ¥3000.00
            啦啦啦
            9月10日 16:00
            转账成功，马上通知TA
            橘生
            转账
            转账给朱娜娜
            ¥1500.00
            8月21日 13:32
            转账成功，马上通知TA
            资金往来记录
            啦啦啦
            返回
            """.trimIndent(),
            "资金往来历史页，每条记录都带'转账成功'",
        )
    }

    @Test
    fun bankPushWithDateAndTimeIsStillAccepted() {
        // 「多个时间戳 = 历史页」这条规则被这条否掉了：付款推送本来就自带
        // 一个日期和一个时刻。它是自动记账最典型的输入，挡掉它等于关掉整条路。
        accept("【工商银行】您尾号1234的储蓄卡9月29日15:30消费支出人民币23.50元，余额1234.56元。")
    }

    // ------------------------------------- 一笔支付弹出两条草稿的根因

    @Test
    fun alipayConfirmPaymentPageIsRejectedBecauseMoneyHasNotLeftYet() {
        // 2026-09-30 真机事故：用户付了一笔 1.00 元，App 弹出**两条**草稿。
        // 原因不是去重失效，是**确认付款页根本不该被当成"已支付"**——
        // 它证的是"用户打算付"，不是"钱已经出去了"。
        //
        // 这条以前"正常"是个巧合：更早的版本里它被「查看全部」里的「全部」
        // 误杀掉了，于是恰好只剩结果页那一条。修掉误杀，语义错误就露出来了。
        reject(
            """
            付款,确认付款，向啦啦啦(朱娜娜)转账，付款金额1.00元人民币
            付款
            查看全部
            未选中,工商银行储蓄卡,尾号(1353),支付渠道说明内容: 银行卡渠道
            银行卡
            (1353)
            工商银行储蓄卡
            已选中,中国银行储蓄卡,尾号(0416)
            """.trimIndent(),
            "这是付款前的确认页，钱还没出去",
        )
    }

    @Test
    fun pendingWordsOnACompletedPageDoNotBlockIt() {
        // 兜底：有的 App 结果页上仍带着"确认"二字（"确认付款成功"）。
        // 有"成功"字样就说明钱已经出去了，不能被待付判据挡下。
        accept("确认付款\n已支付\n¥23.50\n星巴克")
    }

    @Test
    fun resultPageAfterAPendingPageIsStillAccepted() {
        // 同一次支付里，两个屏要一个挡一个放行——这才是修好的样子。
        val confirm = PaymentGate.verdict("付款,确认付款，向啦啦啦(朱娜娜)转账，付款金额1.00元人民币\n付款\n查看全部")
        assertTrue("确认页应被挡下，实际 $confirm", confirm is Verdict.NotPayment)
        accept("回首页\n完成\n付款方式\n啦啦啦(朱娜娜)\n收款方\n转账成功￥1.00")
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun textWithNoAmountIsRejectedAndSaysSo() {
        val v = PaymentGate.verdict("支付成功，欢迎下次使用")
        assertTrue(v is Verdict.NotPayment)
        assertTrue("理由要能写进日志：$v", (v as Verdict.NotPayment).why.contains("金额"))
    }

    @Test
    fun blankAndNullNeverThrowAndNeverPass() {
        for (bad in listOf(null, "", "   ", "\n\t ")) {
            val v = PaymentGate.verdict(bad)
            assertTrue("空输入必须挡下，实际 $v", v is Verdict.NotPayment)
        }
    }

    /**
     * 微信 H5 的常驻「加载中」哨兵（2026-10-02 真机，mcode 扫描）：
     * 详情页节点流第一位永远有个「加载中」，与 转账单号/付款方式/收款时间
     * 等已渲染字段共存。按词直接拦 = 微信转账/收款页 100% 永久丢弃
     * （真机停留 7 秒与 11 秒结果相同）。加载词必须给「已渲染证据」让路。
     */
    @Test
    fun wechatResidentLoadingSentinelDoesNotBlockRenderedDetailPages() {
        accept(wechatDetailWithSentinel)
    }

    /** 真正在加载的屏（9-30 的 -3.99/加载中 假账）：没有字段没有动词，照拦。 */
    @Test
    fun genuinelyLoadingScreenWithOnlyAnAmountIsStillBlocked() {
        reject("加载中\n-3.99", "半屏加载，金额不可信")
        reject("加载中", "纯加载屏")
    }
}
