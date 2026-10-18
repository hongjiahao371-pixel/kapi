package kapi.core.llm

import java.text.Normalizer
import kapi.core.auto.ScreenFields
import kapi.core.auto.TransactionTime

/** Screenshot-only cleanup. Never replace ambiguous O/0 or invent missing decimal points. */
object ScreenshotBillParser {
    data class Line(val text:String,val left:Int,val top:Int,val right:Int,val bottom:Int)
    private val labels=ScreenFields.FIELD_LABELS.filter{it.length>=3}.sortedByDescending{it.length}
    private val splitDate=Regex("^((?:(?:创建时间|交易创建时间|支付时间|付款时间|转账时间|收款时间|领取时间|退款时间|退款成功时间)[ :：]+)?)(\\d{4})([-/.年])\\s*(\\d(?:[ \\t]*\\d)?)([-/.月])\\s*(\\d(?:[ \\t]*\\d)?)(日?)([ \\t]+\\d{1,2}:\\d{2}(?::\\d{2})?)?$")
    fun normalize(text:String):String {
        val lines=Normalizer.normalize(text,Normalizer.Form.NFKC)
            .replace('−','-').replace('﹣','-').replace('\u00a0',' ')
            .lines().map { raw ->
                var line=raw.trim().replace(Regex("(?<=\\d)[ \\t]*([.:])[ \\t]*(?=\\d)"),"$1")
                // Known label glyph confusions from real screenshots; amounts are never repaired.
                if(line.startsWith("付歆方式 ")) line=line.replaceFirst("付歆方式", "付款方式")
                if(line.startsWith("付款歆方式 ")) line=line.replaceFirst("付款歆方式", "付款方式")
                if(line.startsWith("支付万式 ")) line=line.replaceFirst("支付万式", "支付方式")
                if(line.startsWith("当前狀态 ")) line=line.replaceFirst("当前狀态","当前状态")
                if(line=="当前状态 已转入零线通") line="当前状态 已转入零钱通"
                // Observed WeChat receipt OCR: dropped heading separator and 待/侍.
                // Repair only the known heading/labelled status; never names, money or IDs.
                if(Regex("^转账转给.{1,45}$").matches(line)) line=line.replaceFirst("转账转给","转账-转给")
                if(Regex("^转账来自.{1,45}$").matches(line)) line=line.replaceFirst("转账来自","转账-来自")
                if(Regex("^微信红包(发给|来自).{1,45}$").matches(line)) line=line.replaceFirst("微信红包","微信红包-")
                if(Regex("^当前状态[ :：]+等侍对方确认收[钱款]$").matches(line))
                    line=line.replaceFirst("等侍对方","等待对方")
                if(line.startsWith("订单全额 ")) line=line.replaceFirst("订单全额", "订单金额")
                if(line.startsWith("收款方金称 ")) line=line.replaceFirst("收款方金称", "收款方全称")
                if(Regex("^[く〈‹< ]*账单详[情惰婧清](?:\\s+全[部都]账单)?$").matches(line)) line="账单详情"
                if(line=="交易详信") line="交易详情"
                if(Regex("^账单详情\\s+全部账单$").matches(line)) line="账单详情"
                // WeChat renders its two tabs on one baseline; OCR includes the small green icon.
                if(Regex("^(?:主页\\s+)?[oO〇◯○]?\\s*交易详情$").matches(line)) line="交易详情"
                // Leading back/column icons can be read as punctuation. Strip only before known fields.
                val undecorated=line.trimStart('|','丨','<','く','〈','‹',' ')
                if((labels+listOf("账单详情","交易详情")).any { undecorated==it || undecorated.startsWith("$it ") } ||
                    Regex("^共\\d+件$").matches(undecorated)) line=undecorated
                // OCR can split a date digit (2026-1 0-01). Join only a whole date
                // or labelled timestamp, with a valid calendar date; never monetary digits.
                splitDate.matchEntire(line)?.let {m->
                    val g=m.groupValues;val month=g[4].replace(Regex("[ \\t]"),"");val day=g[6].replace(Regex("[ \\t]"),"")
                    if(runCatching {java.time.LocalDate.of(g[2].toInt(),month.toInt(),day.toInt())}.isSuccess)
                        line=g[1]+g[2]+g[3]+month+g[5]+day+g[7]+g[8]
                }
                val label=labels.firstOrNull{line.startsWith(it)}
                if(label!=null && line.length>label.length && line[label.length] !in " :：") line=label+" "+line.substring(label.length)
                if(line.startsWith("付款方式 ") || line.startsWith("支付方式 "))
                    line=line.replace("账戶系额", "账户余额")
                line
            }.filter{it.isNotBlank()}
        val out=mutableListOf<String>();var i=0
        val date=Regex(".*\\d{4}[-/.年]\\s*\\d{1,2}[-/.月]\\s*\\d{1,2}日?$")
        val clock=Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
        while(i<lines.size) {
            if(date.matches(lines[i]) && clock.matches(lines.getOrNull(i+1).orEmpty())) {
                out+=lines[i]+" "+lines[i+1];i+=2
            } else {out+=lines[i];i++}
        }
        // A labelled transaction number can wrap onto a short numeric continuation.
        // Preserve the whole identity, rather than deduplicating on the truncated prefix.
        val idLabels=listOf("交易单号","转账单号","微信支付单号","支付宝交易号","商户单号")
        var j=1
        while(j<out.size) {
            val previous=out[j-1]
            val same=idLabels.firstOrNull { previous.startsWith("$it ") }
            val value=if(same!=null)previous.removePrefix(same).trim() else previous
            val labelled=same!=null || out.getOrNull(j-2) in idLabels
            if(labelled && Regex("^\\d{12,69}$").matches(value) && Regex("^\\d{1,11}$").matches(out[j]) && value.length+out[j].length<=80) {
                out[j-1]=previous+out.removeAt(j)
            } else j++
        }
        return out.joinToString("\n")
    }
    /** Align left/right fields by actual vertical overlap, not by the taller item's half-height. */
    fun rows(lines:List<Line>,fallback:String=""):String {
        if(lines.isEmpty())return normalize(fallback)
        val rows=mutableListOf<MutableList<Line>>()
        for(line in lines.filter{it.bottom>it.top && it.right>it.left}.sortedWith(compareBy<Line>{(it.top+it.bottom)/2}.thenBy{it.left})) {
            val row=rows.lastOrNull();val anchor=row?.firstOrNull()
            val overlap=if(anchor==null)0 else minOf(anchor.bottom,line.bottom)-maxOf(anchor.top,line.top)
            if(anchor!=null && overlap*2>=minOf(anchor.bottom-anchor.top,line.bottom-line.top) &&
                kotlin.math.abs((anchor.top+anchor.bottom)-(line.top+line.bottom))<=minOf(anchor.bottom-anchor.top,line.bottom-line.top))row+=line
            else rows+=mutableListOf(line)
        }
        return normalize(rows.joinToString("\n"){row->row.sortedBy{it.left}.joinToString(" "){it.text}})
    }
    private val validMoney=Regex("^[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?(?![\\d.,A-Za-z])")
    private val primary=Regex("(?:实付|实收|付款金额|支付金额|交易金额|消费金额)")
    fun parse(text:String,now:Long=System.currentTimeMillis()):LocalTextParser.LocalParseResult? {
        val normalized=normalize(text)
        val cleaned=mutableListOf<String>()
        for(line in normalized.lines()) {
            var invalid=false
            for(m in Regex("[¥￥]").findAll(line)) {
                val rest=line.substring(m.range.last+1).trimStart()
                val money=validMoney.find(rest)
                if(money==null || Regex("^\\s+\\d").containsMatchIn(rest.substring(money.range.last+1)))invalid=true
            }
            if(invalid && (primary.containsMatchIn(line) || line.trimStart().startsWith("¥") || line.trimStart().startsWith("￥")))return null
            if(!invalid)cleaned+=line
        }
        val parsed=LocalTextParser.parse(cleaned.joinToString("\n"),now) ?: return null
        // Detail pages show a store heading immediately above the isolated main amount.
        // Prefer that name to the legal company in lower metadata; do not infer brands from a similar name alone.
        val title=displayHeading(cleaned.joinToString("\n"),parsed.amountsMinor.firstOrNull() ?: return parsed)
        // A known OCR confusion is corrected only with an independent legal counterparty AND product.
        // Never rename a similarly spelled business without both pieces of evidence.
        val company=ScreenFields.valueNear(cleaned,"收款方全称",ScreenFields.orientation(cleaned))
        val heading=if(title in setOf("哈逻出行","哈罗出行","哈喽出行") &&
            company?.matches(Regex("上海哈[\\p{IsHan}]普惠科技有限公司"))==true &&
            kapi.core.auto.NoteComposer.productText(normalized)?.contains("助力车")==true) "哈啰出行" else title
        val qrRecipient=kapi.core.auto.WechatQrCounterparty.read(normalized)
        return if(qrRecipient!=null)parsed.copy(merchant=qrRecipient)
            else if(heading!=null)parsed.copy(merchant=heading) else parsed
    }

