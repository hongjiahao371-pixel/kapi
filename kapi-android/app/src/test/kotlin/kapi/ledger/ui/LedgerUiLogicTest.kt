package kapi.ledger.ui

import kapi.ledger.data.TxnEntity
import kapi.ledger.llm.calculateInSampleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 金额键盘解析的回归测试。
 *
 * 这个函数只值五行，但它决定的是"用户记的账是多少"。错一位就是错账，
 * 而错账在账本产品里是最不可接受的一类 bug——用户不会发现，只会继续错下去。
 *
 * 特意覆盖了"用户正在输入的中间态"：刚按下小数点那一刻按钮不能突然变灰。
 */
class ParseAmountToMinorTest {

    @Test
    fun plainIntegerIsCents() {
        assertEquals(1200L, parseAmountToMinor("12"))
        assertEquals(0L, parseAmountToMinor("0"))
    }

    @Test
    fun oneAndTwoDecimals() {
        assertEquals(2350L, parseAmountToMinor("23.5"))
        assertEquals(2350L, parseAmountToMinor("23.50"))
        assertEquals(1L, parseAmountToMinor("0.01"))
    }

    @Test
    fun leadingZeroDoesNotLosePrecision() {
        // 键盘上先按 0 再按 . 再按 5，是最常见的一条路径。
        // 0.50 元 = 50 分。写成 500 就等于把账整整记大 10 倍。
        assertEquals(0L, parseAmountToMinor("0.0"))
        assertEquals(50L, parseAmountToMinor("0.5"))
        assertEquals(50L, parseAmountToMinor("0.50"))
    }

    @Test
    fun intermediateTypingStates() {
        // 正在输入：按钮保持可用，值为 0，保存仍然禁用
        assertEquals(0L, parseAmountToMinor("0."))
        assertEquals(1200L, parseAmountToMinor("12."))
    }

