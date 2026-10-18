package kapi.core.auto

import kapi.core.llm.Direction
import kapi.core.llm.Money

/** Positive evidence for native peer receipts. Chat cards and unopened packets are not receipts. */
object PeerPaymentReceipt {
    enum class Kind { TRANSFER, RED_PACKET }
    data class Receipt(val kind:Kind,val amountMinor:Long,val direction:Direction,val merchant:String?,val text:String)
    private val forbidden=listOf("按住说话","更多功能按钮","选择聊天","选择联系人","发送给朋友","转发给","全部会话","搜索聊天记录")
    private val money=Regex("^[+\\-−]?\\s*[¥￥]?\\s*(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?(?:\\s*元)?$")
    private val sender=Regex("^(.{1,45})的红包$")
    private val date=Regex("\\d{4}[-年/.]\\s*\\d{1,2}[-月/.]\\s*\\d{1,2}")
    /** A rejected peer state must not be reinterpreted by the generic money parser.
     * A returned original debit is not a new credit; its separate refund receipt
     * supplies its own amount, refund time and identity. */
    fun rejectionReason(text:String):String? {
        val lines=ScreenFields.collapse(text.lines())
        val peer=lines.any { it.startsWith("转账-转给") || it.startsWith("转账-来自") ||
            it.matches(Regex("^微信红包[-－—](?:发给|来自).{1,45}$")) ||
            it in setOf("微信转账","转账详情","红包说明","对方账户") ||
            listOf("红包说明","对方账户").any {key->it.startsWith("$key：")||it.startsWith("$key:")||it.startsWith("$key ")} }
        if(!peer)return null
        val refundReceipt=lines.any {it in setOf("退款详情","转账-退款")} &&
            lines.any {line->listOf("退款时间","退款成功时间").any {key->line==key||line.startsWith("$key：")||line.startsWith("$key:")||line.startsWith("$key ")}}
        val denied=setOf("过期已退还","过期已退回","已退回","已退还","转账已退回","转账已退还","已过期",
            "交易关闭","交易失败","支付失败","付款失败","发送失败","已退款","退款成功",
            "等待付款","待付款","待支付","确认付款","确认支付","确认收款","待收款","等待收款",
            "等待你收款","等待你确认收款","等待你确认收钱","待你确认收款","领取红包","红包待领取","待领取",
            "红包已过期","红包已失效","红包已退回","红包已退还","红包已退款","超时已退回","超时已退还",
            "已失效","已撤销","已取消","转账失败","转账关闭","转账中","处理中","等待领取","未领取","未收款","待领取红包")
        fun deniedState(value:String)=denied.any {value==it||value.startsWith("$it（")||value.startsWith("$it(")}
        val completed=setOf("支付成功","付款成功","支付完成","已支付","交易成功","已收款","已收钱","收款成功","已到账",
            "已存入零钱","已存入零钱，可直接消费","转账成功","已转账","对方已收钱","对方已收款",
            "等待对方确认收钱","等待对方收款","等待对方确认收款")
        val labels=listOf("当前状态","交易状态")
        val labeled=labels.any {key->lines.any {it==key||it.startsWith("$key：")||it.startsWith("$key:")||it.startsWith("$key ")}}
        val states=if(labeled) labels.mapNotNull {key->ScreenFields.valueNear(lines,key,ScreenFields.orientation(lines)) {
            it in completed || deniedState(it)
        }} else lines
        val rejected=states.firstOrNull {deniedState(it) && !(refundReceipt && it in setOf("已退款","退款成功"))} ?: return null
        return "转账/红包尚未到账、失败或原单已退还（$rejected）"
    }
    private fun amount(line:String):Long? {
        if(!money.matches(line) || !(line.contains('.')||line.contains('¥')||line.contains('￥')||line.endsWith("元")))return null
        return Money.parse(line.replace("−","-").trim().removePrefix("+").removePrefix("-").trim().removeSuffix("元"))?.minor?.takeIf {it>0}
    }
    private fun safeName(value:String):String?=value.trim().takeIf {
        it.length in 1..45 && !PaymentGate.hasAmount(it) && it !in setOf("我","你","您","本人","微信","对方") &&
            !it.contains("红包详情") && !it.contains("转账详情")
    }
    private fun evidence(lines:List<String>):String {
        val orientation=ScreenFields.orientation(lines)
        val keys=listOf("交易单号","转账单号","支付时间","转账时间","收款时间","领取时间","发送时间")
        return keys.mapNotNull {key ->
            ScreenFields.valueNear(lines,key,orientation) {value->
                if(key.endsWith("单号")) Regex("[0-9]{16,64}").matches(value) else date.containsMatchIn(value)
            }?.let {"$key：$it"}
        }.joinToString("\n").let {if(it.isEmpty()) "" else "\n$it"}
    }
    fun read(text:String):Receipt? {
        if(text.length>4000 || forbidden.any(text::contains) || rejectionReason(text)!=null)return null
        val lines=ScreenFields.collapse(text.lines())
        if(lines.any {it in setOf("打开","开","领取红包","红包待领取","待领取","确认收款","待你确认收款")} &&
            lines.none {it in setOf("已存入零钱","已存入零钱，可直接消费","红包已发出","红包发送成功")})return null
        // OPPO genuine wallet bill (MMWebViewUI): the title is 微信红包-发给…,
        // not 发红包/红包已发出. Its negative amount is the payment itself, not a coupon.
        // Require the specific title, receipt field, paid-time field and settled status;
        // never exempt arbitrary screens merely because they contain 红包详情.
        val sentBill=lines.singleOrNull { it.matches(Regex("^微信红包[-－—]发给.{1,45}$")) }
        if(sentBill!=null && lines.any { it in setOf("红包详情","红包详情 查看","红包详情：查看","红包详情:查看") } &&
            lines.none {it in setOf("支付失败","付款失败","等待付款","已退回","已退款","退款成功","交易关闭")}) {
            val orientation=ScreenFields.orientation(lines)
            val status=ScreenFields.valueNear(lines,"当前状态",orientation) {
                it in setOf("支付成功","付款成功","支付完成","已支付")
            }
            val paid=ScreenFields.valueNear(lines,"支付时间",orientation,date::containsMatchIn)
            val values=lines.mapNotNull(::amount)
            val signed=lines.count { (it.startsWith("-")||it.startsWith("−")) && amount(it)!=null }
            val hasMethod=lines.any {it=="支付方式"||it.startsWith("支付方式：")||it.startsWith("支付方式:")}
            if(status!=null && paid!=null && values.size==1 && signed==1 &&
                (hasMethod || PaymentId.authoritative(text)!=null))
                return Receipt(Kind.RED_PACKET,values.single(),Direction.EXPENSE,sentBill,lines.joinToString("\n"))
        }
        val receivedBill=lines.singleOrNull {it.matches(Regex("^微信红包[-－—]来自.{1,45}$"))}
        if(receivedBill!=null && lines.any {it in setOf("红包详情","红包详情 查看","红包详情：查看","红包详情:查看")}) {
            val orientation=ScreenFields.orientation(lines)
            val status=ScreenFields.valueNear(lines,"当前状态",orientation) {
                it in setOf("已收款","已收钱","收款成功","已到账","已存入零钱","已存入零钱，可直接消费")
            }
            val credited=listOf("领取时间","收款时间","支付时间").firstNotNullOfOrNull {ScreenFields.valueNear(lines,it,orientation,date::containsMatchIn)}
            val values=lines.mapNotNull(::amount)
            if(status!=null && credited!=null && values.size==1 &&
                lines.count {it.startsWith("+")&&amount(it)!=null}==1 && PaymentId.authoritative(text)!=null)
                return Receipt(Kind.RED_PACKET,values.single(),Direction.INCOME,receivedBill,lines.joinToString("\n"))
        }
        // Do not infer receipt status from the word 红包 or from other people's claims.
        val deposited=lines.indexOfFirst {it=="已存入零钱"||it=="已存入零钱，可直接消费"||it=="已存入零钱,可直接消费"}
        if(deposited>=0) {
            val header=lines.take(deposited)
            val names=header.mapNotNull {sender.matchEntire(it)?.groupValues?.get(1)?.let(::safeName)}.distinct()
            if(names.size!=1 || header.none {it=="微信红包"||sender.matches(it)})return null
            val amounts=header.mapNotNull(::amount)
            if(amounts.size!=1)return null
            // Canonicalize only the user's settled header. Group totals and other recipients
            // below 已存入零钱 must never become extra amounts or alter the receipt identity.
            val yuan=java.math.BigDecimal.valueOf(amounts.single(),2).toPlainString()
            val canonical="微信红包\n${names.single()}的红包\n$yuan\n元\n已存入零钱\n收到红包"+evidence(lines)
            return Receipt(Kind.RED_PACKET,amounts.single(),Direction.INCOME,names.single(),canonical)
        }
        val sent=lines.any {it in setOf("红包已发出","红包已发送","红包发送成功")}
        if(sent && lines.any {it in setOf("微信红包","发红包")} && lines.any {it in setOf("完成","查看红包")}) {
            if(lines.any {it in setOf("塞钱进红包","确认支付","确认付款","支付失败","发送失败")})return null
            val amounts=lines.mapNotNull {amount(it.removePrefix("支出 "))}
            if(amounts.size!=1)return null
            val to=lines.firstNotNullOfOrNull {line->line.takeIf {it.startsWith("发给")&&!it.contains("红包") }?.removePrefix("发给")?.let(::safeName)}
            val yuan=java.math.BigDecimal.valueOf(amounts.single(),2).toPlainString()
            val canonical="微信红包\n红包已发出\n支出 $yuan 元\n完成\n发红包"+(to?.let {"\n发给$it"} ?: "")+evidence(lines)
            return Receipt(Kind.RED_PACKET,amounts.single(),Direction.EXPENSE,to,canonical)
        }
        val detail=lines.any {it in setOf("微信转账","转账详情","收款详情")||it.startsWith("转账-转给")||it.startsWith("转账-来自")}
        if(!detail || lines.any {it in setOf("已退回","已退还","转账已退回","转账已退还","已过期","交易关闭","交易失败")})return null
        val amounts=lines.mapNotNull(::amount)
        if(amounts.size!=1)return null
        val labels=listOf("当前状态","转账时间","收款时间","转账备注","付款方式","支付方式","转账单号")
        if(labels.count {label->lines.any {it==label||it.startsWith("$label：")||it.startsWith("$label:")||it.startsWith("$label ")}}<2)return null
        if(lines.none(date::containsMatchIn))return null
        val outgoing=lines.any {it.startsWith("转账-转给")||it.startsWith("-")||it.startsWith("−")}
        val statuses=lines.map {it.removePrefix("当前状态：").removePrefix("当前状态:").removePrefix("当前状态 ").trim()}
        val settled=statuses.any {it in setOf("已收款","已收钱","已到账","收款已到账","转账已收钱","已转入零钱通","已转入零钱","已存入零钱通")}
        val incoming=lines.any {it.startsWith("转账-来自")||it.startsWith("+")} && settled
        if(outgoing==incoming)return null
        if(!outgoing && lines.any {it in setOf("确认收款","待收款","等待你收款","待你确认收款")})return null
        if(outgoing && statuses.none {it in setOf("转账成功","已转账","支付成功","已收款","对方已收钱","对方已收款","等待对方确认收钱","等待对方收款","等待对方确认收款")})return null
        val prefix=if(outgoing) "转账-转给" else "转账-来自"
        val name=lines.firstNotNullOfOrNull {it.takeIf {s->s.startsWith(prefix)}?.removePrefix(prefix)?.let(::safeName)}
        return Receipt(Kind.TRANSFER,amounts.single(),if(outgoing)Direction.EXPENSE else Direction.INCOME,
            name?.let {prefix+it},lines.joinToString("\n"))
    }
}
