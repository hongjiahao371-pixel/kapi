package kapi.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 这些用例全部来自"模型实际会返回什么"，不是理论构造。
 *
 * 解析器是这类 App 的头号故障点，所以这里断言的核心是：
 * **任何形状的回复都不能抛异常，且必须给出可解释的结果。**
 */
class TransactionExtractorTest {

    @Test
    fun `clean json`() {
        val r = TransactionExtractor.extract(
            """{"amount":"23.50","type":"expense","merchant":"星巴克","category":"餐饮","confidence":0.92}"""
        )
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertEquals(Direction.EXPENSE, d.direction)
        assertEquals("星巴克", d.merchant)
        assertEquals("餐饮", d.category)
        assertEquals(0.92, d.confidence, 0.001)
    }

    @Test
    fun `markdown fence`() {
        val r = TransactionExtractor.extract(
            """
            好的,我识别到这笔消费:
            ```json
            {"amount": 23.50, "type": "expense", "merchant": "瑞幸咖啡"}
            ```
            需要我帮你归类吗?
            """.trimIndent()
        )
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertEquals("瑞幸咖啡", d.merchant)
    }

    @Test
    fun `prose wrapped json without fence`() {
        val r = TransactionExtractor.extract(
            "根据图片识别结果如下: {\"amount\":\"¥128.00\",\"type\":\"expense\",\"merchant\":\"盒马\"} 希望有帮助!"
        )
        assertEquals(12800L, r.best!!.amountMinor)
    }

    @Test
    fun `chinese field names`() {
        val r = TransactionExtractor.extract(
            """{"金额":"23.50","收支类型":"支出","商家":"美团","分类":"餐饮","置信度":0.8}"""
        )
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertEquals(Direction.EXPENSE, d.direction)
        assertEquals("美团", d.merchant)
        assertEquals(0.8, d.confidence, 0.001)
    }

    /**
     * 数字写成字符串 / 写成整数 / 写成带符号的浮点，都必须得到同一个分值。
     * 模型的输出格式不受控，解析器不能挑食。
     */
    @Test
    fun `numeric type coercion`() {
        assertEquals(2350L, TransactionExtractor.extract("""{"amount":"23.50"}""").best!!.amountMinor)
        assertEquals(2350L, TransactionExtractor.extract("""{"amount":23.5}""").best!!.amountMinor)
        assertEquals(2300L, TransactionExtractor.extract("""{"amount":23}""").best!!.amountMinor)
        assertEquals(2350L, TransactionExtractor.extract("""{"amount":"23.50元"}""").best!!.amountMinor)
    }

    /** 模型把支出写成负数。量取绝对值，方向照旧，异常记进 anomalies。 */
    @Test
    fun `negative amount on an expense is normalised and reported`() {
        val r = TransactionExtractor.extract("""{"amount":"-23.50","type":"expense","merchant":"肯德基"}""")
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertEquals(Direction.EXPENSE, d.direction)
        assertTrue("符号异常必须被记录", r.anomalies.any { it.contains("负号") })
    }

    /**
     * amount_minor 是唯一的例外:模型如果直接给分,不能再乘 100。
     * 这是最容易搞错 100 倍的地方。
     */
    @Test
    fun `amount_minor is taken verbatim`() {
        assertEquals(2350L, TransactionExtractor.extract("""{"amount_minor":2350}""").best!!.amountMinor)
    }

    @Test
    fun `array of transactions`() {
        val r = TransactionExtractor.extract(
            """[{"amount":"10.00","type":"expense","merchant":"便利店"},
                {"amount":"5.50","type":"expense","merchant":"地铁"}]"""
        )
        assertEquals(2, r.drafts.size)
        assertEquals("便利店", r.drafts[0].merchant)
        assertEquals("地铁", r.drafts[1].merchant)
    }

    @Test
    fun `missing fields degrade gracefully`() {
        val r = TransactionExtractor.extract("""{"amount":"23.50"}""")
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertEquals(Direction.UNKNOWN, d.direction)
        assertNull(d.merchant)
    }

    @Test
    fun `null and empty values are skipped not crashed`() {
        val r = TransactionExtractor.extract(
            """{"amount":"23.50","merchant":null,"category":"","note":null,"extra":{"a":1}}"""
        )
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertNull(d.merchant)
        assertNull(d.category)
    }

    /** 拒答。必须安全失败,不能把整条记账流程带崩。 */
    @Test
    fun `refusal and garbage do not throw`() {
        assertTrue(TransactionExtractor.extract("抱歉,图片中无法识别出金额。").drafts.isEmpty())
        assertTrue(TransactionExtractor.extract("").drafts.isEmpty())
        assertTrue(TransactionExtractor.extract(null).drafts.isEmpty())
        assertTrue(TransactionExtractor.extract("{ broken json").drafts.isEmpty())
        assertTrue(TransactionExtractor.extract("42").drafts.isEmpty())
    }

    @Test
    fun `object with no usable amount is dropped`() {
        val r = TransactionExtractor.extract("""{"merchant":"某店","note":"没有金额"}""")
        assertTrue(r.drafts.isEmpty())
    }

    @Test
    fun `brace inside a string does not end the json early`() {
        // 括号配对必须跳过字符串字面量,否则 "值)" 会让扫描提前收尾。
        val r = TransactionExtractor.extract(
            """说明: {"amount":"23.50","note":"含 } 的备注","type":"expense"} 以上"""
        )
        val d = r.best!!
        assertEquals(2350L, d.amountMinor)
        assertEquals("含 } 的备注", d.note)
    }

