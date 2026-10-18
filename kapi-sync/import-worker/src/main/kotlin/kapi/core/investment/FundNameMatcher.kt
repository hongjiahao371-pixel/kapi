package kapi.core.investment

import kotlinx.serialization.Serializable
import java.util.Locale

/** Exact names may fill a code; edit distance and abbreviations only suggest human-reviewed choices. */
class FundNameMatcher(entries:List<Entry>) {
    @Serializable data class Entry(val code:String,val name:String,val type:String="")
    @Serializable data class Catalog(val source:String,val updatedAt:String,val entries:List<Entry>)
    data class Choice(val fund:Entry,val score:Int)
    data class Result(val automatic:Entry?,val choices:List<Choice>,val message:String)
    private data class Indexed(val fund:Entry,val exact:String,val rank:String,val share:String?)
    private val indexed=entries.filter {it.code.matches(Regex("\\d{6}")) && it.name.length in 4..120}
        .distinctBy {it.code}.map {Indexed(it,normalize(it.name),rankName(it.name),share(it.name))}

    fun match(name:String,code:String?=null):Result {
        val key=normalize(name);val truncated=Regex("\\.{2,}|…").containsMatchIn(name)
        if(key.length !in 5..100) return Result(null,emptyList(),"名称信息不足，请补充名称或手动填写代码")
        val share=share(name)
        val exact=indexed.filter {it.exact==key}
        if(!truncated && exact.size==1 && (code.isNullOrBlank() || code==exact.single().fund.code))
            return Result(exact.single().fund,listOf(Choice(exact.single().fund,100)),"完整名称唯一匹配")
        val rank=rankName(name)
        if(rank.length<5) return Result(null,emptyList(),"名称信息不足，请补充名称或手动填写代码")
        val ranked=indexed.asSequence().filter {share==null || it.share==share}
            .filter { !key.contains("QDII") || it.exact.contains("QDII") }
            .filter { distance(rank.take(4),it.rank.take(4))<=2 }
            .mapNotNull { candidate ->
                val other=candidate.rank
                val score=if(truncated && other.startsWith(rank)) 97 else {
                    val compared=if(truncated) other.take(rank.length) else other
                    100 - distance(rank,compared)*100/maxOf(rank.length,compared.length)
                }
                if(score<55) null else Choice(candidate.fund,score)
            }.sortedWith(compareByDescending<Choice>{it.score}.thenBy{it.fund.code}).take(5).toList()
        val choices=ranked.filter {it.score>=maxOf(60,(ranked.firstOrNull()?.score ?: 0)-12)}
        val message=when {
            !code.isNullOrBlank() && indexed.any {it.fund.code==code} -> "截图已有代码，请核对名称与代码是否对应"
            choices.isEmpty() -> "没有找到可靠候选，请修改名称重试或手动填写代码"
            truncated -> "名称被截断，份额类别可能缺失，请选择确认"
            share==null -> "未识别到份额类别，请确认 A/C 等类别"
            else -> "名称近似匹配，请确认后使用"
        }
        return Result(null,choices,message)
    }

    companion object {
        private val glyphs=mapOf('贏' to '赢','數' to '数','聯' to '联','債' to '债','華' to '华','證' to '证','國' to '国','發' to '发','產' to '产','業' to '业','創' to '创','長' to '长','質' to '质','選' to '选','優' to '优','價' to '价','幣' to '币','後' to '后','資' to '资','強' to '强')
        fun normalize(raw:String)=raw.map {glyphs[it] ?: if(it in 'Ａ'..'Ｚ') ('A'.code+it.code-'Ａ'.code).toChar() else it}
            .joinToString("").uppercase(Locale.ROOT).replace(Regex("[\\s（）()·・._…\\-]"),"")
        private fun share(s:String):String? {
            val key=normalize(s)
            if(listOf("QDII","ETF","LOF").any {key.endsWith(it)})return null
            return Regex("([A-EIHRY])(?:类|類)?$").find(key)?.groupValues?.get(1)
        }
        // Ranking simplifications NEVER qualify for automatic selection.
        private fun rankName(s:String)=normalize(s).replace(Regex("发起式|发起|上证|中证|主题|科创板|混合|股票")) {
            if(it.value=="科创板") "科创" else ""
        }
        private fun distance(a:String,b:String):Int {
            var prev=IntArray(b.length+1){it};var next=IntArray(b.length+1)
            for(i in a.indices) {
                next[0]=i+1
                for(j in b.indices) next[j+1]=minOf(next[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j]) 0 else 1)
                val swap=prev;prev=next;next=swap
            }
            return prev[b.length]
        }
    }
}
