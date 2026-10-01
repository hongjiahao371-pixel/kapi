package kapi.core.io

import java.math.BigDecimal
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 微信 / 支付宝**官方导出账单**的解析。
 *
 * 放在 [kapi.core] 而不是 app 层，因为这里全是纯函数——没有 Room、没有 Context。
 * 账单文件动辄几千行、几十种畸形写法（空备注、带逗号的商户名、GBK 乱码、
 * 中性交易、已退款），这些必须能用 JVM 单测钉死：跑一次单测几毫秒，
 * 跑一次设备测试要等 APK 装到模拟器上。
 *
 * ## 为什么这条通路是必须的
 *
 * 账单是**唯一能补全历史账的通路**，这一点和读屏能不能用无关：
 * 自动记账只在你**当时打开**那个页面时才拿得到数据，而你不可能把过去三年的每一笔
 * 都点开一次。账单是官方提供的、全量、结构化的离线数据。
 *
 * 顺带记一条**曾经被误判、后来实测推翻**的事（2026-09-30，OPPO PMA110 / 微信 8.0.78）：
 * 当时测出「微信不派发无障碍事件、rootInActiveWindow 返回 null」，一度当成
 * "App 侧无解"写进了注释。真相是**测法错了**——用 `adb shell monkey -p <pkg>` 拉起
 * App，在 App 已在前台时不产生窗口切换事件，无障碍自然一条不来。换成
 * `am start -n <pkg>/<activity>` 后，**微信照常派发、节点树读得到（88 个节点）**。
 * 所以别拿这一段当"读屏走不通"的证据，也别因此跳过对微信读屏的适配。
 * 支付宝同理读屏是通的，账单能补上读屏覆盖不到的历史账。
 *
 * ## 三件不能想当然的事
 *
 * ### 1. 表头不在第一行，而且两边行数不一样
 *
 * 微信账单前 16 行左右是元信息和"常见问题及重要提示"，支付宝前 4~8 行。
 * 所以**不能写死行号**——写死的版本在用户换一版 App 导出后就静默导进 0 笔，
 * 而且界面上还显示"导入 0 笔"，看不出任何异常。
 * 这里改成扫全表找那一行同时含「交易时间」和「金额」的行。
 *
 * ### 2. **按列名取列，不按下标**
 *
 * 两家都在改列（微信 11 列、支付宝 16 列，且列顺序变过）。
 * 硬编码 `cells[4]` 在微信上正好是"收/支"，在支付宝上第 4 列是"最近修改时间"——
 * 于是所有支付宝账单的方向都变成"按时间判断"，收入全记成支出。
 * 而且不报错，只是默默全错。
 *
 * ### 3. 「其他」= 中性交易，不是收入也不是支出
 *
 * 微信的「收/支」列有第三个值 `其他`，对应充值、提现、零钱通存取、
 * 信用卡还款、理财申赎。支付宝对应 `其它`。
 * 这些是**钱在账户之间搬家**，不是消费也不是进账。
 * 把它们记成收入 → 月收入虚高；记成支出 → 月支出虚高。两种都是错的。
 * 一律跳过，并且**单独计数告诉用户跳了多少**（见 [Parsed.skippedNeutral]）。
 *
 * ## 状态过滤：钱真的动了吗
 *
 * 账单里混着 `交易关闭` / `支付失败` / `已全额退款`。
 * 把 `交易关闭` 的订单记成消费，用户会发现"我没买过这个"——
 * 而钱确实没出去。这是账单里最容易被忽略的一类错。
 */
object BankBillParser {

    /** 解析结果。[source] 决定写进 TxnEntity.source，让 UI 能告诉用户这一笔怎么来的。 */
    data class Parsed(
        val rows: List<TransferRow>,
        /** 中性交易（充值/提现/还款/理财）条数，单独报给用户。 */
        val skippedNeutral: Int,
        /** 状态不合格（交易关闭/支付失败/已退款）条数。 */
        val skippedBadStatus: Int,
        /** 解析不了的行数。**必须报给用户**，否则用户会以为账单里就这么多。 */
        val skippedBroken: Int,
        val source: String,
    )

