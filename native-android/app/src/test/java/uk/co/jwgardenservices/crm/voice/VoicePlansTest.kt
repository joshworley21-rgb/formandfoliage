package uk.co.jwgardenservices.crm.voice

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

class VoicePlansTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 26)
    private val now = Instant.parse("2026-11-26T10:00:00Z")
    private val period = VoiceCalendar.Period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "this month")

    private fun job(
        id: String,
        client: String = "smith",
        startsAt: String,
        minutes: Int = 60,
        status: String = "scheduled",
        visitId: String? = null,
    ) = ScheduledJob(
        id = id, userId = "u", clientId = client, startsAt = startsAt,
        durationMinutes = minutes, status = status, visitId = visitId,
    )

    private fun visit(
        id: String,
        client: String = "smith",
        day: String,
        hours: Double = 4.0,
        billed: Boolean = false,
        invoiceId: String? = null,
        missed: Boolean = false,
    ) = Visit(
        id = id, userId = "u", clientId = client, visitDate = day, hours = hours,
        billed = billed, invoiceId = invoiceId, missed = missed,
    )

    private fun invoice(id: String, client: String = "smith", status: String, total: Double, issued: String = "2026-11-01") =
        Invoice(id = id, userId = "u", clientId = client, number = id, issueDate = issued, status = status, total = total)

    @Test
    fun `finds the next job for a client, or the one on a day`() {
        val jobs = listOf(
            job("past", startsAt = "2026-11-20T09:00:00Z"),
            job("later", startsAt = "2026-12-03T09:00:00Z"),
            job("soon", startsAt = "2026-11-30T09:00:00Z"),
            job("other", client = "jones", startsAt = "2026-11-27T09:00:00Z"),
            job("done", startsAt = "2026-11-27T09:00:00Z", status = "done"),
        )
        assertEquals("soon", VoicePlans.findJob(jobs, "smith", null, today, zone)?.id)
        assertEquals("later", VoicePlans.findJob(jobs, "smith", LocalDate.of(2026, 12, 3), today, zone)?.id)
        assertNull(VoicePlans.findJob(jobs, "smith", LocalDate.of(2026, 12, 9), today, zone))
        assertNull(VoicePlans.findJob(jobs, "nobody", null, today, zone))
    }

    @Test
    fun `a job today still counts as the next job`() {
        val jobs = listOf(job("today", startsAt = "2026-11-26T08:00:00Z"))
        assertEquals("today", VoicePlans.findJob(jobs, "smith", null, today, zone)?.id)
    }

    @Test
    fun `overlapping bookings clash and back to back ones do not`() {
        val jobs = listOf(job("a", startsAt = "2026-12-01T09:00:00Z", minutes = 60))
        val day = LocalDate.of(2026, 12, 1)
        assertEquals(listOf("a"), VoicePlans.clashes(jobs, day, LocalTime.of(9, 30), 60, zone = zone).map { it.id })
        assertEquals(listOf("a"), VoicePlans.clashes(jobs, day, LocalTime.of(8, 30), 60, zone = zone).map { it.id })
        assertTrue(VoicePlans.clashes(jobs, day, LocalTime.of(10, 0), 60, zone = zone).isEmpty())
        assertTrue(VoicePlans.clashes(jobs, day, LocalTime.of(8, 0), 60, zone = zone).isEmpty())
        assertTrue(VoicePlans.clashes(jobs, day.plusDays(1), LocalTime.of(9, 0), 60, zone = zone).isEmpty())
        assertTrue(VoicePlans.clashes(jobs, day, LocalTime.of(9, 0), 60, excludeJobId = "a", zone = zone).isEmpty())
    }

    @Test
    fun `backfill logs past unlogged jobs and leaves the future alone`() {
        val jobs = listOf(
            job("p1", startsAt = "2026-11-03T09:00:00Z"),
            job("p2", startsAt = "2026-11-10T09:00:00Z"),
            job("future", startsAt = "2026-11-28T09:00:00Z"),
            job("later today", startsAt = "2026-11-26T15:00:00Z"),
            job("logged", startsAt = "2026-11-12T09:00:00Z", visitId = "v9"),
            job("other client", client = "jones", startsAt = "2026-11-05T09:00:00Z"),
            job("last month", startsAt = "2026-10-20T09:00:00Z"),
        )
        val plan = VoicePlans.backfill(jobs, emptyList(), "smith", period, now, hours = null, zone = zone)
        assertEquals(listOf("p1", "p2"), plan.toLog.map { it.id })
        assertTrue(plan.toLink.isEmpty())
        assertTrue(plan.toFix.isEmpty())
    }

    @Test
    fun `a job that already has a visit that day is linked, not billed twice`() {
        val jobs = listOf(job("p1", startsAt = "2026-11-03T09:00:00Z"), job("p2", startsAt = "2026-11-10T09:00:00Z"))
        val visits = listOf(visit("v1", day = "2026-11-03", hours = 8.0))
        val plan = VoicePlans.backfill(jobs, visits, "smith", period, now, hours = null, zone = zone)
        assertEquals(listOf("p2"), plan.toLog.map { it.id })
        assertEquals(listOf("p1" to "v1"), plan.toLink.map { it.first.id to it.second.id })
    }

    @Test
    fun `one visit is never linked to two jobs`() {
        val jobs = listOf(job("a", startsAt = "2026-11-03T09:00:00Z"), job("b", startsAt = "2026-11-03T14:00:00Z"))
        val visits = listOf(visit("v1", day = "2026-11-03"))
        val plan = VoicePlans.backfill(jobs, visits, "smith", period, now, hours = null, zone = zone)
        assertEquals(1, plan.toLink.size)
        assertEquals(1, plan.toLog.size)
    }

    @Test
    fun `when hours are given only unbilled visits with different hours are corrected`() {
        val visits = listOf(
            visit("same", day = "2026-11-02", hours = 8.0),
            visit("short", day = "2026-11-09", hours = 4.0),
            visit("billed", day = "2026-11-16", hours = 4.0, billed = true),
            visit("on invoice", day = "2026-11-17", hours = 4.0, invoiceId = "inv"),
            visit("missed", day = "2026-11-18", hours = 0.0, missed = true),
            visit("outside", day = "2026-10-30", hours = 4.0),
            visit("other client", client = "jones", day = "2026-11-09", hours = 4.0),
        )
        val plan = VoicePlans.backfill(emptyList(), visits, "smith", period, now, hours = 8.0, zone = zone)
        assertEquals(listOf("short"), plan.toFix.map { it.id })
        val none = VoicePlans.backfill(emptyList(), visits, "smith", period, now, hours = null, zone = zone)
        assertTrue(none.isEmpty)
    }

    @Test
    fun `unpaid invoices are sent or overdue and owing`() {
        val invoices = listOf(
            invoice("sent", status = "sent", total = 100.0, issued = "2026-11-10"),
            invoice("overdue", status = "overdue", total = 50.0, issued = "2026-10-10"),
            invoice("paid", status = "paid", total = 80.0),
            invoice("draft", status = "draft", total = 70.0),
            invoice("zero", status = "sent", total = 0.0),
            invoice("other", client = "jones", status = "sent", total = 30.0),
        )
        assertEquals(listOf("overdue", "sent"), VoicePlans.unpaid(invoices, "smith").map { it.id })
        assertEquals(3, VoicePlans.unpaid(invoices, null).size)
    }

    @Test
    fun `unlogged jobs are past, scheduled and have no visit`() {
        val jobs = listOf(
            job("a", startsAt = "2026-11-03T09:00:00Z"),
            job("b", client = "jones", startsAt = "2026-11-05T09:00:00Z"),
            job("done", startsAt = "2026-11-06T09:00:00Z", status = "done"),
            job("linked", startsAt = "2026-11-07T09:00:00Z", visitId = "v"),
            job("future", startsAt = "2026-11-30T09:00:00Z"),
        )
        assertEquals(listOf("a", "b"), VoicePlans.unlogged(jobs, now, null, zone).map { it.id })
        val narrow = VoiceCalendar.Period(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 30), "x")
        assertEquals(listOf("b"), VoicePlans.unlogged(jobs, now, narrow, zone).map { it.id })
    }
}
