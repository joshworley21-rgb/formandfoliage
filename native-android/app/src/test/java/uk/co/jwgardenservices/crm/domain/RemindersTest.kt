package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

class RemindersTest {
    private val today = LocalDate.of(2026, 1, 15)

    private fun job(date: String, status: String = "scheduled") = ScheduledJob(
        id = "j-$date",
        userId = "u",
        clientId = "c",
        startsAt = "${date}T09:00:00Z",
        status = status,
    )

    private fun invoice(total: Double, status: String = "sent", dueDate: String = "2026-01-01") = Invoice(
        id = "i-$total",
        userId = "u",
        clientId = "c",
        number = "INV-1",
        issueDate = "2026-01-05",
        status = status,
        dueDate = dueDate,
        subtotal = total,
        vatAmount = 0.0,
        total = total,
    )

    private fun visit(amount: Double, billed: Boolean = false, missed: Boolean = false) = Visit(
        id = "v-$amount",
        userId = "u",
        clientId = "c",
        visitDate = "2026-01-10",
        hours = 1.0,
        amount = amount,
        billed = billed,
        missed = missed,
    )

    @Test
    fun tomorrowJobsOnly() {
        val summary = buildReminderSummary(
            jobs = listOf(job("2026-01-15"), job("2026-01-16"), job("2026-01-14")),
            invoices = emptyList(),
            today = today,
        )
        assertEquals(1, summary.tomorrowJobs.size)
        assertEquals("j-2026-01-16", summary.tomorrowJobs[0].id)
        assertTrue(summary.unbilledVisits.isEmpty())
    }

    @Test
    fun unbilledVisitsExcludeBilledAndMissed() {
        val summary = buildReminderSummary(
            jobs = emptyList(),
            invoices = emptyList(),
            visits = listOf(
                visit(50.0, billed = false),
                visit(20.0, billed = true),
                visit(30.0, missed = true),
                visit(40.0),
            ),
            today = today,
        )
        assertEquals(listOf(50.0, 40.0), summary.unbilledVisits.map { it.amount })
        assertEquals(90.0, summary.unbilledVisits.sumOf { it.amount }, 0.01)
    }

    @Test
    fun emptyWhenNothingToReport() {
        val summary = buildReminderSummary(
            jobs = listOf(job("2026-01-16", status = "completed")),
            invoices = listOf(invoice(100.0, status = "paid", dueDate = "2026-01-14")),
            visits = listOf(visit(10.0, billed = true)),
            today = today,
        )
        assertTrue(summary.isEmpty)
    }

    @Test
    fun overdueInvoicesStillSurface() {
        val summary = buildReminderSummary(
            jobs = emptyList(),
            invoices = listOf(invoice(120.0, status = "sent", dueDate = "2026-01-10")),
            visits = listOf(visit(10.0)),
            today = today,
        )
        assertFalse(summary.isEmpty)
        assertEquals(1, summary.overdue.size)
        assertTrue(summary.unbilledVisits.isNotEmpty())
    }
}
