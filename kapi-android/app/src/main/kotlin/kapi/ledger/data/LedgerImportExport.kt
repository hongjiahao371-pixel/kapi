package kapi.ledger.data

import kapi.core.io.BankBillParser
import kapi.core.io.LedgerCsv
import kapi.core.io.LedgerJson
import kapi.core.io.TransferFingerprint
import kapi.core.io.TransferRow
import kapi.ledger.sync.LocalEditWriter
import java.time.ZoneId

/**
 * 账目进出：把 Room 里的行变成导出文件，把导出文件变回 Room 里的行。
 *
 * ## 写路径只有一条
 *
 * 导入**不走 `txnDao().upsertAll()`**，一律走
 * [LocalEditWriter.createTransaction]。理由是硬的：pending 的字段版本戳只有
 * 一处正确实现，手拼的少了任何一个戳，那个字段在 NAS 上就永远停在旧值——
 * 用户的表现是"导入了，App 里看得见，同步到 NAS 就变了或没了"。
 * 这条路径上已经因为编码对不上让**所有金额**静默变 0 过一次，不再来第二次。
 *
 * ## 去重不是可选项
 *
 * 用户把同一份文件导两次的概率很高（导错了想重来、换手机后又导了一遍）。
 * 不去重的后果是**账目翻倍**——在记账 App 里这是最严重的一类错，而且用户
 * 当下看不出来，等发现时汇总已经错了一倍。
 */
