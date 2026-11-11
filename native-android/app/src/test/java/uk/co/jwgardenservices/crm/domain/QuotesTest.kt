package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QuotesTest {
    private val lines = listOf(
        QuoteLine(quantity = 3.0, unitPrice = 12.5),
        QuoteLine(quantity = 1.0, unitPrice = 40.0),
    )

    @Test
    fun `line amount multiplies quantity by unit price`() {
        assertEquals(37.5, lineAmount(QuoteLine(3.0, 12.5)), 0.0001)
    }

    @Test
    fun `totals add VAT when the business is registered`() {
        val totals = quoteTotals(lines, vatRegistered = true, vatRate = 20.0)
        assertEquals(77.5, totals.subtotal, 0.0001)
        assertEquals(15.5, totals.vatAmount, 0.0001)
        assertEquals(93.0, totals.total, 0.0001)
    }

    @Test
    fun `VAT is ignored when the business is not registered`() {
        val totals = quoteTotals(lines, vatRegistered = false, vatRate = 20.0)
        assertEquals(77.5, totals.subtotal, 0.0001)
        assertEquals(0.0, totals.vatAmount, 0.0001)
        assertEquals(77.5, totals.total, 0.0001)
    }

    @Test
    fun `an empty quote is zero`() {
        val totals = quoteTotals(emptyList(), vatRegistered = true, vatRate = 20.0)
        assertEquals(0.0, totals.total, 0.0001)
    }
}