    sealed interface Result {
        data class Ok(val parsed: Parsed) : Result
        /** [why] 直接给用户看。 */
        data class Bad(val why: String) : Result
    }

    // ---- 表头识别 ----

    /**
     * 判定一行是不是表头：**同时**含时间列和金额列。
     *
     * 微信：「交易时间,交易类型,交易对方,商品,收/支,金额(元),…」
     * 支付宝：「交易号,商家订单号,交易创建时间,付款时间,…,金额(元),收/支,…」
     *
     * 微信那行前面还带一串 `----------------微信支付账单明细列表----------------`
     * 分隔线，是**同一个字段**里的，split 后粘在第一个列名上，
     * 所以这里对每个列名都做 `contains` 而不是等值匹配。
     */
    private fun isHeader(cells: List<String>): Boolean {
        val hasTime = cells.any { it.contains("交易时间") || it.contains("交易创建时间") || it.contains("付款时间") }
        val hasAmount = cells.any { it.contains("金额") && (it.contains("元") || it.contains("(")) }
        return hasTime && hasAmount
    }

    // ---- 状态 ----

    /**
     * 明确**不该**记的。
     *
     * `已全额退款` 单独拎出来是因为它有个陷阱：账单里退款是**另一行**
     * （收/支=收入），原订单那行还写着「商户消费/支出/¥88」。两行都在。
     * 只按原订单记会漏掉退款那一行，所以这里**不**把「已退款」当垃圾行丢掉，
     * 交给去重和用户判断；真正丢的是「交易关闭」和「支付失败」——
     * 这两种钱根本没动过。
     */
    private val DEAD_STATUS = listOf(
        "交易关闭", "支付失败", "交易失败", "已关闭", "已取消", "支付中止", "订单关闭",
    )

    // ---- 解析 ----

