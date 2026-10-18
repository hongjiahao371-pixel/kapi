package kapi.core.auto

/** 拼多多原生订单和多多支付账单。只提取单笔字段，推荐商品不进入规范文本。 */
object PinduoduoOrder {
    private val orderId=Regex("""(?<!\d)\d{6}\s*-\s*\d{9,18}(?!\d)""")
    private val date=Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}""")
    private val negative=listOf("待付款","待支付","交易关闭","订单已取消","已取消","已退款","退款成功","退款完成","付款失败")
    fun read(text:String): CommerceOrder.Snapshot? {
        if(CommerceOrder.isKnownNonDetail(text)) return null
        val lines=ScreenFields.collapse(text.lines()).map { it.trim().trimStart('|') }
        if(lines.any { row -> negative.any { row==it || row.startsWith("$it，") || row.startsWith("$it ") } }) return null
        val status=lines.indexOf("当前状态")
        val before=status>0 && lines.getOrNull(status-1)=="支付成功" && lines.getOrNull(status+1)!="支付成功"
        val candidates=if(before)listOfNotNull(forward(lines.reversed()),forward(lines)) else listOfNotNull(forward(lines),forward(lines.reversed()))
        return candidates.firstOrNull { it.canonicalText()!=null } ?: candidates.firstOrNull { it.paid } ?: candidates.firstOrNull()
    }
    private fun forward(lines:List<String>): CommerceOrder.Snapshot? {
        fun field(names:List<String>,accept:(String)->Boolean):String? {
            for(name in names) for(i in lines.indices) {
                val row=lines[i]
                if(row.startsWith(name)) {
                    val rest=row.removePrefix(name).trimStart(' ',':','：').trim()
                    if(rest.isNotEmpty() && accept(rest)) return rest
                    for(j in listOf(i+1,i-1)) lines.getOrNull(j)?.takeIf(accept)?.let { return it }
                }
            }
            return null
        }
        val canonical=lines.any { it=="拼多多订单号" || it=="多多支付交易单号" }
        val billing=lines.any { it=="账单详情" } && (canonical || lines.any { it=="当前状态" })
        val ids=lines.filter { it.startsWith("订单编号") || it.startsWith("拼多多订单号") }
            .mapNotNull { orderId.find(it)?.value?.replace(" ","") }.toMutableSet()
        field(listOf("订单编号","拼多多订单号")) { orderId.matches(it) }?.let { ids+=it.replace(" ","") }
        if(ids.size>1) return null
        val order=ids.singleOrNull()
        var paymentId=field(if(canonical)listOf("多多支付交易单号") else listOf("交易单号")) { Regex("\\d{20,40}").matches(it) }
        val paymentLabel=lines.indexOfFirst { it=="交易单号" || it=="多多支付交易单号" }
        if(paymentLabel>=0) {
            val chunks=lines.drop(paymentLabel+1).take(2).takeWhile { Regex("\\d{1,40}").matches(it) }
            chunks.joinToString("").takeIf { it.length in 20..40 }?.let { paymentId=it }
        }
        if(order==null && (!billing || paymentId==null)) return null
        if(lines.take(10).any { row->negative.any(row::contains) }) return null
        val method=field(listOf("支付方式","付款方式")) { it.length in 2..70 &&
            listOf("支付","银行卡","储蓄卡","信用卡","花呗","零钱","钱包","先用后付").any(it::contains) }
        if(method?.contains("先用后付")==true || method?.contains("货到付款")==true) return null
        val paidTime=field(listOf("支付时间","付款时间")) { date.containsMatchIn(it) }?.let { date.find(it)!!.value }
        val createdTime=field(listOf("下单时间","创建时间")) { date.containsMatchIn(it) }?.let { date.find(it)!!.value }
        val source=field(listOf("时间来源")) { it in setOf("付款时间","下单时间") } ?: if(paidTime!=null) "付款时间" else "下单时间"
        val time=(if(source=="付款时间")paidTime else createdTime)?.takeIf { PaymentLink.timestamp(it)!=null }
        val paid=if(billing) lines.any { it=="支付成功" } else lines.take(10).any {
            listOf("运输中","待收货","交易成功","待评价","已签收","拼单成功","已发货","已支付","打包中").any(it::startsWith)
        }
        val nativeProduct=field(listOf("商品名称","商品说明")) { it.length in 3..700 && !it.startsWith("当前状态") }
            ?.substringBefore(",单价")?.substringBefore("，单价")?.trim()
        val detailProduct=if(billing) {
            val i=lines.indexOf("商品详情")
            if(i>=0) lines.drop(i+1).takeWhile { !it.startsWith("订单详情") && it !in setOf("支付时间","支付方式","交易单号","商户单号") }
                .filter { it.length>2 && it!="图片" }.take(3).joinToString("").takeIf { it.length in 3..300 } else null
        } else null
        val shop=field(listOf("商户全称","店铺名称")) { it.length in 2..100 && !it.contains("时间") } ?: if(billing) {
            lines.takeWhile { it!="当前状态" }.filter { it.length in 2..70 && it!="账单详情" && !Regex("-?[¥￥]?\\d+(?:\\.\\d+)?").matches(it) }
                .lastOrNull()
        } else lines.firstOrNull { it.startsWith("百亿补贴·") || it.startsWith("百亿补贴・") || it.contains("旗舰店") || it=="拼多多福利社" }
        val merchant=shop?.removePrefix("百亿补贴·")?.removePrefix("百亿补贴・")?.trim()?.takeIf { it.length in 2..100 }
        val product=nativeProduct ?: detailProduct
        val shopName=merchant ?: if(nativeProduct!=null && order!=null) "拼多多" else null
        val amount=if(billing && !canonical) lines.firstNotNullOfOrNull { row ->
            Regex("""^-\s*(\d+(?:\.\d{1,2})?)$""").matchEntire(row)?.groupValues?.get(1)?.let(::minor)
        } else (field(listOf("实付金额","实付款","实付")) { Regex("[¥￥]|元").containsMatchIn(it) }
            ?: lines.firstOrNull { it.contains("实付") }?.substringAfter("实付"))?.let { row ->
            Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)|(?<!\d)(\d+(?:\.\d{1,2})?)\s*元""")
                .findAll(row).mapNotNull { m->minor(m.groupValues[1].ifBlank { m.groupValues[2] }) }.lastOrNull()
        }
        if(amount!=null && amount<=0) return null
        return CommerceOrder.Snapshot(CommerceOrder.PDD,order ?: paymentId!!,amount,time,shopName,product,method,
            if(order!=null && paymentId!=null)PaymentId.Id(PaymentId.Kind.PDD_TXN,paymentId) else null,paid,
            source,if(order!=null)PaymentId.Kind.PDD_ORDER else PaymentId.Kind.PDD_TXN)
    }
    private fun minor(v:String)=runCatching { v.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
}
