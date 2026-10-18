package kapi.core.auto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Read-only decisions: this engine never writes a transaction or changes a previous category. */
object CategoryIntelligence {
    @Serializable data class Condition(val field:String, val op:String, val value:String)
    @Serializable data class Combination(val version:Int=1, val conditions:List<Condition>)
    data class Input(val merchant:String?,val text:String?,val direction:String,val accountId:String?=null)
    data class Rule(val id:String,val kind:String,val pattern:String,val categoryId:String,val direction:String)
    data class Candidate(val categoryId:String,val reason:String)
    data class Decision(val categoryId:String?,val source:String,val reason:String,val candidates:List<Candidate>,val needsReview:Boolean=false)
    data class Sample(val id:String,val merchant:String,val categoryId:String,val direction:String,
        val occurredAt:Long,val amountMinor:Long,val identity:String?=null)
    data class Learning(val categoryId:String?,val source:String,val reason:String,val candidates:List<String>,val disputed:Boolean=false,val sampleCount:Int=0)
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    fun decode(pattern:String):Combination? = runCatching {json.decodeFromString<Combination>(pattern)}.getOrNull()?.takeIf(::valid)
    fun encode(conditions:List<Condition>):String {
        val combination=Combination(conditions=conditions);require(valid(combination)) {"请设置有效的组合条件"}
        return json.encodeToString(Combination.serializer(),combination)
    }
    private fun valid(c:Combination):Boolean = c.version==1 && c.conditions.size in 1..4 &&
        c.conditions.map {it.field}.distinct().size==c.conditions.size && c.conditions.any {it.field!="DIRECTION"} && c.conditions.all {
            it.value.isNotBlank() && it.value.length<=120 && when(it.field) {
                "MERCHANT" -> it.op in setOf("EXACT","CONTAINS")
                "PRODUCT" -> it.op=="CONTAINS"
                "ACCOUNT" -> it.op=="EXACT"
                "DIRECTION" -> it.op=="EXACT" && it.value in setOf("EXPENSE","INCOME")
                else -> false
            }
        }
    fun conditions(rule:Rule):List<Condition>? = when(rule.kind) {
        "MERCHANT" -> listOf(Condition("MERCHANT","CONTAINS",rule.pattern))
        "MERCHANT_EXACT" -> listOf(Condition("MERCHANT","EXACT",rule.pattern))
        "KEYWORD" -> listOf(Condition("PRODUCT","CONTAINS",rule.pattern))
        "COMPOSITE" -> decode(rule.pattern)?.conditions
        else -> null
    }?.takeIf {it.all {c->c.value.isNotBlank()}}
    fun matches(rule:Rule,input:Input):Boolean = rule.direction==input.direction && conditions(rule)?.all { c ->
        val value=when(c.field) {
            "MERCHANT" -> MerchantLearning.normalize(input.merchant).orEmpty()
            "PRODUCT" -> NoteComposer.productText(input.text.orEmpty()) ?: CategoryRules.classificationText(input.text)
            "ACCOUNT" -> input.accountId.orEmpty()
            "DIRECTION" -> input.direction
            else -> ""
        }
        value.isNotEmpty() && if(c.op=="EXACT") value.equals(c.value.trim(),ignoreCase=c.field=="MERCHANT") else value.contains(c.value.trim(),ignoreCase=true)
    }==true
    private fun specificity(rule:Rule):List<Int> {
        val c=conditions(rule).orEmpty().filter {it.field!="DIRECTION"}
        return listOf(c.size,c.count {it.op=="EXACT"},c.sumOf {it.value.length})
    }
    private fun label(rule:Rule):String = if(rule.kind=="COMPOSITE") "你的组合规则" else if(rule.kind=="MERCHANT_EXACT") "你的精确商户规则" else "你的分类规则"
    fun decide(input:Input,rules:List<Rule>,learning:Learning?,builtin:String?,alternatives:List<String>):Decision {
        if(input.direction !in setOf("EXPENSE","INCOME")) return Decision(null,"UNCLASSIFIED","收支类型需要核对",emptyList(),true)
        val ranked=rules.filter {matches(it,input)}.sortedWith(compareByDescending<Rule>{specificity(it)[0]}
            .thenByDescending {specificity(it)[1]}.thenByDescending {specificity(it)[2]}.thenBy {it.id})
        val first=ranked.firstOrNull()
        if(first!=null) {
            val tied=ranked.takeWhile {specificity(it)==specificity(first)}.distinctBy {it.categoryId}
            if(tied.size>1) return Decision(null,"RULE_CONFLICT","多条同等具体的规则指向不同分类，请核对",tied.take(3).map {Candidate(it.categoryId,label(it))},true)
            return Decision(first.categoryId,"USER_RULE",label(first),listOf(Candidate(first.categoryId,label(first))))
        }
        val options=(learning?.candidates.orEmpty().map {Candidate(it,"你的人工归类记录")}+
            listOfNotNull(builtin?.let {Candidate(it,"商品与商户信息")})+alternatives.map {Candidate(it,"商品内容候选")}).distinctBy {it.categoryId}.take(3)
        if(learning?.disputed==true) return Decision(null,"LEARNING_REVIEW",learning.reason,options,true)
        if(learning?.categoryId!=null) return Decision(learning.categoryId,learning.source,learning.reason,
            (listOf(Candidate(learning.categoryId,learning.reason))+options).distinctBy {it.categoryId}.take(3))
        if(builtin!=null) return Decision(builtin,"BUILTIN","依据商品与商户信息",(listOf(Candidate(builtin,"商品与商户信息"))+options).distinctBy {it.categoryId}.take(3))
        return Decision(null,"UNCLASSIFIED",if(options.isEmpty()) "用途信息不足，请选择分类" else "用途不够明确，请核对候选分类",options,options.isNotEmpty())
    }
    fun learn(merchant:String?,direction:String,samples:List<Sample>):Learning? {
        val key=MerchantLearning.key(merchant) ?: return null
        val eligible=samples.filter {it.direction==direction && MerchantLearning.key(it.merchant)==key && it.categoryId.isNotBlank() && it.occurredAt>0 && it.amountMinor>0}
        // Duplicate payments cannot cast multiple votes. Conflicting duplicate categories are excluded.
        val unique=eligible.groupBy {it.identity?.takeIf(String::isNotBlank) ?: "${it.amountMinor}:${it.occurredAt}"}
            .values.filter {rows->rows.map {it.categoryId}.distinct().size==1}.map {rows->rows.minBy {it.id}}
            .sortedWith(compareByDescending<Sample>{it.occurredAt}.thenBy {it.id})
        if(unique.isEmpty()) return null
        val counts=unique.groupingBy {it.categoryId}.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String,Int>>{it.value}.thenBy {it.key})
        val candidates=(unique.take(3).map {it.categoryId}+counts.map {it.key}).distinct().take(3)
        val recent=unique.take(3)
        if(recent.size==3 && recent.map {it.categoryId}.distinct().size==1)
            return Learning(recent.first().categoryId,"RECENT_LEARNING","最近三笔人工归类一致",candidates,sampleCount=unique.size)
        val historical=MerchantLearning.decide(counts.map {it.key to it.value})?.first
        if(historical!=null && unique.first().categoryId!=historical)
            return Learning(null,"LEARNING_REVIEW","近期人工归类发生变化，请核对",candidates,true,unique.size)
        return Learning(historical,"HISTORY_LEARNING",if(historical==null) "人工归类还未形成稳定习惯" else "依据你的人工归类习惯",candidates,sampleCount=unique.size)
    }
}
