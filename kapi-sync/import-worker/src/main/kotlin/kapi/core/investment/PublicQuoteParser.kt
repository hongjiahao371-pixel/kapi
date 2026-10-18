package kapi.core.investment

import kotlinx.serialization.json.*
import java.time.*

/** Read public data only; downloaded JavaScript is never executed. */
object PublicQuoteParser {
    private val json=Json { ignoreUnknownKeys=true }
    val zone:ZoneId=ZoneId.of("Asia/Shanghai")
    fun validSymbol(symbol:String)=symbol.matches(Regex("^(FUND|SH|SZ):[0-9]{6}$"))
    fun validate(q:Quote,now:Long=System.currentTimeMillis()):Quote {
        require(validSymbol(q.symbol)) { "行情代码格式无效" }
        require(q.price?.matches(Regex("[0-9]{1,10}(?:\\.[0-9]{1,8})?"))==true && Portfolio.quantity(q.price!!).signum()>0) { "行情价格格式无效" }
        val date=runCatching { LocalDate.parse(q.date) }.getOrNull()
        require(date!=null && date.toString()==q.date && date<=Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) { "行情日期无效" }
        return q
    }
    fun fresh(q:Quote,now:Long=System.currentTimeMillis()):Boolean =
        q.source!="手动报价" && q.error.isNullOrBlank() && q.fetchedAt>0 && now>=q.fetchedAt && now-q.fetchedAt<15*60*1000 && runCatching {validate(q,now)}.isSuccess
    fun retain(old:Quote?,new:Quote,now:Long=System.currentTimeMillis()):Quote {
        if(new.price==null || new.date==null) return old?.copy(error=new.error ?: "更新失败，保留已有报价") ?: new
        validate(new,now)
        return if(old?.date!=null && new.date<old.date) old.copy(error="行情日期早于已保存报价，保留旧报价") else new
    }
    fun fund(symbol:String,body:String,now:Long=System.currentTimeMillis()):Quote {
        require(validSymbol(symbol) && symbol.startsWith("FUND:")) { "基金代码格式无效" }
        val raw=Regex("(?s)\\bvar\\s+Data_netWorthTrend\\s*=\\s*(\\[.*?])\\s*;").find(body)?.groupValues?.get(1) ?: error("净值字段缺失")
        val rows=json.parseToJsonElement(raw).jsonArray
        val latest=rows.maxByOrNull { it.jsonObject["x"]!!.jsonPrimitive.long }?.jsonObject ?: error("暂无净值")
        val price=latest["y"]!!.jsonPrimitive.content
        val date=Instant.ofEpochMilli(latest["x"]!!.jsonPrimitive.long).atZone(zone).toLocalDate().toString()
        return validate(Quote(symbol,price,date,"天天基金公开净值",now),now)
    }
    private fun closed(date:String,now:Long):Boolean {
        val d=runCatching {LocalDate.parse(date)}.getOrNull() ?: return false
        val time=Instant.ofEpochMilli(now).atZone(zone)
        return d<time.toLocalDate() || (d==time.toLocalDate() && time.toLocalTime()>=LocalTime.of(15,15))
    }
    fun stock(symbol:String,body:String,tencent:Boolean=false,now:Long=System.currentTimeMillis()):Quote {
        require(validSymbol(symbol) && !symbol.startsWith("FUND:")) { "股票代码格式无效" }
        val root=json.parseToJsonElement(body).jsonObject
        val rows:List<List<String>> = if(tencent) {
            require(root["code"]?.jsonPrimitive?.int==0) { "股票行情格式异常" }
            val key=symbol.replace(":", "").lowercase()
            // Only unadjusted rows; qfqday/hfqday would distort portfolio value.
            root["data"]?.jsonObject?.get(key)?.jsonObject?.get("day")?.jsonArray?.map { row->row.jsonArray.map {it.jsonPrimitive.content} } ?: emptyList()
        } else {
            require(root["rc"]?.jsonPrimitive?.int==0) { "股票行情格式异常" }
            root["data"]?.takeUnless {it is JsonNull}?.jsonObject?.get("klines")?.jsonArray?.map {it.jsonPrimitive.content.split(',')} ?: emptyList()
        }
        val latest=rows.filter {it.size>=3 && closed(it[0],now)}.maxByOrNull {it[0]} ?: error("暂无已收盘的不复权股票价格")
        return validate(Quote(symbol,latest[2],latest[0],if(tencent) "腾讯证券公开收盘价" else "东方财富公开收盘价",now),now)
    }
}
