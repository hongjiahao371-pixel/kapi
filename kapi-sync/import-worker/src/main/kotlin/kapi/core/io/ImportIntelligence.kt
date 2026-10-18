package kapi.core.io

/** Versioned local profiles and conservative accounting evidence; never writes or merges a bill. */
object ImportIntelligence {
    const val VERSION=2
    fun formatKey(t:SmartBillParser.Table):String=java.security.MessageDigest.getInstance("SHA-256").digest(("v2:"+(t.identityHeaders ?: t.headers).map {it.trim().removePrefix(LedgerCsv.BOM)}.joinToString("\u001f")).toByteArray()).joinToString(""){"%02x".format(it)}
    fun profile(t:SmartBillParser.Table):String = ImportProfiles.detect(t.identityHeaders ?: t.headers)?.let {p->when(p.id) {"ICBC_CREDIT_SPLIT_V2"->"工商银行信用卡 · 双金额明细";"CCB_SIGNED_V2"->"建设银行 · 收支明细";"WECHAT_DETAIL_V2"->"微信支付 · 账单明细";else->"支付宝 · 支付时间明细"}+" / v$VERSION"} ?: when(t.source) {
        SmartBillParser.SOURCE_BANK_TABLE -> when {
            t.headers.any {it.startsWith("记账金额")} -> "银行·交易与记账双金额"
            SmartBillParser.Field.INCOME in t.mapping -> "银行·收入支出分列"
            else -> "银行·带符号流水"
        }
        BankBillParser.SOURCE_ALIPAY -> "支付宝·"+if(t.headers.any {it.contains("支付时间")}) "支付时间版" else "交易时间版"
        BankBillParser.SOURCE_WECHAT -> "微信支付·交易明细"
        else -> "通用·字段匹配"
    }+" / v$VERSION"
    fun decorate(t:SmartBillParser.Table):SmartBillParser.Table {
        if(t.source!=SmartBillParser.SOURCE_BANK_TABLE)return t
        val extra=listOf(SmartBillParser.Field.NATURE to "交易性质（核对）",SmartBillParser.Field.TO_ACCOUNT to "转入账户",SmartBillParser.Field.ACCOUNT to "账户")
            .filter {it.first !in t.mapping}
        return t.copy(identityHeaders=t.identityHeaders ?: t.headers,headers=t.headers+extra.map {it.second},records=t.records.map {it+List(extra.size){""}},
            mapping=t.mapping+extra.mapIndexed {i,p->p.first to t.headers.size+i})
    }
    fun totalsCheck(t:SmartBillParser.Table,m:Map<SmartBillParser.Field,Int>):String? {
        if(t.source!=SmartBillParser.SOURCE_BANK_TABLE || t.footer.isEmpty())return null
        val money=listOf(SmartBillParser.Field.AMOUNT,SmartBillParser.Field.INCOME,SmartBillParser.Field.EXPENSE).filter {it in m}
        var checked=0
        for(field in money) {
            val col=m.getValue(field)
            val declared=t.footer.mapNotNull {it.getOrNull(col)?.takeIf {v->v.isNotBlank()}?.let(SmartBillParser::parseMoney)}
            if(declared.size!=1)continue
            var sum=0L
            for(row in t.records) {val raw=row.getOrNull(col).orEmpty().trim();if(raw in setOf("","/","-"))continue
                val value=SmartBillParser.parseMoney(raw) ?: return "原文件合计暂未核对：有待修正金额"
                sum=Math.addExact(sum,value)
            }
            if(sum!=declared.single())return "失败：原文件${field.label}合计与逐行金额不一致，请检查字段匹配或原文件"
            checked++
        }
        return if(checked>0) "原文件 $checked 列金额合计已核对（包含待核对交易性质的流水）" else "原文件合计未提供可核对金额"
    }
    fun natureWarning(summary:String):String? {
        val s=summary.trim()
        val kind=when {
            listOf("退款","退货","冲正","撤销","退回").any(s::contains)->"退款或冲正"
            listOf("手续费","服务费","年费").any(s::contains)->return null
            listOf("还款","还贷","还本").any(s::contains)->"还款"
            listOf("转账","转帐","汇兑","汇款","提现","充值","划转","划款","归集","转入","转出","取现","存现","取款","存款","现存","现取","银证").any(s::contains)->"资金划转"
            listOf("申购","赎回","理财","基金","借款","贷款","放款","认购").any(s::contains)->"投资或借贷"
            else->return null
        }
        return "$kind 需要核对交易性质；请选择账户划转、不计收支，或经核对按普通收支导入。退款按普通收入导入不会自动抵扣原消费。"
    }
    private fun wallet(r:TransferRow):String? = when {
        r.source==BankBillParser.SOURCE_WECHAT || r.externalKey?.startsWith("WECHAT_TXN:")==true -> "WECHAT"
        r.source==BankBillParser.SOURCE_ALIPAY || r.externalKey?.startsWith("ALIPAY_TXN:")==true -> "ALIPAY"
        else->null
    }
    private fun channel(r:TransferRow):String? {
        val s=r.account.orEmpty()+" "+r.note.orEmpty()
        val wx=listOf("微信","财付通").any(s::contains);val ali=s.contains("支付宝")
        return if(wx==ali)null else if(wx) "WECHAT" else "ALIPAY"
    }
    private fun tail(r:TransferRow):String? = Regex("(?:尾号[：: ]*|[（(])([0-9]{4})[）)]?").find(r.account.orEmpty()+" "+r.note.orEmpty())?.groupValues?.get(1)
    fun bankWalletCandidate(a:TransferRow,b:TransferRow):Boolean {
        val bank=if(a.source==SmartBillParser.SOURCE_BANK_TABLE)a else if(b.source==SmartBillParser.SOURCE_BANK_TABLE)b else return false
        val pay=if(bank===a)b else a
        if(a.amountMinor!=b.amountMinor || a.direction!=b.direction || a.toAccount!=null || b.toAccount!=null)return false
        // A day-only bank statement cannot single out one of many purchases on that day.
        if(a.timeEvidence?.precision !in setOf("MINUTE","SECOND") || b.timeEvidence?.precision !in setOf("MINUTE","SECOND"))return false
        if(kotlin.math.abs(a.occurredAt-b.occurredAt)>120_000 || channel(bank)!=wallet(pay) || wallet(pay)==null)return false
        val bt=tail(bank);val pt=tail(pay)
        if(bt!=null && pt!=null && bt!=pt)return false
        val bm=bank.merchant?.trim()?.takeIf {it.length>=4 && it !in setOf("微信支付","支付宝支付","财付通支付")}
        val pm=pay.merchant?.trim()
        return bt!=null && bt==pt || bm!=null && pm!=null && (bm==pm || minOf(bm.length,pm.length)>=4 && (bm.contains(pm) || pm.contains(bm)))
    }
    fun accountCandidates(raw:String?,names:List<String>):List<String> {
        if(raw.isNullOrBlank())return emptyList()
        val tail=Regex("(?:尾号[：: ]*|[（(])([0-9]{4})[）)]?").find(raw)?.groupValues?.get(1)
        val bank=listOf("工商","建设","农业","中国银行","招商","交通","邮储","平安","兴业","中信","浦发","民生","光大").firstOrNull(raw::contains)
        return names.filter {n -> n==raw || (tail!=null && n.contains(tail) && (bank==null || n.contains(bank))) || (tail==null && bank!=null && n.contains(bank))}.distinct().take(3)
    }
}
