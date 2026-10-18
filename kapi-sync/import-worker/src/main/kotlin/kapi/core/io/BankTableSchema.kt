package kapi.core.io

import kapi.core.io.SmartBillParser.Field

/** Bank-specific column families. Never pair foreign transaction money with CNY posting currency. */
internal object BankTableSchema {
    private fun key(s:String)=s.trim().replace(Regex("[\\s_（）()/\\-]"),"")
    private fun index(h:List<String>,name:String)=h.indexOfFirst{key(it)==key(name)}
    fun recognizes(h:List<String>):Boolean {
        val names=h.map(::key).toSet()
        return ("交易日期" in names && "摘要" in names &&
            (("交易金额" in names && "账户余额" in names) ||
                ("交易金额收入" in names && "交易金额支出" in names) ||
                ("记账金额收入" in names && "记账金额支出" in names)))
    }
    fun apply(h:List<String>,mapping:MutableMap<SmartBillParser.Field,Int>) {
        if(!recognizes(h))return
        // Posting date can differ by a day; preserve the original transaction date.
        mapping[Field.TIME]=index(h,"交易日期")
        val postedIn=index(h,"记账金额(收入)");val postedOut=index(h,"记账金额(支出)");val postedCurrency=index(h,"记账币种")
        if(postedIn>=0 && postedOut>=0 && postedCurrency>=0) {
            mapping.remove(Field.AMOUNT);mapping[Field.INCOME]=postedIn;mapping[Field.EXPENSE]=postedOut;mapping[Field.CURRENCY]=postedCurrency
        } else {
            val currency=index(h,"交易币种")
            if(currency>=0)mapping[Field.CURRENCY]=currency
        }
    }
    fun isFooter(h:List<String>,row:List<String>,mapping:Map<SmartBillParser.Field,Int>):Boolean =
        recognizes(h) && mapping[SmartBillParser.Field.TIME]?.let{row.getOrNull(it)?.trim()} in setOf("合计金额","合计","总计","本页合计")
    fun merchant(header:String?,raw:String):String? {
        var value=raw.trim()
        if(header?.let(::key)=="对方账号与户名" && '/' in value)value=value.substringAfter('/').trim()
        return value.takeIf{it.isNotEmpty() && it.any{c->c !in "*—-/ "}}
    }
}
