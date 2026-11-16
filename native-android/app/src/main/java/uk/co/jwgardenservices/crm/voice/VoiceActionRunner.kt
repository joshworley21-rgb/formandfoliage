package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
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
import uk.co.jwgardenservices.crm.domain.topClients
import uk.co.jwgardenservices.crm.domain.round2
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    /** A destructive action waiting for a yes/no. */
    private data class ConfirmDone(
        val jobId: String,
        val clientName: String,
        val hours: Double,
        val description: String,
    )

    private var confirmDone: ConfirmDone? = null

    /** The last change that can be reversed with "undo". */
    private sealed interface Undo {
        data class Visit(val id: String, val clientName: String) : Undo
        data class Note(val clientId: String, val previous: String?, val clientName: String) : Undo
    }

    private var lastUndo: Undo? = null

    fun hasPending(): Boolean = pending != null

    fun clearPending() {
        pending = null
    }

    /**
     * Returns the sentence to speak when the command was executed, or null
     * when this runner cannot handle it (the caller falls back to the
     * generic confirmation).
     */
    suspend fun run(transcript: String, intent: IntentResult): String? {
        val text = transcript.lowercase()
        return when (intent.intent) {
            SpokenIntent.LogHours -> logHours(text)
            SpokenIntent.CheckSchedule -> scheduleFor(text)
            SpokenIntent.NextJob -> nextJob()
            SpokenIntent.NextJobAddress -> nextJobAddress()
            SpokenIntent.MarkJobDone -> markJobDone(text)
            SpokenIntent.WhoOwesMe -> whoOwesMe(text)
            SpokenIntent.WeeklyEarnings -> weeklyEarnings()
            SpokenIntent.MonthEarnings -> monthEarnings(text)
            SpokenIntent.HoursThisWeek -> hoursThisWeek(text)
            SpokenIntent.ClientInfo -> clientInfo(text)
            SpokenIntent.LastVisit -> lastVisit(text)
            SpokenIntent.BestClient -> bestClient()
            SpokenIntent.AddNote -> addNote(transcript)
            SpokenIntent.Unbilled -> unbilled()
            SpokenIntent.ClientList -> clientList()
            SpokenIntent.InvoiceStatus -> invoiceStatus(text)
            SpokenIntent.NextJobForClient -> nextJobForClient(text)
            SpokenIntent.TodayEarnings -> todayEarnings()
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
        val visitId = repository.saveVisit(draft)
        lastUndo = Undo.Visit(visitId, client.name)
        val whenText = if (date == LocalDate.now()) "" else " for ${date.dayOfMonth}/${date.monthValue}"
        return VoiceLines.pick(
            "Logged ${hoursLabel(hours)} for ${client.name}$whenText, ${money(draft.amount)}.",
            "Sorted — ${hoursLabel(hours)} down for ${client.name}$whenText, " +
                "${money(draft.amount)}.",
            "All logged. ${hoursLabel(hours)} at ${client.name}$whenText, " +
                "that's ${money(draft.amount)}.",
            "Done — ${hoursLabel(hours)} for ${client.name}$whenText goes on at " +
                "${money(draft.amount)}.",
        )
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
        val name = client?.name ?: "the client"
        confirmDone = ConfirmDone(job.id, name, hours, "Completed by voice")
        return VoiceLines.pick(
            "Mark the job for $name done, ${hoursLabel(hours)}? Say yes to confirm.",
            "That's $name at ${hoursLabel(hours)} — shall I mark it done? Say yes to confirm.",
            "About to mark $name's job done at ${hoursLabel(hours)}. Say yes to confirm, " +
                "or no to leave it.",
        )
    }

    /**
     * Answer a pending "are you sure?". Returns null when the sentence is not
     * a yes/no, in which case it has been cleared and the service treats the
     * sentence as a new command.
     */
    suspend fun answerConfirmation(transcript: String): String? {
        val waiting = confirmDone ?: return null
        val lower = transcript.lowercase().trim()

        // "No, four hours" / "actually make it four" adjusts the pending
        // confirmation instead of cancelling it, then asks again.
        val changed = spokenHours(lower)
            ?: Regex("\\bmake it ([a-z0-9]+)\\b").find(lower)
                ?.groupValues?.get(1)
                ?.let { parseSpokenNumber(it) }
        if (changed != null && changed > 0.0) {
            val updated = waiting.copy(hours = round2(changed))
            confirmDone = updated
            return VoiceLines.pick(
                "${hoursLabel(updated.hours)} for ${updated.clientName} then — say yes to confirm.",
                "I'll make it ${hoursLabel(updated.hours)} for ${updated.clientName}. " +
                    "Say yes to confirm.",
            )
        }

        confirmDone = null
        val words = lower
            .replace(Regex("[^a-z' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val short = words.isNotEmpty() && words.size <= 4
        return when {
            short && words.all { it in AFFIRM_WORDS } -> {
                repository.staffCompleteJob(waiting.jobId, waiting.hours, waiting.description)
                VoiceLines.pick(
                    "Marked the job for ${waiting.clientName} done, ${hoursLabel(waiting.hours)}.",
                    "Done — ${waiting.clientName} is marked down at ${hoursLabel(waiting.hours)}.",
                    "${waiting.clientName}'s job is closed off, ${hoursLabel(waiting.hours)}.",
                )
            }

            short && words.all { it in CANCEL_WORDS } -> VoiceLines.pick(
                "Cancelled.",
                "Right, I've left it alone.",
                "No problem, cancelled.",
            )

            else -> null
        }
    }

    /** Reverse the last reversible action: a logged visit or a client note. */
    suspend fun undoLast(): String {
        val action = lastUndo ?: return "Nothing to undo."
        lastUndo = null
        return when (action) {
            is Undo.Visit -> {
                runCatching { repository.deleteVisit(action.id) }
                    .getOrElse { return "I couldn't remove that visit." }
                VoiceLines.pick(
                    "Removed the visit for ${action.clientName}.",
                    "Taken off — the visit for ${action.clientName} is gone.",
                    "Done, ${action.clientName}'s visit is deleted.",
                )
            }

            is Undo.Note -> {
                val client = repository.client(action.clientId)
                    ?: return "That client is gone."
                repository.updateClient(client, clientDraft(client, action.previous))
                VoiceLines.pick(
                    "Removed that note for ${action.clientName}.",
                    "Right, the note for ${action.clientName} is gone.",
                )
            }
        }
    }

    /** Add a dated note to a client's existing notes. */
    private suspend fun addNote(transcript: String): String {
        val clients = repository.observeClients().first()
        val match = Regex(
            "\\bnote\\s+(?:for|to|about|that)\\s+(.+)",
            RegexOption.IGNORE_CASE,
        ).find(transcript) ?: return "What should I note?"
        val body = match.groupValues[1]
        val client = clients.firstOrNull { matches(it.name, body.lowercase()) }
            ?: return "Which client is that note for?"
        // "note for the Smiths: gate sticks" keeps the colon body; otherwise
        // strip the client's own name so it is not read back as the note.
        val withoutName = body.replaceFirst(
            Regex(Regex.escape(client.name), RegexOption.IGNORE_CASE),
            " ",
        )
        val note = (if (body.contains(':')) body.substringAfter(':') else withoutName)
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart(':','-','–','—',',')
            .trim()
            .replaceFirst(Regex("^(?:that|about|to|the)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (note.isBlank() || note.equals(client.name, ignoreCase = true)) {
            return "What should I note for ${client.name}?"
        }
        val previous = client.notes
        val combined = buildString {
            previous?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append('\n') }
            append(LocalDate.now()).append(": ").append(note)
        }
        repository.updateClient(client, clientDraft(client, combined))
        lastUndo = Undo.Note(client.id, previous, client.name)
        return VoiceLines.pick(
            "Noted for ${client.name}.",
            "Got it — that's on ${client.name}'s notes.",
            "Written down for ${client.name}.",
        )
    }

    private fun clientDraft(client: Client, notes: String?) = ClientDraft(
        name = client.name,
        address = client.address,
        phone = client.phone,
        email = client.email,
        notes = notes,
        billingMode = client.billingMode,
        hourlyRate = client.hourlyRate,
        fixedPrice = client.fixedPrice,
        paymentReminders = client.paymentReminders,
    )

    private suspend fun unbilled(): String {
        val clients = repository.observeClients().first()
        val groups = repository.observeVisits().first()
            .filter { !it.billed && !it.missed }
            .groupBy { it.clientId }
            .map { (id, rows) -> Triple(id, rows.size, rows.sumOf { it.amount }) }
            .sortedByDescending { it.third }
        if (groups.isEmpty()) return "Nothing is waiting to be invoiced."
        val names = groups.take(3).joinToString { (id, count, total) ->
            val name = clients.firstOrNull { it.id == id }?.name ?: "a client"
            "$name ${money(total)} ($count ${if (count == 1) "visit" else "visits"})"
        }
        val more = if (groups.size > 3) ", and ${groups.size - 3} more" else ""
        return "${groups.size} clients with unbilled work: $names$more."
    }

    private suspend fun clientList(): String {
        val clients = repository.observeClients().first()
            .filter { it.active }
            .sortedBy { it.name }
        if (clients.isEmpty()) return "No clients yet."
        val names = clients.take(12).joinToString { it.name }
        val more = if (clients.size > 12) ", and ${clients.size - 12} more" else ""
        return "${clients.size} clients: $names$more."
    }

    private suspend fun invoiceStatus(text: String): String {
        val client = repository.observeClients().first()
            .firstOrNull { matches(it.name, text) }
            ?: return "Which client's invoice?"
        val latest = repository.observeInvoices().first()
            .filter { it.clientId == client.id }
            .maxByOrNull { parseDate(it.issueDate) ?: LocalDate.MIN }
            ?: return "I have no invoices for ${client.name}."
        val status = when (latest.status) {
            "paid" -> "is paid"
            "draft" -> "is still a draft"
            "sent" -> "has been sent"
            else -> "is ${latest.status}"
        }
        val due = latest.dueDate
            ?.let { parseDate(it)?.formatDayMonth() }
            ?.let { " It was due $it." }
            .orEmpty()
        return "${client.name}'s latest invoice ${latest.number} for " +
            "${money(latest.total)} $status.$due"
    }

    private suspend fun nextJobForClient(text: String): String {
        val client = repository.observeClients().first()
            .firstOrNull { matches(it.name, text) }
            ?: return "Which client?"
        val now = Instant.now()
        val next = scheduledJobs()
            .firstOrNull { (job, starts) -> job.clientId == client.id && !starts.isBefore(now) }
            ?: return "Nothing else is booked for ${client.name}."
        val starts = next.second
        val whenText = if (starts.localDate() == LocalDate.now()) {
            "today at ${starts.formatTime()}"
        } else {
            "${starts.formatWeekday()} at ${starts.formatTime()}"
        }
        return "${client.name} is next booked $whenText."
    }

    private suspend fun todayEarnings(): String {
        val today = LocalDate.now()
        val visits = repository.observeVisits().first().filter { visit ->
            !visit.missed && parseDate(visit.visitDate) == today
        }
        if (visits.isEmpty()) return "Nothing logged today."
        return "Today: ${money(visits.sumOf { it.amount })} from ${visits.size} visits."
    }

    private suspend fun whoOwesMe(text: String): String {
        val clients = repository.observeClients().first()
        val named = clients.firstOrNull { matches(it.name, text) }
        val outstanding = repository.observeInvoices().first()
            .filter { it.status != "paid" && it.status != "draft" && it.total > 0 }
            .filter { named == null || it.clientId == named.id }

        if (outstanding.isEmpty()) {
            return if (named != null) {
                "${named.name} doesn't owe you anything."
            } else {
                "Nobody owes you anything. Every invoice is paid."
            }
        }
        if (named != null) {
            val count = outstanding.size
            return "${named.name} owes ${money(outstanding.sumOf { it.total })} across " +
                "$count ${if (count == 1) "invoice" else "invoices"}."
        }

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

    private suspend fun nextJobAddress(): String {
        val clients = repository.observeClients().first()
        val now = Instant.now()
        val next = scheduledJobs().firstOrNull { (_, starts) -> !starts.isBefore(now) }
            ?: return "Nothing else is booked."
        val client = clients.firstOrNull { it.id == next.first.clientId }
        val name = client?.name ?: "a client"
        val address = client?.address?.trim().orEmpty()
        val starts = next.second
        val whenText = if (starts.localDate() == LocalDate.now()) {
            "today at ${starts.formatTime()}"
        } else {
            "${starts.formatWeekday()} at ${starts.formatTime()}"
        }
        return if (address.isBlank()) {
            "Next is $name, $whenText. I have no address for them."
        } else {
            "Next is $name at $address, $whenText."
        }
    }

    private suspend fun hoursThisWeek(text: String): String {
        val today = LocalDate.now()
        val todayOnly = text.contains("today")
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val from = if (todayOnly) today else monday
        val visits = repository.observeVisits().first().filter { visit ->
            if (visit.missed) return@filter false
            val date = parseDate(visit.visitDate) ?: return@filter false
            !date.isBefore(from) && !date.isAfter(today)
        }
        if (visits.isEmpty()) return "Nothing logged ${if (todayOnly) "today" else "this week"} yet."
        val label = if (todayOnly) "today" else "this week"
        return "${hoursLabel(visits.sumOf { it.hours })} $label across ${visits.size} visits."
    }

    private suspend fun monthEarnings(text: String): String {
        val thisMonth = text.contains("this month")
        val target = YearMonth.from(LocalDate.now()).let { if (thisMonth) it else it.minusMonths(1) }
        val visits = repository.observeVisits().first().filter { visit ->
            if (visit.missed) return@filter false
            val date = parseDate(visit.visitDate) ?: return@filter false
            YearMonth.from(date) == target
        }
        val label = if (thisMonth) "This month" else "Last month"
        if (visits.isEmpty()) return "$label you haven't logged any work yet."
        return "$label you earned ${money(visits.sumOf { it.amount })} from ${visits.size} visits."
    }

    private suspend fun clientInfo(text: String): String {
        val client = repository.observeClients().first()
            .firstOrNull { matches(it.name, text) }
            ?: return "I couldn't match a client in that."
        val details = listOfNotNull(
            client.phone?.takeIf { it.isNotBlank() }?.let { "phone $it" },
            client.email?.takeIf { it.isNotBlank() }?.let { "email $it" },
            client.address?.takeIf { it.isNotBlank() }?.let { "address $it" },
        )
        return if (details.isEmpty()) {
            "I have no contact details for ${client.name}."
        } else {
            "${client.name}: ${details.joinToString(", ")}."
        }
    }

    private suspend fun lastVisit(text: String): String {
        val client = repository.observeClients().first()
            .firstOrNull { matches(it.name, text) }
            ?: return "I couldn't match a client in that."
        val last = repository.observeVisits().first()
            .filter { it.clientId == client.id && !it.missed }
            .mapNotNull { visit -> parseDate(visit.visitDate)?.let { it to visit } }
            .maxByOrNull { it.first }
            ?: return "I have no visits logged for ${client.name}."
        return "Last visit to ${client.name} was ${last.first.formatDayMonthYear()}, " +
            "${hoursLabel(last.second.hours)}."
    }

    private suspend fun bestClient(): String {
        val invoices = repository.observeInvoices().first()
        val since = LocalDate.now().minusYears(1)
        val top = topClients(invoices, since, limit = 1).firstOrNull()
            ?: return "No invoiced work yet."
        val name = repository.observeClients().first()
            .firstOrNull { it.id == top.clientId }?.name ?: "A client"
        return "$name is your best client, ${money(top.total)} in the last year."
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

        /** Natural ways to accept a pending confirmation. */
        val AFFIRM_WORDS = setOf(
            "yes", "yeah", "yep", "yup", "ya", "aye", "sure", "right", "correct",
            "spot", "on", "do", "it", "done", "go", "ahead", "cheers", "good",
            "great", "lovely", "okay", "ok", "alright", "absolutely", "definitely",
            "please", "thanks", "thank", "you", "that", "that's", "fine",
            "confirmed", "confirm", "agree", "accepted", "proper", "sorted",
            "sounds", "brilliant", "perfect", "cracking", "dead",
        )

        /** Natural ways to abandon a pending confirmation. */
        val CANCEL_WORDS = setOf(
            "no", "nope", "nah", "cancel", "stop", "leave", "it", "don't", "dont",
            "do", "not", "forget", "scratch", "that", "never", "mind", "bother",
            "wait", "hold", "scrap", "actually", "wrong", "rubbish",
        )
    }
}
