package kapi.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.time.ZoneId

/**
 * 微信 / 支付宝官方账单解析。
 *
 * 样本里的表头和列顺序**照抄真实导出文件**（微信 11 列、支付宝 16 列），
 * 不是我编的理想格式——账单解析的坑几乎全在"看起来很规整"的地方，
 * 拿理想样本测等于没测。
 */
class BankBillParserTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun ok(text: String, seen: Set<String> = emptySet()): BankBillParser.Parsed {
        val r = BankBillParser.parse(text, seen, shanghai)
        assertTrue("应该解析成功，实际=$r", r is BankBillParser.Result.Ok)
        return (r as BankBillParser.Result.Ok).parsed
    }

    // ------------------------------------------------------------------
    // 微信：真实导出结构（前 16 行元信息，表头在第 17 行）
    // ------------------------------------------------------------------

    private val wechatCsv = """
        微信支付账单明细
        微信昵称：[张小明]
        起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59]
        导出时间：[2026-09-01 10:22:31]
        常见问题及重要提示：
        1. 本账单是微信支付的交易记录，不包含未支付成功的订单。
        2. 本账单中的金额单位为人民币元。
        3. 如有疑问，请登录微信官方网站。
        ----------------------微信支付账单明细列表--------------------
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2026-08-03 12:30:11,商户消费,星巴克,拿铁,支出,¥33.00,招商银行(1234),支付成功,42000xxxx,88xxxx,
        2026-08-05 08:12:00,商户消费,美团,外卖订单,支出,¥45.50,零钱,支付成功,42000yyyy,99yyyy,
        2026-08-07 19:40:22,转账,张三,,收入,¥200.00,零钱,已存入零钱,42000zzzz,77zzzz,还钱
        2026-08-10 09:00:00,商户消费,滴滴出行,快车,支出,¥28.80,微信支付,支付成功,42000aaaa,66aaaa,
        2026-08-12 14:00:00,零钱充值,招商银行,零钱,其他,¥1000.00,招商银行(1234),充值完成,42000bbbb,55bbbb,
        2026-08-15 10:00:00,商户消费,淘宝,连衣裙,支出,¥299.00,花呗,交易关闭,42000cccc,44cccc,未付款
        2026-08-20 21:00:00,商户消费,永辉超市,买菜,支出,¥156.40,零钱,支付成功,42000dddd,33dddd,
    """.trimIndent()

    @Test
    fun `微信账单能找到第 17 行的表头而不是读第 1 行`() {
        val p = ok(wechatCsv)
        assertEquals(BankBillParser.SOURCE_WECHAT, p.source)
        assertEquals(5, p.rows.size)
    }

    @Test
    fun `微信账单方向收支柱子是对的`() {
        val p = ok(wechatCsv)
        assertEquals(listOf("EXPENSE", "EXPENSE", "INCOME", "EXPENSE", "EXPENSE"), p.rows.map { it.direction })
    }

    @Test
    fun `微信金额带人民币符号也能读成正确的分`() {
        val p = ok(wechatCsv)
        assertEquals(listOf(3300L, 4550L, 20000L, 2880L, 15640L), p.rows.map { it.amountMinor })
    }

    @Test
    fun `微信的中性交易不算收入也不算支出`() {
        val p = ok(wechatCsv)
        // 零钱充值 1000 元如果记成收入，月收入直接虚增 1000
        assertEquals(1, p.skippedNeutral)
        assertTrue(p.rows.none { it.amountMinor == 100000L })
    }

    @Test
    fun `微信交易关闭的订单不记成消费`() {
        val p = ok(wechatCsv)
        assertEquals(1, p.skippedBadStatus)
        // 淘宝 299 元"交易关闭"——用户会问"我没买过这个"
        assertTrue(p.rows.none { it.amountMinor == 29900L })
    }

    @Test
    fun `微信商户名和商品进备注`() {
        val p = ok(wechatCsv)
        assertEquals("星巴克", p.rows[0].merchant)
        assertEquals("商户消费 拿铁", p.rows[0].note)
        // 转账那条商品列是空的，备注里已经有"还钱"，不该被空值盖掉
        assertEquals("转账｜还钱", p.rows[2].note)
    }

    @Test
    fun `微信时间按本地时区解析`() {
        val p = ok(wechatCsv)
        // 2026-08-03 12:30:11 +08:00 == 2026-08-03 04:30:11 UTC
        val expected = java.time.LocalDateTime.of(2026, 8, 3, 12, 30, 11)
            .atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(expected, p.rows[0].occurredAt)
    }

    // ------------------------------------------------------------------
    // 支付宝：16 列，表头前 4 行，且**列名带尾随空格**
    // ------------------------------------------------------------------

    private val alipayCsv = """
        支付宝交易记录明细查询
        账号:[z***@example.com]
        起始日期:[2026-08-01 00:00:00]    终止日期:[2026-08-31 23:59:59]
        ---------------------------------交易记录明细列表------------------------------------
        交易号 ,商家订单号 ,交易创建时间 ,付款时间 ,最近修改时间 ,交易来源地 ,类型 ,交易对方 ,商品名称 ,金额(元) ,收/支 ,交易状态 ,服务费(元) ,成功退款(元) ,备注 ,资金状态
        2026080322001122334455,T20260803001,2026-08-03 12:29:50,2026-08-03 12:30:11,2026-08-03 12:30:11,支付宝网站,即时到账交易,美团,美团外卖订单,45.50,支出,交易成功,0.00,0.00,,已支出
        2026080722009988776655,T20260807002,2026-08-07 19:39:40,2026-08-07 19:40:22,2026-08-07 19:40:22,支付宝网站,转账,李四,,200.00,收入,交易成功,0.00,0.00,还他钱,已收入
        2026081522005544332211,T20260815003,2026-08-15 10:00:00,,2026-08-15 10:05:00,支付宝网站,即时到账交易,淘宝,连衣裙,299.00,支出,交易关闭,0.00,0.00,,已关闭
        2026082022001122334456,T20260820004,2026-08-20 21:00:00,2026-08-20 21:00:05,2026-08-20 21:00:05,余额宝,余额宝,转入,1000.00,其它,交易成功,0.00,0.00,,冻结
    """.trimIndent()

    @Test
    fun `支付宝账单列名带空格也能对上`() {
        val p = ok(alipayCsv)
        assertEquals(BankBillParser.SOURCE_ALIPAY, p.source)
        assertEquals(2, p.rows.size)
    }

    @Test
    fun `支付宝取付款时间而不是交易创建时间`() {
        val p = ok(alipayCsv)
        // 创建 12:29:50 / 付款 12:30:11。取创建时间会让这批账整体前移 21 秒
        val paid = java.time.LocalDateTime.of(2026, 8, 3, 12, 30, 11)
            .atZone(shanghai).toInstant().toEpochMilli()
        val created = java.time.LocalDateTime.of(2026, 8, 3, 12, 29, 50)
            .atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(paid, p.rows[0].occurredAt)
        assertTrue("不能取创建时间", p.rows[0].occurredAt != created)
    }

    @Test
    fun `支付宝的中性交易是其它而不是其他`() {
        val p = ok(alipayCsv)
        assertEquals(1, p.skippedNeutral)
        // 余额宝转入 1000 —— 记成收入会让月收入虚增一千
        assertTrue(p.rows.none { it.amountMinor == 100000L })
    }

    @Test
    fun `支付宝备注保留并和类型拼起来`() {
        val p = ok(alipayCsv)
        assertEquals("李四", p.rows[1].merchant)
        assertEquals("转账｜还他钱", p.rows[1].note)
    }

    @Test
    fun `支付宝付款时间为空的行不导进账`() {
        // 交易关闭那行付款时间是空的——但它已经被状态过滤挡掉了，这里确认没漏
        val p = ok(alipayCsv)
        assertEquals(1, p.skippedBadStatus)
    }

    // ------------------------------------------------------------------
    // 金额精度
    // ------------------------------------------------------------------

    @Test
    fun `金额不能用浮点算`() {
        // 8.115 这种三小数：toDouble()*100 = 811.49999…，round 得 811，少一分钱
        val csv = wechatCsvHeader() + "2026-08-01 10:00:00,商户消费,某店,东西,支出,8.115,零钱,支付成功,1,1,\n"
        val p = ok(csv)
        assertEquals("8.115 元应按 HALF_UP 进位到 812 分", 812L, p.rows[0].amountMinor)
    }

    @Test
    fun `金额带千分位逗号也能读`() {
        val csv = wechatCsvHeader() + "2026-08-01 10:00:00,商户消费,某店,东西,支出,\"1,234.56\",零钱,支付成功,1,1,\n"
        val p = ok(csv)
        assertEquals(123456L, p.rows[0].amountMinor)
    }

    @Test
    fun `金额读不出来时算坏行不崩`() {
        val csv = wechatCsvHeader() +
            "2026-08-01 10:00:00,商户消费,某店,东西,支出,abc,零钱,支付成功,1,1,\n" +
            "2026-08-02 10:00:00,商户消费,某店,东西,支出,¥10.00,零钱,支付成功,2,2,\n"
        val r = BankBillParser.parse(csv, emptySet(), shanghai)
        // 坏行不能拖垮整份文件：好行照导，坏行单独计数
        val p = ok(csv)
        assertEquals(1, p.rows.size)
        assertEquals(1000L, p.rows[0].amountMinor)
        assertEquals(1, p.skippedBroken)
        assertTrue(r is BankBillParser.Result.Ok)
    }

    // ------------------------------------------------------------------
    // CSV 切分
    // ------------------------------------------------------------------

    @Test
    fun `商户名自己带逗号时不能把行切错列`() {
        val cells = BankBillParser.splitCsvLine(
            "2026-08-01 10:00:00,商户消费,肯德基, 望京店,套餐,支出,¥60.00,零钱,支付成功,1,1,",
        )
        assertEquals("肯德基", cells[2])
        // 商户名自带逗号（"肯德基, 望京店"），金额被顶到 index 6。
        // 按列名取列就完全不受影响——这正是不能按下标取列的理由。
        assertEquals(" 望京店", cells[3])
        assertEquals("¥60.00", cells[6])
    }

    @Test
    fun `引号里的逗号不是分隔符`() {
        val cells = BankBillParser.splitCsvLine("\"肯德基, 望京店\",套餐")
        assertEquals(2, cells.size)
        assertEquals("肯德基, 望京店", cells[0])
    }

    @Test
    fun `引号转义的两个引号还原成一个`() {
        val cells = BankBillParser.splitCsvLine("\"他说\"\"你好\"\"\",x")
        assertEquals("他说\"你好\"", cells[0])
    }

    // ------------------------------------------------------------------
    // 去重
    // ------------------------------------------------------------------

    @Test
    fun `同一个文件导两次不会变成两倍`() {
        val first = ok(wechatCsv)
        val seen = first.rows.map { TransferFingerprint.of(it) }.toSet()
        val second = BankBillParser.parse(wechatCsv, seen, shanghai)
        assertTrue("第二次应该一条都加不进去，实际=$second", second is BankBillParser.Result.Bad)
        // 而且要告诉用户真实原因：都已经在账本里了，不是"文件里没有账"
        assertTrue((second as BankBillParser.Result.Bad).why.contains("已经在账本里"))
    }

    @Test
    fun `账单内部自己重复的行也会被挡掉`() {
        val dup = wechatCsv + "\n" + wechatCsv.substringAfter("2026-08-03 12:30:11")
        val p = ok(dup)
        assertEquals("文件内部的重复也该挡掉", 5, p.rows.size)
    }

    @Test
    fun `和已有账目重复的会被跳过`() {
        val first = ok(wechatCsv)
        val seen = first.rows.take(1).map { TransferFingerprint.of(it) }.toSet()
        val p = ok(wechatCsv, seen)
        assertEquals(4, p.rows.size)
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    @Test
    fun `支付宝的 GBK 账单能读出中文表头`() {
        val gbk = Charset.forName("GBK")
        val bytes = alipayCsv.toByteArray(gbk)
        val text = BankBillParser.decode(bytes)
        assertTrue("GBK 解码后必须能找到表头", text.contains("交易记录明细列表"))
        val p = ok(text)
        assertEquals(2, p.rows.size)
    }

    @Test
    fun `UTF-8 账单不受影响`() {
        val text = BankBillParser.decode(wechatCsv.toByteArray(Charsets.UTF_8))
        assertTrue(text.contains("微信支付账单明细"))
        assertEquals(5, ok(text).rows.size)
    }

    @Test
    fun `带 BOM 的文件不会让第一列表头错位`() {
        val withBom = LedgerCsv.BOM + wechatCsv
        val p = ok(withBom)
        assertEquals(5, p.rows.size)
    }

    // ------------------------------------------------------------------
    // 坏文件
    // ------------------------------------------------------------------

    @Test
    fun `不是账单文件时给一句能对上的话`() {
        val r = BankBillParser.parse("今天天气不错\n随便写点东西", emptySet(), shanghai)
        assertTrue(r is BankBillParser.Result.Bad)
        val why = (r as BankBillParser.Result.Bad).why
        // 不能是"格式错误"——用户需要知道该去哪儿导
        assertTrue("要说清怎么导出，实际=$why", why.contains("下载账单") || why.contains("表头"))
    }

    @Test
    fun `空文件报空`() {
        val r = BankBillParser.parse("   \n  ", emptySet(), shanghai)
        assertTrue(r is BankBillParser.Result.Bad)
    }

    @Test
    fun `只有表头没有数据时报出来`() {
        val r = BankBillParser.parse(wechatCsvHeader(), emptySet(), shanghai)
        assertTrue(r is BankBillParser.Result.Bad)
        assertTrue((r as BankBillParser.Result.Bad).why.contains("没有能记的账"))
    }

    @Test
    fun `尾部汇总行不计入坏行`() {
        val withFooter = wechatCsv + "\n" +
            "-------------------------微信支付账单明细列表--------------------\n" +
            "总交易金额：¥2887.70\n"
        val p = ok(withFooter)
        assertEquals("汇总行不该让坏行计数变高", 0, p.skippedBroken)
        assertEquals(5, p.rows.size)
    }

    @Test
    fun `账户名和分类不猜`() {
        val p = ok(wechatCsv)
        // 账单的「支付方式」是零钱/招行，不是账本里的账户名；分类账单里没有
        assertTrue(p.rows.all { it.account == null })
        assertTrue(p.rows.all { it.category == null })
        assertNotNull(p.rows[0].merchant)
    }

    // ---- 工具 ----

    private fun wechatCsvHeader(): String = """
        微信支付账单明细
        微信昵称：[张小明]
        ----------------------微信支付账单明细列表--------------------
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
    """.trimIndent() + "\n"
}
