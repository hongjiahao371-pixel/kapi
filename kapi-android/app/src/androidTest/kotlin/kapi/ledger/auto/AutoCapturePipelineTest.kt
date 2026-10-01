package kapi.ledger.auto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kapi.core.auto.AutoBook
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.LedgerRepository
import kapi.ledger.sync.LocalEditWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 自动记账管线在**真 Room 库**上的行为。
 *
 * 纯逻辑（指纹、去重窗口、分流、支付门）已经在 core 里测过了；这里测的是
 * 那些"只有真的数据库才暴露"的部分：唯一索引冲突、跨来源合并的查询能不能
 * 捞到候选、迁移之后新表可不可写。
 */
@RunWith(AndroidJUnit4::class)
class AutoCapturePipelineTest {

    private lateinit var db: KapiDatabase
    private lateinit var pipeline: AutoCapturePipeline
    private lateinit var store: AutoBookStore

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"

    /**
 * 所有用例共用一个**真实墙钟**基准。
 *
 * 之前用 2023 年的固定时间戳，结果踩了两个坑：
 *  - `AutoDraftEntity.createdAt` 记的是 `System.currentTimeMillis()`，而
 *    `purgeExpired` 拿测试传进去的假时间去比，窗口永远对不上。
 *  - `LocalTextParser` 读不出日期时会把时间归到"今天中午"，于是存进草稿的
 *    `occurredAt` 和传进去的假时间差了好几年，跨来源合并的候选查询直接落空。
 * 两者都会伪装成"产品逻辑不对"，其实是用例的时间轴不成立。
 */
private val base: Long get() = System.currentTimeMillis()

private val payText = "支付成功\n¥23.50\n星巴克\n当前状态 支付成功"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 每次用独立的内存库，绝不复用 App 真正的 kapi.db：
        // 设备测试跑在 App 自己的进程里，用真库会把这个开发者自己账本里的数据冲掉。
        db = Room.inMemoryDatabaseBuilder(ctx, KapiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // **去重状态必须清空**。指纹时间戳存在真实的 SharedPreferences 里，
        // 跨测试方法存活——不重置的话，第二个用例用同一段文本时会被上一个用例
        // 判成"窗口内的重复"而直接丢弃，于是断言"应该有 1 条草稿"拿到 0。
        // 这类失败长得像产品 bug，其实是测试之间互相污染，而且**只在整套跑时出现**。
        ctx.getSharedPreferences("kapi_auto", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        store = AutoBookStore(ctx)
        val editor = LocalEditWriter(db)
        val repo = LedgerRepository(db, editor)
        pipeline = AutoCapturePipeline(db, repo, editor, store) { "test-device" }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun input(
        text: String = payText,
        pkg: String = wechat,
        channel: AutoBook.CaptureChannel = AutoBook.CaptureChannel.A11Y,
        at: Long = 1_700_000_000_000L,
    ) = AutoCapturePipeline.CaptureInput(channel, pkg, text, at)

    // ---------------------------------------------------------- 一次真捕获

    @Test
    fun wechatPaymentBecomesADraftNotATransaction() = runBlocking {
        val out = pipeline.capture(input())
        assertTrue("应该是草稿，实际 $out", out is AutoCapturePipeline.Outcome.Drafted)
        val drafts = db.autoDraftDao().observeAll().first()
        assertEquals(1, drafts.size)
        assertEquals(2350L, drafts[0].amountMinor)
        assertEquals("EXPENSE", drafts[0].direction)
        assertEquals("星巴克", drafts[0].merchant)
        // 规则解析**绝不**能直接入账：置信度上限 0.55 不是概率
        assertEquals(0, db.txnDao().pendingBatch(limit = 50).size)
    }

    // -------------------------------------------------------------- 去重

    @Test
    fun theSamePaymentReadTenTimesProducesOneDraft() = runBlocking {
        repeat(10) { pipeline.capture(input()) }
        assertEquals(
            "同一笔支付被读十次只能有一条草稿",
            1, db.autoDraftDao().observeAll().first().size,
        )
    }

    // ------------------------------------------------ 交易号去重：账单详情页通路
    //
    // 2026-09-30 用户实测录屏显示：稳定的那条自动记账通路抓的是**账单详情页**，
    // 不是付款成功页。原文逐字照抄那一屏。
    //
    // 这一屏要能当主通路，靠的是页面上的 `交易单号 4500000486202609301360845789`。
    // 文本指纹是**记不住**的：归一化会把 8 位以上数字抹成 `#`，而这正是交易号本身，
    // 于是两次点开同一笔账单算出来完全一样，3 分钟窗口一过就多一条重复记录。
    // 用户在微信里翻账单是常事，这条重复会反复发生。

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

    private val tenDays = 10L * 24 * 60 * 60 * 1000

    @Test
    fun revisitingTheSameBillDaysLaterIsDroppedByTransactionId() = runBlocking {
        val first = pipeline.capture(input(text = wechatBillDetail, at = base))
        assertTrue("第一眼必须记账，实际 $first", first is AutoCapturePipeline.Outcome.Drafted)
        assertEquals(1, db.autoDraftDao().observeAll().first().size)

        // 十天后用户又点进同一笔账单
        val again = pipeline.capture(input(text = wechatBillDetail, at = base + tenDays))
        assertTrue(
            "隔十天看同一笔账单必须判重复，实际 $again",
            again is AutoCapturePipeline.Outcome.Ignored,
        )
        assertTrue(
            "丢弃原因必须点明是交易号，日志里才看得出走的是哪条判据，实际 ${(again as AutoCapturePipeline.Outcome.Ignored).reason}",
            again.reason.contains("交易号"),
        )
        assertEquals(
            "账本里还是只有那一笔",
            1, db.autoDraftDao().observeAll().first().size,
        )
    }

    /**
     * 两种去重的寿命必须**可对比地不同**。
     *
     * 写在一起是为了钉住"为什么需要交易号"这个前提：文本指纹 3 分钟就忘，
     * 交易号 90 天不忘。哪天有人把交易号通路也套上短窗口，这条会立刻红。
     */
    @Test
    fun textFingerprintForgetsAfterTheWindowButTransactionIdDoesNot() {
        val t0 = 1_700_000_000_000L
        val fp = AutoBook.exactFingerprint(AutoBook.CaptureChannel.A11Y, wechatBillDetail)
        val key = "WECHAT_TXN:4500000486202609301360845789"

        assertFalse(store.isDuplicate(fp, t0))
        assertTrue(store.isDuplicate(fp, t0 + 60_000))
        assertFalse("3 分钟窗口过了就该忘", store.isDuplicate(fp, t0 + tenDays))

        assertFalse(store.isDuplicateTxnId(key, t0))
        assertTrue(store.isDuplicateTxnId(key, t0 + tenDays))
    }

    /** 不同的交易号是**不同的两笔**，绝不能被当成同一笔丢掉——丢账比重复严重得多。 */
    @Test
    fun differentTransactionIdsAreNeverTreatedAsTheSame() {
        val t0 = 1_700_000_000_000L
        assertFalse(store.isDuplicateTxnId("WECHAT_TXN:4500000486202609301360845789", t0))
        assertFalse(
            "另一笔的号不一样，必须当新数据",
            store.isDuplicateTxnId("WECHAT_TXN:4500000486202609301360845790", t0),
        )
    }

    // --------------------------------- 一笔支付横跨多个屏：2026-09-30 真机事故
    //
    // 原文照抄支付宝真机抓到的两个屏（`auto_draft.rawText`）。
    // 用户在支付宝付了 1.00 元，App 弹出**两条**草稿：
    //   1) 确认付款页——它只是"打算付"，本来就不该记（由 PaymentGate 的
    //      "待付"判据挡掉，那部分在 core 的 PaymentGateTest 里）
    //   2) 支付成功页
    // 下面是**兜底**那层：读屏读到的是"当前屏幕"，同一笔的结果页可能因为
    // 动画、加载态被读到略有差异的变体，指纹一变就多一条草稿。

    private val alipayConfirmPage =
        "付款,确认付款，向啦啦啦(朱娜娜)转账，付款金额1.00元人民币\n付款\n查看全部\n" +
            "未选中,工商银行储蓄卡,尾号(1353),支付渠道说明内容: 银行卡渠道\n已选中,中国银行储蓄卡,尾号(0416)"

    private val alipaySuccessPage =
        "回首页\n回首页\n完成\n中国银行储蓄卡(0416)\n付款方式\n啦啦啦(朱娜娜)\n收款方\n转账成功￥1.00"

    @Test
    fun confirmPageIsNotRecordedAsAPayment() = runBlocking {
        val out = pipeline.capture(input(text = alipayConfirmPage, pkg = alipay))
        assertTrue("付款前的确认页不该产生草稿，实际 $out", out is AutoCapturePipeline.Outcome.Ignored)
        assertEquals(0, db.autoDraftDao().observeAll().first().size)
    }

    @Test
    fun oneAlipayTransferProducesExactlyOneDraft() = runBlocking {
        // 确认页先被读到（被挡掉），结果页后到（记一条）
        pipeline.capture(input(text = alipayConfirmPage, pkg = alipay, at = base))
        pipeline.capture(input(text = alipaySuccessPage, pkg = alipay, at = base + 3_000))
        val drafts = db.autoDraftDao().observeAll().first()
        assertEquals("一笔转账只能有一条草稿，实际 ${drafts.size}", 1, drafts.size)
        assertEquals(100L, drafts[0].amountMinor)
        assertEquals("EXPENSE", drafts[0].direction)
    }

    @Test
    fun theResultPageReadTwiceInSlightlyDifferentFormsStaysOneDraft() = runBlocking {
        // 同一条读屏链路，节点集因为动画/加载态多了一项，指纹就变了
        pipeline.capture(input(text = alipaySuccessPage, pkg = alipay, at = base))
        pipeline.capture(
            input(text = alipaySuccessPage + "\n网络连接较慢", pkg = alipay, at = base + 4_000),
        )
        val drafts = db.autoDraftDao().observeAll().first()
        assertEquals("同一屏的变体只能有一条草稿，实际 ${drafts.size}", 1, drafts.size)
        assertEquals(
            "两段原文都该留成证据",
            2, drafts[0].evidenceCount,
        )
    }

    @Test
    fun twoRealSameAmountPaymentsSecondsApartStillSurviveSeparately() = runBlocking {
        // 这是同源近重复的代价，写下来是为了让改窗口的人看见它。
        // 20 秒是权衡：真正的重复扣款合并掉是好事，但两笔独立消费要能分开。
        // 40 秒 > 20 秒窗口，必须记成两笔。
        pipeline.capture(input(text = payText, pkg = wechat, at = base))
        pipeline.capture(input(text = "$payText\n订单号 20260929153012345678", pkg = wechat, at = base + 40_000))
        assertEquals(
            "隔了 40 秒的两笔必须各自记一条",
            2, db.autoDraftDao().observeAll().first().size,
        )
    }

    @Test
    fun reformattingOrderNumberStillCountsAsTheSamePayment() = runBlocking {
        pipeline.capture(input(text = "$payText\n订单号 20260929153012345678\n余额 ¥1234.56"))
        pipeline.capture(input(text = "$payText\n订单号 20260929153099998888\n余额 ¥1211.06"))
        assertEquals(1, db.autoDraftDao().observeAll().first().size)
    }

    @Test
    fun twoDifferentPaymentsMinutesApartBothSurvive() = runBlocking {
        val t0 = base
        pipeline.capture(input(at = t0))
        pipeline.capture(input(at = t0 + 6 * 60_000))
        assertEquals(
            "两笔真实消费不能被去重吃掉——这是丢账",
            2, db.autoDraftDao().observeAll().first().size,
        )
    }

    // -------------------------------------------------------- 跨来源合并

    @Test
    fun notifyAndA11yForOnePaymentMergeIntoOneDraft() = runBlocking {
        val t0 = base
        pipeline.capture(
            input(
                text = "你有一笔支出\n实付款 ¥23.50\n交易成功",
                channel = AutoBook.CaptureChannel.NOTIFY,
                at = t0,
            )
        )
        val out = pipeline.capture(input(text = payText, channel = AutoBook.CaptureChannel.A11Y, at = t0 + 2_000))
        assertTrue("跨来源的同一笔应合并，实际 $out", out is AutoCapturePipeline.Outcome.Merged)
        val drafts = db.autoDraftDao().observeAll().first()
        assertEquals("两笔都入账就是重复记账，用户月底会看到总额翻倍", 1, drafts.size)
        assertEquals(2, drafts[0].evidenceCount)
        assertTrue(drafts[0].sources.contains("NOTIFY"))
        assertTrue(drafts[0].sources.contains("A11Y"))
    }

    @Test
    fun twoRealSameAmountPaymentsInDifferentAppsDoNotMerge() = runBlocking {
        val t0 = base
        pipeline.capture(
            input(
                text = "你有一笔支出\n实付款 ¥23.50\n交易成功",
                channel = AutoBook.CaptureChannel.NOTIFY, at = t0,
            )
        )
        // 五分钟后在支付宝又付了 23.50。这是两笔真的消费，合并就是丢账
        pipeline.capture(
            input(
                text = payText, pkg = alipay,
                channel = AutoBook.CaptureChannel.A11Y, at = t0 + 5 * 60_000,
            )
        )
        assertEquals(2, db.autoDraftDao().observeAll().first().size)
    }

    // ------------------------------------------------------------ 支付门

    @Test
    fun openingTheStatementListDoesNotCreateDrafts() = runBlocking {
        // 用户只是打开账单看了一眼。里面每行都是历史交易，不挡下来就会凭空
        // 多出一笔三天前的消费——这正是"自动记账老记重复"这个差评的来源。
        val statement = """
            账单
            全部  支出  收入
            -23.50  星巴克  餐饮
            -8.00   地铁    交通
            -156.80 盒马    购物
            -42.00  京东    购物
        """.trimIndent()
        repeat(3) { pipeline.capture(input(text = statement)) }
        assertEquals(0, db.autoDraftDao().observeAll().first().size)
    }

    @Test
    fun aPackageOutsideTheWhitelistIsIgnored() = runBlocking {
        val out = pipeline.capture(input(pkg = "com.example.somebank"))
        assertTrue("不在白名单的包必须挡掉，实际 $out", out is AutoCapturePipeline.Outcome.Ignored)
        assertEquals(0, db.autoDraftDao().observeAll().first().size)
    }

    // -------------------------------------------------------------- 确认

    @Test
    fun confirmingADraftWritesExactlyOneTransaction() = runBlocking {
        pipeline.capture(input())
        val draft = db.autoDraftDao().observeAll().first().single()
        val editor = LocalEditWriter(db)
        editor.createTransaction(
            amountMinor = draft.amountMinor,
            direction = draft.direction,
            accountId = draft.accountId ?: "A1",
            occurredAt = draft.occurredAt,
            ledgerId = draft.ledgerId,
            deviceId = "test-device",
            merchant = draft.merchant,
            source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
        )
        db.autoDraftDao().delete(draft.id)
        val rows = db.txnDao().pendingBatch(limit = 50)
        assertEquals(1, rows.size)
        // 金额必须原样落库，且**带 pending 戳**——不然同步时会被当成"本地没有"
        assertEquals(2350L, rows[0].amountMinor)
        assertNotNull(rows[0].pendingJson)
    }

    // ------------------------------------------------------------ 清理

    @Test
    fun purgeOnlyDeletesDraftsOlderThanTheWindow() = runBlocking {
        val t0 = base
        pipeline.capture(input(at = t0))
        val window = AutoBook.effectiveWindowMs(store.dedupeWindowMs)

        // 窗口内的：还在去重窗口里，删掉会让同一笔被记两次
        pipeline.purgeExpired(now = t0 + window - 1_000)
        assertEquals("窗口内的草稿不能被清掉", 1, db.autoDraftDao().observeAll().first().size)

        // 窗口外的：留着只会让"同额两次"的判断一直命中旧数据
        pipeline.purgeExpired(now = t0 + window * 3)
        assertEquals(0, db.autoDraftDao().observeAll().first().size)
    }
}
