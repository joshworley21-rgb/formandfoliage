package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import uk.co.jwgardenservices.crm.data.model.Invoice

data class MonthTotal(val month: YearMonth, val invoiced: Double, val paid: Double)

data class ClientTotal(val clientId: String, val total: Double)

/** Whole days past due, or 0. Falls back to 30 days after issue when no due date is set. */
fun daysOverdue(invoice: Invoice, today: LocalDate = LocalDate.now()): Long {
    if (invoice.status == "paid" || invoice.status == "draft") return 0
    val due = parseDate(invoice.dueDate)
        ?: parseDate(invoice.issueDate)?.plusDays(30)
        ?: return 0
    return ChronoUnit.DAYS.between(due, today).coerceAtLeast(0)
}

fun overdueInvoices(invoices: List<Invoice>, today: LocalDate = LocalDate.now()): List<Invoice> =
    invoices.filter { daysOverdue(it, today) > 0 }

/** Invoiced (by issue date, drafts excluded) and paid (by paid date) for the last [months] months, oldest first. */
fun monthlyTotals(
    invoices: List<Invoice>,
    today: LocalDate = LocalDate.now(),
    months: Int = 6,
): List<MonthTotal> {
    val current = YearMonth.from(today)
    return (months - 1 downTo 0).map { back ->
        val month = current.minusMonths(back.toLong())
        val invoiced = invoices
            .filter { it.status != "draft" && parseDate(it.issueDate)?.let(YearMonth::from) == month }
            .sumOf { it.total }
        val paid = invoices
            .filter { it.status == "paid" }
            .filter { invoice ->
                val paidOn = parseTimestamp(invoice.paidAt)?.localDate() ?: parseDate(invoice.paidAt)
                paidOn?.let(YearMonth::from) == month
            }
            .sumOf { it.total }
        MonthTotal(month, round2(invoiced), round2(paid))
    }
}

/** Clients ranked by invoiced value (drafts excluded) since [since]. */
fun topClients(
    invoices: List<Invoice>,
    since: LocalDate,
    limit: Int = 5,
): List<ClientTotal> =
    invoices
        .filter { it.status != "draft" }
        .filter { parseDate(it.issueDate)?.let { date -> !date.isBefore(since) } == true }
        .groupBy { it.clientId }
        .map { (clientId, rows) -> ClientTotal(clientId, round2(rows.sumOf { it.total })) }
        .sortedByDescending { it.total }
        .take(limit)
