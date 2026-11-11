package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

data class ReminderSummary(
    val tomorrowJobs: List<ScheduledJob>,
    val overdue: List<Invoice>,
    val unbilledVisits: List<Visit> = emptyList(),
) {
    val isEmpty: Boolean get() = tomorrowJobs.isEmpty() && overdue.isEmpty() && unbilledVisits.isEmpty()
}

fun buildReminderSummary(
    jobs: List<ScheduledJob>,
    invoices: List<Invoice>,
    visits: List<Visit> = emptyList(),
    today: LocalDate = LocalDate.now(),
): ReminderSummary {
    val tomorrow = today.plusDays(1)
    return ReminderSummary(
        tomorrowJobs = jobs
            .filter { it.status == "scheduled" }
            .filter { parseTimestamp(it.startsAt)?.localDate() == tomorrow }
            .sortedBy { it.startsAt },
        overdue = overdueInvoices(invoices, today),
        unbilledVisits = visits.filter { !it.billed && !it.missed },
    )
}
