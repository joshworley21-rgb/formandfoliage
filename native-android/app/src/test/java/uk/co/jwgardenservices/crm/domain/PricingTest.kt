package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PricingTest {
    @Test
    fun `hourly work is the rate times the hours`() {
        val client = BillingSource("hourly", hourlyRate = 30.0, fixedPrice = 0.0)
        assertEquals(60.0, priceVisit(client, hours = 2.0, materialsCost = 0.0), 0.0001)
    }

    @Test
    fun `materials are added on top`() {
        val client = BillingSource("hourly", hourlyRate = 30.0, fixedPrice = 0.0)
        assertEquals(72.5, priceVisit(client, hours = 2.0, materialsCost = 12.5), 0.0001)
    }

    @Test
    fun `a fixed client ignores the hours`() {
        val client = BillingSource("fixed", hourlyRate = 999.0, fixedPrice = 180.0)
        assertEquals(180.0, priceVisit(client, hours = 10.0, materialsCost = 0.0), 0.0001)
    }

    @Test
    fun `materials still apply to a fixed client`() {
        val client = BillingSource("fixed", hourlyRate = 0.0, fixedPrice = 100.0)
        assertEquals(115.0, priceVisit(client, hours = 0.0, materialsCost = 15.0), 0.0001)
    }

    @Test
    fun `a third of an hour does not leave a fraction of a penny`() {
        val client = BillingSource("hourly", hourlyRate = 35.0, fixedPrice = 0.0)
        assertEquals(11.55, priceVisit(client, hours = 0.33, materialsCost = 0.0), 0.0001)
    }
}
