package kapi.core.auto

import java.math.BigDecimal

/** 京东/淘宝单笔已付款订单。可接收原生文本，也可接收按视觉行排列的本地 OCR。 */
object CommerceOrder {
    const val JD = "com.jingdong.app.mall"
    const val TAOBAO = "com.taobao.taobao"
    const val PDD = "com.xunmeng.pinduoduo"
    val PACKAGES = setOf(JD, TAOBAO, PDD)
    data class Snapshot(val pkg: String, val id: String, val amount: Long?, val time: String?,
        val merchant: String?, val product: String?, val method: String?, val providerId: PaymentId.Id?,
        val paid: Boolean, val timeSource:String="付款时间", val identityKind:PaymentId.Kind?=null) {
        val kind get() = identityKind ?: if(pkg==JD)PaymentId.Kind.JD_ORDER else if(pkg==TAOBAO)PaymentId.Kind.TAOBAO_ORDER else PaymentId.Kind.PDD_ORDER
        fun merge(previous: Snapshot?): Snapshot {
            if (previous == null || previous.pkg != pkg || previous.id != id) return this
            // 有矛盾的金额/日期不能拼接两个截图来强行成立。
            if (amount != null && previous.amount != null && amount != previous.amount) return this
            if (time != null && previous.time != null && time != previous.time) return this
            return copy(amount=amount ?: previous.amount,time=time ?: previous.time,
                merchant=previous.merchant ?: merchant,product=previous.product ?: product,
                method=method ?: previous.method,providerId=providerId ?: previous.providerId,
                paid=paid || previous.paid)
        }
        fun canonicalText(): String? {
            if (!paid || amount == null || amount <= 0 || time == null || merchant == null || product == null) return null
            return buildString {
                appendLine("账单详情");appendLine("订单信息")
                appendLine(kind.label);appendLine(id)
                appendLine("当前状态");appendLine("支付成功")
                appendLine("支出${BigDecimal.valueOf(amount,2).toPlainString()}元")
                appendLine("实付金额");appendLine("¥${BigDecimal.valueOf(amount,2).toPlainString()}")
                appendLine("商户全称");appendLine(merchant)
                appendLine("商品说明");appendLine(product)
                appendLine(if(timeSource=="付款时间")"支付时间" else "创建时间");appendLine(time)
                appendLine("时间来源");appendLine(timeSource)
                if(pkg==PDD)appendLine("拼多多")
                method?.let { appendLine("付款方式");appendLine(it) }
                providerId?.let { appendLine(it.kind.label);appendLine(it.value) }
            }.trim()
        }
    }
    private val money = Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""")
    private val date = Regex("""\d{4}[-/]\d{2}[-/]\d{2}\s+\d{2}:\d{2}:\d{2}""")
    private val digits = Regex("""(?<!\d)\d{10,30}(?!\d)""")
    private val excluded = listOf("待付款","待支付","交易关闭","订单已取消","已取消","已退款","退款完成","退款成功","付款失败","货到付款","先用后付")
    private fun minor(value: String?): Long? = runCatching { value?.toBigDecimal()?.multiply(BigDecimal(100))?.longValueExact() }.getOrNull()
    fun isDisqualified(text:String): Boolean = text.lines().take(10).any { line -> excluded.any(line::contains) }
    fun isKnownNonDetail(text: String): Boolean = text.lines().any { it.trim() in setOf("搜索我的订单","搜索订单","我的订单","购物车","订单管理") }
    fun read(pkg: String, text: String): Snapshot? {
        if (pkg !in PACKAGES || isKnownNonDetail(text)) return null
        if(pkg==PDD) return PinduoduoOrder.read(text)
        val raw = text.lines().map { it.trim().trimStart('|','l',' ' ).replace("支付万式","支付方式").replace("創建时间","创建时间").replace("实付數","实付款").replace('內','内').replace('褲','裤') }.filter(String::isNotBlank)
        return readForward(pkg,raw) ?: readForward(pkg,raw.reversed())
    }
    private fun readForward(pkg: String, lines: List<String>): Snapshot? {
        // 列表/商品页，即使有金额、成功角标，也没有单笔订单编号与完整付款时间。
        if (lines.any { it in setOf("搜索我的订单","搜索订单","全部订单未选中","订单管理") }) return null
        val canonical = lines.any { it == "京东订单号" || it == "淘宝订单号" }
        if (lines.take(6).any { line -> excluded.any { line.contains(it) } }) return null
        if (lines.any { it.startsWith("支付方式") && (it.contains("货到付款") || it.contains("先用后付")) }) return null
        val backwards=canonical && lines.indexOf("账单详情") > lines.indexOf("商品说明")
        fun field(names: List<String>, accept: (String)->Boolean): String? {
            for (name in names) for (i in lines.indices) {
                val line=lines[i]
                if (line==name) for(j in if(backwards) listOf(i-1,i+1) else listOf(i+1,i-1)) lines.getOrNull(j)?.takeIf(accept)?.let { return it }
                if (line.startsWith(name)) line.removePrefix(name).trimStart(' ',':','：').takeIf(accept)?.let { return it }
            }
            return null
        }
        val orderLines = lines.filter { it.startsWith("订单编号") || it.startsWith("京东订单号") || it.startsWith("淘宝订单号") ||
            (pkg==TAOBAO && Regex("""^订单信(?:息|启)?(?:\s|共)""").containsMatchIn(it)) }
        val ids=orderLines.mapNotNull { digits.find(it)?.value }.toMutableSet()
        field(listOf("订单编号","京东订单号","淘宝订单号")) { digits.matches(it) }?.let { ids+=it }
        if (ids.size!=1) return null
        val id=ids.single()
        val settled = lines.take(7).any { Regex("^[oO0◎○◉©。]{0,3}完成(?:\\s.*)?$").matches(it) ||
            listOf("已签收","交易成功","卖家已发货","等待买家收货","已完成","订单完成","已付款","已支付","等待卖家发货").any(it::startsWith) } ||
            (canonical && lines.any { it=="支付成功" })
        val paidRow=field(listOf("实付款","实付金额")) { money.containsMatchIn(it) }
        val amount=paidRow?.let { row ->
            // Never pick the discount when the final amount is unreadable (OPPO can OCR 6.9 as も9).
            val finalPart=if(pkg==JD && row.contains("合计")) row.substringAfterLast("合计") else row.substringAfterLast('¥',row).substringAfterLast('￥')
            Regex("""^[¥￥\s:：]*(\d+(?:\.\d{1,2})?)\s*[>^)∨∧〉]*$""").matchEntire(finalPart.trim())?.groupValues?.get(1)?.let(::minor)
        }
        if (amount != null && amount <= 0) return null
        val time=field(listOf("支付时间","付款时间")) { date.containsMatchIn(it) }
            ?.let { date.find(it)?.value }?.takeIf { PaymentLink.timestamp(it) != null }
        val provider=field(listOf("支付宝交易号")) { digits.containsMatchIn(it) }?.let {
            PaymentId.Id(PaymentId.Kind.ALIPAY_TXN,digits.find(it)!!.value)
        } ?: field(listOf("微信支付交易单号","微信交易单号")) { digits.containsMatchIn(it) }?.let {
            PaymentId.Id(PaymentId.Kind.WECHAT_TXN,digits.find(it)!!.value)
        }
        val method=field(listOf("支付方式","付款方式")) { row ->
            listOf("白条信用购","京东白条","白条","支付宝支付","支付宝","微信支付","银行卡","京东支付","云闪付","花呗").any(row::contains)
        }?.let { row -> listOf("白条信用购","京东白条","支付宝支付","微信支付","京东支付","云闪付","银行卡","支付宝","花呗","白条").firstOrNull(row::contains) }
            ?: if (provider?.kind==PaymentId.Kind.ALIPAY_TXN) "支付宝" else if (provider?.kind==PaymentId.Kind.WECHAT_TXN) "微信支付" else null
        val shopIndex=lines.indexOfFirst { line ->
            line.contains("旗舰店") || line.contains("专营店") || line.contains("自营店") || line.startsWith("商户全称")
        }
        val merchant=field(listOf("商户全称")) { it.length in 2..100 && !it.contains("时间") && it !in setOf("商品说明","商户全称","实付金额") && !money.containsMatchIn(it) } ?:
            lines.getOrNull(shopIndex)?.substringBefore("进店")?.trim()?.removePrefix("天猫")?.trim()
                ?.removePrefix("當日")?.removePrefix("自国日")?.removePrefix("自营")?.replace(Regex("^[自国國日當川目]{2,5}"),"")?.trim(' ' ,'|')?.replace("澤","泽")?.takeIf { it.length in 2..100 }
        val product=field(listOf("商品说明")) { it.length in 2..200 && !it.contains("时间") && it !in setOf("商品说明","商户全称","实付金额") && !money.containsMatchIn(it) } ?: if(shopIndex>=0) {
            lines.drop(shopIndex+1).take(10).firstOrNull { line ->
                line.length in 5..240 && !line.startsWith("88VIP") && !line.startsWith("数量") &&
                    !line.startsWith("支持7天") && !line.contains("好评率") && !line.contains("平均") &&
                    !line.contains("退货宝") && !line.startsWith("进店") && !line.startsWith("商品总价") &&
                    !line.startsWith("实付") && !line.startsWith("订单") && !line.startsWith("支付") &&
                    !line.startsWith("卖了换钱") && !line.startsWith("加入购物车") &&
                    !Regex("""^[¥￥]?\d+(?:\.\d+)?$""").matches(line)
            }?.replace(Regex("\\s*(?:到手)?[¥￥]\\s*\\d+(?:\\.\\d+)?(?:.*)$"),"")?.trim()
        } else null
        return Snapshot(pkg,id,amount,time,merchant,product,method,provider,settled)
    }
}

/** 只拼同一个订单的可见截图；完整字段需再次稳定读到，才派发。 */
class CommerceOcrSession {
    private var current: CommerceOrder.Snapshot? = null
    private var at=0L
    private var candidate: String? = null
    private var candidateAt=0L
    private var armedPdd:Pair<CommerceOrder.Snapshot,Long>?=null
    fun armPddPayment(now:Long): Boolean {
        val order=current?.takeIf { it.pkg==CommerceOrder.PDD && it.kind==PaymentId.Kind.PDD_ORDER && it.method=="多多支付" && it.canonicalText()!=null } ?: return false
        armedPdd=order to now;at=now;return true
    }
    fun reset() { current=null;candidate=null;at=0;candidateAt=0;armedPdd=null }
    fun offer(pkg:String,text:String,now:Long): String? {
        var incoming=CommerceOrder.read(pkg,text)
        if(incoming==null){if(CommerceOrder.isKnownNonDetail(text) || CommerceOrder.isDisqualified(text))reset();return null}
        if(now<at || now-at>60_000)reset()
        if(incoming.pkg==CommerceOrder.PDD && incoming.kind==PaymentId.Kind.PDD_TXN) {
            val route=armedPdd
            if(route!=null && now-route.second in 0..60_000) {
                val old=route.first;val created=old.time?.let(PaymentLink::timestamp);val paid=incoming.time?.let(PaymentLink::timestamp)
                val short=old.product.orEmpty().replace(" ","").substringBefore("...").substringBefore("…").take(18)
                if(old.amount==incoming.amount && created!=null && paid!=null && paid-created in 0..180_000 && short.length>=8 && incoming.product.orEmpty().replace(" ","").startsWith(short)) {
                    incoming=incoming.copy(id=old.id,merchant=old.merchant,identityKind=PaymentId.Kind.PDD_ORDER,
                        providerId=PaymentId.Id(PaymentId.Kind.PDD_TXN,incoming.id))
                }
            }
        }
        val merged=incoming.merge(current);current=merged;at=now
        val complete=merged.canonicalText() ?: return null
        if(candidate!=complete){candidate=complete;candidateAt=now;return null}
        if(now-candidateAt !in 700..8_000){candidateAt=now;return null}
        return complete
    }
}
