package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatShortWeekday
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseSpokenNumber
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.priceVisit
import uk.co.jwgardenservices.crm.domain.round2
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/**
 * Turns a spoken command into a real change in the CRM, not just a spoken
 * acknowledgement. The service owns no UI, so this talks to the same
 * repository the screens use (Room-backed, offline first).
 *
 * A command that is missing a slot — "log two hours" with no client, or
 * "log Smiths" with no hours — is remembered as [pending] and completed by the
 * next sentence, so the conversation can take two turns instead of failing.
 */
class VoiceActionRunner(private val repository: CrmRepository = ServiceLocator.crmRepository) {

    private data class PendingLog(
        val client: Client?,
        val date: LocalDate,
        val hours: Double?,
    )

    private var pending: PendingLog? = null

    fun hasPending(): Boolean = pending != null

    fun clearPending() {
        pending = null
    }

    /**
     * Returns the sentence to speak when the command was executed, or null
     * when this runner cannot handle it (the caller falls back to the
     * generic confirmation).
     */
    suspend fun run(transcript: String, intent: QwenIntentEngine.Intent): String? {
        val text = transcript.lowercase()
        return when (intent.intent) {
            QwenIntentEngine.Kind.LogHours -> logHours(text)
            QwenIntentEngine.Kind.CheckSchedule -> scheduleFor(text)
            QwenIntentEngine.Kind.NextJob -> nextJob()
            QwenIntentEngine.Kind.MarkJobDone -> markJobDone(text)
            QwenIntentEngine.Kind.WhoOwesMe -> whoOwesMe()
            QwenIntentEngine.Kind.WeeklyEarnings -> weeklyEarnings()
            else -> null
        }
    }

    /**
     * Finish a half-heard "log hours" command from the previous turn. Returns
     * null when this sentence is not an answer to the pending question, in
     * which case the caller classifies it normally.
     */
    suspend fun continuePending(transcript: String): String? {
        val pending = pending ?: return null
        val text = transcript.lowercase()
        val hours = spokenHours(text) ?: pending.hours
        val clients = repository.observeClients().first()
        val client = pending.client ?: clients.firstOrNull { matches(it.name, text) }

        if (hours == null && client == null) {
            this.pending = null
            return null
        }
        if (hours == null) {
            this.pending = pending.copy(client = client)
            return "How many hours for ${client!!.name}?"
        }
        if (client == null) {
            this.pending = pending.copy(hours = hours)
            return "Which client was that for?"
        }
        this.pending = null
        return saveHours(client, hours, pending.date)
    }

    // --- actions ----------------------------------------------------------

    private suspend fun logHours(text: String): String {
        val hours = spokenHours(text)
        val date = dateFor(text)
        val clients = repository.observeClients().first()
        val client = clients.firstOrNull { matches(it.name, text) }

        if (hours == null && client == null) {
            return "How many hours, and for which client?"
        }
        if (hours == null) {
            pending = PendingLog(client = client, date = date, hours = null)
            return "How many hours for ${client!!.name}?"
        }
        if (client == null) {
            pending = PendingLog(client = null, date = date, hours = hours)
            return "Which client was that for?"
        }
        return saveHours(client, hours, date)
    }

    private suspend fun saveHours(client: Client, hours: Double, date: LocalDate): String {
        val draft = VisitDraft(
            clientId = client.id,
            visitDate = date.toString(),
            hours = round2(hours),
            materialsCost = 0.0,
            description = "Logged by voice",
            amount = round2(
                priceVisit(
                    BillingSource(client.billingMode, client.hourlyRate, client.fixedPrice),
                    hours,
                    0.0,
                ),
            ),
        )
        repository.saveVisit(draft)
        val whenText = if (date == LocalDate.now()) "" else " for ${date.dayOfMonth}/${date.monthValue}"
        return "Logged ${hoursLabel(hours)} for ${client.name}$whenText, ${money(draft.amount)}."
    }

    /** Answer "what have I got on" for today, tomorrow, a weekday or next week. */
    private suspend fun scheduleFor(text: String): String {
        val range = rangeFor(text)
        val jobs = scheduledJobs().filter { (_, starts) ->
            val day = starts.localDate()
            !day.isBefore(range.from) && !day.isAfter(range.to)
        }
        if (jobs.isEmpty()) return "Nothing booked ${range.label}."

        val clients = repository.observeClients().first()
        val names = jobs.take(6).joinToString("; ") { (job, starts) ->
            val name = clients.firstOrNull { it.id == job.clientId }?.name ?: "a client"
            when {
                range.from == range.to -> "${starts.formatTime()} $name"
                else -> "${starts.formatShortWeekday()} ${starts.formatDayMonth()} " +
                    "at ${starts.formatTime()} $name"
            }
        }
        val more = if (jobs.size > 6) ", and ${jobs.size - 6} more" else ""
        val count = if (jobs.size == 1) "job" else "jobs"
        return "You have ${jobs.size} $count ${range.label}: $names$more."
    }

    private data class DateRange(val from: LocalDate, val to: LocalDate, val label: String)

    private fun rangeFor(text: String): DateRange {
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val nextMonday = monday.plusWeeks(1)
        val weekday = DayOfWeek.entries.firstOrNull {
            Regex("\\b${it.name.lowercase()}\\b").containsMatchIn(text)
        }
        return when {
            "day after tomorrow" in text ->
                DateRange(today.plusDays(2), today.plusDays(2), "the day after tomorrow")

            "tomorrow" in text ->
                DateRange(today.plusDays(1), today.plusDays(1), "tomorrow")

            "next week" in text || "week after" in text ->
                DateRange(nextMonday, nextMonday.plusDays(6), "next week")

            "this week" in text ->
                DateRange(monday, monday.plusDays(6), "this week")

            "next month" in text -> {
                val first = today.plusMonths(1).withDayOfMonth(1)
                DateRange(first, first.withDayOfMonth(first.lengthOfMonth()), "next month")
            }

            weekday != null -> {
                val date = today.with(TemporalAdjusters.nextOrSame(weekday))
                val label = weekday.name.lowercase().replaceFirstChar { it.uppercase() }
                DateRange(date, date, "on $label")
            }

            else -> DateRange(today, today, "today")
        }
    }

