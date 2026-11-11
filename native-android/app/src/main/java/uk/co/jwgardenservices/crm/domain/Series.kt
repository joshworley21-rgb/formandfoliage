package uk.co.jwgardenservices.crm.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Working out the dates for a repeating run of jobs.
 *
 * A series is not a rule the server evaluates — booking with Repeats creates
 * every job up front and tags them with a shared series_id. So editing a series
 * means rewriting the jobs it already made, and extending one means carrying on
 * from where the last of them landed. That arithmetic lives here, away from the
 * UI, because it is the part worth testing.
 */

enum class Frequency(val wire: String) {
    WEEKLY("weekly"),
    FORTNIGHTLY("fortnightly"),
    FOUR_WEEKLY("four_weekly"),
    MONTHLY("monthly"),
    QUARTERLY("quarterly");

    companion object {
        fun fromWire(value: String): Frequency =
            entries.firstOrNull { it.wire == value } ?: FORTNIGHTLY
    }
}

/** The minimal shape the series arithmetic needs from a scheduled job. */
data class SeriesJob(
    val id: String,
    val startsAt: Instant,
    val status: String,
)

data class RetimedVisit(val id: String, val startsAt: Instant)

fun localDateOf(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    instant.atZone(zone).toLocalDate()

/** How far the i-th visit of a run sits from the first one. */
fun step(day: LocalDate, frequency: Frequency, index: Int): LocalDate = when (frequency) {
    Frequency.WEEKLY -> day.plusWeeks(index.toLong())
    Frequency.FORTNIGHTLY -> day.plusWeeks(2L * index)
    Frequency.FOUR_WEEKLY -> day.plusWeeks(4L * index)
    Frequency.MONTHLY -> day.plusMonths(index.toLong())
    Frequency.QUARTERLY -> day.plusMonths(3L * index)
}

/**
 * A local wall-clock time on a given day.
 *
 * Built from the local date rather than by converting a timestamp, so a job stays
 * at 09:00 across a clock change instead of drifting to 08:00 or 10:00.
 */
fun atTime(day: LocalDate, time: String, zone: ZoneId = ZoneId.systemDefault()): Instant {
    val localTime = LocalTime.parse(time)
    return day.atTime(localTime).atZone(zone).toInstant()
}

/**
 * The visits a series-wide edit is allowed to move: still to come, and not
 * already ticked off. Everything behind now is history.
 */
fun editableVisits(jobs: List<SeriesJob>, now: Instant): List<SeriesJob> =
    jobs.filter { it.status != "done" && !it.startsAt.isBefore(now) }
        .sortedBy { it.startsAt }

/**
 * Where each remaining visit should sit after an edit.
 *
 * Without `respace` every visit keeps its own day and only the clock moves.
 * With it the first remaining visit is the anchor — it keeps its day, and the
 * rest are re-laid from there at the new frequency.
 */
fun retimedVisits(
    visits: List<SeriesJob>,
    startTime: String,
    frequency: Frequency,
    respace: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RetimedVisit> {
    if (visits.isEmpty()) return emptyList()
    val anchor = localDateOf(visits.first().startsAt, zone)
    return visits.mapIndexed { index, visit ->
        val day = if (respace) step(anchor, frequency, index) else localDateOf(visit.startsAt, zone)
        RetimedVisit(visit.id, atTime(day, startTime, zone))
    }
}

/**
 * When the next `count` visits should fall, carrying on past the end of the run.
 *
 * Measured from the last visit in the series whatever its state: a run that has
 * been worked to the end is exactly the one that needs extending.
 */
fun nextVisits(
    jobs: List<SeriesJob>,
    startTime: String,
    frequency: Frequency,
    count: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Instant> {
    if (count <= 0 || jobs.isEmpty()) return emptyList()
    val last = jobs.maxBy { it.startsAt }
    val from = localDateOf(last.startsAt, zone)
    return (1..count).map { index ->
        atTime(step(from, frequency, index), startTime, zone)
    }
}
