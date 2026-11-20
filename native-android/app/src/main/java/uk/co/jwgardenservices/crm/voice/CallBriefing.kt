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
     * A short status line that follows the one fixed opener ("Hello Josh, how
     * can I help?"). Earlier versions shrank the greeting to "Yep?" after a
     * few calls; that made the assistant feel like it had stopped answering,
     * so the opener is now constant and this line only adds context. Null when
     * there is nothing useful to add, or the snapshot cannot be read.
     */
    suspend fun line(@Suppress("UNUSED_PARAMETER") callCount: Int = 1): String? = runCatching {
        build()
    }.getOrNull()

    private suspend fun build(): String {
        val now = clock()
        val zone = ZoneId.systemDefault()
        val time = now.atZone(zone).toLocalTime()
        val today = now.localDate(zone)

        val clients = repository.observeClients().first().associateBy { it.id }
        val scheduled = repository.observeJobs().first()
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .sortedBy { (_, starts) -> starts }
        val todayJobs = scheduled.filter { (_, starts) -> starts.localDate(zone) == today }
        val calendarToday = if (ServiceLocator.isInitialised()) {
            runCatching {
                CalendarSync.events(ServiceLocator.appContext, today, today)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (todayJobs.isEmpty() && calendarToday.isNotEmpty()) {
            val items = calendarToday.map { event ->
                val start = event.start.atZone(zone)
                val whenText = if (event.allDay) {
                    "all day"
                } else {
                    "at ${spokenTime(start.toLocalTime())}"
                }
                val what = if (event.business) event.title else "personal time"
                "$what $whenText"
            }
            val joined = when (items.size) {
                1 -> items[0]
                2 -> "${items[0]} and ${items[1]}"
                else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
            }
            return "You've got $joined."
        }

        // Standing in the middle of a booked window.
        todayJobs.firstOrNull { (job, starts) ->
            !now.isBefore(starts) && now.isBefore(endsAt(job, starts))
        }?.let { (job, starts) ->
            val name = nameOf(job, clients)
            val minutesLeft = Duration.between(now, endsAt(job, starts)).toMinutes()
            return if (minutesLeft <= 35) {
                "You're just wrapping up at $name, or onto the next one?"
            } else {
                val end = spokenTime(endsAt(job, starts).atZone(zone).toLocalTime())
                "You're at $name until $end."
            }
        }

        // Just finished a slot, waiting for the next: the mid-day question.
        todayJobs.lastOrNull { (job, starts) ->
            val end = endsAt(job, starts)
            !now.isBefore(end) && Duration.between(end, now) <= Duration.ofMinutes(90)
        }?.let { (job, _) ->
            return "Just wrapping up at ${nameOf(job, clients)}, or onto the next one?"
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
                    "You've got $name first at $start.$reminder $tail"
                } else {
                    "Next up is $name at $start.$reminder"
                }
            }

        // Nothing today, but the week is not empty.
        scheduled.firstOrNull { (_, starts) -> starts.isAfter(now) }
            ?.let { (_, starts) ->
                val zoneStart = starts.atZone(zone)
                val weekday = zoneStart.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
                return "Nothing on today; next is $weekday at " +
                    "${spokenTime(zoneStart.toLocalTime())}."
            }

        return "Nothing on the books."
    }

    private fun nameOf(job: ScheduledJob, clients: Map<String, Client>): String =
        clients[job.clientId]?.name ?: "a client"

    /** When the booked window ends; a zero-length job still gets a window. */
    private fun endsAt(job: ScheduledJob, starts: Instant): Instant =
        starts.plusSeconds(job.durationMinutes.coerceAtLeast(15) * 60L)

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
