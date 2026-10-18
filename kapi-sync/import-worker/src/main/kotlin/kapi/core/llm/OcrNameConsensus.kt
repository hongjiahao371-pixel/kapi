package kapi.core.llm

import java.text.Normalizer

/** Select only a small, independently repeated pixel reading, never a dictionary guess. */
object OcrNameConsensus {
    private val peerPrefixes=listOf("微信红包-发给","微信红包-来自","转账-转给","转账-来自")
    private fun normalized(value:String):String {
        var text=Normalizer.normalize(value,Normalizer.Form.NFKC).replace(Regex("\\s+"),"")
            .trimEnd('>','〉','›','»')
        for(prefix in peerPrefixes) {
            val joined=prefix.replace("-","")
            if(text.startsWith(joined))text=prefix+text.removePrefix(joined)
        }
        return text
    }
    fun choose(original:String,readings:List<String>):String {
        val base=normalized(original)
        if(base.length !in 2..60 || base.contains('…') || base.contains("..."))return original
        val prefix=peerPrefixes.firstOrNull(base::startsWith)
        val candidates=readings.map(::normalized).filter {text->
            text.length in 2..60 && text.none {it in "\uFFFD?？¥￥:："} &&
                !text.contains("http",true) && text.any(Char::isLetter) &&
                peerPrefixes.firstOrNull(text::startsWith)==prefix &&
                Regex("\\d+").findAll(text).map{it.value}.toList()==Regex("\\d+").findAll(base).map{it.value}.toList() &&
                distance(base,text)<=if(base.length<=10)1 else 2
        }.groupingBy{it}.eachCount()
        val winner=candidates.entries.singleOrNull{it.value>=2}?.key ?: return original
        return if(winner==base)original else winner
    }
    private fun distance(a:String,b:String):Int {
        var prev=IntArray(b.length+1){it}
        for(i in a.indices) {
            val next=IntArray(b.length+1);next[0]=i+1
            for(j in b.indices)next[j+1]=minOf(next[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j])0 else 1)
            prev=next
        }
        return prev[b.length]
    }
}
