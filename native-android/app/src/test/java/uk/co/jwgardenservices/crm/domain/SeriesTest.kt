package uk.co.jwgardenservices.crm.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesTest {
    private val zone = ZoneId.of("Europe/London")

    private fun job(id: String, local: String, status: String = "scheduled"): SeriesJob =
        SeriesJob(id, LocalDateTime.parse(local).atZone(zone).toInstant(), status)

    private fun dayOf(instant: Instant): String = localDateOf(instant, zone).toString()

    private fun timeOf(instant: Instant): String = instant.atZone(zone).toLocalTime().toString()

    private val now: Instant = LocalDateTime.parse("2026-03-10T12:00:00").atZone(zone).toInstant()

    @Test
    fun `a visit still to come is editable`() {
        val visits = editableVisits(listOf(job("a", "2026-03-17T09:00:00")), now)
        assertEquals(listOf("a"), visits.map { it.id })
    }

    @Test
    fun `a visit already done is left alone`() {
        assertEquals(emptyList<SeriesJob>(), editableVisits(listOf(job("a", "2026-03-17T09:00:00", "done")), now))
    }

    @Test
    fun `a visit in the past is left alone even when it was never ticked off`() {
        assertEquals(emptyList<SeriesJob>(), editableVisits(listOf(job("a", "2026-03-03T09:00:00")), now))
    }

    @Test
    fun `earlier today before now counts as past`() {
        assertEquals(emptyList<SeriesJob>(), editableVisits(listOf(job("a", "2026-03-10T09:00:00")), now))
    }

    @Test
    fun `editable visits come back in date order however they arrive`() {
        val visits = editableVisits(
            listOf(
                job("c", "2026-03-31T09:00:00"),
                job("a", "2026-03-17T09:00:00"),
                job("b", "2026-03-24T09:00:00"),
            ),
            now,
        )
        assertEquals(listOf("a", "b", "c"), visits.map { it.id })
    }

    @Test
    fun `changing the time moves the clock and leaves every day where it was`() {
        val visits = listOf(job("a", "2026-03-17T09:00:00"), job("b", "2026-03-31T09:00:00"))
        val out = retimedVisits(
            visits,
            startTime = "14:30",
            frequency = Frequency.FORTNIGHTLY,
            respace = false,
            zone = zone,
        )
        assertEquals(listOf("2026-03-17", "2026-03-31"), out.map { dayOf(it.startsAt) })
        assertEquals(listOf("14:30", "14:30"), out.map { timeOf(it.startsAt) })
    }

    @Test
    fun `a run with a gap keeps that gap when only the time changes`() {
        val visits = listOf(job("a", "2026-03-17T09:00:00"), job("b", "2026-04-02T09:00:00"))
        val out = retimedVisits(
            visits,
            startTime = "08:00",
            frequency = Frequency.FORTNIGHTLY,
            respace = false,
            zone = zone,
        )
        assertEquals(listOf("2026-03-17", "2026-04-02"), out.map { dayOf(it.startsAt) })
    }

    @Test
    fun `changing the frequency respaces from the first visit still to come`() {
        val visits = listOf(
            job("a", "2026-03-17T09:00:00"),
            job("b", "2026-03-31T09:00:00"),
            job("c", "2026-04-14T09:00:00"),
        )
        val out = retimedVisits(
            visits,
            startTime = "09:00",
            frequency = Frequency.WEEKLY,
            respace = true,
            zone = zone,
        )
        assertEquals(
            listOf("2026-03-17", "2026-03-24", "2026-03-31"),
            out.map { dayOf(it.startsAt) },
        )
    }

    @Test
    fun `respacing keeps each visit's own id`() {
        val visits = listOf(job("a", "2026-03-17T09:00:00"), job("b", "2026-03-31T09:00:00"))
        val out = retimedVisits(
            visits,
            startTime = "09:00",
            frequency = Frequency.MONTHLY,
            respace = true,
            zone = zone,
        )
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test
    fun `nothing to edit produces no writes`() {
        assertEquals(
            emptyList<RetimedVisit>(),
            retimedVisits(emptyList(), "09:00", Frequency.WEEKLY, true, zone),
        )
    }

    @Test
    fun `extending carries on from the last visit in the run`() {
        val jobs = listOf(job("a", "2026-03-17T09:00:00"), job("b", "2026-03-31T09:00:00"))
        val out = nextVisits(jobs, "09:00", Frequency.FORTNIGHTLY, 2, zone)
        assertEquals(listOf("2026-04-14", "2026-04-28"), out.map { dayOf(it) })
    }

    @Test
    fun `a run worked to the end still extends from its last visit`() {
        val jobs = listOf(job("a", "2026-03-17T09:00:00", "done"), job("b", "2026-03-31T09:00:00", "done"))
        val out = nextVisits(jobs, "09:00", Frequency.WEEKLY, 1, zone)
        assertEquals(listOf("2026-04-07"), out.map { dayOf(it) })
    }

    @Test
    fun `extending is measured from the latest visit not the last listed`() {
        val jobs = listOf(job("b", "2026-03-31T09:00:00"), job("a", "2026-03-17T09:00:00"))
        val out = nextVisits(jobs, "09:00", Frequency.WEEKLY, 1, zone)
        assertEquals(listOf("2026-04-07"), out.map { dayOf(it) })
    }

    @Test
    fun `extending an empty series adds nothing`() {
        assertEquals(emptyList<Instant>(), nextVisits(emptyList(), "09:00", Frequency.WEEKLY, 4, zone))
    }

    @Test
    fun `asking for no extra visits adds nothing`() {
        assertEquals(
            emptyList<Instant>(),
            nextVisits(listOf(job("a", "2026-03-17T09:00:00")), "09:00", Frequency.WEEKLY, 0, zone),
        )
    }

    @Test
    fun `a monthly run holds its date rather than drifting by four weeks`() {
        val jobs = listOf(job("a", "2026-01-31T09:00:00"))
        val out = nextVisits(jobs, "09:00", Frequency.MONTHLY, 2, zone)
        // February has no 31st: java.time clamps to the last day rather than
        // spilling into March.
        assertEquals(listOf("2026-02-28", "2026-03-31"), out.map { dayOf(it) })
    }

    @Test
    fun `a visit keeps its wall-clock time across the spring clock change`() {
        // 29 March 2026 is when the UK clocks go forward. A 09:00 job stays 09:00.
        val jobs = listOf(job("a", "2026-03-22T09:00:00"))
        val out = nextVisits(jobs, "09:00", Frequency.WEEKLY, 1, zone)
        assertEquals("2026-03-29", dayOf(out.single()))
        assertEquals("09:00", timeOf(out.single()))
    }

    @Test
    fun `atTime puts the given clock time on the given day`() {
        val instant = atTime(java.time.LocalDate.of(2026, 3, 17), "07:45", zone)
        assertEquals("2026-03-17", dayOf(instant))
        assertEquals("07:45", timeOf(instant))
    }
}
