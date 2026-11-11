package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Invoice

class ReportsTest {
    private val today = LocalDate.of(2026, 11, 11)

    private fun invoice(
        id: String,
        client: String = "c1",
        issue: String,
        due: String? = null,
        status: String = "sent",
        total: Double,
        paidAt: String? = null,
    ) = Invoice(
        id = id,
        userId = "u",
        clientId = client,
        number = "JW-$id",
        issueDate = issue,
        dueDate = due,
        total = total,
        status = status,
        paidAt = paidAt,
    )

    @Test
    fun `overdue counts days past due and ignores paid and draft`() {
        assertEquals(10, daysOverdue(invoice("1", issue = "2026-10-01", due = "2026-11-01", total = 1.0), today))
        assertEquals(0, daysOverdue(invoice("2", issue = "2026-10-01", due = "2026-11-20", total = 1.0), today))
        assertEquals(0, daysOverdue(invoice("3", issue = "2026-09-01", due = "2026-09-10", status = "paid", total = 1.0), today))
        assertEquals(0, daysOverdue(invoice("4", issue = "2026-09-01", due = "2026-09-10", status = "draft", total = 1.0), today))
    }

    @Test
    fun `missing due date falls back to thirty days after issue`() {
        assertEquals(5, daysOverdue(invoice("1", issue = "2026-10-07", total = 1.0), today))
    }

    @Test
    fun `monthly totals are oldest first and exclude drafts`() {
        val rows = monthlyTotals(
            listOf(
                invoice("1", issue = "2026-11-02", total = 100.0),
                invoice("2", issue = "2026-11-05", total = 50.0, status = "paid", paidAt = "2026-11-09T10:00:00Z"),
                invoice("3", issue = "2026-10-20", total = 80.0, status = "paid", paidAt = "2026-11-01T09:00:00Z"),
                invoice("4", issue = "2026-11-06", total = 999.0, status = "draft"),
            ),
            today,
            months = 3,
        )
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), rows.map { it.month })
        assertEquals(80.0, rows[1].invoiced, 0.001)
        assertEquals(150.0, rows[2].invoiced, 0.001)
        assertEquals(130.0, rows[2].paid, 0.001)
    }

    @Test
    fun `top clients rank by invoiced value since the cut-off`() {
        val rows = topClients(
            listOf(
                invoice("1", client = "a", issue = "2026-11-01", total = 100.0),
                invoice("2", client = "b", issue = "2026-10-01", total = 300.0),
                invoice("3", client = "a", issue = "2026-09-01", total = 50.0),
                invoice("4", client = "c", issue = "2025-01-01", total = 900.0),
                invoice("5", client = "d", issue = "2026-10-15", total = 999.0, status = "draft"),
            ),
            since = LocalDate.of(2026, 6, 1),
        )
        assertEquals(listOf("b", "a"), rows.map { it.clientId })
        assertEquals(150.0, rows[1].total, 0.001)
    }
}