    @Test
    fun moreThanTwoDecimalsIsRejectedNotRounded() {
        // 用户在这里输 12.345 几乎一定是手滑。直接存成 1235 分比让他看到
        // 自己输错更糟——四舍五入会悄悄改掉他写的数。
        assertNull(parseAmountToMinor("12.345"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(parseAmountToMinor(""))
        assertNull(parseAmountToMinor("   "))
        assertNull(parseAmountToMinor("."))
        assertNull(parseAmountToMinor("abc"))
        assertNull(parseAmountToMinor("-12"))
        assertNull(parseAmountToMinor("1.2.3"))
        assertNull(parseAmountToMinor("¥12"))
        // 键盘上打不出但剪贴板里可能有：千位分隔不是金额输入的合法形态
        assertNull(parseAmountToMinor("1,200"))
    }

    @Test
    fun hugeAmountsSurvive() {
        // 期望值用算式写出来。手写字面量时把下划线分组看串了，
        // 差出来的量级足以让这个断言变成"一个看起来在测边界、其实测错了"的假测试。
        val yuan = 99_999_999L          // 元
        val cents = 1L                  // 分
        assertEquals(yuan * 100 + cents, parseAmountToMinor("99999999.01"))
        // 尾数为奇数：任何一处被当成浮点做加减，这一笔最先露馅
        assertEquals(1L, parseAmountToMinor("0.01"))
        assertEquals(9L, parseAmountToMinor("0.09"))
    }
}

/**
 * 列表分组的回归测试。
 *
 * 分组错了的后果不是"显示难看"，而是**月度汇总和当日小计对不上**——
 * 用户会以为账丢了。而汇总用的是 Long 分，累加顺序错了也不该有影响，
 * 这正是当初选 Long 而不是 Double 的理由，这里把它钉住。
 */
class GroupByDayTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun txn(
        id: String,
        at: Long,
        amount: Long,
        direction: String = "EXPENSE",
        categoryId: String? = null,
    ) = TxnEntity(
        id = id,
        ledgerId = "L1",
        amountMinor = amount,
        direction = direction,
        accountId = "A1",
        categoryId = categoryId,
        occurredAt = at,
    )

    @Test
    fun groupsByDayNewestFirst() {
        val rows = listOf(
            txn("a", at(2026, 9, 1, 9), 100),
            txn("b", at(2026, 9, 2, 9), 200),
            txn("c", at(2026, 9, 1, 20), 300),
        )
        val groups = groupByDay(rows, zone)
        assertEquals(2, groups.size)
        // 账本列表的时间倒序是硬要求：用户刚记的那笔必须在最上面，
        // 否则他要翻很久才知道记没记上。
        assertEquals("最新的一天排在最上面", LocalDate.of(2026, 9, 2), groups[0].day)
        assertEquals(LocalDate.of(2026, 9, 1), groups[1].day)
        assertEquals(2, groups[1].rows.size)
    }

    @Test
    fun withinDayRowsAreNewestFirst() {
        val rows = listOf(
            txn("early", at(2026, 9, 1, 8), 100),
            txn("late", at(2026, 9, 1, 22), 200),
        )
        val groups = groupByDay(rows, zone)
        assertEquals(1, groups.size)
        assertEquals(listOf("late", "early"), groups[0].rows.map { it.id })
    }

    @Test
    fun dayTotalOnlyCountsExpenses() {
        // 收入混进"当日支出"小计是这类列表最常见的算错：用户会看到
        // "今天支出 130" 而实际只花了 100。
        val rows = listOf(
            txn("a", at(2026, 9, 1, 9), 100),
            txn("b", at(2026, 9, 1, 10), 500, direction = "INCOME"),
            txn("c", at(2026, 9, 1, 11), 30),
        )
        val groups = groupByDay(rows, zone)
        assertEquals(130L, groups[0].expenseMinor)
    }

    @Test
    fun unknownDirectionIsNotCountedAsExpense() {
        // 识别失败的草稿方向是 UNKNOWN。硬算进支出会让汇总凭空多一笔。
        val rows = listOf(txn("u", at(2026, 9, 1, 9), 999, direction = "UNKNOWN"))
        assertEquals(0L, groupByDay(rows, zone)[0].expenseMinor)
    }

    @Test
    fun emptyInputYieldsNoGroups() {
        assertEquals(0, groupByDay(emptyList(), zone).size)
    }

    /**
     * 统计页的占比文案。
     *
     * 真实数据里小额很常见：8800 元的月支出里有一笔 42 元，占比 0.48%。
     * 早期版本直接 `(share*100).toInt()`，屏幕上显示的是「0%」——用户会以为
     * 这一栏是空的或者金额算错了，而不是"确实很少"。所以小于 1% 必须明说。
     */
    @Test
    fun shareBelowOnePercentIsNotShownAsZero() {
        assertEquals("<1%", formatShare(0.0048f))
        assertEquals("<1%", formatShare(0.0099f))
        // 正好 1% 应该显示 1%，不能被 <1% 吃掉
        assertEquals("1%", formatShare(0.01f))
    }

    @Test
    fun shareRoundsDownWithinOnePercentAndNeverShowsHundred() {
        assertEquals("93%", formatShare(0.9349f))
        assertEquals("99%", formatShare(0.994f))
        // 99.5% 以上统一收敛到 100%，不显示 99%
        assertEquals("100%", formatShare(0.996f))
        assertEquals("100%", formatShare(1f))
    }

    @Test
    fun shareHandlesZeroAndNonPositiveWithoutPrintingNaN() {
        assertEquals("0%", formatShare(0f))
        assertEquals("0%", formatShare(-0.5f))
        // totalExpense 为 0 时分母是 0，ratio 会是 NaN 或 Inf；屏幕上不能出现 "NaN%"
        assertEquals("0%", formatShare(Float.NaN))
        assertEquals("100%", formatShare(Float.POSITIVE_INFINITY))
    }

    @Test
    fun expenseByCategorySortsByAmountAndIgnoresIncome() {
        val rows = listOf(
            txn("a", at(2026, 9, 1, 9), 100, categoryId = "cat-1"),
            txn("b", at(2026, 9, 2, 9), 300, categoryId = "cat-2"),
            txn("c", at(2026, 9, 2, 10), 999, categoryId = "cat-2", direction = "INCOME"),
            txn("d", at(2026, 9, 3, 9), 50, categoryId = "cat-1"),
        )
        val byCat = expenseByCategory(rows)
        // 收入不能混进支出构成，否则"这个月花在哪了"会答错
        assertEquals(listOf("cat-2" to 300L, "cat-1" to 150L), byCat)
    }

    /**
     * 截图降采样。
     *
     * 两个方向的错都很贵：
     *  - 采样率给 **1** → 一张 1080×2400 的截图原样进内存再 base64，
     *    请求体几百 KB，弱端点必然超时，而用户只看到"识别失败"。
     *  - 给**非 2 的幂** → BitmapFactory 内部按位跳行，非幂值行为未定义，
     *    有些机型直接解出全尺寸，等于白算一遍还以为是省了。
     */
    @Test
    fun inSampleSizeIsAlwaysAPowerOfTwo() {
        val cases = listOf(1080 to 2400, 4000 to 3000, 999 to 999, 1280 to 720, 3000 to 3000)
        for ((w, h) in cases) {
            val s = calculateInSampleSize(w, h, 1280, 1280)
            // 2 的幂的判据：s > 0 且二进制里只有一个 1。s == 1 也要算通过（2^0），
            // 所以不能写成 `s and (s - 1) == 1` —— 那会把 1 自己判成不通过。
            assertTrue("$w x $h 得到采样率 $s <= 0", s > 0)
            assertEquals(
                "${w}x$h 的采样率 $s 不是 2 的幂",
                0,
                s and (s - 1),
            )
        }
    }

    @Test
    fun inSampleSizeShrinksLargeScreenshotsButKeepsSmallOnesIntact() {
        // 1080x2400：半尺寸 540x1200，1200 < 1280 砍不动 → 保持 1，
        // 精确缩放交给 createScaledBitmap。
        assertEquals(1, calculateInSampleSize(1080, 2400, 1280, 1280))
        // 4000x3000：半尺寸 2000x1500 都 >= 1280 → 砍到 2；
        // 再砍一次是 1000x750，750 < 1280 会糊，所以停在 2（解出 2000x1500）。
        assertEquals(2, calculateInSampleSize(4000, 3000, 1280, 1280))
        // 已经小于目标的一律不动
        assertEquals(1, calculateInSampleSize(640, 480, 1280, 1280))
    }

    @Test
    fun inSampleSizeSurvivesDegenerateInput() {
        // 目标尺寸为 0 时循环条件恒真，sample 翻倍到溢出成 0，下一轮 halfH / 0
        // 会抛 ArithmeticException。这是实测出来的真崩溃，不是假想。
        assertEquals(1, calculateInSampleSize(0, 0, 1280, 1280))
        assertEquals(1, calculateInSampleSize(-5, 100, 1280, 1280))
        assertEquals(1, calculateInSampleSize(100, 100, 0, 0))
        assertEquals(1, calculateInSampleSize(100, 100, 1280, -1))
    }
}
