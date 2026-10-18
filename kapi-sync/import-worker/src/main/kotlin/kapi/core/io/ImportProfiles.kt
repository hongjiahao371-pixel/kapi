package kapi.core.io

/** Known export schemas have explicit signatures and priorities; aliases remain the fallback. */
object ImportProfiles {
    data class Profile(val id:String,val source:String,val required:Set<String>,val priorities:Map<SmartBillParser.Field,List<String>>)
    private fun key(s:String)=s.trim().replace(Regex("[\\s_（）()/\\-]"),"")
    private val time=SmartBillParser.Field.TIME
    val all=listOf(
        Profile("ICBC_CREDIT_SPLIT_V2",SmartBillParser.SOURCE_BANK_TABLE,setOf("交易日期","记账日期","交易金额(收入)","交易金额(支出)","记账币种"),mapOf(time to listOf("交易日期"),SmartBillParser.Field.MERCHANT to listOf("交易场所","对方户名"))),
        Profile("CCB_SIGNED_V2",SmartBillParser.SOURCE_BANK_TABLE,setOf("交易日期","交易金额","账户余额","摘要","对方账号与户名"),mapOf(time to listOf("交易日期"),SmartBillParser.Field.MERCHANT to listOf("对方账号与户名"))),
        Profile("WECHAT_DETAIL_V2",BankBillParser.SOURCE_WECHAT,setOf("交易时间","交易单号","当前状态","商品"),mapOf(time to listOf("交易时间"),SmartBillParser.Field.ID to listOf("交易单号"),SmartBillParser.Field.DIRECTION to listOf("收/支"))),
        Profile("ALIPAY_PAID_V2",BankBillParser.SOURCE_ALIPAY,setOf("交易创建时间","付款时间","交易对方","交易号"),mapOf(time to listOf("付款时间","交易创建时间"),SmartBillParser.Field.ID to listOf("交易号")))
    )
    fun detect(headers:List<String>):Profile? {val names=headers.map(::key).toSet();return all.firstOrNull {p->p.required.all {key(it) in names}}}
    fun apply(headers:List<String>,mapping:MutableMap<SmartBillParser.Field,Int>) {
        detect(headers)?.priorities?.forEach {(field,names)->names.firstNotNullOfOrNull {name->headers.indexOfFirst {key(it)==key(name)}.takeIf{it>=0}}?.let {mapping[field]=it}}
    }
}
