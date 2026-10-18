package kapi.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 从大模型的自由文本回复里抠出一笔（或多笔）账单草稿。
 *
 * 这类解析器是这类 App 的头号故障点，所以规则是：
 *  **永不抛异常，永远不返回半个对象。** 任何看不懂的地方都记进
 *  [ExtractionResult.anomalies] 交给 UI 提示，而不是抛一个异常让整个记账流程挂掉。
 *
 * 必须容忍的输入（都来自真实模型）：
 *  - ```` ```json ... ``` ```` markdown 围栏
 *  - JSON 前后带自然语言："好的，我识别到：" + JSON + "希望有帮助"
 *  - 顶层是数组而不是对象
 *  - 字段名中英文混杂，甚至用中文
 *  - 数字写成字符串 `"23.50"`，或写成 `23.5`，或写成 `"¥23.50元"`
 *  - 金额带负号而 type 是 expense
 *  - 少字段、null 字段、多余字段
 *  - 拒答："图片中无法识别出金额"
 */
object TransactionExtractor {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 金额字段别名。注意金额一律按「元」理解，只有 amount_minor 是例外。 */
    private val AMOUNT_YUAN = listOf(
        "amount", "price", "total", "total_amount", "amount_yuan", "pay",
        "金额", "金额元", "价格", "实付", "实付金额", "总价", "消费金额",
    )

    /** 已经是「分」的字段，拿到就原样用，不再乘 100。 */
    private val AMOUNT_MINOR = listOf("amount_minor", "金额分")

    private val DIRECTION = listOf(
        "direction", "type", "kind", "flow", "收支", "收支类型", "类型", "方向",
    )

    private val MERCHANT = listOf(
        "merchant", "shop", "store", "seller", "vendor",
        "商户", "商家", "店名", "店铺",
    )

    private val CATEGORY = listOf("category", "分类", "类别", "消费类型")

    private val NOTE = listOf("note", "remark", "desc", "description", "备注", "说明", "摘要")

    private val TIME = listOf("occurred_at", "time", "date", "timestamp", "时间", "日期", "交易时间")

    private val CONFIDENCE = listOf("confidence", "置信度", "certainty")

    data class ExtractionResult(
        val drafts: List<TransactionDraft> = emptyList(),
        /** 解析过程中发现的可疑之处。UI 应当展示，但绝不能因此中断流程。 */
        val anomalies: List<String> = emptyList(),
    ) {
        val best: TransactionDraft? get() = drafts.maxByOrNull { it.confidence }
    }

    /**
     * 主入口。模型返回什么都能进，永远不抛。
     */
    fun extract(raw: String?): ExtractionResult = extractIn(raw, ZoneId.systemDefault())

    /**
     * 指定时区版本。
     *
     * 只为测试而存在：时间解析的断言必须能钉死一个时区，否则在 UTC 的机器上
     * 跑和在东八区跑会得到不同的毫秒数，测试就成了"看机器脸色"。
     */
    fun extractIn(raw: String?, zone: ZoneId): ExtractionResult {
        if (raw.isNullOrBlank()) return ExtractionResult()
        val anomalies = mutableListOf<String>()

        val candidate = findJson(stripThink(raw))
        if (candidate == null) {
            anomalies += "模型回复里找不到 JSON"
            return ExtractionResult(anomalies = anomalies)
        }

        val element = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
        if (element == null) {
            anomalies += "JSON 解析失败"
            return ExtractionResult(anomalies = anomalies)
        }

        val objects: List<JsonObject> = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            else -> {
                anomalies += "顶层既不是对象也不是数组"
                return ExtractionResult(anomalies = anomalies)
            }
        }
        if (objects.isEmpty()) {
            anomalies += "没有可用的交易对象"
            return ExtractionResult(anomalies = anomalies)
        }

