package kapi.core.auto

import kapi.core.llm.LocalTextParser
import java.text.Normalizer
import java.util.UUID

/** One processing lifecycle for a receipt visit. Recognition text is evidence, not its lifecycle ID. */
class ReceiptSession(private val nextId:()->String = { UUID.randomUUID().toString() }) {
    enum class Phase { UNREAD, CAPTURING, PENDING, EDITING, CONFIRMED, DISMISSED }
    data class Snapshot(val id:String,val phase:Phase,val draftId:String?,val txnId:String?)
    private data class Visit(val id:String,val scope:String,val financial:String,
        var provider:String?,var nativeAliases:Set<String>,var ocrAliases:Set<String>,
        var paidAt:Long?=null,var timeSpan:Long=Long.MAX_VALUE,
        var phase:Phase=Phase.UNREAD,var draftId:String?=null,var txnId:String?=null)
    private var current:Visit?=null

    @Synchronized fun detail(pkg:String,windowId:Int,text:String,now:Long,screenshot:Boolean):String? {
        if(pkg !in WalletOcrPolicy.packages || windowId<0 || PaymentGate.screenVerdict(text,pkg)!=PaymentGate.Verdict.LooksLikePayment)return null
        val parsed=LocalTextParser.parse(text,now) ?: return null
        val amount=parsed.best?.amountMinor ?: return null
        val time=TransactionTime.resolve(text,now,parsed.direction.name)
        if(amount<=0)return null
        val actual=time.source in setOf("PAYMENT","REFUND") && !time.malformed && time.precision in setOf("SECOND","MINUTE","DATE")
        // A genuine detail can need time review. Its reading clock must never become
        // a receipt identity; later paid-time metadata enriches the same visit.
        if(!actual && !PaymentPageIdentity.isDetail(text))return null
        val paidAt=time.selectedAt.takeIf {actual}
        val span=when(time.precision) {"SECOND"->1000L;"MINUTE"->60000L;"DATE"->86400000L;else->Long.MAX_VALUE}
        val scope="$pkg/$windowId";val financial="$amount/${parsed.direction}"
        val provider=PaymentId.importIdentity(text)
        val lines=ScreenFields.collapse(text.lines());val order=ScreenFields.orientation(lines)
        fun normal(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKC).replace(Regex("\\s+"),"").trim('|','丨','>','〉','›','»')
        val names=(listOf("商户全称","收款方全称","商户名称","商家名称").flatMap { ScreenFields.valuesNear(lines,it,order) }+
            listOfNotNull(parsed.merchant)).map(::normal).filter(String::isNotBlank).toSet()
        val old=current
        val changed=old==null || old.scope!=scope || old.financial!=financial ||
            (provider!=null && old.provider!=null && provider!=old.provider) ||
            (paidAt!=null && old.paidAt!=null && (paidAt>=old.paidAt!!+old.timeSpan || old.paidAt!!>=paidAt+span)) ||
            (old.phase==Phase.UNREAD && !screenshot && old.nativeAliases.isNotEmpty() && names.isNotEmpty() && old.nativeAliases.intersect(names).isEmpty())
        val visit=if(changed) Visit(nextId(),scope,financial,provider,emptySet(),emptySet()).also { current=it } else requireNotNull(old)
        if(provider!=null)visit.provider=provider
        if(paidAt!=null && (visit.paidAt==null || span<visit.timeSpan)) {visit.paidAt=paidAt;visit.timeSpan=span}
        if(screenshot)visit.ocrAliases=visit.ocrAliases+names else visit.nativeAliases=visit.nativeAliases+names
        return visit.id
    }

    /** Native cashier adapters already supply an opaque visit that distinguishes identical new payments. */
    @Synchronized fun opaque(pkg:String,key:String):String {
        val scope="opaque/$pkg/$key"
        if(current?.scope!=scope)current=Visit(nextId(),scope,"",null,emptySet(),emptySet())
        return requireNotNull(current).id
    }
    @Synchronized fun claim(id:String):Boolean {
        val v=current?.takeIf { it.id==id && it.phase==Phase.UNREAD } ?: return false
        v.phase=Phase.CAPTURING;return true
    }
    @Synchronized fun pending(id:String,draftId:String):Boolean {
        val v=current?.takeIf { it.id==id && it.phase==Phase.CAPTURING } ?: return false
        v.draftId=draftId;v.phase=Phase.PENDING;return true
    }
    @Synchronized fun editing(draftId:String) {current?.takeIf {it.draftId==draftId && it.phase==Phase.PENDING}?.phase=Phase.EDITING}
    @Synchronized fun confirmed(draftId:String,txnId:String) {
        current?.takeIf {it.draftId==draftId}?.let {it.phase=Phase.CONFIRMED;it.txnId=txnId}
    }
    @Synchronized fun existing(id:String,txnId:String) {current?.takeIf {it.id==id}?.let {it.phase=Phase.CONFIRMED;it.txnId=txnId}}
    @Synchronized fun dismissed(draftId:String) {current?.takeIf {it.draftId==draftId}?.phase=Phase.DISMISSED}
    @Synchronized fun isCurrent(id:String)=current?.id==id
    @Synchronized fun snapshot():Snapshot?=current?.let {Snapshot(it.id,it.phase,it.draftId,it.txnId)}
    @Synchronized fun leave() {current=null}
}
