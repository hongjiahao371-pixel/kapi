package kapi.core.finance

import java.time.*
import java.math.BigDecimal
import java.math.RoundingMode

data class MerchantEntry(val entry: Entry, val merchant: String?)
data class MerchantTotal(val name: String, val minor: Long)
data class MonthlyInsight(val current: Totals, val previous: Totals, val lastYear: Totals, val merchants: List<MerchantTotal>, val count: Int)
object Analytics {
    fun period(entries: List<Entry>, from: LocalDate, to: LocalDate, zone: ZoneId): Totals {
        val a=from.atStartOfDay(zone).toInstant().toEpochMilli();val b=to.atStartOfDay(zone).toInstant().toEpochMilli()
        return Finance.totals(entries.filter { it.at>=a && it.at<b }, entries.associateBy { it.id })
    }
    fun month(entries: List<MerchantEntry>, month: YearMonth, today: LocalDate=LocalDate.now(), zone: ZoneId=ZoneId.systemDefault()): MonthlyInsight {
        val countDays=when { month==YearMonth.from(today) -> today.dayOfMonth; month>YearMonth.from(today) -> 0; else -> month.lengthOfMonth() }
        val raw=entries.map { it.entry };val start=month.atDay(1);val end=start.plusDays(countDays.toLong())
        fun other(m: YearMonth)=period(raw,m.atDay(1),m.atDay(1).plusDays(minOf(countDays,m.lengthOfMonth()).toLong()),zone)
        val a=start.atStartOfDay(zone).toInstant().toEpochMilli();val b=end.atStartOfDay(zone).toInstant().toEpochMilli();val originals=entries.associateBy { it.entry.id }
        val rawOriginals=originals.mapValues { it.value.entry }
        val byName=linkedMapOf<String,Long>();var n=0
        for (item in entries) {
            val e=item.entry;if(e.deleted || e.toAccount!=null || e.at<a || e.at>=b) continue
            n++
            val refund=Finance.isRefund(e, rawOriginals)
            if(e.direction!="EXPENSE" && !refund) continue
            val name=(if(refund) originals[e.refundOf]?.merchant else item.merchant)?.trim().orEmpty().ifBlank { "未填写商户" }
            byName[name]=Math.addExact(byName[name] ?: 0L, if(refund) -e.amount else e.amount)
        }
        return MonthlyInsight(period(raw,start,end,zone),other(month.minusMonths(1)),other(month.minusYears(1)),byName.filterValues { it>0 }.map { MerchantTotal(it.key,it.value) }.sortedWith(compareByDescending<MerchantTotal> { it.minor }.thenBy { it.name }),n)
    }
    fun year(entries: List<Entry>, year: Int, zone: ZoneId=ZoneId.systemDefault()): List<Totals> = (1..12).map { month ->
        val first=LocalDate.of(year,month,1);period(entries,first,first.plusMonths(1),zone)
    }
    fun percentage(current: Long, previous: Long): String? {
        if(previous<=0) return null
        val v=BigDecimal.valueOf(current).subtract(BigDecimal.valueOf(previous)).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(previous),1,RoundingMode.HALF_UP)
        return (if(v.signum()>0) "+" else "")+v.toPlainString()+"%"
    }
    fun annualCsv(entries: List<MerchantEntry>, year: Int, book: String, zone: ZoneId=ZoneId.systemDefault()): String {
        fun cell(s: String): String { val safe=if(s.firstOrNull() in listOf('=','+','-','@','\t','\r')) "'"+s else s;return "\""+safe.replace("\"","\"\"")+"\"" }
        fun yuan(v: Long)=BigDecimal.valueOf(v,2).toPlainString()
        val months=year(entries.map { it.entry },year,zone)
        return buildString {
            append("\uFEFF归账年度报告,").append(cell(book)).append(',').append(year).append("\r\n")
            append("月份,收入（元）,净支出（元）,结余（元）,已到账退款（元）\r\n")
            months.forEachIndexed { i,t -> append("${i+1},${yuan(t.income)},${yuan(t.expense)},${yuan(t.balance)},${yuan(t.refunded)}\r\n") }
            val total=period(entries.map { it.entry },LocalDate.of(year,1,1),LocalDate.of(year+1,1,1),zone)
            append("全年,${yuan(total.income)},${yuan(total.expense)},${yuan(total.balance)},${yuan(total.refunded)}\r\n")
            append("\r\n说明,转账不计收支；关联退款抵减到账期间的支出；无关联的退款按收入统计。\r\n")
        }
    }
}
