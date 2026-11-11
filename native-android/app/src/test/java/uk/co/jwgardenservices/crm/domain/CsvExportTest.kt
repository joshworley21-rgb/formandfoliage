package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Visit

class CsvExportTest {
    @Test
    fun `fields with commas quotes or newlines are quoted`() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
        assertEquals("\"line\nbreak\"", csvField("line\nbreak"))
        assertEquals("", csvField(null))
    }

    @Test
    fun `formula-looking text is neutralised but negative numbers are kept`() {
        assertEquals("'=SUM(A1)", csvField("=SUM(A1)"))
        assertEquals("'@cmd", csvField("@cmd"))
        assertEquals("-12.50", csvField("-12.50"))
    }

    @Test
    fun `invoices export has a header and one row each`() {
        val text = invoicesCsv(
            listOf(
                Invoice(
                    id = "1", userId = "u", clientId = "c1", number = "JW-1",
                    issueDate = "2026-11-01", total = 120.0, subtotal = 100.0, vatAmount = 20.0,
                    status = "paid", paidAt = "2026-11-05T10:00:00Z",
                ),
            ),
        ) { "Smith, Jo" }
        val lines = text.trim().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("Number,Client"))
        assertEquals("JW-1,\"Smith, Jo\",2026-11-01,,paid,100.00,20.00,120.00,2026-11-05", lines[1])
    }

    @Test
    fun `visits export marks billed and missed`() {
        val text = visitsCsv(
            listOf(Visit(id = "1", userId = "u", clientId = "c1", visitDate = "2026-11-02", hours = 2.5, amount = 75.0, billed = true)),
        ) { "Client" }
        assertTrue(text.contains("2026-11-02,Client,2.50,0.00,75.00,Yes,No,"))
    }

    @Test
    fun `tax year export keeps only invoices in the current UK year and adds a summary row`() {
        val text = taxYearCsv(
            listOf(
                Invoice(
                    id = "1", userId = "u", clientId = "c1", number = "JW-1",
                    issueDate = "2026-01-05", total = 120.0, subtotal = 100.0, vatAmount = 20.0, status = "paid",
                ),
                Invoice(
                    id = "2", userId = "u", clientId = "c1", number = "JW-2",
                    issueDate = "2025-03-01", total = 120.0, subtotal = 100.0, vatAmount = 20.0, status = "paid",
                ),
                Invoice(
                    id = "3", userId = "u", clientId = "c1", number = "JW-3",
                    issueDate = "2026-03-01", total = 120.0, subtotal = 100.0, vatAmount = 20.0, status = "draft",
                ),
            ),
            clientName = { "Client" },
            ratePercent = 30.0,
            today = LocalDate.of(2026, 1, 15),
        )
        val lines = text.trim().split("\r\n")
        // header + the one in-year invoice + the summary row
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("Number,Client,Issue date,Status,Net,Tax pot,Drawn"))
        assertEquals("JW-1,Client,2026-01-05,paid,100.00,30.00,70.00", lines[1])
        assertEquals("Tax year 2025/26,1 invoices,,,100.00,30.00,70.00", lines[2])
    }
}
