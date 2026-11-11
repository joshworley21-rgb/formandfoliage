package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test
    fun `money is formatted as pounds with two decimals`() {
        assertEquals("£0.00", money(0.0))
        assertEquals("£12.50", money(12.5))
        assertEquals("£1,234.56", money(1234.56))
        assertEquals("£0.00", money(null))
    }

    @Test
    fun `whole hours do not show decimals`() {
        assertEquals("2 h", hoursLabel(2.0))
        assertEquals("0 h", hoursLabel(0.0))
    }

    @Test
    fun `fractional hours keep two decimals`() {
        assertEquals("2.50 h", hoursLabel(2.5))
    }

    @Test
    fun `rounding is to pennies`() {
        assertEquals(11.55, round2(11.549999), 0.00001)
        assertEquals(11.56, round2(11.556), 0.00001)
    }

    @Test
    fun `quote expiry compares against today`() {
        val today = LocalDate.of(2026, 3, 10)
        assertFalse(isQuoteExpired("sent", "2026-03-11", today))
        assertFalse(isQuoteExpired("sent", "2026-03-10", today))
        assertTrue(isQuoteExpired("sent", "2026-03-09", today))
        assertFalse(isQuoteExpired("draft", "2026-03-01", today))
        assertFalse(isQuoteExpired("sent", null, today))
    }

    @Test
    fun `expired quotes display as expired without changing stored status`() {
        val today = LocalDate.of(2026, 3, 10)
        assertEquals("expired", displayQuoteStatus("sent", "2026-03-01", today))
        assertEquals("accepted", displayQuoteStatus("accepted", "2026-03-01", today))
    }

    @Test
    fun `quote links trim a trailing slash`() {
        assertEquals(
            "https://example.test/quote/abc",
            quoteLink("abc", "https://example.test/"),
        )
    }

    @Test
    fun `a timestamp with an offset parses`() {
        val instant = parseTimestamp("2026-03-17T09:00:00+00:00")
        assertTrue(instant != null)
        val london = instant!!.atZone(ZoneId.of("Europe/London"))
        assertEquals(9, london.hour)
    }
}
