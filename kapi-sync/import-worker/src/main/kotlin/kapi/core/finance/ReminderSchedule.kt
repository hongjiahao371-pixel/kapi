package kapi.core.finance

import java.time.LocalDate
import java.time.YearMonth

object ReminderSchedule {
    fun next(date: LocalDate, frequency: String, anchorDay: Int, today: LocalDate): LocalDate {
        if (frequency == "ONCE") return date
        require(frequency in setOf("MONTHLY","YEARLY") && anchorDay in 1..31)
        var result=date
        var count=0
        do {
            val month=YearMonth.from(result).let { if(frequency=="MONTHLY") it.plusMonths(1) else it.plusYears(1) }
            result=month.atDay(minOf(anchorDay,month.lengthOfMonth()))
            check(++count<=2400) { "提醒日期跨度过大" }
        } while(result<=today)
        return result
    }
}