    private suspend fun nextJob(): String {
        val clients = repository.observeClients().first()
        val now = Instant.now()
        val upcoming = scheduledJobs().filter { (_, starts) -> !starts.isBefore(now) }
        val next = upcoming.firstOrNull() ?: return "Nothing else is booked."
        val name = clients.firstOrNull { it.id == next.first.clientId }?.name ?: "a client"
        val starts = next.second
        val whenText = if (starts.localDate() == LocalDate.now()) {
            "today at ${starts.formatTime()}"
        } else {
            "${starts.formatWeekday()} at ${starts.formatTime()}"
        }
        return "Next is $name, $whenText."
    }

    private suspend fun markJobDone(text: String): String {
        val clients = repository.observeClients().first()
        val jobs = scheduledJobs()
        if (jobs.isEmpty()) return "There is no scheduled job to mark done."

        val named = clients.firstOrNull { matches(it.name, text) }
        val today = LocalDate.now()
        val target = when {
            named != null -> jobs.firstOrNull { (job, _) -> job.clientId == named.id }
            else -> jobs.firstOrNull { (_, starts) -> starts.localDate() == today } ?: jobs.first()
        } ?: return "I couldn't find a job for ${named?.name ?: "that client"}."

        val job = target.first
        val client = clients.firstOrNull { it.id == job.clientId }
        val hours = round2((job.durationMinutes / 60.0).coerceAtLeast(0.5))
        repository.staffCompleteJob(job.id, hours, "Completed by voice")
        val name = client?.name ?: "the client"
        return "Marked $name's job done, ${hoursLabel(hours)}."
    }

    private suspend fun whoOwesMe(): String {
        val outstanding = repository.observeInvoices().first()
            .filter { it.status != "paid" && it.status != "draft" && it.total > 0 }
        if (outstanding.isEmpty()) return "Nobody owes you anything. Every invoice is paid."

        val clients = repository.observeClients().first()
        val byClient = outstanding
            .groupBy { it.clientId }
            .mapValues { (_, invoices) -> invoices.sumOf { it.total } }
            .toList()
            .sortedByDescending { (_, total) -> total }
        val total = byClient.sumOf { (_, amount) -> amount }
        val names = byClient.take(3).joinToString { (clientId, amount) ->
            val name = clients.firstOrNull { it.id == clientId }?.name ?: "a client"
            "$name ${money(amount)}"
        }
        val more = if (byClient.size > 3) ", and ${byClient.size - 3} more" else ""
        val invoices = if (outstanding.size == 1) "invoice" else "invoices"
        return "${money(total)} outstanding across ${outstanding.size} $invoices: $names$more."
    }

    private suspend fun weeklyEarnings(): String {
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val visits = repository.observeVisits().first().filter { visit ->
            if (visit.missed) return@filter false
            val date = parseDate(visit.visitDate) ?: return@filter false
            !date.isBefore(monday) && !date.isAfter(today)
        }
        if (visits.isEmpty()) return "Nothing logged this week yet."
        val total = visits.sumOf { it.amount }
        val label = if (visits.size == 1) "visit" else "visits"
        return "${money(total)} so far this week, from ${visits.size} $label."
    }

    // --- helpers ----------------------------------------------------------

    /** Scheduled jobs with a usable timestamp, earliest first. */
    private suspend fun scheduledJobs(): List<Pair<ScheduledJob, Instant>> =
        repository.observeJobs().first()
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .sortedBy { (_, starts) -> starts }

    /** "eight hours", "8 hrs", "two and a half hours", "at eight hours". */
    private fun spokenHours(text: String): Double? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hrs?)").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()
        }
        Regex("([a-z]+(?:\\s+(?:and\\s+)?a\\s+half)?)\\s*(?:hours?|hrs?)").find(text)?.let {
            return parseSpokenNumber(it.groupValues[1])
        }
        return null
    }

    private fun dateFor(text: String): LocalDate = when {
        "day before yesterday" in text -> LocalDate.now().minusDays(2)
        "yesterday" in text -> LocalDate.now().minusDays(1)
        else -> LocalDate.now()
    }

    /**
     * Match a spoken client name. Every significant word of the name must be
     * in the sentence, and a word of five letters or more may be one letter
     * out — "Smiths" is heard as "smith" or "smiff" often enough to matter.
     */
    private fun matches(clientName: String, text: String): Boolean {
        val name = clientName.lowercase()
        if (name.isNotBlank() && text.contains(name)) return true
        val words = name.split(Regex("\\s+")).filter { it.length >= 3 && it !in STOP_WORDS }
        if (words.isEmpty()) return false
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.all { word ->
            tokens.any { token ->
                token == word ||
                    (token.length >= 3 && word.startsWith(token)) ||
                    (word.length >= 5 && token.length >= 5 && abs(word.length - token.length) <= 1 && plausible(token, word))
            }
        }
    }

    /** Cheap edit-distance check, bounded to one substitution/insertion. */
    private fun plausible(a: String, b: String): Boolean {        if (a == b) return true
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            edits++
            if (edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        return true
    }

    private companion object {
        /** Articles and company suffixes never identify a client on their own. */
        val STOP_WORDS = setOf("the", "and", "ltd", "limited", "llp")
    }
}
