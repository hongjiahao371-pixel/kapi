package kapi.core.io

/** Normalize each sheet with its own column map before joining, avoiding blank columns from other sheets. */
internal object BillWorkbookAdapter {
    data class Sheet(val name:String,val text:String)
    data class Combined(val text:String,val reports:List<SmartBillParser.SheetReport>)
    private val kinds=mapOf("支出" to "EXPENSE","支出账单" to "EXPENSE","收入" to "INCOME","收入账单" to "INCOME",
        "转账" to "TRANSFER","内部转账" to "TRANSFER","转账还款" to "TRANSFER",
        "借贷" to "UNSUPPORTED","借款" to "UNSUPPORTED","应付款" to "UNSUPPORTED","应收款" to "UNSUPPORTED",
        "余额变更" to "UNSUPPORTED","负债变更" to "UNSUPPORTED","债权变更" to "UNSUPPORTED")
    private val mixed=setOf("收支账单","账单","收支明细","账单明细")
    fun combine(sheets:List<Sheet>):Combined {
        require(sheets.size<=64) {"工作表数量超过 64 张，请分批导入"}
        val tables=sheets.map {s -> s to runCatching {SmartBillParser.inspect(s.text)}.getOrNull()}
        val populated=tables.filter {(_,t) -> t?.records?.isNotEmpty()==true}
        val kapi=tables.any {(_,t) -> t?.source==SmartBillParser.SOURCE_KAPI}
        val kapiTransferHeaders=listOf("日期","时间","类型","金额","转入账户","转出账户","备注")
        val canCombine=populated.all {(s,t) -> s.name.trim() in kinds || s.name.trim() in mixed ||
            (kapi && (t?.source==SmartBillParser.SOURCE_KAPI || t?.headers?.containsAll(kapiTransferHeaders)==true))}
        require(populated.size<=1 || canCombine) {"工作簿有多个非空账单表且业务类型不明确，请分别导出 CSV 后导入"}
        // Non-table text is not silently discarded from a multi-sheet workbook.
        require(tables.none {(s,t) -> t==null && s.text.isNotBlank() && sheets.size>1}) {"工作簿有未识别的工作表，请分别导出 CSV 后核对"}
        val reports=tables.map {(s,t) ->
            val n=t?.records?.size ?: 0
            SmartBillParser.SheetReport(s.name,n,if(n==0) "空表" else if(kinds[s.name.trim()]=="UNSUPPORTED") "暂不支持余额调整或债权债务关系" else "待核对")
        }
        if(populated.size<=1 && populated.none {(s,_) -> s.name.trim() in kinds} && !kapi) {
            return Combined(populated.firstOrNull()?.first?.text ?: sheets.firstOrNull()?.text.orEmpty(),reports)
        }
        val supported=tables.filter {(s,t) -> t!=null && kinds[s.name.trim()]!="UNSUPPORTED"}
        val source=when {
            kapi -> SmartBillParser.SOURCE_KAPI
            tables.any {(_,t) -> t?.source==SmartBillParser.SOURCE_SUISHOUJI} -> SmartBillParser.SOURCE_SUISHOUJI
            else -> supported.firstOrNull()?.second?.source ?: "BILL_GENERIC"
        }
        val fields=(supported.flatMap {it.second!!.mapping.keys}+listOf(SmartBillParser.Field.TIME,SmartBillParser.Field.AMOUNT)).distinct()
        val canonical=fields.map {"_归账_"+it.name}
        val original=supported.flatMap {it.second!!.headers}.distinct().filter {it !in canonical}
        val headers=canonical+original+listOf("_归账_工作表","_归账_表类型")
        val rows=supported.flatMap {(s,maybe) ->
            val table=maybe!!
            table.records.map {row ->
                fields.map {f ->
                    table.mapping[f]?.let {row.getOrNull(it)}.orEmpty()
                } + original.map {h -> table.headers.indexOf(h).takeIf {it>=0}?.let {row.getOrNull(it)}.orEmpty()}+
                    listOf(s.name,kinds[s.name.trim()].orEmpty())
            }
        }
        require(rows.size<=100_000) {"账单超过十万行，请分批导入"}
        val marker=when(source) {
            SmartBillParser.SOURCE_KAPI -> "咔皮记账导出"
            SmartBillParser.SOURCE_SUISHOUJI -> "随手记导出"
            SmartBillParser.SOURCE_QIANJI_TEMPLATE -> "钱迹模板"
            else -> ""
        }
        val text=marker+"\r\n"+(listOf(headers)+rows).joinToString("\r\n") {row ->row.joinToString(",",transform=LedgerCsv::quote)}
        require(text.length<=BillFileReader.MAX_BYTES) {"工作簿内容过大，请分批导入"}
        return Combined(text,reports)
    }
}
