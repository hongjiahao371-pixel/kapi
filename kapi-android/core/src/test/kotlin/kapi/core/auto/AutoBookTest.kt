package kapi.core.auto

import kapi.core.auto.AutoBook.AutoOutcome
import kapi.core.auto.AutoBook.CaptureChannel
import kapi.core.auto.AutoBook.ParserKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBookTest {

    // ------------------------------------------------------------ 归一化

    @Test
    fun `samePaymentPageReadTwiceProducesTheSameFingerprint`() {
        // 微信支付成功页被无障碍连续读两次，只有换行和空格不同
        val a = AutoBook.exactFingerprint(
            CaptureChannel.A11Y,
            "支付成功\n¥23.50\n星巴克\n当前状态：支付成功"
        )
        val b = AutoBook.exactFingerprint(
            CaptureChannel.A11Y,
            "支付成功 ¥23.50 星巴克 当前状态：支付成功"
        )
        assertEquals("换行和空格不该改变指纹", a, b)
        assertEquals(16, a.length)
        // 全角/半角也不该改变
        val c = AutoBook.exactFingerprint(
            CaptureChannel.A11Y,
            "支付成功\n￥２３．５０\n星巴克"
        )
        assertEquals("全角数字应归一到半角", AutoBook.normalize("￥２３．５０"), AutoBook.normalize("¥23.50"))
        assertNotEquals("金额不同必须是不同指纹", a, c)
    }

    @Test
    fun `orderNumberAndBalanceDoNotBreakFingerprint`() {
        // 同一笔支付，页面上订单号与余额在两次读取间变了
        val a = AutoBook.exactFingerprint(
            CaptureChannel.A11Y,
            "支付成功\n¥23.50\n订单号 20260929153012345678\n余额 ¥1234.56"
        )
        val b = AutoBook.exactFingerprint(
            CaptureChannel.A11Y,
            "支付成功\n¥23.50\n订单号 20260929153099998888\n余额 ¥1211.06"
        )
        assertEquals("订单号和余额变了仍应判为同一笔", a, b)
    }

    @Test
    fun `balanceIsStrippedEvenWithoutCurrencySymbol`() {
        val a = AutoBook.normalize("实付 ¥23.50 当前余额 1200.00")
        val b = AutoBook.normalize("实付 ¥23.50")
        assertEquals("余额片段应被抹掉", b, a)
    }

    @Test
    fun `amountIsTheAnchorAndSurvivesNormalization`() {
        val a = AutoBook.exactFingerprint(CaptureChannel.NOTIFY, "您有一笔支出，人民币23.50元")
        val b = AutoBook.exactFingerprint(CaptureChannel.NOTIFY, "您有一笔支出，人民币2350元")
        assertNotEquals("2 位小数和整数是不同的金额", a, b)
    }

    @Test
    fun `differentChannelsNeverShareAFingerprint`() {
        val notify = AutoBook.exactFingerprint(CaptureChannel.NOTIFY, "支付成功 ¥23.50 星巴克")
        val a11y = AutoBook.exactFingerprint(CaptureChannel.A11Y, "支付成功 ¥23.50 星巴克")
        assertNotEquals("两段文本相同也不能跨源去重", notify, a11y)
    }

    @Test
    fun `emptyTextFallsBackToOneSharedBucketAndDoesNotThrow`() {
        val a = AutoBook.exactFingerprint(CaptureChannel.A11Y, null)
        val b = AutoBook.exactFingerprint(CaptureChannel.A11Y, "   \n  ")
        assertEquals("空文本不该抛异常", a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun `longTextIsNotSilentlyTruncatedIntoCollisions`() {
        val long1 = "¥23.50 " + "备注".repeat(5000)
        val long2 = "¥23.51 " + "备注".repeat(5000)
        assertNotEquals(
            "超长文本不同金额仍要分得开",
            AutoBook.exactFingerprint(CaptureChannel.A11Y, long1),
            AutoBook.exactFingerprint(CaptureChannel.A11Y, long2),
        )
    }

    // ------------------------------------------------------------ 时间窗

    @Test
    fun `duplicateInsideWindowIsDroppedOutsideItIsNot`() {
        val t0 = 1_700_000_000_000L
        val window = AutoBook.DEFAULT_WINDOW_MS
        assertFalse("第一次必须放行", AutoBook.isDuplicate(0L, t0, window))
        assertTrue("窗口内是重复", AutoBook.isDuplicate(t0, t0 + 1_000, window))
        assertTrue("窗口内是重复", AutoBook.isDuplicate(t0, t0 + window - 1, window))
        assertFalse("刚好超出窗口是新的一笔", AutoBook.isDuplicate(t0, t0 + window, window))
        assertFalse("超出窗口是新的一笔", AutoBook.isDuplicate(t0, t0 + window * 4, window))
    }

    @Test
    fun `windowBelowFloorIsClampedInsteadOfHonoured`() {
        val t0 = 1_700_000_000_000L
        // 用户在设置里把窗口拉到 0 或 1 秒——这只能造成漏记，没有任何收益
        for (bad in listOf(0L, 1L, -1L, 999L, AutoBook.MIN_WINDOW_MS - 1)) {
            assertEquals(
                "窗口 $bad 应被抬到下限",
                AutoBook.MIN_WINDOW_MS,
                AutoBook.effectiveWindowMs(bad),
            )
            assertTrue(
                "窗口 $bad 下 10 秒内的重复仍应被识别（下限要真的兜住）",
                AutoBook.isDuplicate(t0, t0 + 10_000, bad),
            )
            assertFalse(
                "下限是地板不是无限期：窗口 $bad 下 21 秒已算新的一笔",
                AutoBook.isDuplicate(t0, t0 + AutoBook.MIN_WINDOW_MS + 1, bad),
            )
        }
    }

    @Test
    fun `clockWentBackwardsIsTreatedAsDuplicateNotAsNew`() {
        val t0 = 1_700_000_000_000L
        // 墙钟被往回拨：now < lastSeenAt。此时判成"新的一笔"会放行一次重复
        assertTrue(AutoBook.isDuplicate(t0, t0 - 5_000, AutoBook.DEFAULT_WINDOW_MS))
        assertTrue(AutoBook.isDuplicate(t0, 0L, AutoBook.DEFAULT_WINDOW_MS))
    }

    // -------------------------------------------------------------- 分流

    @Test
    fun `ruleParsedNeverAutoPostsEvenAtMaxConfidenceAndThresholdZero`() {
        val out = AutoBook.route(
            parser = ParserKind.RULE,
            confidence = 0.55,
            autoPostEnabled = true,
            threshold = 0.0,
        )
        assertTrue(
            "规则解析的 0.55 不是概率，不能拿去和阈值比，实际拿到的是 $out",
            out is AutoOutcome.Draft,
        )
    }

    @Test
    fun `llmAboveThresholdPostsWithTheReasonRecorded`() {
        val out = AutoBook.route(ParserKind.LLM, 0.92, autoPostEnabled = true, threshold = 0.5)
        assertTrue(out is AutoOutcome.Post)
        assertTrue((out as AutoOutcome.Post).reason.contains("0.92"))
        assertTrue(out.reason.contains("0.50"))
    }

    @Test
    fun `llmBelowThresholdGoesToDraftAndSaysWhy`() {
        val out = AutoBook.route(ParserKind.LLM, 0.42, autoPostEnabled = true, threshold = 0.5)
        assertTrue(out is AutoOutcome.Draft)
        val why = (out as AutoOutcome.Draft).reason
        assertTrue("界面上要能解释为什么还要确认: $why", why.contains("0.42"))
        assertTrue(why.contains("0.50"))
    }

    @Test
    fun `autoPostOffSendsEverythingToDraftRegardlessOfConfidence`() {
        val out = AutoBook.route(ParserKind.LLM, 0.99, autoPostEnabled = false, threshold = 0.5)
        assertTrue(out is AutoOutcome.Draft)
    }

    @Test
    fun `nanConfidenceIsDraftedInsteadOfSilentlyPosted`() {
        // NaN 和任何数比较都是 false，朴素的 `confidence >= threshold` 写法会让
        // NaN 静默放行，而 NaN 恰恰是最不该放行的输入
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            val out = AutoBook.route(ParserKind.LLM, bad, autoPostEnabled = true, threshold = 0.5)
            assertTrue("置信度 $bad 必须进草稿，实际拿到 $out", out is AutoOutcome.Draft)
        }
    }

    // ---------------------------------------------------------- 跨源合并

    @Test
    fun `notifyAndA11yForOnePaymentAreFlaggedAsSameEvent`() {
        assertTrue(
            AutoBook.isSamePaymentEvent(
                2350, "EXPENSE", 1_700_000_000_000L, CaptureChannel.NOTIFY,
                2350, "expense", 1_700_000_003_000L, CaptureChannel.A11Y,
            )
        )
    }

    @Test
    fun `twoRealPaymentsOfTheSameAmountMinutesApartAreNotMerged`() {
        val t = 1_700_000_000_000L
        assertFalse(
            "隔了 5 分钟的两笔 23.50 是两笔真的消费",
            AutoBook.isSamePaymentEvent(
                2350, "EXPENSE", t, CaptureChannel.NOTIFY,
                2350, "EXPENSE", t + 5 * 60_000, CaptureChannel.A11Y,
            ),
        )
    }

    @Test
    fun `sameAmountButOppositeDirectionIsNeverMerged`() {
        val t = 1_700_000_000_000L
        assertFalse(
            "退款和支付金额相同也不能并成一笔",
            AutoBook.isSamePaymentEvent(
                2350, "EXPENSE", t, CaptureChannel.NOTIFY,
                2350, "INCOME", t + 1_000, CaptureChannel.A11Y,
            ),
        )
    }

    @Test
    fun `sameChannelNeverReachesTheMergePath`() {
        val t = 1_700_000_000_000L
        assertFalse(
            "同源的重复要靠丢弃解决，不该走到合并——合并会留证据，丢弃不留",
            AutoBook.isSamePaymentEvent(
                2350, "EXPENSE", t, CaptureChannel.A11Y,
                2350, "EXPENSE", t + 1_000, CaptureChannel.A11Y,
            ),
        )
    }

    // ------------------------------------------------ 同源近重复合并
    //
    // 2026-09-30 真机事故：一笔 1.00 元弹出两条草稿。除了「确认付款页不该记」
    // 那个语义修正之外，读屏读到同一笔的**多个屏/多个变体**也必须兜住——
    // 精确指纹是按整段文本算的，页面一有动画或加载态，指纹就变了。

    @Test
    fun `same channel near miss merges one payment read twice`() {
        val t = 1_700_000_000_000L
        assertTrue(
            "同一条读屏链路、同额同向、3 秒内——是同一笔",
            AutoBook.isSameChannelNearMiss(100, "EXPENSE", t, 100, "EXPENSE", t + 3_000),
        )
    }

    @Test
    fun `same channel near miss respects its own much shorter window`() {
        val t = 1_700_000_000_000L
        assertFalse(
            "同源窗口是 20 秒，30 秒就该算两笔",
            AutoBook.isSameChannelNearMiss(100, "EXPENSE", t, 100, "EXPENSE", t + 30_000),
        )
        assertTrue(
            "刚好 20 秒内仍算同一笔",
            AutoBook.isSameChannelNearMiss(100, "EXPENSE", t, 100, "EXPENSE", t + AutoBook.SAME_CHANNEL_WINDOW_MS),
        )
        assertTrue(
            "同源窗口必须远小于跨源的 120 秒，否则会把两笔真消费合并",
            AutoBook.SAME_CHANNEL_WINDOW_MS < AutoBook.CROSS_SOURCE_WINDOW_MS,
        )
    }

    @Test
    fun `same channel near miss still requires equal amount and direction`() {
        val t = 1_700_000_000_000L
        assertFalse(
            "金额不同",
            AutoBook.isSameChannelNearMiss(100, "EXPENSE", t, 2350, "EXPENSE", t + 3_000),
        )
        assertFalse(
            "方向不同：退款和支付不能并",
            AutoBook.isSameChannelNearMiss(2350, "EXPENSE", t, 2350, "INCOME", t + 3_000),
        )
    }

    @Test
    fun `cross source merge is still preferred over the same channel rule`() {
        // 两条判据是并列的兜底关系，不是替代关系：跨源那套先跑。
        val t = 1_700_000_000_000L
        assertTrue(
            "跨源（另一条通路的证据）仍然成立",
            AutoBook.isSamePaymentEvent(
                100, "EXPENSE", t, CaptureChannel.NOTIFY,
                100, "EXPENSE", t + 2_000, CaptureChannel.A11Y,
            ),
        )
        assertTrue(
            "同源那套只在跨源不成立时才起作用",
            AutoBook.isSameChannelNearMiss(100, "EXPENSE", t, 100, "EXPENSE", t + 2_000),
        )
    }
}
