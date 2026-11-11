package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Invoice

class TaxReserveTest {
    private fun invoice(issue: String, subtotal: Double, status: String = "sent") = Invoice(
        id = issue, userId = "u", clientId = "c", number = "JW-1",
        issueDate = issue, subtotal = subtotal, total = subtotal, status = status,
    )

    @Test
    fun `reserve is rate percent of the net amount`() {
        assertEquals(90.0, taxReserveAmount(300.0, 30.0), 0.001)
        assertEquals(41.6, taxReserveAmount(208.0, 20.0), 0.001)
        assertEquals(0.0, taxReserveAmount(100.0, 0.0), 0.001)
        assertEquals(0.0, taxReserveAmount(-50.0, 30.0), 0.001)
    }

    @Test
    fun `tax year runs 6 april to 5 april`() {
        assertEquals(
            LocalDate.of(2025, 4, 6)..LocalDate.of(2026, 4, 5),
            ukTaxYearContaining(LocalDate.of(2025, 11, 11)).let { it.start..it.end },
        )
        assertEquals(
            LocalDate.of(2025, 4, 6)..LocalDate.of(2026, 4, 5),
            ukTaxYearContaining(LocalDate.of(2026, 4, 5)).let { it.start..it.end },
        )
        assertEquals(
            LocalDate.of(2026, 4, 6)..LocalDate.of(2027, 4, 5),
            ukTaxYearContaining(LocalDate.of(2026, 4, 6)).let { it.start..it.end },
        )
        assertEquals("2025/26", ukTaxYearLabel(ukTaxYearContaining(LocalDate.of(2025, 11, 11))))
    }

    @Test
    fun `pot sums non draft invoices in the current tax year only`() {
        val rows = listOf(
            invoice("2025-04-01", 1000.0), // previous tax year
            invoice("2025-04-07", 200.0),
            invoice("2026-01-15", 300.0),
            invoice("2026-02-01", 100.0, status = "draft"), // drafts excluded
        )
        assertEquals(150.0, taxSetAside(rows, 30.0, today = LocalDate.of(2025, 11, 11)), 0.001)
        assertEquals(0.0, taxSetAside(rows, 0.0, today = LocalDate.of(2025, 11, 11)), 0.001)
    }
}