class LedgerImportExport(
    private val db: KapiDatabase,
    private val editor: LocalEditWriter,
) {

    sealed interface ExportResult {
        data class Ok(val fileName: String, val rowCount: Int, val text: String) : ExportResult
        data object Empty : ExportResult
    }

    sealed interface ImportResult {
        /**
         * [skipped] 是去重跳过的条数，**必须**回显给用户。
         * 用户导了两遍之后，界面上只说"导入完成"会让他以为去重没生效、
         * 进而反复导入。
         */
        data class Ok(
            val added: Int,
            val skipped: Int,
            /** 账单导入才有：充值/提现/还款这类中性交易条数。 */
            val skippedNeutral: Int = 0,
            /** 账单导入才有：交易关闭/支付失败这类钱没动过的条数。 */
            val skippedBadStatus: Int = 0,
            /** 账单导入才有：行格式不对、没能解析出来的条数。 */
            val skippedBroken: Int = 0,
            /** 「微信」/「支付宝」，非账单导入时为 null。 */
            val from: String? = null,
        ) : ImportResult

        data class Failed(val why: String) : ImportResult
    }

    // ---- 导出 ----

    suspend fun exportCsv(zone: ZoneId = ZoneId.systemDefault()): ExportResult {
        val rows = transferRows()
        if (rows.isEmpty()) return ExportResult.Empty
        val name = fileName("kapi-", "csv")
        return ExportResult.Ok(name, rows.size, LedgerCsv.toCsv(rows, zone))
    }

    suspend fun exportJson(): ExportResult {
        val rows = transferRows()
        if (rows.isEmpty()) return ExportResult.Empty
        val name = fileName("kapi-", "json")
        return ExportResult.Ok(name, rows.size, LedgerJson.toJson(rows, System.currentTimeMillis()))
    }

    /** `kapi-20260930-1458.csv`。带时分是为了同一天导出两次不互相覆盖。 */
    private fun fileName(prefix: String, ext: String): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        return prefix + java.time.LocalDateTime.now().format(f) + "." + ext
    }

    /**
     * 把 Room 行翻成导出行，**顺带把 id 换成显示名**。
     *
     * 见 `LedgerTransfer` 的文件头：导出的是给人看的名字，不是跨设备无意义的 id。
     * 一次性把分类/账户表读成 Map，避免每行查一次库。
     */
    private suspend fun transferRows(): List<TransferRow> {
        val txns = db.txnDao().allNotDeleted()
        if (txns.isEmpty()) return emptyList()
        val cats = db.categoryDao().allForPush().associate { it.id to it.name }
        val accts = db.accountDao().allForPush().associate { it.id to it.name }
        return txns.map { toTransferRow(it, cats, accts) }
    }

    /**
     * Room 行 → 导出行，把 id 换成显示名。
     *
     * [cats] / [accts] 必须是 **id → name**。
     *
     * 方向写反过一次，后果是**去重彻底失效**：已有行取名字时全部拿到 null，
     * 而导入行带的是真名字，两边指纹永远对不上，于是每导一次账目就翻一倍。
     * 而且这个 bug 不会报错——导入"成功"了，金额也全对，只是默默多了一倍。
     * 这类"方向错了但两边都是合法 Map"的情况，编译器一句忙都帮不上。
     */
    private fun toTransferRow(
        t: TxnEntity,
        cats: Map<String, String>,
        accts: Map<String, String>,
    ) = TransferRow(
        occurredAt = t.occurredAt,
        direction = t.direction,
        amountMinor = t.amountMinor,
        merchant = t.merchant,
        note = t.note,
        category = t.categoryId?.let { cats[it] },
        account = t.accountId?.let { accts[it] },
        toAccount = t.toAccountId?.let { accts[it] },
        source = t.source,
    )

    // ---- 导入 ----

    suspend fun importJson(text: String, deviceId: String): ImportResult {
        val rows = when (val p = LedgerJson.parse(text)) {
            is LedgerJson.Parsed.Bad -> return ImportResult.Failed(p.why)
            is LedgerJson.Parsed.Ok -> p.rows
        }
        if (rows.isEmpty()) return ImportResult.Failed("文件里一条账都没有")
        return writeRows(rows, deviceId)
    }

    /**
     * 导入微信 / 支付宝**官方导出账单**。
     *
     * 这是**补全历史账**的通路：自动记账只在你当时打开那个页面时才拿得到数据，
     * 过去几年的账不可能靠逐笔点开。账单是官方给用户自己的、结构化的、全量离线数据。
     * 读屏能用的 App（微信、支付宝、银行，见 KapiAccessibilityService）当场就能记，
     * 账单负责把它们漏掉的历史和零钱那一部分补齐。
     *
     * [bytes] 必须传**原始字节**而不是字符串：支付宝账单是 GBK，
     * 读成 String 的那一步就已经把中文毁了，后面谁也救不回来。
     */
    suspend fun importBankBill(bytes: ByteArray, deviceId: String): ImportResult {
        val text = BankBillParser.decode(bytes)
        val existing = existingFingerprints()
        val parsed = when (val r = BankBillParser.parse(text, existing)) {
            is BankBillParser.Result.Bad -> return ImportResult.Failed(r.why)
            is BankBillParser.Result.Ok -> r.parsed
        }
        val out = writeRows(parsed.rows, deviceId, existing)
        val ok = out as? ImportResult.Ok ?: return out
        return ok.copy(
            skippedNeutral = parsed.skippedNeutral,
            skippedBadStatus = parsed.skippedBadStatus,
            skippedBroken = parsed.skippedBroken,
            from = if (parsed.source == BankBillParser.SOURCE_WECHAT) "微信" else "支付宝",
        )
    }

    /**
     * 已有账目的去重指纹。
     *
     * 算它要用 **id → name**（[toTransferRow] 的入参方向），
     * 和按名字查 id 的那两张表是**方向相反**的两张，混用会让去重静默失效——
     * 表现是"导入成功"但账目翻倍，不报任何错。
     */
    private suspend fun existingFingerprints(): Set<String> {
        val cats = db.categoryDao().allForPush().associate { it.id to it.name }
        val accts = db.accountDao().allForPush().associate { it.id to it.name }
        return db.txnDao().allNotDeleted()
            .map { TransferFingerprint.of(toTransferRow(it, cats, accts)) }
            .toSet()
    }

    /**
     * 写库的唯一入口。JSON 导入和账单导入**必须**走这一条。
     *
     * 写路径和去重各只有一份实现，这件事本身是被 bug 教出来的：
     * 两条路各写一遍时，去重的那份在某一条上忘了把"文件内部的重复"加进集合，
     * 用户跨月导两份有重叠区间的账单，账目就多出一截。
     *
     * [preSeen] 是调用方已经算好的已有指纹（账单解析时要拿它去重，
     * 免得解析完再从头查一次库）。传空集合就是自己查。
     */
    private suspend fun writeRows(
        rows: List<TransferRow>,
        deviceId: String,
        preSeen: Set<String> = emptySet(),
    ): ImportResult {
        val ledgerId = db.ledgerDao().firstActive()?.id ?: LedgerRepository.DEFAULT_LEDGER_ID

        // 名字 → id。用户自建分类如果名字对不上，就留空（界面上显示"未分类"），
        // 绝不能**猜**一个——把餐饮的钱记到交通下面比不分类更糟。
        val catByName = db.categoryDao().allForPush().associate { it.name to it.id }
        val acctByName = db.accountDao().allForPush().associate { it.name to it.id }
        val defaultAccount = db.accountDao().allForPush().firstOrNull()?.id ?: "A1"

        // 逐行边导边加，让文件内部的重复也能被挡掉。
        val seen = HashSet(preSeen)

        var added = 0
        var skipped = 0
        for (r in rows) {
            val fp = TransferFingerprint.of(r)
            if (!seen.add(fp)) { skipped++; continue }

            editor.createTransaction(
                amountMinor = r.amountMinor,
                direction = r.direction,
                accountId = acctByName[r.account ?: ""] ?: defaultAccount,
                occurredAt = r.occurredAt,
                ledgerId = ledgerId,
                deviceId = deviceId,
                note = r.note,
                merchant = r.merchant,
                categoryId = catByName[r.category ?: ""],
                toAccountId = r.toAccount?.let { acctByName[it] },
                source = r.source,
                confidence = 1.0,
            )
            added++
        }
        return ImportResult.Ok(added, skipped)
    }
}
