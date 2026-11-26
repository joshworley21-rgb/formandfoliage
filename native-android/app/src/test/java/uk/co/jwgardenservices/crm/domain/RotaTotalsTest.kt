package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

class RotaTotalsTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private val sunday = monday.plusDays(6)

    private fun client(id: String, mode: String, hourly: Double, fixed: Double) = Client(
        id = id,
        userId = "u",
        name = id,
        billingMode = mode,
        hourlyRate = hourly,
        fixedPrice = fixed,
    )

    @Test
    fun `scheduled jobs without visits are valued from the client rate`() {
        val clients = listOf(
            client("hourly", "hourly", 20.0, 0.0),
            client("fixed", "fixed", 0.0, 50.0),
        )
        val jobs = listOf(
            ScheduledJob(
                id = "j1",
                userId = "u",
                clientId = "hourly",
                startsAt = "2026-11-23T09:00:00Z",
                durationMinutes = 90,
            ),
            ScheduledJob(
                id = "j2",
                userId = "u",
                clientId = "fixed",
                startsAt = "2026-11-24T09:00:00Z",
                durationMinutes = 60,
            ),
        )
        val totals = rotaWeekTotals(
            weekStart = monday,
            weekEnd = sunday,
            visits = emptyList(),
            jobs = jobs,
            clients = clients,
            labourRate = 10.0,
            weeklyOverheads = 0.0,
        )
        assertEquals(80.0, totals.scheduledIncome, 0.001)
        assertEquals(80.0, totals.income, 0.001)
        assertEquals(2.5, totals.hours, 0.001)
        assertEquals(25.0, totals.labour, 0.001)
    }

    @Test
    fun `completed jobs use the logged visit and are not counted twice`() {
        val clients = listOf(client("smiths", "hourly", 20.0, 0.0))
        val visit = Visit(
            id = "v1",
            userId = "u",
            clientId = "smiths",
            visitDate = "2026-11-23",
            hours = 2.0,
            amount = 45.0,
            materialsCost = 5.0,
        )
        val job = ScheduledJob(
            id = "j1",
            userId = "u",
            clientId = "smiths",
            startsAt = "2026-11-23T09:00:00Z",
            durationMinutes = 120,
            status = "done",
            visitId = "v1",
        )
        val totals = rotaWeekTotals(
            weekStart = monday,
            weekEnd = sunday,
            visits = listOf(visit),
            jobs = listOf(job),
            clients = clients,
            labourRate = 10.0,
            weeklyOverheads = 20.0,
        )
        assertEquals(45.0, totals.visitIncome, 0.001)
        assertEquals(0.0, totals.scheduledIncome, 0.001)
        assertEquals(45.0, totals.income, 0.001)
        assertEquals(20.0, totals.labour, 0.001)
        assertEquals(25.0, totals.overheads, 0.001)
        assertEquals(0.0, totals.net, 0.001)
    }
}
