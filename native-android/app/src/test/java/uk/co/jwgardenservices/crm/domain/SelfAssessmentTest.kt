package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Visit

class SelfAssessmentTest {
    @Test
    fun `below the allowance nothing is owed`() {
        val e = estimateSelfAssessmentTax(10000.0)
        assertEquals(0.0, e.incomeTax, 0.001)
        assertEquals(0.0, e.class4, 0.001)
        assertEquals(0.0, e.total, 0.001)
    }

    @Test
    fun `basic rate profit pays 20 percent income tax and 6 percent class4`() {
        val e = estimateSelfAssessmentTax(20000.0)
        assertEquals(1486.0, e.incomeTax, 0.001) // (20000 - 12570) * 20%
        assertEquals(445.8, e.class4, 0.001) // (20000 - 12570) * 6%
        assertEquals(1931.8, e.total, 0.001)
    }

    @Test
    fun `higher rate profit adds 40 percent and 2 percent bands`() {
        val e = estimateSelfAssessmentTax(60000.0)
        assertEquals(11432.0, e.incomeTax, 0.001) // 37700*20% + 9730*40%
        assertEquals(2456.6, e.class4, 0.001) // 37700*6% + 9730*2%
        assertEquals(13888.6, e.total, 0.001)
    }

    @Test
    fun `profit is invoiced minus materials in the tax year`() {
        val invoices = listOf(
            Invoice(id = "1", userId = "u", clientId = "c", number = "JW-1",
                issueDate = "2025-04-07", subtotal = 5000.0, total = 5000.0, status = "sent"),
            Invoice(id = "2", userId = "u", clientId = "c", number = "JW-2",
                issueDate = "2025-03-01", subtotal = 9000.0, total = 9000.0, status = "paid"), // last year
            Invoice(id = "3", userId = "u", clientId = "c", number = "JW-3",
                issueDate = "2025-06-01", subtotal = 1000.0, total = 1000.0, status = "draft"),
        )
        val visits = listOf(
            Visit(id = "1", userId = "u", clientId = "c", visitDate = "2025-04-10", materialsCost = 150.0),
            Visit(id = "2", userId = "u", clientId = "c", visitDate = "2026-04-10", materialsCost = 999.0), // next year
            Visit(id = "3", userId = "u", clientId = "c", visitDate = "2025-05-01", missed = true, materialsCost = 50.0),
        )
        assertEquals(
            4850.0,
            estimatedTaxYearProfit(invoices, visits, today = LocalDate.of(2025, 11, 11)),
            0.001,
        )
    }
}