    /**
     * 解析一份账单。纯函数，**不碰** Room。
     *
     * [text] 必须已经按正确编码解码（支付宝是 GBK，见 [decode]）。
     * [seen] 是已有账目的指纹，用来跳过重复——不传就是不去重。
     */
    fun parse(
        text: String,
        seen: Set<String> = emptySet(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val body = text.removePrefix(LedgerCsv.BOM).trim()
        if (body.isEmpty()) return Result.Bad("文件是空的")

        val lines = body.lines()
        val headerIdx = lines.indexOfFirst { line ->
            !line.isBlank() && isHeader(splitCsvLine(line))
        }
        // 表头都找不到，说明这不是账单文件。给一句能对上的话，
        // 而不是"格式错误"——用户需要知道该去哪儿导。
        if (headerIdx < 0) {
            return Result.Bad("没找到账单表头。这不像微信/支付宝的账单文件，请从" +
                "微信「我→服务→钱包→账单→常见问题→下载账单」，或支付宝账单页导出")
        }

        val header = splitCsvLine(lines[headerIdx]).map { it.trim() }
        // 判来源不能只看"有没有交易类型"——补上支付宝的「类型」之后这条就废了，
        // 支付宝会被判成微信。改用**微信独有**的「支付方式」列，判据更硬。
        val isWechat = header.any { it == "支付方式" }
        val source = if (isWechat) SOURCE_WECHAT else SOURCE_ALIPAY

        val col = Columns(header)
        if (col.time < 0 || col.amount < 0 || col.income < 0) {
            return Result.Bad("这份账单的列认不全（缺时间或金额列），可能是导出版本太新")
        }

        val rows = ArrayList<TransferRow>()
        val localSeen = HashSet(seen)
        var neutral = 0
        var badStatus = 0
        var broken = 0
        var dup = 0

        // 数据行至少要能覆盖到最后一根用到的列。比这还短的行不是数据行：
        // 账单尾部有「总交易金额：xxx」「----支付宝电子回单----」这类汇总/落款，
        // 它们只有 1 列。当成坏行报给用户，坏行计数就永远不为 0，
        // 用户会以为自己的账单文件坏了。
        val minCells = maxOf(col.time, col.amount, col.income) + 1

        for (i in (headerIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue

            val cells = splitCsvLine(line)
            if (cells.size < minCells) continue

            val status = col.status.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.trim().orEmpty()
            if (DEAD_STATUS.any { status.contains(it) }) { badStatus++; continue }

            val dirRaw = cells.getOrNull(col.income)?.trim().orEmpty()
            val direction = when {
                dirRaw.startsWith("支") -> "EXPENSE"
                dirRaw.startsWith("收") -> "INCOME"
                else -> null   // 「其他」/「其它」/空
            }
            // 中性交易单独计数，不混进 broken——用户要知道"这 23 笔不是漏了，是本来就不算账"
            if (direction == null) { neutral++; continue }

            val timeText = cells.getOrNull(col.time)?.trim().orEmpty()
            val at = parseTime(timeText, zone)
            val amount = parseAmount(cells.getOrNull(col.amount)?.trim().orEmpty())
            if (at == null || amount == null || amount <= 0L) { broken++; continue }

            val merchant = col.merchant.takeIf { it >= 0 }
                ?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
            val goods = col.goods.takeIf { it >= 0 }
                ?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
            val note = col.note.takeIf { it >= 0 }
                ?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() && it != "/" }

            // 备注里塞「类型 + 商品」，比空着有用。
            // 微信的「交易类型」是商户消费/转账/红包，比「商品」更能说明这是什么钱。
            val typeText = col.type.takeIf { it >= 0 }
                ?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val composed = listOfNotNull(typeText, goods).distinct().joinToString(" ")
            val finalNote = when {
                !composed.isNullOrEmpty() && !note.isNullOrEmpty() -> "$composed｜$note"
                !composed.isNullOrEmpty() -> composed
                else -> note
            }

            val row = TransferRow(
                occurredAt = at,
                direction = direction,
                amountMinor = amount,
                merchant = merchant,
                note = finalNote,
                category = null,   // 账单没有分类，留空让用户自己分，绝不猜
                account = null,     // 账单里是「支付方式」（零钱/银行卡），不是账本里的账户名
                source = source,
            )

            // 同一份文件里自己重复（微信账单跨月导出会重叠）也要挡，
            // 否则用户连着导 3 个月的重叠区间，账目就是 3 倍。
            if (!localSeen.add(TransferFingerprint.of(row))) { dup++; continue }
            rows += row
        }

        if (rows.isEmpty()) {
            // 全部是重复时**必须**说"都已经在账本里了"，不能说"没有能记的账"——
            // 用户导第二遍的真实问题是"我重复导了"，而"没有能记的账"会让他
            // 以为自己导错了文件、进而去找别的文件反复试。
            if (dup > 0) {
                return Result.Bad("这份账单里的 $dup 笔都已经在账本里了，没有新的可导入")
            }
            return Result.Bad("这份账单里没有能记的账。" +
                "（跳过中性交易 $neutral 笔、状态不合格 $badStatus 笔、格式不对 $broken 笔）")
        }

        return Result.Ok(
            Parsed(
                rows = rows,
                skippedNeutral = neutral,
                skippedBadStatus = badStatus,
                skippedBroken = broken,
                source = source,
            )
        )
    }

    // ---- 列定位 ----

    private class Columns(private val header: List<String>) {
        private fun idx(pred: (String) -> Boolean): Int = header.indexOfFirst(pred)

        /**
         * 时间列取「**付款**时间」，没有才退「交易创建时间」。
         *
         * 支付宝有 `交易创建时间` 和 `付款时间` 两列，创建时间可能比付款早几分钟
         * （下单 → 输密码）。取创建时间会让这批账在时间轴上整体前移，
         * 跨零点时甚至会落到**前一天**——用户翻账本会看到"今天凌晨点外卖"。
         */
        val time: Int = idx { it == "付款时间" }.takeIf { it >= 0 }
            ?: idx { it == "交易时间" }
            ?: idx { it.contains("交易创建时间") }
            ?: idx { it.contains("时间") }

        val amount: Int = idx { it.startsWith("金额") }
        val income: Int = idx { it.startsWith("收/支") || it.startsWith("收支") }
        val status: Int = idx { it.contains("交易状态") || it.contains("当前状态") }
        val merchant: Int = idx { it == "交易对方" }
        val goods: Int = idx { it == "商品" || it == "商品名称" }

        /**
         * 微信叫「交易类型」，支付宝叫「类型」。只认前者的话，
         * 支付宝每一笔的备注都会丢掉「转账/即时到账交易」这个前缀——
         * 而它恰恰是账单里最能说明"这是什么钱"的一列。
         */
        val type: Int = idx { it == "交易类型" || it == "类型" }

        val note: Int = idx { it == "备注" }
    }

    // ---- 字段转换 ----

    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val TS_SLASH = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

    /** 账单时间是**当地时间且不带时区**，所以按 [zone] 本地化，不能当 UTC。 */
    private fun parseTime(raw: String, zone: ZoneId): Long? {
        if (raw.isEmpty()) return null
        val f = when {
            raw.contains('/') -> TS_SLASH
            else -> TS
        }
        return try {
            LocalDateTime.parse(raw, f).atZone(zone).toInstant().toEpochMilli()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 金额 → 分。**全程 [BigDecimal]**，绝不用 `toDouble() * 100`。
     *
     * `0.29` 用 double 算是 `28.999999999999996`，`.roundToInt()` 得 29 看着对，
     * 但 `8.115` 这种三小数会得 811（应为 812），而且这种错不会报错，
     * 只是某几笔金额差一分钱——用户永远查不出来，却会算错一整年的账。
     *
     * 用 `setScale(2, HALF_UP)` 再取整，比「乘 100 再四舍五入」稳：
     * 后者对 `1.005` 这类会得 100 而正确答案是 101。
     *
     * 要剥掉的：微信账单金额带 `¥`（`¥25.00`），有的版本还带千分位逗号。
     */
    private fun parseAmount(raw: String): Long? {
        if (raw.isEmpty()) return null
        val cleaned = raw.replace("¥", "").replace("￥", "").replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned).multiply(BigDecimal(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toLong()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 账单 CSV 的行切分。
     *
     * 账单里的商户名可能**自己带逗号**（"肯德基, 望京店"），
     * `split(",")` 会把一行切成两半，于是金额列取到的是" 望京店" → 解析失败 → 整行丢掉。
     * 所以要认 RFC 4180 的引号。
     *
     * 微信/支付宝的账单**不规范**用引号包住含逗号的字段（实测有的行包了有的没包），
     * 所以这里两种都支持：引号内的逗号不当分隔符，引号外的当分隔符。
     */
    fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    // 连续两个引号 = 转义后的一个引号
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i += 2; continue
                    }
                    inQuote = !inQuote
                }
                c == ',' && !inQuote -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    // ---- 编码 ----

    const val SOURCE_WECHAT = "BILL_WECHAT"
    const val SOURCE_ALIPAY = "BILL_ALIPAY"

    /**
     * 猜编码。
     *
     * **微信是 UTF-8，支付宝是 GBK**（各家导出工具都这么干，且多年没变过）。
     * 读错的表现特别隐蔽：GBK 字节按 UTF-8 解出来是 `锟斤拷` 这种乱码，
     * 而且**长度还是对的**，不抛任何异常。
     * 但**表头行照样匹配不上**（含「交易时间」那行已经变成乱码），
     * 于是报"没找到账单表头"——用户拿到的是一句完全指不出问题在哪的话。
     *
     * 判据：按 UTF-8 严格解码成功就用 UTF-8，失败退 GBK。
     * 比引入 chardet 便宜得多，而且对这两个来源足够准。
     */
    fun decode(bytes: ByteArray): String {
        val utf8 = try {
            val d = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
            d.toString()
        } catch (_: Throwable) {
            null
        }
        if (utf8 != null) return utf8.removePrefix(LedgerCsv.BOM)

        val gbk = try {
            Charset.forName("GBK").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Throwable) {
            null
        }
        return gbk?.removePrefix(LedgerCsv.BOM) ?: String(bytes, Charsets.UTF_8)
    }
}
