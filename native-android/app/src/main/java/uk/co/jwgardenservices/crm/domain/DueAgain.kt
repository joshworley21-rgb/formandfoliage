package uk.co.jwgardenservices.crm.domain

import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A regular client who has gone longer than usual without a visit and has nothing booked. */
data class DueClient(
    val client: Client,
    val lastVisit: LocalDate,
    val usualGapDays: Int,
    val daysSince: Int,
)

/**
 * Finds regular work that is slipping: a client seen at least three times in
 * the last six months, whose usual gap between visits has now passed, and
 * who has nothing in the diary. Most overdue first.
 */
fun clientsDueAgain(
    clients: List<Client>,
    visits: List<Visit>,
    jobs: List<ScheduledJob>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DueClient> {
    val since = today.minusMonths(6)
    val booked = jobs.filter { it.status == "scheduled" }
        .mapNotNull { job -> parseTimestamp(job.startsAt)?.atZone(zone)?.toLocalDate()?.let { job.clientId to it } }
        .filter { (_, day) -> !day.isBefore(today) }
        .map { it.first }
        .toSet()
    return clients
        .filter { it.active && it.id !in booked }
        .mapNotNull { client ->
            val days = visits
                .filter { it.clientId == client.id && !it.missed }
                .mapNotNull { parseDate(it.visitDate) }
                .filter { !it.isBefore(since) && !it.isAfter(today) }
                .distinct()
                .sorted()
            if (days.size < 3) return@mapNotNull null
            val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.sorted()
            val usual = gaps[gaps.size / 2]
            if (usual < 5) return@mapNotNull null
            val last = days.last()
            val since = ChronoUnit.DAYS.between(last, today).toInt()
            // A couple of days' grace, so it does not nag the day a visit falls due.
            if (since < usual + 2) return@mapNotNull null
            DueClient(client, last, usual, since)
        }
        .sortedByDescending { it.daysSince - it.usualGapDays }
}

/** "every 2 weeks", "every 10 days", "every month". */
fun describeGap(days: Int): String = when {
    days in 27..33 -> "every month"
    days % 7 == 0 -> if (days == 7) "every week" else "every ${days / 7} weeks"
    else -> "every $days days"
}
