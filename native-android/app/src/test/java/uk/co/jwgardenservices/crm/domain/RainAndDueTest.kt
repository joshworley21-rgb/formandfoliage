package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.weather.RainForecast
import java.time.LocalDate
import java.time.ZoneOffset

class RainAndDueTest {
    private val today = LocalDate.of(2026, 11, 28)

    @Test
    fun `a wet day needs both a high chance and real rain`() {
        assertTrue(RainPlan.isWet(DayRain(80, 6.0)))
        assertFalse(RainPlan.isWet(DayRain(80, 0.5)))
        assertFalse(RainPlan.isWet(DayRain(30, 8.0)))
        assertFalse(RainPlan.isWet(null))
    }

    @Test
    fun `the next dry day skips wet ones and stays inside the forecast`() {
        val forecast = mapOf(
            today to DayRain(90, 8.0),
            today.plusDays(1) to DayRain(70, 4.0),
            today.plusDays(2) to DayRain(20, 0.0),
        )
        assertEquals(today.plusDays(2), RainPlan.nextDryDay(today, forecast))
        assertNull(RainPlan.nextDryDay(today.plusDays(2), forecast))
    }

    @Test
    fun `the saved forecast reads back the same`() {
        val forecast = mapOf(today to DayRain(80, 6.5), today.plusDays(1) to DayRain(10, 0.0))
        assertEquals(forecast, RainForecast.decode(RainForecast.encode(forecast)))
    }

    private fun visits(client: String, vararg daysAgo: Long) = daysAgo.mapIndexed { i, ago ->
        Visit(id = "$client$i", userId = "u", clientId = client, visitDate = today.minusDays(ago).toString(), hours = 2.0, amount = 60.0)
    }

    @Test
    fun `a fortnightly client four weeks on with nothing booked is due`() {
        val rose = Client(id = "rose", userId = "u", name = "Rose Cottage")
        val due = clientsDueAgain(listOf(rose), visits("rose", 28, 42, 56, 70), emptyList(), today, ZoneOffset.UTC)
        assertEquals(1, due.size)
        assertEquals(14, due[0].usualGapDays)
        assertEquals(28, due[0].daysSince)
        assertEquals("every 2 weeks", describeGap(14))
    }

    @Test
    fun `booked, recent, irregular or inactive clients are left alone`() {
        val booked = Client(id = "b", userId = "u", name = "Booked")
        val recent = Client(id = "r", userId = "u", name = "Recent")
        val once = Client(id = "o", userId = "u", name = "Once")
        val gone = Client(id = "g", userId = "u", name = "Gone", active = false)
        val job = ScheduledJob(id = "j", userId = "u", clientId = "b", startsAt = "2026-12-02T09:00:00Z")
        val all = visits("b", 28, 42, 56) + visits("r", 10, 24, 38) + visits("o", 60) + visits("g", 28, 42, 56)
        assertTrue(clientsDueAgain(listOf(booked, recent, once, gone), all, listOf(job), today, ZoneOffset.UTC).isEmpty())
    }
}
