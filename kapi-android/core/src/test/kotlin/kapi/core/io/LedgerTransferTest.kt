package kapi.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LedgerTransferTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun row(
        amount: Long = 12345,
        dir: String = "EXPENSE",
        merchant: String? = "星巴克",
        note: String? = null,
        category: String? = "餐饮",
        account: String? = "微信",
        at: Long = 1_757_000_000_000,
    ) = TransferRow(at, dir, amount, merchant, note, category, account)

    // ---- 金额：Long 分 → 元，全程精确 ----

    @Test
    fun `金额转元不做浮点`() {
        // 12345 分。写成 minor/100.0 会得到 123.44999999999999
        assertEquals("123.45", Money.toYuan(12345))
        assertEquals("0.01", Money.toYuan(1))
        assertEquals("0.00", Money.toYuan(0))
        assertEquals("-0.01", Money.toYuan(-1))
        assertEquals("99999999999.99", Money.toYuan(9_999_999_999_999L))
    }

    @Test
    fun `支出为负收入为正`() {
        assertEquals("-123.45", row(dir = "EXPENSE").signedYuan())
        assertEquals("123.45", row(dir = "INCOME").signedYuan())
    }

    // ---- CSV 转义：两类静默错误 ----

    @Test
    fun `含逗号的字段必须加引号`() {
        assertEquals("\"星巴克, 上海\"", LedgerCsv.quote("星巴克, 上海"))
    }

    @Test
    fun `引号要写成两个`() {
        assertEquals("\"a\"\"b\"", LedgerCsv.quote("a\"b"))
    }

    @Test
    fun `换行必须转义否则一行变两行`() {
        assertEquals("\"第一行\n第二行\"", LedgerCsv.quote("第一行\n第二行"))
    }

    @Test
    fun `文本字段的公式注入必须被中和`() {
        val csv = LedgerCsv.toCsv(listOf(row(merchant = "=cmd|'/c calc'!A0")), shanghai)
        val body = csv.removePrefix("﻿").trim().split("\r\n")[1]
        // 第 4 列是商户
        assertTrue("商户列没被防护：$body", splitCsvLine(body)[3].startsWith("'="))
    }

    @Test
    fun `负数金额必须是数字不能被防护成文本`() {
        // 这是真事故：防护把金额列变成文本列，Excel 求和得 0，
        // 而导出 CSV 的全部意义就是"拖进去能算"。
        val csv = LedgerCsv.toCsv(listOf(row(dir = "EXPENSE", amount = 12345)), shanghai)
        val body = csv.removePrefix("﻿").trim().split("\r\n")[1]
        assertEquals("-123.45", splitCsvLine(body)[2])
        assertTrue("金额列被加了前导单引号：$body", !splitCsvLine(body)[2].startsWith("'"))
    }

    @Test
    fun `普通中文和数字不加引号`() {
        assertEquals("星巴克", LedgerCsv.quote("星巴克"))
        assertEquals("2400.00", LedgerCsv.quote("2400.00"))
    }

    // ---- BOM：Excel 在 Windows 上不认 UTF-8 ----

    @Test
    fun `CSV 必须以 BOM 开头`() {
        val csv = LedgerCsv.toCsv(listOf(row()), shanghai)
        assertTrue(
            "少了 BOM，Windows 版 Excel 会按 GBK 解码，全表中文乱码且不报错",
            csv.startsWith("﻿"),
        )
    }

    @Test
    fun `表头和行数对得上且按时间正序`() {
        // 用真实量级的时间戳。拿 1000ms 这种"1970 年"的值来断言年份，
        // 测的就不是排序而是常量了。
        val base = 1_757_000_000_000L
        val rows = listOf(row(at = base + 3_000), row(at = base + 1_000), row(at = base + 2_000))
        val lines = LedgerCsv.toCsv(rows, shanghai).removePrefix("﻿").trim().split("\r\n")
        assertEquals(4, lines.size)                       // 表头 + 3 行
        assertTrue(lines[1].startsWith("2025-"))
        val t1 = lines[1].substringBefore(',')
        val t3 = lines[3].substringBefore(',')
        assertTrue("$t1 应当早于 $t3", t1 < t3)
    }

    @Test
    fun `逗号备注不会让列错位`() {
        val csv = LedgerCsv.toCsv(listOf(row(note = "午饭, 和同事")), shanghai)
        val body = csv.removePrefix("﻿").trim().split("\r\n")[1]
        // 8 列，末尾备注里的逗号被引号包住
        assertEquals(8, splitCsvLine(body).size)
        assertEquals("午饭, 和同事", splitCsvLine(body)[7])
    }

    // ---- JSON 往返 ----

    @Test
    fun `导出再导入完全还原`() {
        val rows = listOf(
            row(amount = 12345, dir = "EXPENSE", note = "带,逗号和\"引号\""),
            row(amount = 1_200_000, dir = "INCOME", merchant = "公司", category = "工资", account = "银行卡"),
            row(amount = 0, dir = "EXPENSE", merchant = null, note = null, category = null, account = null),
        )
        when (val p = LedgerJson.parse(LedgerJson.toJson(rows, 1_000L))) {
            is LedgerJson.Parsed.Ok -> assertEquals(rows, p.rows)
            is LedgerJson.Parsed.Bad -> throw AssertionError("往返失败：${p.why}")
        }
    }

    @Test
    fun `超大金额和负数时间戳不丢精度`() {
        val rows = listOf(row(amount = 9_007_199_254_740_991L, at = -1L))
        when (val p = LedgerJson.parse(LedgerJson.toJson(rows, 0L))) {
            is LedgerJson.Parsed.Ok -> {
                assertEquals(9_007_199_254_740_991L, p.rows[0].amountMinor)
                assertEquals(-1L, p.rows[0].occurredAt)
            }
            is LedgerJson.Parsed.Bad -> throw AssertionError(p.why)
        }
    }

    @Test
    fun `坏文件给的是人话不是偏移量`() {
        val p = LedgerJson.parse("这不是 json")
        assertTrue(p is LedgerJson.Parsed.Bad)
        val why = (p as LedgerJson.Parsed.Bad).why
        assertTrue("不该把 offset/异常栈甩给用户：$why", !why.contains("offset"))
        assertTrue("不该出现 kotlinx 的黑话：$why", !why.contains("JSON token"))
    }

    @Test
    fun `未来版本的文件拒绝导入而不是乱读`() {
        val future = """{"format":"kapi-ledger","version":99,"exportedAt":0,"rows":[]}"""
        val p = LedgerJson.parse(future) as LedgerJson.Parsed.Bad
        assertTrue(p.why.contains("先升级"))
    }

    @Test
    fun `缺字段的老文件照样能读`() {
        // 早期版本没有 toAccount/source
        val old = """{"format":"kapi-ledger","version":1,"exportedAt":0,
            "rows":[{"occurredAt":0,"direction":"EXPENSE","amountMinor":100}]}"""
        val p = LedgerJson.parse(old) as LedgerJson.Parsed.Ok
        assertEquals(1, p.rows.size)
        assertEquals("MANUAL", p.rows[0].source)
    }

    // ---- 去重 ----

    @Test
    fun `完全相同的两行指纹相同`() {
        assertEquals(TransferFingerprint.of(row()), TransferFingerprint.of(row()))
    }

    @Test
    fun `金额或方向不同就是另一笔`() {
        assertNotEquals(TransferFingerprint.of(row()), TransferFingerprint.of(row(amount = 12346)))
        assertNotEquals(TransferFingerprint.of(row()), TransferFingerprint.of(row(dir = "INCOME")))
    }

    @Test
    fun `同分钟内视为同一笔，超过一分钟就不是`() {
        // 无障碍和通知栏抓到同一笔时毫秒戳会差几毫秒，不能因此多一条
        val a = row(at = 1_757_000_000_000L)
        val b = row(at = 1_757_000_000_123L)      // 123ms 之后
        assertEquals(TransferFingerprint.of(a), TransferFingerprint.of(b))

        val c = row(at = 1_757_000_061_000L)      // 61 秒之后
        assertNotEquals(TransferFingerprint.of(a), TransferFingerprint.of(c))
    }

    // ---- 一个够用的 CSV 行解析器（RFC 4180），测试里自己用 ----

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
