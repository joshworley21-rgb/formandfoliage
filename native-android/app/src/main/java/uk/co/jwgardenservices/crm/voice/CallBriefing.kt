package uk.co.jwgardenservices.crm.voice

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp

/**
 * The first sentence of a call, built from today's real schedule.
 *
 * A generic "Hello, how can I help?" makes the owner repeat what the phone
 * already knows. This reads the next (or current) slot and says it back:
 *
 *   Morning — you've got Simon's estate first at nine, and two more today.
 *   Afternoon — just wrapping up at the Smiths, or onto the next one?
 *
 * It also names the active client out loud, which is what lets the next
 * sentence be "log four hours and mark it done" with no name in it.
 *
 * Everything here is deterministic and local: no model, no network. When the
 * database cannot be read the caller falls back to the cached hello clip.
 */
class CallBriefing(
    private val repository: CrmRepository = ServiceLocator.crmRepository,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Null only when the snapshot could not be read at all.
     *
     * [callCount] is today's 1-based call number: the first call gets the
     * full contextual briefing, calls two and three a one-liner, and every
     * call after that a word. Later calls skip the database entirely.
     */
    suspend fun line(callCount: Int = 1): String? = runCatching {
        when {
            callCount >= 4 -> microGreeting(callCount)
            callCount in 2..3 -> shortGreeting(callCount)
            else -> build()
        }
    }.getOrNull()

    private fun microGreeting(callCount: Int): String {
        val options = arrayOf("Yep?", "Go ahead.", "Ready.")
        return options[(callCount - 4) % options.size]
    }

    private fun shortGreeting(callCount: Int): String {
        if (callCount == 2) return "Hey — what are we logging?"
        val now = clock()
        val zone = ZoneId.systemDefault()
        return greetingFor(now.atZone(zone).toLocalTime().hour, now.localDate(zone).month) +
            " — what are we logging?"
    }

    private suspend fun build(): String {
        val now = clock()
        val zone = ZoneId.systemDefault()
        val time = now.atZone(zone).toLocalTime()
        val today = now.localDate(zone)
        val greeting = greetingFor(time.hour, today.month)

        val clients = repository.observeClients().first().associateBy { it.id }
        val scheduled = repository.observeJobs().first()
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .sortedBy { (_, starts) -> starts }
        val todayJobs = scheduled.filter { (_, starts) -> starts.localDate(zone) == today }

        // Standing in the middle of a booked window.
        todayJobs.firstOrNull { (job, starts) ->
            !now.isBefore(starts) && now.isBefore(endsAt(job, starts))
        }?.let { (job, starts) ->
            val name = nameOf(job, clients)
            val minutesLeft = Duration.between(now, endsAt(job, starts)).toMinutes()
            return if (minutesLeft <= 35) {
                "$greeting — just wrapping up at $name, or onto the next one?"
            } else {
                val end = spokenTime(endsAt(job, starts).atZone(zone).toLocalTime())
                "$greeting — you're at $name until $end. What are we logging?"
            }
        }

        // Just finished a slot, waiting for the next: the mid-day question.
        todayJobs.lastOrNull { (job, starts) ->
            val end = endsAt(job, starts)
            !now.isBefore(end) && Duration.between(end, now) <= Duration.ofMinutes(90)
        }?.let { (job, _) ->
            return "$greeting — just wrapping up at ${nameOf(job, clients)}, " +
                "or onto the next one?"
        }

        // Still ahead today: lead with the first slot in the morning, or the
        // next one once the day is under way.
        todayJobs.firstOrNull { (_, starts) -> starts.isAfter(now) }
            ?.let { (job, starts) ->
                val name = nameOf(job, clients)
                val start = spokenTime(starts.atZone(zone).toLocalTime())
                val after = todayJobs.count { (_, other) -> other.isAfter(starts) }
                val firstOfDay = todayJobs.first() == (job to starts)
                // The standing reminder the owner asked to hear at this
                // client's next visit.
                val reminder = job.clientId.let { id ->
                    runCatching { repository.nextVisitNoteFor(id) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                }?.let { " Remember: $it." } ?: ""
                return if (firstOfDay && time.hour < 12) {
                    val tail = when (after) {
                        0 -> "Nothing else today."
                        1 -> "And one more today."
                        else -> "And $after more today."
                    }
                    "$greeting — you've got $name first at $start.$reminder $tail " +
                        "What are we logging?"
                } else {
                    "$greeting — next up is $name at $start.$reminder " +
                        "What are we logging?"
                }
            }

        // Nothing today, but the week is not empty.
        scheduled.firstOrNull { (_, starts) -> starts.isAfter(now) }
            ?.let { (_, starts) ->
                val zoneStart = starts.atZone(zone)
                val weekday = zoneStart.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
                return "$greeting — nothing on today; next is $weekday at " +
                    "${spokenTime(zoneStart.toLocalTime())}. What are we logging?"
            }

        return "$greeting — nothing on the books. What are we logging?"
    }

    private fun nameOf(job: ScheduledJob, clients: Map<String, Client>): String =
        clients[job.clientId]?.name ?: "a client"

    /** When the booked window ends; a zero-length job still gets a window. */
    private fun endsAt(job: ScheduledJob, starts: Instant): Instant =
        starts.plusSeconds(job.durationMinutes.coerceAtLeast(15) * 60L)

    /**
     * Time of day, plus a line of trade small talk when the season and hour
     * make one obvious: dark winters, hot summers, busy springs.
     */
    private fun greetingFor(hour: Int, month: java.time.Month): String {
        val time = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Hello"
        }
        val seasonal = when {
            month == java.time.Month.NOVEMBER ||
                month == java.time.Month.DECEMBER ||
                month == java.time.Month.JANUARY ||
                month == java.time.Month.FEBRUARY ->
                if (hour >= 15) "getting dark early out there" else null
            month in java.time.Month.JUNE..java.time.Month.AUGUST ->
                if (hour >= 15) "long day in the heat" else null
            month == java.time.Month.MARCH ||
                month == java.time.Month.APRIL ||
                month == java.time.Month.MAY -> "busy one today"
            else -> null
        }
        return if (seasonal != null) "$time, $seasonal" else time
    }

    /**
     * "nine", "half past two", "quarter to five". Booked slots are almost
     * always on the hour or half hour; anything else keeps its digits, which
     * every engine reads sensibly.
     */
    private fun spokenTime(time: LocalTime): String {
        val hour = hourWord(time.hour)
        return when (time.minute) {
            0 -> hour
            15 -> "quarter past $hour"
            30 -> "half past $hour"
            45 -> "quarter to ${hourWord(time.hour + 1)}"
            else -> "${time.hour}:${time.minute.toString().padStart(2, '0')}"
        }
    }

    private fun hourWord(hour24: Int): String = when (hour24 % 12) {
        0 -> "twelve"
        1 -> "one"
        2 -> "two"
        3 -> "three"
        4 -> "four"
        5 -> "five"
        6 -> "six"
        7 -> "seven"
        8 -> "eight"
        9 -> "nine"
        10 -> "ten"
        else -> "eleven"
    }
}
