package kapi.core.io

/** Weak evidence only: callers must offer review, never silently delete or merge a bill. */
class ImportDuplicateMatcher(private val rows: List<TransferRow>) {
    enum class Reason(val label: String) {
        SAME_CONTENT("账单内容相同"),
        SAME_IMPORT_NOTE("金额、原始备注相同，交易时间接近"),
        SAME_PRECISE_TIME("金额与发生时间精确到秒相同；原导出缺少商户或支付平台，请核对"),
        SAME_MERCHANT("金额、商户相同，交易时间接近"),
        SAME_GOODS("金额、商品说明相同，交易时间接近"),
        SAME_WALLET("金额、支付平台相同，交易时间接近；原账单缺少有效商户名"),
        RECURRING_TIME_DIFFERENCE("自动扣款内容相同，但日期不同；请核对支付时间与创建时间"),
        TIME_FIELD_ALIAS("支付时间与创建时间口径不同，其余交易信息相符"),
        BANK_PAYMENT_TIME("银行扣款与已有支付记录金额相同、时间接近，请核对是否为同一笔付款"),
    }
    data class Hit(val index: Int, val reason: Reason)
    private val byMoney = rows.indices.groupBy { rows[it].amountMinor to rows[it].direction }

    fun suspected(incoming: TransferRow): List<Hit> = byMoney[incoming.amountMinor to incoming.direction].orEmpty()
        .mapNotNull { index -> reason(incoming, rows[index])?.let { Hit(index, it) } }
        .sortedBy { kotlin.math.abs(incoming.occurredAt - rows[it.index].occurredAt) }

    private fun reason(a: TransferRow, b: TransferRow): Reason? {
        // Two distinct IDs from the same platform are two transactions, even if visible fields agree.
        if (a.externalKey != null && b.externalKey != null && a.externalKey != b.externalKey &&
            a.externalKey.substringBefore(':') == b.externalKey.substringBefore(':')) return null
        val ap = wallet(a); val bp = wallet(b)
        if (ap != null && bp != null && ap != bp) return null
        if (TransferFingerprint.of(a) == TransferFingerprint.of(b)) return Reason.SAME_CONTENT
        // A confirmed foreign import can add an account/category which was absent in the file.
        // Retain a repeat-import review suggestion; never turn it into an exact-ID match.
        if(a.source.startsWith("BILL_") && a.source==b.source &&
            (SmartBillParser.allowsCategoryPreservation(a.source) || a.source==SmartBillParser.SOURCE_BANK_TABLE) &&
            kotlin.math.abs(a.occurredAt-b.occurredAt)<=1000 &&
            a.toAccount==b.toAccount && (a.account==null || b.account==null || a.account==b.account) &&
            !a.note.isNullOrBlank() && a.note==b.note) return Reason.SAME_IMPORT_NOTE
        // Transfers need both legs to agree; ordinary wallet timing must not match a transfer.
        if (a.toAccount != null || b.toAccount != null || a.direction !in setOf("EXPENSE", "INCOME")) return null
        val primaryDelta = kotlin.math.abs(a.occurredAt - b.occurredAt)
        if(primaryDelta<=120_000 && (a.source==BankPdfTable.SOURCE || b.source==BankPdfTable.SOURCE) &&
            (wallet(a)!=null || wallet(b)!=null)) return Reason.BANK_PAYMENT_TIME
        // Foreign ledger exports often have no merchant or payment account. Their original
        // receipt note is evidence, but only a review suggestion, never an automatic merge.
        if(a.source==SmartBillParser.SOURCE_KAPI && primaryDelta<=120_000) {
            if(b.source==SmartBillParser.SOURCE_KAPI && primaryDelta<=1000 && !a.note.isNullOrBlank() && a.note==b.note) return Reason.SAME_IMPORT_NOTE
            fun originalNote(r:TransferRow)=r.note.orEmpty().substringBefore("\n所属账本：").trim()
            val note=originalNote(a)
            if(note.length>=4 && note==originalNote(b)) return Reason.SAME_IMPORT_NOTE
            val oldMerchant=merchant(b.merchant)
            val cleanNote=note.lowercase().replace(Regex("[\\s\\p{P}\\p{S}]"),"")
            if(a.merchant.isNullOrBlank() && oldMerchant!=null && oldMerchant.length>=4 && cleanNote.contains(oldMerchant)) return Reason.SAME_MERCHANT
            if(a.occurredAt/1000==b.occurredAt/1000 && a.timeEvidence?.precision=="SECOND" &&
                (merchant(a.merchant)==null || ap==null)) return Reason.SAME_PRECISE_TIME
        }
        val delta = (listOf(a.occurredAt)+a.timeEvidence?.aliases(a.direction).orEmpty()).minOf { aa ->
            (listOf(b.occurredAt)+b.timeEvidence?.aliases(b.direction).orEmpty()).minOf { bb -> kotlin.math.abs(aa-bb) }
        }
        val am = merchant(a.merchant); val bm = merchant(b.merchant)
        if(ImportIntelligence.bankWalletCandidate(a,b)) return Reason.BANK_PAYMENT_TIME
        if (delta > 120_000) {
            // Alipay exports can use creation time while the detail page has a later paid time.
            // This remains review-only, restricted to a known wallet, a distinctive description,
            // and a capture explicitly labelled as automatic renewal; never all same-day purchases.
            if (delta <= 36 * 60 * 60_000L && ap != null && ap == bp && am != null && am == bm &&
                b.source in setOf("LOCAL_FALLBACK", "MANUAL") && b.note?.contains("自动续费") == true &&
                sameDescription(a.note,b.note)) return Reason.RECURRING_TIME_DIFFERENCE
            return null
        }
        if (am != null && bm != null && (am == bm ||
                minOf(am.length, bm.length) >= 4 && (am.contains(bm) || bm.contains(am)))) return if(primaryDelta>120_000 && ap!=null && ap==bp) Reason.TIME_FIELD_ALIAS else if(primaryDelta<=120_000) Reason.SAME_MERCHANT else null
        if (ap != null && ap == bp && sameDescription(a.note,b.note)) return if(primaryDelta>120_000) Reason.TIME_FIELD_ALIAS else Reason.SAME_GOODS
        // A vague screen label is insufficient on its own. Require the same known wallet and a short window.
        if ((am == null || bm == null) && ap != null && ap == bp && primaryDelta <= 60_000) {
            if (a.account != null && b.account != null && a.account != b.account &&
                walletLabel(a.account) == null && walletLabel(b.account) == null) return null
            return Reason.SAME_WALLET
        }
        return null
    }

