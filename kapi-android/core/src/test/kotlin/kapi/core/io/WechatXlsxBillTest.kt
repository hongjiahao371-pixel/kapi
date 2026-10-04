package kapi.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 微信「下载明细」现在给的是 **xlsx**（2026-10-04 用户实测，此前是 csv/zip-csv）。
 * 这组测试用合成 xlsx 钉住整条链：解包 → sheet 转 CSV（日期序列号转文本）→
 * 表头扫描 → 行解析。
 */
class WechatXlsxBillTest {

    /** Excel 1900 日期系统序列号：与转换器（LocalDate(1899,12,30).plusDays）互逆。 */
    private fun excelSerial(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Double {
        val date = LocalDate.of(y, mo, d)
        val days = date.toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()
        val secs = h * 3600L + mi * 60L + s
        return days + secs / 86400.0
    }

    /** 造一个最小但结构同构的微信账单 xlsx（sharedStrings + sheet1）。 */
    private fun wechatXlsx(rows: List<List<String>>): ByteArray {
        val shared = ArrayList<String>()
        fun sid(text: String): String {
            shared += text
            return (shared.size - 1).toString()
        }

        // 先构建 sheet body（这一步会向 shared 注册字符串），最后统一写包——
        // 否则 sharedStrings 先落包时还是空的（第一次跑就栽在这）。
        val body = StringBuilder()
        rows.forEachIndexed { idx, row ->
            body.append("<row r=\"${idx + 1}\">")
            row.forEachIndexed { col, cell ->
                when {
                    cell.isEmpty() -> {}
                    cell.toDoubleOrNull() != null && col == 0 && idx > 0 ->
                        body.append("<c r=\"A${idx + 1}\"><v>$cell</v></c>")
                    else ->
                        body.append("<c r=\"${'A' + col}${idx + 1}\" t=\"s\"><v>${sid(cell)}</v></c>")
                }
            }
            body.append("</row>")
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("xl/workbook.xml"))
            z.write("<workbook><sheets></sheets></workbook>".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            z.write(
                ("<sst>" + shared.map { "<si><t>$it</t></si>" }.joinToString("") + "</sst>").toByteArray(),
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            z.write(("<worksheet>$body</worksheet>").toByteArray())
            z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `wechat xlsx bill parses end to end`() {
        // 结构同构于真实导出：元信息行、分隔行、表头在第 18 行附近、
        // 交易时间是 Excel 日期序列号数字、支付方式有零钱和银行卡两种。
        val serial = excelSerial(2026, 10, 4, 10, 34, 30)
        val rows = listOf(
            listOf("微信支付账单明细"),
            listOf("微信昵称：[橘生]"),
            listOf("----------------------微信支付账单明细列表--------------------"),
            listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"),
            listOf(serial.toString(), "商户消费", "春城精品果汇和谐店", "水果", "支出", "16.05", "零钱", "支付成功", "10001", "/"),
            listOf(serial.toString(), "商户消费", "靖晨园餐饮店", "午餐", "支出", "29.00", "招商银行信用卡(9590)", "支付成功", "10002", "/"),
        )
        val bytes = wechatXlsx(rows)

        val text = BankBillParser.decode(bytes)
        val result = BankBillParser.parse(text)

        val p = when (result) {
            is BankBillParser.Result.Ok -> result.parsed
            is BankBillParser.Result.Bad -> throw AssertionError("解析失败：${result.why}｜文本头 300：${text.take(300)}")
        }
        assertEquals(2, p.rows.size)
        val first = p.rows[0]
        assertEquals("春城精品果汇和谐店", first.merchant)
        assertEquals(1605L, first.amountMinor)
        // 日期序列号转换：Excel 序列号（数字文本）必须转成解析器认识的时间文本
        assertEquals(
            LocalDateTime.of(2026, 10, 4, 10, 34, 30),
            java.time.Instant.ofEpochMilli(first.occurredAt).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime(),
        )
        assertEquals("靖晨园餐饮店", p.rows[1].merchant)
        assertEquals(2900L, p.rows[1].amountMinor)
        assertTrue(p.skippedNeutral == 0)
    }

    @Test
    fun `rows matching an existing account by amount-direction-minute are skipped`() {
        // 2026-10-04 用户反馈：自动记账已记的账，再从微信 xlsx 导一遍会重复入账。
        // 根因：完整指纹含商户/备注文本，两个渠道的文本必然不同。
        // 修复后：parse 接受 looseSeen（金额+方向+分钟桶），命中的行跳过。
        val serial = excelSerial(2026, 10, 4, 10, 34, 30)
        val rows = listOf(
            listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"),
            listOf(serial.toString(), "商户消费", "扫二维码付款-给L♪ii♪♪", "早餐", "支出", "2.00", "零钱", "支付成功", "10003", "/"),
        )
        val bytes = wechatXlsx(rows)

        // 已有账目：同一天 10:34（同一分钟桶）、2.00、支出——就是自动记的那笔
        val existing = kapi.core.io.TransferRow(
            occurredAt = java.time.LocalDateTime.of(2026, 10, 4, 10, 34, 30)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            direction = "EXPENSE",
            amountMinor = 200,
            merchant = null,
            note = "早餐",
        )
        val loose = setOf(kapi.core.io.TransferFingerprint.looseOf(existing))

        val text = BankBillParser.decode(bytes)
        val result = BankBillParser.parse(text, looseSeen = loose)

        // 全部行都被宽松判重命中时，parse 回 Bad 并交代"都在账本里了"
        assertTrue(result is BankBillParser.Result.Bad)
        assertTrue((result as BankBillParser.Result.Bad).why.contains("已经在账本里"))
        
    }

    @Test
    fun `zip without csv or xlsx degrades to header error`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("readme.txt"))
            z.write("这不是账单".toByteArray())
            z.closeEntry()
        }
        val result = BankBillParser.parse(BankBillParser.decode(out.toByteArray()))
        assertTrue(result is BankBillParser.Result.Bad)
    }
}
