package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.RunningCost
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

class BusinessCoachTest {
    private val today = LocalDate.of(2026, 11, 11)

    private fun visit(
        id: String,
        client: String = "c1",
        date: String = "2026-11-10",
        amount: Double = 1000.0,
        materials: Double = 0.0,
        billed: Boolean = false,
    ) = Visit(
        id = id,
        userId = "u",
        clientId = client,
        visitDate = date,
        hours = 4.0,
        amount = amount,
        materialsCost = materials,
        billed = billed,
    )

    private fun invoice(
        id: String,
        total: Double,
        status: String = "sent",
        issue: String = "2026-10-01",
        due: String = "2026-11-01",
    ) = Invoice(
        id = id,
        userId = "u",
        clientId = "c1",
        number = "JW-$id",
        issueDate = issue,
        dueDate = due,
        total = total,
        status = status,
    )

    private fun client(
        id: String,
        name: String,
        rate: Double,
        mode: String = "hourly",
    ) = Client(
        id = id,
        userId = "u",
        name = name,
        billingMode = mode,
        hourlyRate = rate,
        active = true,
    )

    private fun cost(amount: Double, frequency: String = "monthly") = RunningCost(
        id = "cost",
        userId = "u",
        name = "Van",
        category = "vehicle",
        amount = amount,
        frequency = frequency,
    )

    @Test
    fun `snapshot turns work costs and debt into one month`() {
        val snapshot = buildBusinessSnapshot(
            invoices = listOf(invoice("1", 300.0)),
            visits = listOf(visit("1", materials = 100.0)),
            jobs = listOf(
                ScheduledJob(
                    id = "j",
                    userId = "u",
                    clientId = "c1",
                    startsAt = "2026-11-09T09:00:00Z",
                    durationMinutes = 240,
                ),
            ),
            clients = listOf(client("c1", "Smiths", 40.0)),
            costs = listOf(cost(200.0)),
            today = today,
        )

        assertEquals(1000.0, snapshot.monthIncome, 0.001)
        assertEquals(100.0, snapshot.monthMaterials, 0.001)
        assertEquals(200.0, snapshot.monthlyCosts, 0.001)
        assertEquals(700.0, snapshot.netProfit, 0.001)
        assertEquals(0.7, snapshot.netMargin, 0.001)
        assertEquals(1000.0, snapshot.unbilledTotal, 0.001)
        assertEquals(300.0, snapshot.overdueTotal, 0.001)
        assertEquals(1, snapshot.overdueCount)
        assertEquals(4.0, snapshot.bookedHours, 0.001)
        assertTrue(snapshot.hasData)
    }

    @Test
    fun `score rewards collected cash and healthy margin`() {
        val healthy = buildBusinessSnapshot(
            invoices = listOf(invoice("1", 500.0, status = "paid")),
            visits = listOf(visit("1", amount = 1000.0, materials = 100.0, billed = true)),
            jobs = emptyList(),
            clients = listOf(client("c1", "Smiths", 40.0)),
            costs = listOf(cost(200.0)),
            today = today,
        )
        val pressured = buildBusinessSnapshot(
            invoices = listOf(invoice("1", 500.0, due = "2026-09-01")),
            visits = listOf(visit("1", amount = 1000.0, materials = 500.0)),
            jobs = emptyList(),
            clients = listOf(client("c1", "Smiths", 40.0)),
            costs = listOf(cost(700.0)),
            today = today,
        )
        assertTrue(coachScore(healthy) > coachScore(pressured))
        assertTrue(coachScore(healthy) in 0..100)
    }

    @Test
    fun `coach names overdue money and unbilled work`() {
        val snapshot = buildBusinessSnapshot(
            invoices = listOf(invoice("1", 300.0)),
            visits = listOf(visit("1")),
            jobs = emptyList(),
            clients = listOf(client("c1", "Smiths", 40.0)),
            costs = listOf(cost(100.0)),
            today = today,
        )
        val insights = coachInsights(snapshot, listOf(cost(100.0)), listOf(client("c1", "Smiths", 40.0)))
        assertTrue(insights.any { it.id == "overdue" && it.tone == CoachTone.Attention })
        assertTrue(insights.any { it.id == "unbilled" && it.tone == CoachTone.Opportunity })
        assertEquals("overdue", insights.first().id)
    }

    @Test
    fun `missing costs ask for them once`() {
        val snapshot = buildBusinessSnapshot(
            invoices = emptyList(),
            visits = listOf(visit("1")),
            jobs = emptyList(),
            clients = listOf(client("c1", "Smiths", 40.0)),
            costs = emptyList(),
            today = today,
        )
        val insights = coachInsights(snapshot, emptyList(), listOf(client("c1", "Smiths", 40.0)))
        assertTrue(insights.any { it.id == "no-costs" })
    }

    @Test
    fun `lowest hourly rate is called out against the average`() {
        val clients = listOf(
            client("c1", "Smiths", 40.0),
            client("c2", "Jones", 20.0),
        )
        val snapshot = buildBusinessSnapshot(
            invoices = emptyList(),
            visits = listOf(visit("1", client = "c2")),
            jobs = emptyList(),
            clients = clients,
            costs = listOf(cost(10.0)),
            today = today,
        )
        val insights = coachInsights(snapshot, listOf(cost(10.0)), clients)
        assertTrue(insights.any { it.id == "lowest-rate" && it.action == CoachAction.Clients })
    }
}