    companion object {
        private val generic = setOf("计入收支", "管理极速付款", "账单", "账单详情", "交易详情", "支出", "收入", "付款", "收款", "支付成功", "付款成功", "交易成功", "订单支付", "商户", "未知商户", "无备注")
        private fun sameDescription(a:String?,b:String?):Boolean {
            fun content(s:String?):String = s.orEmpty().substringBefore('｜')
                .replace(Regex("^(自动续费|商户消费|美团平台消费)[·\\s:：]*"),"")
                .lowercase().replace(Regex("[\\s\\p{P}\\p{S}]"),"")
            val aa=content(a);val bb=content(b)
            return minOf(aa.length,bb.length)>=12 && aa.count { it in '\u4e00'..'\u9fff' }>=6 &&
                bb.count { it in '\u4e00'..'\u9fff' }>=6 && (aa.startsWith(bb) || bb.startsWith(aa))
        }
        private fun merchant(name: String?): String? = name?.trim()?.takeUnless { it.isEmpty() || it in generic }
            ?.lowercase()?.replace(Regex("[\\s\\p{P}\\p{S}]"), "")?.takeIf { it.isNotEmpty() }
        private fun walletLabel(name: String?): String? = when (name?.trim()) {
            "支付宝", "支付宝余额", "余额宝" -> "ALIPAY"
            "微信", "微信支付", "微信零钱", "零钱" -> "WECHAT"
            else -> null
        }
        private fun wallet(row: TransferRow): String? = when {
            row.externalKey?.startsWith("ALIPAY_TXN:") == true -> "ALIPAY"
            row.externalKey?.startsWith("WECHAT_TXN:") == true -> "WECHAT"
            row.source == BankBillParser.SOURCE_ALIPAY -> "ALIPAY"
            row.source == BankBillParser.SOURCE_WECHAT -> "WECHAT"
            else -> walletLabel(row.account)
        }
    }
}
