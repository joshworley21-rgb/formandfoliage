package uk.co.jwgardenservices.crm.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * Pairing up the two halves of the same piece of work.
 *
 * A job on the schedule and a visit in the log are the same afternoon in the
 * garden, recorded from two directions. Both helpers answer the same question —
 * is the other half of this already recorded? — and both are pure, so the rule
 * can be tested without a database.
 */

data class JobLink(
    val id: String,
    val clientId: String,
    val startsAt: Instant,
    val status: String,
    val visitId: String?,
)

data class VisitLink(
    val id: String,
    val clientId: String,
    val visitDate: String,
)

/** The calendar day of a timestamp, in the phone's own timezone. */
fun localDay(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    instant.atZone(zone).toLocalDate().toString()

/**
 * The job a newly logged visit finishes off, or null.
 *
 * Matches on client and day only: the time a job was booked for and the time it
 * was actually done are rarely the same. Earliest first when a client has two
 * jobs on one day, so a morning and an afternoon visit tick off in that order.
 */
fun findJobForVisit(jobs: List<JobLink>, clientId: String, visitDate: String): JobLink? =
    jobs.filter {
        it.clientId == clientId &&
            it.status == "scheduled" &&
            it.visitId == null &&
            localDay(it.startsAt) == visitDate
    }.minByOrNull { it.startsAt }

/**
 * The visit already logged for a job being marked complete, or null.
 *
 * `linkedVisitIds` are visits already claimed by another job, so two jobs on one
 * day cannot both point at the same visit.
 */
fun findVisitForJob(
    visits: List<VisitLink>,
    clientId: String,
    jobStartsAt: Instant,
    linkedVisitIds: Set<String> = emptySet(),
): VisitLink? {
    val day = localDay(jobStartsAt)
    return visits.firstOrNull {
        it.clientId == clientId && it.visitDate == day && it.id !in linkedVisitIds
    }
}

/** Convenience for the common case of comparing against today's local date. */
fun todayIso(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).toString()
