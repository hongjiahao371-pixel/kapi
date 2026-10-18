package kapi.core.investment

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.*

@Serializable data class InvestmentTrade(
    val id:String,
    @SerialName("account_id") val accountId:String,
    @SerialName("instrument_code") val instrumentCode:String,
    val name:String,
    /** FUND / SH / SZ; additional markets require an adapter and explicit currency. */
    val market:String,
    val currency:String="CNY",
    val action:String,
    /** Decimal text, never Float/Double. Quantity for SPLIT is the number of additional units. */
    val quantity:String,
    @SerialName("cash_minor") val cashMinor:Long,
    @SerialName("fee_minor") val feeMinor:Long=0,
    @SerialName("occurred_at") val occurredAt:Long,
    @SerialName("cost_note") val costNote:String="",
)
@Serializable data class Quote(
    val symbol:String,
    val price:String?=null,
    val date:String?=null,
    val source:String="",
    @SerialName("fetched_at") val fetchedAt:Long=0,
    val error:String?=null,
)
object Portfolio {
    data class Position(val symbol:String,val name:String,val currency:String,val quantity:String,val costMinor:Long,val realizedMinor:Long,val cashDeltaMinor:Long,val valueMinor:Long?,val unrealizedMinor:Long?,val quote:Quote?)
    fun quantity(raw:String):BigDecimal {
        require(raw.matches(Regex("[0-9]+(?:\\.[0-9]{1,8})?"))) { "份额最多保留八位小数" }
        return BigDecimal(raw).also { require(it>=BigDecimal.ZERO && it<=BigDecimal("99999999999")) { "份额超出范围" } }
    }
    fun validate(t:InvestmentTrade) {
        require(t.accountId.isNotBlank() && t.id.isNotBlank()) { "账户或交易标识为空" }
        require(t.instrumentCode.matches(Regex("[0-9]{6}")) && t.market in setOf("FUND","SH","SZ")) { "请填写六位基金或股票代码" }
        require(t.currency=="CNY" && t.name.isNotBlank()) { "首版持仓支持人民币资产，请填写名称" }
        require(t.action in setOf("BUY","SELL","DIVIDEND","SPLIT","OPENING")) { "投资交易类型无效" }
        val q=quantity(t.quantity)
        require(t.cashMinor in 0..99_999_999_999L && t.feeMinor in 0..99_999_999_999L && t.occurredAt>0) { "金额或日期无效" }
        when(t.action) {
            "OPENING" -> require(q>BigDecimal.ZERO && t.feeMinor==0L) { "期初持仓需要实际份额和经核对的成本，手续费已包含在成本中" }
            "BUY","SELL" -> require(q>BigDecimal.ZERO && t.cashMinor>0 && (t.action!="SELL" || t.feeMinor<=t.cashMinor)) { "买卖需填写份额及成交金额，卖出手续费不能超过成交金额" }
            "DIVIDEND" -> require(q==BigDecimal.ZERO && t.cashMinor>0 && t.feeMinor<=t.cashMinor) { "现金分红无需份额" }
            "SPLIT" -> require(q>BigDecimal.ZERO && t.cashMinor==0L && t.feeMinor==0L) { "拆分填写增加的份额，金额为零" }
        }
    }
    fun positions(trades:List<InvestmentTrade>,quotes:Map<String,Quote> = emptyMap()):List<Position> {
        require(trades.map { it.id }.distinct().size==trades.size) { "投资交易标识重复" }
        trades.forEach(::validate)
        return trades.groupBy { "${it.market}:${it.instrumentCode}" }.toSortedMap().map { (symbol,events) ->
            val ordered=events.sortedWith(compareBy<InvestmentTrade> { it.occurredAt }.thenBy { it.id })
            if(events.any { it.action=="OPENING" }) require(events.count { it.action=="OPENING" }==1 && ordered.first().action=="OPENING") { "期初持仓只能在该资产的交易记录之前导入一次" }
            var q=BigDecimal.ZERO;var cost=0L;var realized=0L;var cash=0L
            ordered.forEach { t ->
                val units=quantity(t.quantity)
                when(t.action) {
                    "OPENING" -> { q=units;cost=t.cashMinor }
                    "BUY" -> { q=q.add(units);val paid=Math.addExact(t.cashMinor,t.feeMinor);cost=Math.addExact(cost,paid);cash=Math.subtractExact(cash,paid) }
                    "SELL" -> {
                        require(units<=q) { "${t.name} 卖出份额超过当时持仓" }
                        val allocated=if(units==q) cost else BigDecimal(cost).multiply(units).divide(q,0,RoundingMode.HALF_UP).longValueExact()
                        val received=Math.subtractExact(t.cashMinor,t.feeMinor);q=q.subtract(units);cost=Math.subtractExact(cost,allocated)
                        realized=Math.addExact(realized,Math.subtractExact(received,allocated));cash=Math.addExact(cash,received)
                    }
                    "DIVIDEND" -> { require(q>BigDecimal.ZERO) { "没有持仓时不能记录分红" };val received=t.cashMinor-t.feeMinor;cash=Math.addExact(cash,received);realized=Math.addExact(realized,received) }
                    "SPLIT" -> { require(q>BigDecimal.ZERO) { "没有持仓时不能增加拆分份额" };q=q.add(units) }
                }
                require(q<=BigDecimal("99999999999")) { "持仓份额超出范围" }
            }
            val quote=quotes[symbol]
            val value=quote?.price?.let { price -> quantity(price).multiply(q).movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact() }
            Position(symbol,events.last().name,events.last().currency,q.stripTrailingZeros().toPlainString(),cost,realized,cash,value,value?.let { Math.subtractExact(it,cost) },quote)
        }
    }
}

fun investmentEquity(cashBalanceMinor:Long,cashAsOf:Long,trades:List<InvestmentTrade>,quotes:Map<String,Quote>):Long? =
    investmentAccountValue(cashBalanceMinor,cashAsOf,trades,quotes).totalMinor


/** Cash and marked-to-market holdings stay distinct, including incomplete accounts. */
data class InvestmentAccountValue(
    val cashMinor:Long?,
    val knownHoldingsMinor:Long,
    val missingQuotes:Int,
    val totalMinor:Long?,
)
fun investmentAccountValue(cashBalanceMinor:Long?,cashAsOf:Long?,trades:List<InvestmentTrade>,quotes:Map<String,Quote>):InvestmentAccountValue {
    val positions=Portfolio.positions(trades,quotes)
    var holdings=0L;var missing=0
    for(p in positions.filter {Portfolio.quantity(it.quantity).signum()>0}) {
        p.valueMinor?.let {holdings=Math.addExact(holdings,it)} ?: run {missing++}
    }
    var cash=if(cashAsOf!=null) cashBalanceMinor else null
    if(cash!=null) for(t in trades.filter {it.occurredAt>=cashAsOf!!}) {
        val change=when(t.action) {"BUY" -> -Math.addExact(t.cashMinor,t.feeMinor);"SELL","DIVIDEND" -> Math.subtractExact(t.cashMinor,t.feeMinor);else -> 0L}
        cash=Math.addExact(cash!!,change)
    }
    return InvestmentAccountValue(cash,holdings,missing,if(cash!=null && missing==0) Math.addExact(cash!!,holdings) else null)
}
