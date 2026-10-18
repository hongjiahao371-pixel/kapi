package kapi.core.llm

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额解析：十进制字符串 → 以「分」为单位的整数。
 *
 * 全程 BigDecimal，不碰 Double。账本里 Double 出现在金额上只有一个结局。
 *
 * 这里刻意接受并清洗一堆脏输入，因为输入是大模型的自由文本，不是数据库字段：
 *   "23"        → 2300
 *   "23.5"      → 2350
 *   "23.50"     → 2350
 *   "¥23.50"    → 2350
 *   "23.50元"   → 2350
 *   "1,234.56"  → 123456
 *   "1.234,56"  → 123456     (欧式写法)
 *   "23,50"     → 2350       (小数逗号)
 *   "订单 23.50 已支付" → 2350
 *
 * 无法解析时返回 null，由调用方走降级路径。**绝不返回 0**——一笔识别失败的账
 * 变成 0 元，用户会以为今天花了 0，这比识别失败本身更糟。
 */
object Money {

    /** 金额一律用分存储。任何字段类型都不许是浮点。 */
    const val MINOR_PER_MAJOR = 100L

    /** 匹配一段「看起来像数字」的 token：可选负号 + 数字 + 可能的千位/小数分隔。 */
    private val NUMERIC_TOKEN = Regex("""-?\d[\d.,\u00a0 ]*\d|-?\d""")

    /**
     * 解析十进制金额字符串。
     *
     * 返回的 [ParseResult.minor] 恒为**正数**（绝对值），收支方向由
     * [Direction] 单独表达。
     *
     * 原因：账本最严重的 bug 就是「支出被存成收入」。如果金额自带符号，一个把
     * -23.50 当成支出的模型和一个把 -23.50 当成收入的模型就无法区分，界面上一笔
     * 支出会显示成收入。拆成「量 + 方向」之后，这种错误在结构上就不可能发生，
     * 模型写反符号只会变成一个可提示的 [ParseResult.negative] 标记。
     */
    fun parse(text: String?): ParseResult? {
        if (text.isNullOrBlank()) return null
        val normalized = normalize(text.trim()) ?: return null
        return try {
            val raw = BigDecimal(normalized)
            val decimals = normalized.substringAfter('.', "").length
            val scaled = raw
                .abs()
                .multiply(BigDecimal(MINOR_PER_MAJOR))
                .setScale(0, RoundingMode.HALF_UP)
            ParseResult(
                minor = scaled.toLong(),
                rounded = decimals > 2,
                negative = raw.signum() < 0,
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * 把任意含噪文本归一成 BigDecimal 能吃的形式（保留原始负号）。
     *
     * 分隔符按**类型**判断，只在需要时用位数消歧。这是个中文账本，
     * `.` 就是小数点、`,` 就是千位分隔，这是没有歧义的常规：
     *
     *   "23.5" / "23.50" / "23.500"   → 一个小数点，一定是小数点
     *   "23.9999"                     → 同上，多余小数位由 parse() 四舍五入并标记
     *   "1,234" / "1,000,000"         → 只有一个逗号，后面 3 位且前段 1~3 位 → 千位
     *   "23,50"                       → 只有一个逗号，后面不是 3 位 → 小数逗号
     *   "1,234.56"                    → 两者都有 → 最后一个是小数点，其余是千位
     *   "1.234,56"                    → 同上，欧式写法也对
     *   "1.2.3"                       → 两个小数点，直接判为垃圾，返回 null
     *
     * 早先试过「最后一个分隔符后跟 1~2 位就是小数点」，看着漂亮，实测把
     * `23.500` 读成 23500、`23.9999` 读成 239999，还把 `1.2.3` 洗成 `12.3`。
     * **把垃圾输入洗成一个看起来合理的数字，比返回 null 危险得多**——用户会
     * 以为系统认对了。
     */
    private fun normalize(text: String): String? {
        val m = NUMERIC_TOKEN.find(text) ?: return null
        var s = m.value.replace(" ", "").replace('\u00a0', ' ').trim()
        s = s.trimEnd('.', ',')
        if (s.isEmpty()) return null

        val negative = s.startsWith('-')
        if (negative) s = s.substring(1)
        if (s.isEmpty()) return null

        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }

        s = when {
            dots > 0 && commas > 0 -> {
                // 最后一个分隔符是小数点，靠前的都是千位分隔。
                val last = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
                s.substring(0, last).filter { it.isDigit() } + "." + s.substring(last + 1)
            }
            dots > 0 -> {
                if (dots > 1) return null // "1.2.3" 是垃圾，不给它编一个解释
                s
            }
            commas > 0 -> {
                val groups = s.split(',')
                val first = groups.first()
                val rest = groups.drop(1)
                val firstIsPlain = first.isNotEmpty() && first.all { it.isDigit() }

                // 多个逗号：千位分组要求第一组之后**每组都正好 3 位**。
                // 早先这里只判断「前段 1~3 位纯数字」，于是 "1,000,000" 的前段
                // "1,000" 自己带逗号 → 判负 → 掉进小数分支 → 变成 1000.000，
                // 100 万元被读成 1000 元。
                val isGrouped = firstIsPlain &&
                    first.length in 1..3 &&
                    rest.isNotEmpty() &&
                    rest.all { it.length == 3 && it.all(Char::isDigit) }

                if (isGrouped) {
                    s.filter { it.isDigit() }
                } else {
                    val last = s.lastIndexOf(',')
                    s.substring(0, last).filter { it.isDigit() } + "." + s.substring(last + 1)
                }
            }
            else -> s
        }

        if (s.isEmpty() || s == ".") return null
        if (!s.all { it.isDigit() || it == '.' }) return null
        if (s.first() == '.' || s.last() == '.') return null

        return if (negative) "-$s" else s
    }

    /** 分 → 人类可读元。仅用于 UI 展示，不参与任何存储或计算。 */
    fun toYuanDisplay(minor: Long): String =
        BigDecimal(minor)
            .divide(BigDecimal(MINOR_PER_MAJOR), 2, RoundingMode.HALF_UP)
            .toPlainString()
}

/** 收支方向。独立于金额符号，见 [Money]。 */
enum class Direction { EXPENSE, INCOME, UNKNOWN }

data class ParseResult(
    /** 以分为单位的金额绝对值。 */
    val minor: Long,
    /** 输入小数位超过两位，已四舍五入。调用方应当提示用户核对。 */
    val rounded: Boolean,
    /** 原始字符串带负号。支出场景下这说明模型把符号写反了，值得提示。 */
    val negative: Boolean,
)
