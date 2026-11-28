package uk.co.jwgardenservices.crm.voice

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp

/**
 * The decisions behind the voice actions that change the diary and the books,
 * kept free of the database so they can be tested: which job "move the Smiths"
 * means, whether a booking clashes, which past jobs still need a visit, and who
 * counts as unpaid.
 */
object VoicePlans {
    fun startOf(job: ScheduledJob, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? =
        parseTimestamp(job.startsAt)?.atZone(zone)

    /** The job a spoken change means: the one on [on], else the client's next one from [today]. */
    fun findJob(
        jobs: List<ScheduledJob>,
        clientId: String,
        on: LocalDate?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScheduledJob? = jobs
        .filter { it.clientId == clientId && it.status == "scheduled" }
        .mapNotNull { job -> startOf(job, zone)?.let { job to it } }
        .filter { (_, start) ->
            if (on != null) start.toLocalDate() == on else !start.toLocalDate().isBefore(today)
        }
        .minByOrNull { (_, start) -> start }
        ?.first

    /** Scheduled jobs that overlap a proposed slot; back-to-back is not a clash. */
    fun clashes(
        jobs: List<ScheduledJob>,
        date: LocalDate,
        time: LocalTime,
        minutes: Int,
        excludeJobId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScheduledJob> {
        val start = LocalDateTime.of(date, time)
        val end = start.plusMinutes(minutes.toLong())
        return jobs.filter { it.status == "scheduled" && it.id != excludeJobId }.filter { job ->
            val jobStart = startOf(job, zone)?.toLocalDateTime() ?: return@filter false
            val jobEnd = jobStart.plusMinutes(job.durationMinutes.toLong())
            jobStart.isBefore(end) && start.isBefore(jobEnd)
        }
    }

    /**
     * What "fill in [client]'s visits for [period]" would do. Past jobs with no
     * visit get one; if a visit already exists that day the job is linked to it
     * instead, so the day is never billed twice; and, when an hours figure was
     * given, any unbilled visit in the period with different hours is corrected.
     */
    data class Backfill(
        val toLog: List<ScheduledJob>,
        val toLink: List<Pair<ScheduledJob, Visit>>,
        val toFix: List<Visit>,
    ) {
        val isEmpty: Boolean get() = toLog.isEmpty() && toLink.isEmpty() && toFix.isEmpty()
    }

    fun backfill(
        jobs: List<ScheduledJob>,
        visits: List<Visit>,
        clientId: String,
        period: VoiceCalendar.Period,
        now: Instant,
        hours: Double?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Backfill {
        val clientVisits = visits.filter { it.clientId == clientId && !it.missed }
        val due = jobs
            .filter { it.clientId == clientId && it.status == "scheduled" && it.visitId == null }
            .mapNotNull { job -> startOf(job, zone)?.let { job to it } }
            .filter { (_, start) -> !start.toInstant().isAfter(now) && start.toLocalDate() in period }
            .sortedBy { (_, start) -> start }
        val toLog = mutableListOf<ScheduledJob>()
        val toLink = mutableListOf<Pair<ScheduledJob, Visit>>()
        val taken = mutableSetOf<String>()
        for ((job, start) in due) {
            val sameDay = clientVisits.firstOrNull { it.id !in taken && parseDate(it.visitDate) == start.toLocalDate() }
            if (sameDay != null) {
                taken += sameDay.id
                toLink += job to sameDay
            } else {
                toLog += job
            }
        }
        val toFix = if (hours == null) {
            emptyList()
        } else {
            clientVisits.filter { visit ->
                val day = parseDate(visit.visitDate)
                day != null && day in period && !visit.billed && visit.invoiceId == null &&
                    abs(visit.hours - hours) > 0.01
            }
        }
        return Backfill(toLog, toLink, toFix)
    }

    /** Invoices still to be paid: sent or overdue with something owing. */
    fun unpaid(invoices: List<Invoice>, clientId: String?): List<Invoice> = invoices
        .filter {
            (clientId == null || it.clientId == clientId) &&
                it.status != "paid" && it.status != "draft" && it.total > 0
        }
        .sortedBy { parseDate(it.issueDate) ?: LocalDate.MIN }

    /** Jobs that have already started but were never logged. */
    fun unlogged(
        jobs: List<ScheduledJob>,
        now: Instant,
        period: VoiceCalendar.Period?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ScheduledJob> = jobs
        .filter { it.status == "scheduled" && it.visitId == null }
        .mapNotNull { job -> startOf(job, zone)?.let { job to it } }
        .filter { (_, start) -> !start.toInstant().isAfter(now) && (period == null || start.toLocalDate() in period) }
        .sortedBy { (_, start) -> start }
        .map { it.first }
}
