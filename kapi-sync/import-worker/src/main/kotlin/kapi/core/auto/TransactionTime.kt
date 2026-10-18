package kapi.core.auto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.*

/** Business timestamps and the reading clock are separate evidence, never merge ordering clocks. */
object TransactionTime {
    @Serializable data class Evidence(
        val selectedAt:Long, val source:String, val precision:String = "SECOND",
        val paidAt:Long?=null, val createdAt:Long?=null, val refundAt:Long?=null,
        val capturedAt:Long?=null, val malformed:Boolean=false,
    ) {
        val requiresReview get() = malformed || precision in setOf("READ_TIME","CLOCK","DATE")
        fun aliases(direction:String):List<Long> = if(requiresReview) emptyList() else if(direction=="INCOME") listOfNotNull(refundAt)
            else listOfNotNull(paidAt,createdAt)
    }
    private val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    fun encode(e:Evidence)=json.encodeToString(Evidence.serializer(),e)
    fun decode(s:String?):Evidence?=s?.takeIf { it.length<=4096 }?.let { runCatching { json.decodeFromString(Evidence.serializer(),it) }.getOrNull() }
    private val full=Regex("""(?<!\d)(\d{4})[-/.年]\s*(\d{1,2})[-/.月]\s*(\d{1,2})日?(?!\d)""")
    private val partial=Regex("""(?<!\d)(\d{1,2})[-/月]\s*(\d{1,2})日?(?!\d)""")
    private val clock=Regex("""(?<!\d)(\d{1,2})[:：](\d{2})(?:[:：](\d{2}))?(?!\d)""")
    private data class Parsed(val at:Long,val precision:String)
    private fun parse(value:String,now:Long,zone:ZoneId):Parsed? = runCatching {
        runCatching { Instant.parse(value.trim()) }.getOrNull()?.let { return Parsed(it.toEpochMilli(),"SECOND") }
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val f=full.find(value);val p=if(f==null) partial.find(value) else null
        var date=when {
            f!=null -> LocalDate.of(f.groupValues[1].toInt(),f.groupValues[2].toInt(),f.groupValues[3].toInt())
            value.contains("前天") -> today.minusDays(2)
            value.contains("昨天") || value.contains("昨日") -> today.minusDays(1)
            value.contains("今天") || value.contains("今日") -> today
            p!=null -> LocalDate.of(today.year,p.groupValues[1].toInt(),p.groupValues[2].toInt()).let { if(it>today.plusDays(1)) it.minusYears(1) else it }
            else -> null
        }
        // Time must belong to this date value; never take a clock from the preceding field.
        val c=clock.find(if(f!=null) value.substring(f.range.last+1).take(24) else if(p!=null) value.substring(p.range.last+1).take(24) else value)
        if(date==null && c==null) return null
        if(c==null) return Parsed(requireNotNull(date).atStartOfDay(zone).toInstant().toEpochMilli(),"DATE")
        val precision=if(date==null) "CLOCK" else if(c.groupValues[3].isEmpty()) "MINUTE" else "SECOND"
        if(date==null) date=today
        val time=LocalTime.of(c.groupValues[1].toInt(),c.groupValues[2].toInt(),c.groupValues[3].toIntOrNull() ?: 0)
        Parsed(date.atTime(time).atZone(zone).toInstant().toEpochMilli(),precision)
    }.getOrNull()

    fun resolve(text:String,now:Long,direction:String="EXPENSE",zone:ZoneId=ZoneId.systemDefault()):Evidence {
        val lines=ScreenFields.collapse(text.lines())
        val paid=listOf("支付时间","付款时间","扣款时间","交易时间","转账时间","领取时间","发送时间")
        val received=listOf("退款成功时间","退款时间","到账时间","收款时间")
        val created=listOf("交易创建时间","创建时间","下单时间")
        val labels=paid+received+created
        // Resolve ambiguous neighbours by the whole page's layout, rather than one date line.
        var before=0;var after=0;var firstOrientation:ScreenFields.Orientation?=null
        for(i in lines.indices) if(lines[i] in labels) {
            val prev=lines.getOrNull(i-1)?.let { parse(it,now,zone) }!=null
            val next=lines.getOrNull(i+1)?.let { parse(it,now,zone) }!=null
            if(prev) before++;if(next) after++
            if(firstOrientation==null && prev!=next) firstOrientation=if(prev) ScreenFields.Orientation.VALUE_BEFORE_LABEL else ScreenFields.Orientation.VALUE_AFTER_LABEL
        }
        val orientation=when {after>before->ScreenFields.Orientation.VALUE_AFTER_LABEL;before>after->ScreenFields.Orientation.VALUE_BEFORE_LABEL;else->firstOrientation}
        var malformed=false
        fun field(names:List<String>):Parsed? {
            for(label in names) {
                val present=lines.any { it==label || it.startsWith("$label：") || it.startsWith("$label:") || it.startsWith("$label ") }
                if(!present) continue
                for(i in lines.indices) {
                    val line=lines[i]
                    if(line.startsWith("$label：") || line.startsWith("$label:") || line.startsWith("$label ")) {
                        parse(line.drop(label.length+1).trim(),now,zone)?.let {return it};continue
                    }
                    if(line!=label) continue
                    val offsets=when(orientation) {ScreenFields.Orientation.VALUE_BEFORE_LABEL->listOf(-1);ScreenFields.Orientation.VALUE_AFTER_LABEL->listOf(1);null->listOf(-1,1)}
                    val options=offsets.mapNotNull { lines.getOrNull(i+it)?.let { s->parse(s,now,zone) } }.distinct()
                    if(options.size==1) return options.single()
                }
                malformed=true
            }
            return null
        }
        val pay=field(paid);val refund=field(received);val creation=field(created)
        val chosen=if(direction=="INCOME") listOf(refund to "REFUND",pay to "PAYMENT",creation to "CREATION") else listOf(pay to "PAYMENT",creation to "CREATION")
        chosen.firstOrNull { it.first!=null }?.let { (v,source) -> return Evidence(v!!.at,source,v.precision,pay?.at,creation?.at,refund?.at,now,malformed) }
        val unlabeled=if(labels.none { l -> lines.any { it==l || it.startsWith("$l：") || it.startsWith("$l:") } } && full.findAll(text).count()<=1) parse(text,now,zone) else null
        return if(unlabeled!=null) Evidence(unlabeled.at,"TEXT",unlabeled.precision,capturedAt=now,malformed=malformed)
            else Evidence(now/1000*1000,"READ_TIME","READ_TIME",capturedAt=now,malformed=malformed)
    }
    fun timestamp(value:String,zone:ZoneId=ZoneId.systemDefault()):Long? =
        if(full.containsMatchIn(value) && clock.find(value)?.groupValues?.get(3)?.isNotEmpty()==true)
            parse(value,0,zone)?.at else runCatching { Instant.parse(value.trim()).toEpochMilli() }.getOrNull()
    fun historyNeedsReview(text:String,direction:String,now:Long):Boolean {
        val e=resolve(text,now,direction)
        return (PaymentPageIdentity.isDetail(text) || text.contains("自动扣款成功")) && (e.requiresReview || e.source=="TEXT")
    }
}
