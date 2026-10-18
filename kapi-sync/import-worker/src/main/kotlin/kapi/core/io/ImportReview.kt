package kapi.core.io

/** Preview edits never mutate the source file or weaken the money/time/identity validators. */
object ImportReview {
    val editableFields=setOf(SmartBillParser.Field.TIME,SmartBillParser.Field.CLOCK,SmartBillParser.Field.DIRECTION,
        SmartBillParser.Field.AMOUNT,SmartBillParser.Field.INCOME,SmartBillParser.Field.EXPENSE,SmartBillParser.Field.CURRENCY,SmartBillParser.Field.NATURE,SmartBillParser.Field.ACCOUNT,SmartBillParser.Field.TO_ACCOUNT)
    fun repair(table:SmartBillParser.Table,row:Int,changes:Map<SmartBillParser.Field,String>,mapping:Map<SmartBillParser.Field,Int>?=null):SmartBillParser.Preview {
        return repairMany(table,mapOf(row to changes),mapping)
    }
    fun repairMany(table:SmartBillParser.Table,edits:Map<Int,Map<SmartBillParser.Field,String>>,mapping:Map<SmartBillParser.Field,Int>?=null):SmartBillParser.Preview {
        val columns=mapping ?: table.mapping
        require(edits.isNotEmpty() && edits.keys.all {it in 1..table.records.size}) {"请选择有效的待修正行"}
        edits.values.forEach {changes ->
            require(changes.isNotEmpty() && changes.keys.all {it in editableFields && columns[it] in table.headers.indices}) {"只能修正时间、金额、收支、币种、交易性质和账户"}
            require(changes.values.all {it.length<=128}) {"修正内容过长"}
        }
        val records=table.records.mapIndexed {i,original ->edits[i+1]?.let {changes ->
            val cells=original.toMutableList();while(cells.size<table.headers.size) cells.add("")
            changes.forEach {(field,value)->cells[columns.getValue(field)]=value};cells.toList()
        } ?: original}
        return SmartBillParser.preview(table.copy(records=records,mapping=columns),mapping)
    }

    fun categoryKey(row:TransferRow):String? = row.category?.trim()?.takeIf {it.isNotBlank()}?.let {
        listOf(row.source,row.direction,it).joinToString("\u001f")
    }
    fun sourceRecords(p:SmartBillParser.Preview)=p.table.records.count {it.size>1 || p.table.headers.size==1}
    fun totals(rows:List<TransferRow>,selected:Set<Int>):Pair<Long,Long> {
        require(selected.all {it in rows.indices}) {"导入选择已失效"}
        var income=0L;var expense=0L
        selected.forEach {i -> val row=rows[i];if(row.toAccount==null) {
            if(row.direction=="INCOME") income=Math.addExact(income,row.amountMinor) else expense=Math.addExact(expense,row.amountMinor)
        }}
        return income to expense
    }
}
