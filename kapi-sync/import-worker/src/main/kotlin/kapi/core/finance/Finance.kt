package kapi.core.finance

/** Business accounting, independent of Room and UI. Amounts are always integral minor units. */
data class Entry(val id: String, val amount: Long, val direction: String, val account: String, val at: Long,
    val category: String? = null, val toAccount: String? = null, val refundOf: String? = null, val deleted: Boolean = false, val excludeFromBudget: Boolean = false)
data class Totals(val income: Long, val expense: Long, val refunded: Long) { val balance: Long get() = income - expense }

object Finance {
    fun isRefund(entry: Entry, originals: Map<String, Entry>): Boolean {
        val original = entry.refundOf?.let { originals[it] } ?: return false
        return !entry.deleted && entry.direction == "INCOME" && entry.toAccount == null &&
            !original.deleted && original.direction == "EXPENSE" && original.toAccount == null
    }
    fun totals(entries: List<Entry>, originals: Map<String, Entry> = entries.associateBy { it.id }): Totals {
        var income=0L; var expense=0L; var refunded=0L
        for (e in entries) {
            if (e.deleted || e.toAccount != null) continue
            if (isRefund(e, originals)) { expense=Math.subtractExact(expense,e.amount); refunded=Math.addExact(refunded,e.amount) }
            else if (e.direction == "EXPENSE") expense=Math.addExact(expense,e.amount)
            else if (e.direction == "INCOME") income=Math.addExact(income,e.amount)
        }
        return Totals(income,expense,refunded)
    }
    fun byCategory(entries: List<Entry>, originals: Map<String, Entry> = entries.associateBy { it.id }): Map<String?,Long> {
        val result=linkedMapOf<String?,Long>()
        for (e in entries) {
            if (e.deleted || e.toAccount != null) continue
            val refund=isRefund(e, originals)
            if (e.direction != "EXPENSE" && !refund) continue
            val category=if (refund) originals[e.refundOf]?.category else e.category
            result[category]=Math.addExact(result[category] ?: 0L, if (refund) -e.amount else e.amount)
        }
        return result
    }
    fun budgetEntries(entries: List<Entry>, originals: Map<String, Entry> = entries.associateBy { it.id }) =
        entries.filter { !it.excludeFromBudget && !(isRefund(it, originals) && originals[it.refundOf]?.excludeFromBudget==true) }

    /** Exactly the rows contributing to a category's net expense, including related refunds. */
    fun categoryEntries(entries: List<Entry>, category: String?, originals: Map<String, Entry> = entries.associateBy { it.id }) =
        entries.filter { e ->
            !e.deleted && e.toAccount==null && if(isRefund(e, originals)) originals[e.refundOf]?.category==category
            else e.direction=="EXPENSE" && e.category==category
        }.sortedByDescending { it.at }

    fun balance(account: String, opening: Long, asOf: Long, entries: List<Entry>): Long {
        var total=opening
        for (e in entries) {
            if (e.deleted || e.at < asOf) continue
            if (e.toAccount != null) {
                if (e.account == account) total=Math.subtractExact(total,e.amount)
                if (e.toAccount == account) total=Math.addExact(total,e.amount)
            } else if (e.account == account) {
                if (e.direction == "EXPENSE") total=Math.subtractExact(total,e.amount)
                if (e.direction == "INCOME") total=Math.addExact(total,e.amount)
            }
        }
        return total
    }
}
