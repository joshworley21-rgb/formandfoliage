package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.RunningCost

class RunningCostsTest {
    private fun cost(
        id: String,
        amount: Double,
        frequency: String,
        category: String = "other",
    ) = RunningCost(
        id = id,
        userId = "u",
        name = id,
        category = category,
        amount = amount,
        frequency = frequency,
    )

    @Test
    fun `monthly equivalent converts every frequency`() {
        assertEquals(100.0, cost("m", 100.0, "monthly").monthlyAmount(), 0.001)
        assertEquals(100.0, cost("a", 1200.0, "annual").monthlyAmount(), 0.001)
        assertEquals(100.0, cost("q", 300.0, "quarterly").monthlyAmount(), 0.001)
        assertEquals(520.0, cost("w", 120.0, "weekly").monthlyAmount(), 0.001)
        assertEquals(216.67, cost("f", 100.0, "fortnightly").monthlyAmount(), 0.01)
    }

    @Test
    fun `totals are monthly annual and daily`() {
        val rows = listOf(
            cost("m", 100.0, "monthly"),
            cost("a", 1200.0, "annual"),
        )
        assertEquals(200.0, rows.monthlyRunningCosts(), 0.001)
        assertEquals(2400.0, rows.annualRunningCosts(), 0.001)
        assertEquals(6.58, rows.dailyRunningCosts(), 0.01)
    }

    @Test
    fun `breakdown ranks categories and shares add up`() {
        val rows = listOf(
            cost("van", 300.0, "monthly", "vehicle"),
            cost("fuel", 100.0, "monthly", "fuel"),
            cost("insurance", 1200.0, "annual", "insurance"),
        )
        val breakdown = costBreakdown(rows)
        assertEquals(listOf("vehicle", "fuel", "insurance"), breakdown.map { it.category.key })
        assertEquals(1.0, breakdown.sumOf { it.share }, 0.001)
        assertEquals(300.0, breakdown.first().monthly, 0.001)
    }

    @Test
    fun `compact money keeps the card readable`() {
        assertEquals("£950", moneyCompact(950.0))
        assertEquals("£1.5k", moneyCompact(1500.0))
        assertEquals("£18k", moneyCompact(18_000.0))
        assertTrue(moneyCompact(1_200_000.0).endsWith("m"))
    }
}