    /** Visible heading immediately above the main amount; native speech may say 支出2000元. */
    fun displayHeading(text:String,amountMinor:Long):String? {
        val cleaned=normalize(text).lines()
        val amountRow=Regex("^(?:支出|收入)?[+-]?[¥￥]?\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*元?$")
        val indices=cleaned.indices.filter { i -> amountRow.matchEntire(cleaned[i])?.groupValues?.get(1)?.let{Money.parse(it)?.minor}==amountMinor }
        if(indices.size!=1)return null
        return cleaned.getOrNull(indices.single()-1)?.trimEnd('>','〉','›','»',' ','　')?.takeIf {
            it.length in 2..50 && it.any{c->c.isLetter()} &&
                !kapi.core.auto.MerchantName.isInterfaceLabel(it) &&
                ScreenFields.FIELD_LABELS.none{label->it.contains(label)} &&
                listOf("账单","支付成功","付款成功","交易成功","收款成功","转账成功","扣款成功","消费成功","退款成功","加载","失败","等待","取消","关闭","处理中","交易详情","支付详情","订单详情","完成","收入","支出","优惠","余额","当前状态","人民币","金额","收支").none{word->it.contains(word)} &&
                !Regex("[¥￥:：]|https?://|\\d{4}[-/年]").containsMatchIn(it)
        }
    }
    /** Missing business fields and competing amounts merit a clearer pass, even when amount is usable. */
    fun needsRefinement(parsed:LocalTextParser.LocalParseResult?,text:String):Boolean {
        val best=parsed?.best ?: return true
        if(!best.isUsable || best.merchant.isNullOrBlank() || best.direction==Direction.UNKNOWN || parsed.amountsMinor.size>1)return true
        val evidence=TransactionTime.resolve(text,best.occurredAt,best.direction.name)
        return evidence.requiresReview || evidence.source=="READ_TIME"
    }
    fun quality(parsed:LocalTextParser.LocalParseResult?,text:String):Int {
        val best=parsed?.best ?: return 0
        if(!best.isUsable)return 0
        var score=10
        if(parsed.amountsMinor.size==1)score+=2
        if(best.direction!=Direction.UNKNOWN)score+=2
        if(!best.merchant.isNullOrBlank())score+=2
        val evidence=TransactionTime.resolve(text,best.occurredAt,best.direction.name)
        if(!evidence.requiresReview && evidence.source!="READ_TIME")score+=3
        return score
    }
}
