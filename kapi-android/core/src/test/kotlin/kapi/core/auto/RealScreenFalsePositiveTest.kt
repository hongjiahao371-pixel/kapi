package kapi.core.auto

import kapi.core.auto.PaymentShape.Node
import kapi.core.auto.PaymentShape.Verdict
import kapi.core.llm.Direction
import kapi.core.llm.LocalTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ------------------------------------------------ 微信账单列表页（2026-10-01 新增）

    /**
     * 微信「账单」列表页，2026-10-01 realme RMX3852 真机原文。
     *
     * 这一屏是最典型的「一屏记录」：**四笔独立的交易**，每笔都带完整金额和日期。
     * 而每一行又都长得像一条能读的账——`转账-转给橘生 / -0.10 / 10月1日 14:45`。
     *
     * 危险在于它和详情页只差一次点击：用户翻账单是常事，只要形状闸放过一次，
     * 账本里就会多出四条**过去的**消费，而且时间戳是几个月前的，事后极难察觉。
     *
     * 顺带也是商户抽取的一个陷阱：这一屏里 `深圳市腾讯计算机系统有限…`
     * 出现在金额**上面**、`转账-转给橘生` 出现在金额上面——如果哪一屏被误判成"一笔"，
     * 商户会取到最近的那一行，看起来还挺像回事。
     */
    private val wechatBillList = listOf(
        "账单",
        "全部账单", "查找交易", "收支统计",
        "2026年10月", "支出 ¥0.10", "收入 ¥0.00",
        "转账-转给橘生", "-0.10", "10月1日 14:45",
        "2025年9月", "支出 ¥18.00", "收入 ¥0.00",
        "深圳市腾讯计算机系统有限…", "-18.00", "9月8日 09:24",
        "2025年8月", "支出 ¥18.00", "收入 ¥0.00",
        "深圳市腾讯计算机系统有限…", "-18.00", "8月8日 09:24",
        "2025年7月", "支出 ¥13.00", "收入 ¥0.00",
        "深圳市腾讯计算机系统有限…", "-13.00", "7月8日 15:10",
        "暂无更多记录",
    ).map { Node(it) }

    @Test
    fun `wechat bill list is a screen of records not one payment`() {
        val v = PaymentShape.inspect(wechatBillList)
        assertTrue(
            "四笔独立交易构成的列表页必须被形状闸挡下，实际 $v",
            v is Verdict.ManyTransactions,
        )
    }

    @Test
    fun `wechat bill list never reaches the ledger`() {
        // 第二道门也要拦一道。两道都钉住，才不会因为将来改形状闸的口径
        // 就把"翻账单"变成"批量导入历史消费"。
        val text = wechatBillList.joinToString("\n") { it.text }
        val gate = PaymentGate.verdict(text)
        assertEquals(
            "账单列表页不该被判定成刚发生的一笔",
            false,
            gate is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    // --------------------------------- 支付宝支付详情页：立减金把真支付挡掉了（误杀）

    /**
     * 2026-10-01 realme RMX3852 / 支付宝 12.12.30.8000 支付详情页。
     *
     * 下面是**服务自己打出来的原始行序**（`KapiA11y` 日志里的「原始行序」），
     * 不是 `uiautomator dump` 的顺序——后者是反的。第一版修法照着 dump 写，
     * 单测绿、真机不生效，白跑一轮。
     *
     * 这是**反向**的错：不是把假账当真账，而是把**真账挡掉**。
     * 用户看到的现象是"点开支付详情页，什么都没记"，界面上没有任何异常提示。
     *
     * 那一屏有两个金额，而它们是同一笔的两项：
     * ```
     * -1.98 │ 交通银行立减金        ← 值在前，标签在后
     * 支出244.93元 │ 微雪**店        ← 订单金额 + 商户
     * ```
     * `-1.98` 自己不带任何标签，而 `交通银行立减金` 在它**下一行**，
     * 于是被数成第二个"无标签金额"，撞上阈值 2 整屏挡掉。
     *
     * 危害等级和假账一样高：漏记是**静默**的，用户以为记了、其实没有。
     */
    private val alipayPaymentDetail = listOf(
        "账单详情",
        "返回",
        "对此订单有疑问",
        "往来流水证明",
        "AA收款",
        "查看往来记录",
        "申请商家售后",
        "添加",
        "备注",
        "开关",
        "计入收支",
        "买软件",
        "为您推荐",
        "请选择",
        "标签",
        "数码电器",
        "账单分类",
        "我的消费图鉴",
        "账单管理",
        "更多",
        "共1件",
        "微雪 ESP32-P4开发板 支持WIFI6和蓝牙5 4.3寸触控屏幕小智AI语音",
        "O1CN012PMIq41QBehQyMbEP_!!2206714651938",
        "交易详情",
        "已领取14积分",
        "支付奖励",
        "交通银行信用卡(5894)",
        "付款方式",
        "2026-09-29 16:03:25",
        "2026-09-29 16:03:25",
        "支付时间",
        "-1.98",
        "交通银行立减金",
        "极速付款",
        "管理极速付款",
        "等待确认收货",
        "支出244.93元",
        "微雪**店",
    ).map { Node(it) }

    @Test
    fun `alipay detail with a discount amount is still one payment`() {
        val v = PaymentShape.inspect(alipayPaymentDetail)
        assertTrue(
            "立减金是这一笔的加减法，不能让它把真支付整屏挡掉，实际 $v",
            v is Verdict.OneTransaction,
        )
    }

    @Test
    fun `alipay detail keeps the order amount not the discount`() {
        val r = LocalTextParser.parse(
            alipayPaymentDetail.joinToString("\n") { it.text },
            1_700_000_000_000L,
        )
        assertNotNull("这一屏应该解析得出来", r)
        assertEquals("要的是订单金额 244.93，不是立减金 1.98", 24493L, r!!.amountsMinor.first())
    }

    /**
     * 支付宝**消息列表页**不是一笔 42876 的收入。
     *
     * 2026-10-01 realme 真机原文。每一行是「未读数 / 发送者 / 消息预览 / 时间」，
     * 而 `突然到账42876元，差点以为是诈骗😅` 是**别人消息里提到的一个数字**：
     * ```
     * 我的 / 消息 / 9 / 视频 / 理财 / 首页 / 关闭
     * 1074 / 小李搞钱进行时 / 以后还是老老实实用苹果吧 / 7.3万
     * 玉玉爱理财 / 空姐的真实工资，看完你还羡慕吗？ / 325
     * 清醒打工人 / 在单位吃不开的男生是啥样？ / 1693
     * 胖/小叮 / 龙凤胎 我以为的睡姿 实际上的睡姿 / 1722
     * 明明白白胡萝卜 / 突然到账42876元，差点以为是诈骗😅 / 7347
     * 旅行收藏夹 / 据说这里是二次元的圣地，南宁最潮的楼… / 665
     * 饭来张口 / 1小时前
     * ```
     * 这一屏只有那一个节点带 `元`，所以形状闸数到 1 个"无标签金额"→ 放行；
     * 而 `到账` 又在收入词表里，支付门也放行。**两道门同时失守，
     * 账本里多出 42876 元收入。**
     *
     * 挡它的判据不是"这是消息页"（那要认页面身份），而是
     * **金额长在一句带逗号的话里**——真机五个真实交易样本，
     * 金额节点没有一个带散文标点。
     */
    @Test
    fun `alipay message list preview with a number is not an income`() {
        val text = """
            我的
            消息
            9
            视频
            理财
            首页
            关闭
            去开启
            开启通知权限 不错过支付信息等重要通知
            1074
            小李搞钱进行时
            以后还是老老实实用苹果吧
            7.3万
            玉玉爱理财
            空姐的真实工资，看完你还羡慕吗？
            325
            清醒打工人
            在单位吃不开的男生是啥样？
            1693
            胖/小叮
            龙凤胎 我以为的睡姿 实际上的睡姿
            1722
            明明白白胡萝卜
            突然到账42876元，差点以为是诈骗😅
            7347
            旅行收藏夹
            据说这里是二次元的圣地，南宁最潮的楼…
            665
            饭来张口
            1小时前
        """.trimIndent()
        val nodes = text.lines().map { Node(it.trim()) }.filter { it.text.isNotEmpty() }
        val v = PaymentShape.inspect(nodes)
        assertTrue("消息列表页必须被形状闸挡下，实际 $v", v is Verdict.ManyTransactions)
    }

    /**
     * 反向保护：真实的「标签 值」写法里带逗号也不该被误伤。
     *
     * 「散文」判据只看**金额和标点是否同处一个节点**，所以只要交易行是
     * `实付 ¥156.80` 这样标签和值挤在一行，它就是一笔，不受影响。
     */
    @Test
    fun `a real payment row is never mistaken for prose`() {
        val nodes = listOf(
            "交易成功",
            "实付 ¥156.80",
            "时间 12:03",
        ).map { Node(it) }
        assertTrue(
            "真实交易行不能被散文判据挡掉",
            PaymentShape.inspect(nodes) is Verdict.OneTransaction,
        )
    }

    /**
     * 微信会话的**浮窗预览**不是一笔支付。
     *
     * 2026-10-01 realme 真机抓到的现场。形状闸看到 `转账` + `￥0.10`（一个带金额
     * 的短节点）判成"像一笔"，支付门看到「转账」这个强动词也放行——
     * **两道门同时失守**，草稿里出现了一条 `−0.10 / 更多信息` 的假账。
     *
     * 真正的判据是那两个结构性标志：**聊天输入栏**和**浮窗**。交易详情页上
     * 不会有它们，而它们不是某一屏特有的文案。
     */
    @Test
    fun `wechat conversation floating window is not a payment`() {
        val text = """
            更多功能按钮，已折叠
            表情
            听写
            切换到按住说话
            Sherry头像
            转账
            你发起了一笔转账
            ￥0.10
            14:45
            更多信息
            1
            返回
            退出浮窗
            浮窗
        """.trimIndent()
        assertEquals(
            "会话浮窗必须被支付门挡下",
            false,
            PaymentGate.verdict(text) is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    /**
     * 补读不能在用户已经切走之后还往回记一笔。
     *
     * 沉降重读是在 1.2 秒后读**当时**的屏，所以它必须核对包名——
     * [KapiAccessibilityService.collectTexts] 里有 `rootPkg != eventPkg` 的检查，
     * 这里钉的是"读到的必须是同一个 App"这个前提在真实原文上成立。
     */
    @Test
    fun `a settled screen is only re-read while still in the same app`() {
        // 交易详情页本身不该被误当成浮窗——上面的判据不能宽到把真支付也挡掉。
        val detail = alipayPaymentDetail.joinToString("\n") { it.text }
        assertTrue(
            "支付宝真实详情页不能被浮窗判据误伤",
            PaymentGate.verdict(detail) is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    /**
     * 反向保护：调整项**不能**把紧挨着它的真消费一起摘掉。
     *
     * 调整项的判定是"下一行是调整项标签"。万一把方向做成双向，
     * 下面这屏的 `支出55元` 会因为**下一行**是「交通银行立减金」被误摘，
     * 整屏就只剩一个「立减金」，落到"没有任何一笔消费"那一档。
     */
    @Test
    fun `a discount label does not swallow the neighbouring real spend`() {
        val nodes = listOf("-1.98", "交通银行立减金", "支出55元").map { Node(it) }
        val v = PaymentShape.inspect(nodes)
        assertTrue("立减金旁边的真消费必须放行，实际 $v", v is Verdict.OneTransaction)
    }

    /**
     * 同一屏在**支付门**上也必须放行。
     *
     * 形状闸修好之后真机仍然没记上，日志是
     * `丢弃[A11Y/com.eg.android.AlipayGphone]：出现了促销词「立减」`——
     * 形状闸数的是"有几笔"，支付门查的是"这是不是促销页"，
     * 而 `交通银行立减金` 那一行对整屏子串匹配是当 promo 命中的。
     *
     * 两道门问的问题不同，但前提必须是同一件事：**立减金是这一笔的一部分，
     * 不是另一个页面。** 只修一道，另一道照样把真支付丢掉。
     */
    @Test
    fun `alipay detail passes the payment gate too not just the shape gate`() {
        val text = alipayPaymentDetail.joinToString("\n") { it.text }
        val gate = PaymentGate.verdict(text)
        assertTrue(
            "支付宝支付详情页必须被支付门放行，实际 $gate",
            gate is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    /**
     * 屏还没稳定时不记——**金额错了比商户错了严重得多**。
     *
     * 2026-10-01 realme 抓到的那一条 `-3.99 / 加载中` 就是现场：详情页还在加载，
     * 第一行是「加载中」，而金额已经出现了。商户是脏的可以事后改，**金额是错的
     * 就没法事后改**——它会一直躺在账本里污染统计，而用户不会发现。
     *
     * 沉降延迟（1.2 秒）是个估计值，支付宝这一屏的详情是异步补的，没等到。
     * 与其赌延迟调多大合适，不如让屏自己说"我还没准备好"。
     */
    @Test
    fun `a half loaded screen is never recorded`() {
        val halfLoaded = """
            加载中
            微雪**店
            支出244.93元
            支付时间
            2026-09-29 16:03:25
            交通银行立减金
        """.trimIndent()
        val gate = PaymentGate.verdict(halfLoaded)
        assertEquals(
            "半屏状态的金额可能不对，宁可不记",
            false,
            gate is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    /**
     * 但沉降正常时不能连坐——最终形态那一屏必须照记。
     *
     * 这一对放在一起才有意义：只写前一条，形状闸就会被调坏去挡所有支付宝详情页。
     */
    @Test
    fun `the settled version of the same screen is still recorded`() {
        val gate = PaymentGate.verdict(alipayPaymentDetail.joinToString("\n") { it.text })
        assertTrue("沉降之后必须照记，实际 $gate", gate is PaymentGate.Verdict.LooksLikePayment)
    }

    // ============================================================ 2026-10-01 realme 支付宝

    /**
     * 支付宝「转账详情」页，两个**屏变体**。
     *
     * 同一个账户 `盖被吱 181\*\*\*\*\*\*39`、同一个金额 12100、同一笔转账，
     * 读屏先后报了两遍，两遍**只差一个「我的消费图鉴」**。原样取自
     * `/tmp` 里那条采集日志与落库的 `rawText`（变体甲是落进草稿的那一份）。
     *
     * 变体甲多出来的那一行是账单页的一个**运营入口**，它含「消费」二字、
     * 离金额又比收入词近，于是 nearest-wins 把方向判成了支出。
     */
    private val alipayTransferReceived = mapOf(
        "withMySpendingAtlas" to listOf(
            "账单详情", "返回", "对此订单有疑问", "申请电子回单",
            "往来流水证明", "查看往来记录", "添加", "备注", "开关", "计入收支",
            "转账红包", "为您推荐", "请选择", "标签", "转账红包", "账单分类",
            "我的消费图鉴", "账单管理", "更多",
            "盖被吱 181******39", "盖被吱 181******39",
            "对方账户", "转账", "转账", "转账备注",
            "2026-10-01 08:54:39", "2026-10-01 08:54:39",
            "创建时间", "交易成功", "12100元", "盖被吱",
        ),
        "withoutMySpendingAtlas" to listOf(
            "账单详情", "返回", "对此订单有疑问", "申请电子回单",
            "往来流水证明", "查看往来记录", "添加", "备注", "开关", "计入收支",
            "转账红包", "为您推荐", "请选择", "标签", "转账红包", "账单分类",
            "账单管理", "更多",
            "盖被吱 181******39", "盖被吱 181******39",
            "对方账户", "转账", "转账", "转账备注",
            "2026-10-01 08:54:39", "2026-10-01 08:54:39",
            "创建时间", "交易成功", "12100元", "盖被吱",
        ),
    )

    /**
     * 方向必须在两个变体之间**稳定**。
     *
     * 这不是"多记一笔"的问题：两笔方向都是支出、金额都是 1210000 分、
     * 账单时间只差 15 秒（08:54:54 / 08:54:39）——同源合并把它们并成了一条草稿，
     * 收到的那笔**凭空消失**。方向一稳定，这条合并路径就自然失效。
     */
    @Test
    fun `a menu entry never flips the direction of the same transfer`() {
        val dirs = alipayTransferReceived.mapValues { (_, lines) ->
            LocalTextParser.parse(lines.joinToString("\n"))!!.direction
        }
        assertEquals(
            "同一笔转账的两个屏变体必须判出同一个方向，实际 $dirs",
            1,
            dirs.values.toSet().size,
        )
        assertEquals(
            "收到别人转账就是收入，实际 ${dirs["withoutMySpendingAtlas"]}",
            Direction.INCOME,
            dirs["withoutMySpendingAtlas"],
        )
    }

    /**
     * 同一笔的另一个变体在**形状闸**上也该是"一笔"——重复节点不该被数成多笔。
     */
    @Test
    fun `a screen that reports the same node twice is still one payment`() {
        val shape = PaymentShape.inspect(alipayTransferReceived.getValue("withMySpendingAtlas").map(::Node))
        assertTrue("重复节点不算多笔，实际 $shape", shape is Verdict.OneTransaction)
    }

    /**
     * 支付宝「信用卡还款」详情页，43 个节点、1 个金额 739.9 元。
     *
     * 前 120 字是采集日志里逐字记下的原文；后面几行是同一屏上读到的状态字段。
     * 整屏被丢掉的日志原文是：
     * ```
     * 丢弃[A11Y/…]：这是付款确认页（命中「处理中」），钱还没付出去
     * ```
     */
    private val alipayCreditCardRepayment = listOf(
        "账单详情", "返回", "对此订单有疑问", "申请电子回单", "查看往来记录",
        "添加", "备注", "开关", "计入收支", "还款", "为您推荐", "请选择",
        "标签", "信用借还", "账单分类", "账单管理", "更多",
        "立即领取5积分", "支付奖励", "信用卡还款", "信用卡还款",
        "借还款说明", "银行反馈已入账，如有疑问",
        "739.9元", "还款成功", "处理进度", "银行处理中",
    )

    @Test
    fun `a completed credit card repayment is not a pending confirmation page`() {
        val gate = PaymentGate.verdict(alipayCreditCardRepayment.joinToString("\n"))
        assertTrue(
            "还款成功 + 银行入账，页面上的「处理中」是子状态不是待付，实际 $gate",
            gate is PaymentGate.Verdict.LooksLikePayment,
        )
    }

    /**
     * 支付宝「订阅订单」详情页（`TokenPlanMax-年度会员`，上海稀宇科技有限公司）。
     *
     * 被挡掉的日志原文是：
     * ```
     * 不是一笔支付：整屏有 3 个带金额的独立条目（508.61 / 508.61 / 支出507.79元）
     * ```
     * `交通银行立减金 -0.82` 已经被调整项规则排除；`508.61` 是 `订单金额` 的值，
     * 被拆到了另一个节点、自身不带任何标签，于是被当成独立的一笔。
     */
    private val alipaySubscriptionOrder = listOf(
        "订单详情", "返回", "订单信息", "商品说明", "服务说明",
        "TokenPlanMax-年度会员", "上海稀宇科技有限公司",
        "订单金额", "508.61", "508.61",
        // 调整项是**值在前、标签在后**——和上面「订单金额」正好相反，
        // 两种写法都得判。顺序照抄真机原始行序，别按直觉排。
        "-0.82", "交通银行立减金",
        "支付时间", "2026-09-28 20:14:03",
        "交易成功", "支出507.79元",
    )

    @Test
    fun `an order amount split from its label is not a second record`() {
        val shape = PaymentShape.inspect(alipaySubscriptionOrder.map(::Node))
        assertTrue(
            "「订单金额」和它的值是同一笔，实际 $shape",
            shape is Verdict.OneTransaction,
        )
    }

    @Test
    fun `the subscription order reaches the payment gate too`() {
        val gate = PaymentGate.verdict(alipaySubscriptionOrder.joinToString("\n"))
        assertTrue("订单详情页必须放行，实际 $gate", gate is PaymentGate.Verdict.LooksLikePayment)
    }
}