    @Test
    fun `escaped quote inside a string`() {
        val r = TransactionExtractor.extract(
            """{"amount":"23.50","note":"说了 \"你好\" 两个字","type":"expense"}"""
        )
        assertNotNull(r.best)
        assertEquals(2350L, r.best!!.amountMinor)
    }

    @Test
    fun `confidence given as a word or a percentage`() {
        assertEquals(0.9, TransactionExtractor.extract("""{"amount":"1","confidence":"high"}""").best!!.confidence, 0.001)
        assertEquals(0.6, TransactionExtractor.extract("""{"amount":"1","confidence":"medium"}""").best!!.confidence, 0.001)
        assertEquals(0.8, TransactionExtractor.extract("""{"amount":"1","confidence":80}""").best!!.confidence, 0.001)
        assertEquals(0.8, TransactionExtractor.extract("""{"amount":"1","confidence":"0.8"}""").best!!.confidence, 0.001)
    }

    @Test
    fun `unknown direction words stay unknown rather than guessing`() {
        // 猜错方向 = 把支出记成收入,是账本里最不能接受的错误。
        val r = TransactionExtractor.extract("""{"amount":"23.50","type":"转账"}""")
        assertEquals(Direction.UNKNOWN, r.best!!.direction)
    }

    /**
     * 时间解析。
     *
     * 这一组是用例逼出来的：**提示词明确要求模型回 `"2026-09-29T15:30:00"`**，
     * 而解析器原先只认 epoch 毫秒，于是每一笔识别出来的时间都被静默丢成 0，
     * 界面上变成"这笔发生在此时此刻"。错得毫无痕迹——截图上明明写着 15:30。
     *
     * 无时区偏移的按本机时区解释：账单上的时间就是用户所在地的本地时间，
     * 转成 UTC 反而会凭空差一个时区。
     */
    @Test
    fun `iso local datetime is parsed into epoch millis`() {
        val tz = ZoneId.of("Asia/Shanghai")
        val expected = LocalDateTime.of(2026, 9, 29, 15, 30, 0)
            .atZone(tz).toInstant().toEpochMilli()
        for (text in listOf("2026-09-29T15:30:00", "2026-09-29 15:30:00")) {
            val r = extractWithZone("""{"amount":"23.50","type":"expense","occurred_at":"$text"}""", tz)
            assertEquals("没能解析 [$text]", expected, r.best!!.occurredAt)
        }
    }

    @Test
    fun `bare date becomes start of that day`() {
        val tz = ZoneId.of("Asia/Shanghai")
        val expected = LocalDate.of(2026, 9, 29).atStartOfDay(tz).toInstant().toEpochMilli()
        val r = extractWithZone("""{"amount":"23.50","type":"expense","occurred_at":"2026-09-29"}""", tz)
        assertEquals(expected, r.best!!.occurredAt)
    }

    @Test
    fun `epoch millis and epoch seconds both work`() {
        val millis = 1_790_000_000_000L
        val tz = ZoneId.of("Asia/Shanghai")
        assertEquals(
            millis,
            extractWithZone("""{"amount":"1","occurred_at":$millis}""", tz).best!!.occurredAt,
        )
        // 10 位是秒，必须补成毫秒，否则落在 1970 年 1 月
        assertEquals(
            millis,
            extractWithZone("""{"amount":"1","occurred_at":1790000000}""", tz).best!!.occurredAt,
        )
    }

    @Test
    fun `garbage time degrades to zero instead of throwing`() {
        val tz = ZoneId.of("Asia/Shanghai")
        val r = extractWithZone("""{"amount":"23.50","occurred_at":"昨天下午吧"}""", tz)
        // 0 = "没给时间"，界面会用截图时间兜底。绝不能因为时间字段脏了就整笔丢掉。
        assertNotNull(r.best)
        assertEquals(0L, r.best!!.occurredAt)
    }

    @Test
    fun `think block with a fake draft is stripped before json scan`() {
        // 推理模型（MiniMax M3）的真实形态：思考里盘算出一个没写完的假 JSON，
        // 正式答案在后面的围栏里。不剥 think 的话，括号配对从思考里的
        // 第一个 { 开始扫，抽出来的就是残次品。
        val raw = """
            <think>用户想记账。先估个金额：{"amount":"2400
            等等，再看一眼，应该是 24.00。</think>
            ```json
            {"amount": 24.00, "merchant": "云南乳冠", "direction": "EXPENSE"}
            ```
        """.trimIndent()
        val r = TransactionExtractor.extract(raw)
        assertEquals(1, r.drafts.size)
        assertEquals(2400L, r.best!!.amountMinor)
        assertEquals("云南乳冠", r.best!!.merchant)
    }

    @Test
    fun `unclosed think block does not hide the answer`() {
        // 被截断的回复没有 </think>：不能剥（剥了丢锚点），但围栏里的
        // JSON 若存在仍应被找到。
        val raw = """
            <think>用户想记账。金额大概是 {"amount":"24
            ```json
            {"amount": 12.00, "merchant": "测试", "direction": "INCOME"}
            ```
        """.trimIndent()
        val r = TransactionExtractor.extract(raw)
        assertEquals(1, r.drafts.size)
        assertEquals(1200L, r.best!!.amountMinor)
    }

    private fun extractWithZone(json: String, zone: ZoneId) =
        TransactionExtractor.extractIn(json, zone)
}