        val drafts = objects.mapNotNull { readDraft(it, anomalies) }
        return ExtractionResult(drafts, anomalies)
    }

    // -----------------------------------------------------------------------

    private fun readDraft(o: JsonObject, anomalies: MutableList<String>): TransactionDraft? {
        val amount = readAmount(o, anomalies) ?: return null

        val dir = readDirection(o)
        val negAnomaly = (amount.negative && dir == Direction.EXPENSE) ||
            (amount.negative && dir == Direction.INCOME)
        if (negAnomaly) {
            anomalies += "金额带负号但收支类型为${if (dir == Direction.EXPENSE) "支出" else "收入"}，已按绝对值处理"
        }

        return TransactionDraft(
            amountMinor = amount.minor,
            direction = dir,
            occurredAt = readTime(o, TIME, ZoneId.systemDefault()),
            merchant = readString(o, MERCHANT),
            category = readString(o, CATEGORY),
            note = readString(o, NOTE),
            // 模型不给置信度时按 0 处理，**不是**按 1.0。
            // 缺省成"很确定"会让这一笔跳过用户确认，而它恰恰是最不该跳过的。
            confidence = readDouble(o, CONFIDENCE) ?: 0.0,
        )
    }

    private fun readAmount(o: JsonObject, anomalies: MutableList<String>): ParseResult? {
        for (k in AMOUNT_MINOR) {
            val raw = o[k]?.asLongOrNull()
            if (raw != null && raw != 0L) {
                return ParseResult(minor = raw, rounded = false, negative = raw < 0)
            }
        }
        for (k in AMOUNT_YUAN) {
            val text = o[k]?.asTextOrNull() ?: continue
            val parsed = Money.parse(text) ?: continue
            if (parsed.rounded) anomalies += "金额 $text 小数位过多，已四舍五入"
            return parsed
        }
        return null
    }

    private fun readDirection(o: JsonObject): Direction {
        val text = readString(o, DIRECTION)?.lowercase() ?: return Direction.UNKNOWN
        return when {
            text in EXPENSE_WORDS || text.contains("expense") || text.contains("支出") ||
                text.contains("付款") || text == "out" -> Direction.EXPENSE
            text in INCOME_WORDS || text.contains("income") || text.contains("收入") ||
                text.contains("退款") || text == "in" -> Direction.INCOME
            else -> Direction.UNKNOWN
        }
    }

    private val EXPENSE_WORDS = setOf("expense", "out", "debit", "pay", "payment", "消费", "付款", "支付")
    private val INCOME_WORDS = setOf("income", "in", "credit", "refund", "收入", "退款", "转账收入")

    // -----------------------------------------------------------------------
    // 宽松取值
    // -----------------------------------------------------------------------

    private fun pick(o: JsonObject, keys: List<String>): JsonElement? {
        for (k in keys) {
            val v = o[k]
            if (v != null && v !is JsonNull) return v
        }
        return null
    }

    private fun readString(o: JsonObject, keys: List<String>): String? {
        val p = pick(o, keys)?.asJsonPrimitiveOrNull() ?: return null
        val s = p.contentOrNull?.trim() ?: return null
        return s.ifBlank { null }
    }

    private fun readDouble(o: JsonObject, keys: List<String>): Double? {
        val p = pick(o, keys)?.asJsonPrimitiveOrNull() ?: return null

        p.doubleOrNull?.let { return asConfidence(it) }

        val s = p.contentOrNull?.trim()?.lowercase() ?: return null
        return when {
            s == "high" -> 0.9
            s == "medium" || s == "mid" -> 0.6
            s == "low" -> 0.3
            else -> s.toDoubleOrNull()?.let(::asConfidence)
        }
    }

    /**
     * 归一化置信度到 0..1。
     *
     * 必须**先**判断是不是百分比再夹取。早先的写法是先 `coerceIn(0,1)`，
     * 于是模型返回 `confidence: 80`（80%，很常见）会被压成 1.0 = 非常确信，
     * 一笔其实没把握的账就跳过了用户确认。置信度虚高比置信度偏低危险得多。
     */
    private fun asConfidence(v: Double): Double =
        (if (v > 1.0) v / 100.0 else v).coerceIn(0.0, 1.0)

    private fun readTime(o: JsonObject, keys: List<String>, zone: ZoneId): Long {
        val p = pick(o, keys)?.asJsonPrimitiveOrNull() ?: return 0

        // 1) epoch 毫秒 / 秒
        p.contentOrNull?.trim()?.toLongOrNull()?.let { return normalizeEpoch(it) }
        p.doubleOrNull?.toLong()?.let { return normalizeEpoch(it) }

        // 2) ISO-8601：「2026-09-29T15:30:00」「2026-09-29 15:30」「2026-09-29」
        //
        // 这一支不是锦上添花，是**必须有**：提示词里明确要求模型输出这个格式，
        // 而模型也确实这么回。只认 epoch 的话，识别出来的每一笔时间都会被静默
        // 丢成 0，界面上就变成"这笔交易发生在此时此刻"——错得毫无痕迹。
        //
        // 没有时区偏移的按**本机时区**解释：账单截图上的时间就是用户所在地的
        // 本地时间，转成 UTC 反而会差一个时区。
        val text = p.contentOrNull?.trim() ?: return 0
        parseLocalDateTime(text, zone)?.let { return it }
        return 0
    }

    private fun parseLocalDateTime(text: String, zone: ZoneId): Long? {
        // 先按秒级 ISO 试（带 T 或空格）
        for (fmt in listOf(ISO_SECONDS, ISO_SPACE_SECONDS)) {
            runCatching {
                return LocalDateTime.parse(text, fmt).atZone(zone).toInstant().toEpochMilli()
            }
        }
        // 只有日期
        runCatching {
            return LocalDate.parse(text, ISO_DATE).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return null
    }

    /**
     * 模型偶尔会给秒级时间戳（10 位）。补成毫秒。
     *
     * 区间是 **10 位**，不是 12 位：现在的秒级时间戳约 1.79e9（10 位），
     * 毫秒级约 1.79e12（13 位）。早先这里写成了 1e11..2e11（12 位），
     * 于是 10 位秒级**永远匹配不上**——模型给秒级时间戳，这一笔就落在
     * 1970 年 1 月。是单测 `epoch seconds both work` 逮到的。
     *
     * 只认这一个窄窗：1e9..2e9 覆盖 2001-09 到 2033-05，
     * 13 位毫秒值一定落在窗外，不会被误乘。
     */
    private fun normalizeEpoch(v: Long): Long =
        if (v in 1_000_000_000L..2_000_000_000L) v * 1000 else v

    private val ISO_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val ISO_SPACE_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // -----------------------------------------------------------------------
    // 从自由文本里定位 JSON
    // -----------------------------------------------------------------------

    /**
     * 剥掉 markdown 围栏、前后废话，截出最外层的 JSON。
     *
     * 先看有没有围栏（最常见也最省事），没有就从第一个 `{` 或 `[` 开始按括号配对
     * 扫描。括号配对要跳过字符串字面量里的括号，否则 `"note": "值)"` 会提前收尾。
     */
    internal fun findJson(text: String): String? {
        val t = text.trim()

        FENCE.findAll(t).forEach { m ->
            val body = m.groupValues[1].trim()
            if (body.startsWith("{") || body.startsWith("[")) return body
        }

        return scanBalanced(t)
    }

    private val FENCE = Regex("""```(?:json|JSON)?\s*([\s\S]*?)```""")

    /**
     * 剥掉推理模型的思考块（MiniMax M3 等）：`<think>…</think>`。
     *
     * 思考过程里经常会出现**没写完的假 JSON**（模型盘算着该怎么输出），
     * findJson 的括号配对从第一个 `{` 开始扫——不先剥掉的话，抽出来的
     * 往往是思考里的残次品而不是正式答案。已闭合的才剥，没闭合的留着
     * （那通常是被 max_tokens 截断的回复，剥了反而丢掉整个锚点）。
     */
    private val THINK_BLOCK = Regex("""<think>[\s\S]*?</think>""")

    private fun stripThink(text: String): String = text.replace(THINK_BLOCK, "").trim()

    private fun scanBalanced(t: String): String? {
        for (start in t.indices) {
            val c = t[start]
            if (c != '{' && c != '[') continue
            val close = if (c == '{') '}' else ']'
            var depth = 0
            var inStr = false
            var escaped = false
            for (i in start until t.length) {
                val ch = t[i]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inStr -> escaped = true
                    ch == '"' -> inStr = !inStr
                    inStr -> Unit
                    ch == c -> depth++
                    ch == close -> {
                        depth--
                        if (depth == 0) return t.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // JsonElement 宽松取值
    // -----------------------------------------------------------------------

    private fun JsonElement.asJsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    /** 标量取文本。对象/数组返回 null，绝不炸。 */
    private fun JsonElement.asTextOrNull(): String? = when (this) {
        is JsonPrimitive -> if (isString || contentOrNull != null) contentOrNull else null
        else -> null
    }

    private fun JsonElement.asLongOrNull(): Long? = when (this) {
        is JsonPrimitive -> contentOrNull?.trim()?.toLongOrNull() ?: doubleOrNull?.toLong()
        else -> null
    }
}
