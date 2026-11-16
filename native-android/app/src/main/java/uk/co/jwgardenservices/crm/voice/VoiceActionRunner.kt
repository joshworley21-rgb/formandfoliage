package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.data.model.NewInvoiceInput
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.data.repository.createInvoice
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
class VoiceActionRunner(
    private val repository: CrmRepository = ServiceLocator.crmRepository,
    /**
     * Builds the PDF and emails a draft invoice. Supplied by the service,
     * which owns the Context; null only in tests, where sending is refused.
     */
    private val sendInvoice: (suspend (String) -> String)? = null,
) {

    private data class PendingLog(
        val client: Client?,
        val date: LocalDate,
        val hours: Double?,
    )

    private var pending: PendingLog? = null

    /**
     * A destructive action waiting for a yes/no. [visitId] is set when hours
     * were already logged for this job, so confirming links that visit rather
     * than creating a second one and billing the day twice.
     */
    private data class ConfirmDone(
        val jobId: String,
        val clientName: String,
        val hours: Double,
        val description: String,
        val visitId: String? = null,
    )

    private var confirmDone: ConfirmDone? = null

    /** The last change that can be reversed with "undo". */
    private sealed interface Undo {
        data class Visit(val id: String, val clientName: String) : Undo
        data class Note(val clientId: String, val previous: String?, val clientName: String) : Undo
    }

    private var lastUndo: Undo? = null

    /** The visit voice most recently wrote, for follow-up corrections. */
    private data class VoiceVisit(
        val id: String,
        val clientId: String,
        val clientName: String,
        val materials: Double,
    )

    private var lastVoiceVisit: VoiceVisit? = null

    /** A draft invoice waiting for "send it" or "leave it". */
    private data class PendingSend(
        val invoiceId: String,
        val clientName: String,
        val total: Double,
    )

    private var pendingSend: PendingSend? = null

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
            SpokenIntent.DayWrapUp -> startWrapUp()
            SpokenIntent.RaiseInvoice -> raiseInvoice(text)
            SpokenIntent.AdjustCharge -> adjustCharge(text)
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
        return saveHours(client, hours, pending.date, transcript)
    }

    // --- end-of-day wrap-up ----------------------------------------------
    //
    // One conversational loop over everything still scheduled today: ask how
    // long each job took, log the visit, complete the job, and move straight
    // to the next one. Answers may be hours ("three and a half"), a plain
    // yes (booked duration), "as booked", or "skip"; "stop" ends the loop.
    // The visits are collected per client so the service can raise one draft
    // invoice each once the call is done.

    private data class WrapUpItem(
        val jobId: String,
        val clientId: String,
        val clientName: String,
        val note: String?,
        val bookedHours: Double,
    )

    private class WrapUp(val items: List<WrapUpItem>) {
        var index = 0
        var jobsLogged = 0
        var skipped = 0
        var hoursLogged = 0.0
        var amountLogged = 0.0

        fun current(): WrapUpItem = items[index]
        fun hasNext(): Boolean = index + 1 < items.size
    }

    private var wrapUp: WrapUp? = null
    private val wrapUpBillables = linkedMapOf<String, MutableList<String>>()

    /** True while a wrap-up question is waiting for its answer. */
    fun wrapUpActive(): Boolean = wrapUp != null

    fun clearWrapUp() {
        wrapUp = null
        wrapUpBillables.clear()
        // A half-finished "log hours" question must not outlive the call or
        // hijack the first answer inside a new wrap-up.
        pending = null
        confirmDone = null
        // Corrections and "send it" only apply inside the call that raised
        // them; never let a visit from last week be adjusted by accident.
        pendingSend = null
        lastVoiceVisit = null
    }

    /** Hand the visits of a finished wrap-up to the caller for invoicing. */
    fun takeWrapUpBillables(): Map<String, List<String>> {
        val copy = wrapUpBillables.mapValues { (_, visits) -> visits.toList() }
        wrapUpBillables.clear()
        return copy
    }

    /** Ask for draft invoices per client; returns how many the server made. */
    suspend fun raiseDraftInvoices(billables: Map<String, List<String>>): Int {
        var raised = 0
        for ((clientId, visitIds) in billables) {
            val made = runCatching {
                repository.createInvoice(NewInvoiceInput(clientId = clientId, visitIds = visitIds))
            }.getOrNull()
            if (made != null) raised++
        }
        return raised
    }

    /**
     * Start the loop over today's unfinished jobs. Returns the first question
     * (ending in "?"), or a one-line all-done answer.
     */
    suspend fun startWrapUp(): String {
        clearWrapUp()
        val now = Instant.now()
        val today = now.localDate()
        val clients = repository.observeClients().first().associateBy { it.id }
        val items = scheduledJobs()
            .filter { (_, starts) -> starts.localDate() == today }
            .map { (job, _) ->
                WrapUpItem(
                    jobId = job.id,
                    clientId = job.clientId,
                    clientName = clients[job.clientId]?.name ?: "a client",
                    note = job.notes?.trim()?.takeIf { it.isNotEmpty() }?.take(70),
                    bookedHours = round2((job.durationMinutes / 60.0).coerceAtLeast(0.5)),
                )
            }
        if (items.isEmpty()) {
            return "Nothing left on today's schedule — everything is already marked done."
        }
        val state = WrapUp(items)
        wrapUp = state
        return prompt(state, first = true)
    }

    /**
     * Answer a wrap-up question. Returns null when no wrap-up is running, so
     * the caller can classify the sentence normally.
     */
    suspend fun continueWrapUp(transcript: String): String? {
        val state = wrapUp ?: return null
        val text = transcript.lowercase().trim()
        if (text.isEmpty()) return null

        if (isWrapUpStop(text)) {
            wrapUp = null
            return summary(state, stopped = true)
        }
        // "Run through today's jobs" again restarts from the freshest list,
        // so a job added or removed mid-loop is picked up.
        if (IntentRules.classify(transcript).intent == SpokenIntent.DayWrapUp) {
            return startWrapUp()
        }

        val item = state.current()
        val hours = wrapUpHours(text)
        val asBooked = text.contains("as booked") || text.contains("same as booked")
        val skip = hours == null && isWrapUpSkip(text)

        return when {
            hours != null -> {
                val line = logWrapUpVisit(state, item, hours)
                advance(state, line)
            }
            asBooked || isWrapUpYes(text) -> {
                val line = logWrapUpVisit(state, item, item.bookedHours)
                advance(state, line)
            }
            skip -> {
                state.skipped++
                advance(state, "Skipped ${item.clientName}.")
            }
            else -> "How many hours for ${item.clientName}? Or say skip."
        }
    }

    private fun prompt(state: WrapUp, first: Boolean): String {
        val item = state.current()
        val lead = when {
            first && state.items.size == 1 -> "Only one left"
            first -> "First one"
            !state.hasNext() -> "Last one"
            else -> "Next"
        }
        val detail = item.note?.let { ", $it" }.orEmpty()
        return "$lead: ${item.clientName}$detail. How long did that take?"
    }

    private fun advance(state: WrapUp, loggedLine: String): String {
        if (!state.hasNext()) {
            wrapUp = null
            return "$loggedLine ${summary(state)}"
        }
        state.index++
        return "$loggedLine ${prompt(state, first = false)}"
    }

    private suspend fun logWrapUpVisit(
        state: WrapUp,
        item: WrapUpItem,
        hours: Double,
    ): String {
        val safeHours = round2(hours.coerceIn(0.25, 24.0))
        val client = repository.observeClients().first().firstOrNull { it.id == item.clientId }
        val amount = client?.let {
            priceVisit(BillingSource(it.billingMode, it.hourlyRate, it.fixedPrice), safeHours, 0.0)
        } ?: 0.0
        val visit = VisitDraft(
            clientId = item.clientId,
            visitDate = LocalDate.now().toString(),
            hours = safeHours,
            materialsCost = 0.0,
            description = item.note ?: "End of day wrap-up",
            amount = round2(amount),
            completesJobId = item.jobId,
            jobOutcome = "done",
        )
        val visitId = repository.saveVisit(visit)
        wrapUpBillables.getOrPut(item.clientId) { mutableListOf() } += visitId
        lastUndo = Undo.Visit(visitId, item.clientName)
        lastVoiceVisit = VoiceVisit(visitId, item.clientId, item.clientName, materials = 0.0)
        state.jobsLogged++
        state.hoursLogged = round2(state.hoursLogged + safeHours)
        state.amountLogged = round2(state.amountLogged + visit.amount)
        // Ultra-short cadence: inside a rapid loop a full read-back on every
        // job feels like a machine; the totals come once at the end.
        return VoiceLines.pick(
            "Logged ${hoursLabel(safeHours)}, ${money(visit.amount)}.",
            "${hoursLabel(safeHours)} down, ${money(visit.amount)}.",
            "Done, ${hoursLabel(safeHours)}, ${money(visit.amount)}.",
            "That's in, ${hoursLabel(safeHours)}.",
        )
    }

    private fun summary(state: WrapUp, stopped: Boolean = false): String {
        if (state.jobsLogged == 0) {
            val skipped = if (state.skipped > 0) " ${state.skipped} skipped." else ""
            return "Nothing logged.$skipped"
        }
        val jobs = if (state.jobsLogged == 1) "one job" else "${state.jobsLogged} jobs"
        val skipped = if (state.skipped > 0) ", ${state.skipped} skipped" else ""
        val lead = if (stopped) "Stopped" else "That's the lot"
        return "$lead — $jobs, ${hoursLabel(state.hoursLogged)}, " +
            "${money(state.amountLogged)}$skipped."
    }

    /** Hours for a wrap-up answer: "3.5", "three and a half", "45 minutes". */
    private fun wrapUpHours(text: String): Double? {
        spokenHours(text)?.let { return it }
        val cleaned = text.replace(Regex("[^a-z0-9.\\s]"), " ").trim()
        if (cleaned == "half") return 0.5
        if (cleaned == "quarter") return 0.25
        Regex("^(\\d+(?:\\.\\d+)?)$").find(cleaned)?.let {
            return it.groupValues[1].toDoubleOrNull()
        }
        Regex("([a-z0-9 ]+?)\\s*(?:minutes?|mins?)\\b").find(cleaned)?.let { match ->
            parseSpokenNumber(match.groupValues[1].trim())?.let { minutes ->
                return round2(minutes / 60.0)
            }
        }
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.all { it in WRAP_UP_NUMBERS }) {
            return parseSpokenNumber(words.joinToString(" "))
        }
        return null
    }

    private fun isWrapUpYes(text: String): Boolean {
        val words = text.replace(Regex("[^a-z' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return words.isNotEmpty() && words.size <= 4 && words.all { it in WRAP_UP_AFFIRM }
    }

    private fun isWrapUpSkip(text: String): Boolean {
        val words = text.replace(Regex("[^a-z' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return words.any { it in WRAP_UP_SKIP } ||
            text.contains("didn't") ||
            text.contains("did not") ||
            text.contains("not done") ||
            text.contains("no job")
    }

    private fun isWrapUpStop(text: String): Boolean {
        val words = text.replace(Regex("[^a-z' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return words.any { it in WRAP_UP_STOP } ||
            text.contains("stop the wrap") ||
            text.contains("cancel the wrap") ||
            text.contains("that's enough") ||
            text.contains("thats enough")
    }

    // --- actions ----------------------------------------------------------

    private suspend fun logHours(text: String): String {
        val hours = spokenHours(text)
        val date = dateFor(text)
        val clients = repository.observeClients().first()
        // No name spoken: the call briefing just said where the owner is, so
        // use the job on the schedule right now. Today only — "log four hours
        // yesterday" must not land on this afternoon's client.
        val client = clients.firstOrNull { matches(it.name, text) }
            ?: if (date == LocalDate.now()) inferredClient(clients) else null

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
        return saveHours(client, hours, date, transcript)
    }

    private suspend fun saveHours(
        client: Client,
        hours: Double,
        date: LocalDate,
        source: String = "",
    ): String {
        val draft = VisitDraft(
            clientId = client.id,
            visitDate = date.toString(),
            hours = round2(hours),
            materialsCost = 0.0,
            // The shorthand the owner said ("maintenance", "hedge") becomes
            // the website's own line-item wording for the visit, the invoice
            // PDF and the client email; the spoken reply stays short.
            description = TradeTerms.lineItemFor(source) ?: "Logged by voice",
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
        lastVoiceVisit = VoiceVisit(visitId, client.id, client.name, materials = 0.0)
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
            // "Mark it done" straight after the briefing: finish the slot the
            // owner is on, not whichever job happens to come first.
            else -> activeJob() ?: jobs.firstOrNull { (_, starts) -> starts.localDate() == today }
                ?: jobs.first()
        } ?: return "I couldn't find a job for ${named?.name ?: "that client"}."

        val job = target.first
        val client = clients.firstOrNull { it.id == job.clientId }
        val name = client?.name ?: "the client"
        // "Log four hours and mark it done": the visit is already saved, so
        // confirm those hours and close the job against that visit. Summing
        // them again would bill the day twice. The visit just logged wins —
        // locally it has no createdAt yet, so server rows would outrank it.
        val todaysVisits = repository.observeVisits().first()
            .filter { it.clientId == job.clientId && !it.missed && parseDate(it.visitDate) == today }
        val justLogged = (lastUndo as? Undo.Visit)
            ?.takeIf { it.clientName == name }
            ?.let { undo -> todaysVisits.firstOrNull { it.id == undo.id } }
        val todaysVisit = justLogged ?: todaysVisits.maxByOrNull { it.createdAt ?: "" }
        val hours = round2(
            todaysVisit?.hours?.takeIf { it > 0.0 }
                ?: (job.durationMinutes / 60.0).coerceAtLeast(0.5),
        )
        confirmDone = ConfirmDone(job.id, name, hours, "Completed by voice", todaysVisit?.id)
        return if (todaysVisit != null) {
            VoiceLines.pick(
                "Mark the job for $name done with the ${hoursLabel(hours)} already logged? " +
                    "Say yes to confirm.",
                "That's $name at ${hoursLabel(hours)}, already down. Mark it done? " +
                    "Say yes to confirm.",
            )
        } else {
            VoiceLines.pick(
                "Mark the job for $name done, ${hoursLabel(hours)}? Say yes to confirm.",
                "That's $name at ${hoursLabel(hours)} — shall I mark it done? Say yes to confirm.",
                "About to mark $name's job done at ${hoursLabel(hours)}. Say yes to confirm, " +
                    "or no to leave it.",
            )
        }
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
                val visitId = waiting.visitId
                if (visitId != null) {
                    // Close the job against hours that are already recorded.
                    repository.completeJobWithVisit(waiting.jobId, visitId)
                } else {
                    repository.staffCompleteJob(waiting.jobId, waiting.hours, waiting.description)
                }
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

    /**
     * "Raise an invoice for Simon" always creates a DRAFT and reads it back.
     * Sending stays a deliberate screen action: money only moves when the
     * owner has seen the line items, and a misheard sentence cannot email a
     * client.
     */
    private suspend fun raiseInvoice(text: String): String {
        val clients = repository.observeClients().first()
        val client = clients.firstOrNull { matches(it.name, text) } ?: inferredClient(clients)
            ?: return "Which client is the invoice for?"

        val visits = repository.observeVisits().first()
            .filter {
                it.clientId == client.id &&
                    !it.missed &&
                    !it.billed &&
                    it.invoiceId == null &&
                    it.hours > 0.0
            }
        if (visits.isEmpty()) {
            return "There's nothing unbilled for ${client.name}."
        }
        val expected = round2(visits.sumOf { it.amount })
        val made = runCatching {
            repository.createInvoice(
                NewInvoiceInput(clientId = client.id, visitIds = visits.map { it.id }),
            )
        }.getOrNull()
            ?: return "I couldn't raise that without a signal. The ${visits.size} " +
                "${if (visits.size == 1) "visit will be" else "visits will be"} waiting in " +
                "ready to bill."

        val visitsText = if (visits.size == 1) "one visit" else "${visits.size} visits"
        val totalValue = if (made.total > 0.0) made.total else expected
        pendingSend = PendingSend(made.invoiceId, client.name, totalValue)
        return VoiceLines.pick(
            "Draft invoice for ${client.name} — $visitsText, ${money(totalValue)}. " +
                "Send it, or leave it in drafts?",
            "That's a draft for ${client.name}, $visitsText, ${money(totalValue)}. " +
                "Send it now, or leave it?",
            "In drafts: ${client.name}, $visitsText, ${money(totalValue)}. " +
                "Send it, or leave it there?",
        )
    }

    /**
     * The answer to "say send it". Deliberately a separate two-step: money
     * only leaves the phone after a spoken confirmation with the total in it.
     * Anything that is not a yes or a no clears the question and is
     * classified normally.
     */
    suspend fun continueSend(transcript: String): String? {
        val pending = pendingSend ?: return null
        val lower = transcript.lowercase().trim()
        val words = lower.replace(Regex("[^a-z' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val yes = words.any { it in SEND_YES } && words.none { it in SEND_NO }
        val no = words.any { it in SEND_NO }
        return when {
            yes -> {
                pendingSend = null
                val sender = sendInvoice ?: return "Open the invoice to send it — I can't attach the PDF from here."
                sender(pending.invoiceId)
            }
            no -> {
                pendingSend = null
                "Left it in drafts — ${pending.clientName}, ${money(pending.total)}."
            }
            else -> {
                pendingSend = null
                null
            }
        }
    }

    /**
     * Follow-up corrections to the last voice-logged visit: "add £40 for
     * green waste" changes the materials and re-prices it, "change the rate
     * to £45 an hour" updates the client's rate and re-prices the visit.
     */
    private suspend fun adjustCharge(text: String): String {
        val last = lastVoiceVisit ?: return "There's no voice-logged visit to adjust yet."
        val lower = text.lowercase()

        val rateMatch = Regex("rate\\s+(?:to|of|at)\\s*£?(\\d+(?:\\.\\d+)?)").find(lower)
        val rate = rateMatch?.groupValues?.get(1)?.toDoubleOrNull()
        if (rate != null) return changeRate(last, rate)

        val added = Regex("add\\s*£?(\\d+(?:\\.\\d+)?)\\s*(?:for|of|on)?\\s*(.*)").find(lower)
            ?: return "Add how much, and for what?"
        val amount = added.groupValues[1].toDoubleOrNull() ?: return "Add how much?"
        val note = added.groupValues[2].trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .dropWhile { it in NOTE_FILLER }
            .joinToString(" ")
            .trim()
            .ifBlank { "extra materials" }
        return repriced(last, last.materials + amount, note)
    }

    private suspend fun changeRate(last: VoiceVisit, rate: Double): String {
        if (rate <= 0.0 || rate > 1000.0) return "That rate doesn't look right."
        val client = repository.observeClients().first().firstOrNull { it.id == last.clientId }
            ?: return "That visit's client is missing."
        repository.updateClient(
            client,
            ClientDraft(
                name = client.name,
                address = client.address,
                phone = client.phone,
                email = client.email,
                notes = client.notes,
                billingMode = "hourly",
                hourlyRate = rate,
                fixedPrice = client.fixedPrice,
                paymentReminders = client.paymentReminders,
            ),
        )
        val line = repriced(last, last.materials, null)
        return "$line ${client.name}'s rate is £${format(rate)} an hour from now on."
    }

    /** Apply a materials change to the visit, then re-price and read it back. */
    private suspend fun repriced(
        last: VoiceVisit,
        materials: Double,
        note: String?,
    ): String {
        val current = repository.observeVisits().first().firstOrNull { it.id == last.id }
            ?: return "That visit has gone."
        if (current.billed || current.invoiceId != null) {
            return "That visit is already on an invoice — adjust it there."
        }
        val client = repository.observeClients().first().firstOrNull { it.id == current.clientId }
            ?: return "That visit's client is missing."
        val safeMaterials = round2(materials.coerceAtLeast(0.0))
        val amount = round2(
            priceVisit(
                BillingSource(client.billingMode, client.hourlyRate, client.fixedPrice),
                current.hours,
                safeMaterials,
            ),
        )
        repository.saveVisit(
            VisitDraft(
                id = current.id,
                clientId = current.clientId,
                visitDate = current.visitDate,
                hours = current.hours,
                materialsCost = safeMaterials,
                materialsNote = note ?: current.materialsNote,
                description = current.description,
                amount = amount,
                missed = current.missed,
            ),
        )
        lastVoiceVisit = last.copy(materials = safeMaterials)
        val extra = note?.takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty()
        return VoiceLines.pick(
            "Sorted — that visit is ${money(amount)} now$extra.",
            "Added. It comes to ${money(amount)}$extra.",
        )
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

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

    /**
     * The job the owner is most likely standing in: the booked window
     * covering this minute, else the latest that has already started today
     * (a visit that overran still counts). Today only, and never a slot that
     * has not begun yet — logging hours before a job starts is a mistake,
     * not an inference.
     */
    private suspend fun activeJob(): Pair<ScheduledJob, Instant>? {
        val now = Instant.now()
        val today = now.localDate()
        val todays = scheduledJobs().filter { (_, starts) -> starts.localDate() == today }
        if (todays.isEmpty()) return null
        todays.firstOrNull { (job, starts) ->
            !now.isBefore(starts) &&
                now.isBefore(starts.plusSeconds(job.durationMinutes.coerceAtLeast(15) * 60L))
        }?.let { return it }
        return todays.lastOrNull { (_, starts) -> !starts.isAfter(now) }
    }

    /** The client for the slot the owner is on, when one is booked today. */
    private suspend fun inferredClient(clients: List<Client>): Client? {
        val (job, _) = activeJob() ?: return null
        return clients.firstOrNull { it.id == job.clientId }
    }

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

        /** The words an hours answer to a wrap-up question may contain. */
        val WRAP_UP_NUMBERS = setOf(
            "and", "a", "an", "half", "quarter", "quarters", "point",
            "hour", "hours", "hrs", "minute", "minutes", "min", "mins",
            "zero", "oh", "nought", "one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
            "sixty", "seventy", "eighty", "ninety", "hundred",
        )

        /** "Yeah, spot on", "did that", "went fine". */
        val WRAP_UP_AFFIRM = AFFIRM_WORDS + setOf("did", "went", "all", "finished")

        /** Ways to pass over a job without logging anything. */
        val WRAP_UP_SKIP = setOf("skip", "skipped", "no", "nope", "nah", "missed")

        /** Ways to end the loop early. */
        val WRAP_UP_STOP = setOf("stop", "cancel", "exit", "quit", "enough")

        /** "say send it" answers for a draft invoice. */
        val SEND_YES = setOf(
            "send", "sent", "email", "post", "yes", "yeah", "yep", "yup",
            "go", "ahead", "do", "it", "please", "now",
        )

        val SEND_NO = setOf(
            "no", "nope", "nah", "don't", "dont", "leave", "hold", "later",
            "drafts", "cancel", "wait",
        )

        /** Words in "add £40 to that visit" that are not a materials note. */
        val NOTE_FILLER = setOf(
            "the", "to", "on", "for", "that", "it", "this", "visit", "invoice", "and",
        )

        /** Natural ways to abandon a pending confirmation. */
        val CANCEL_WORDS = setOf(
            "no", "nope", "nah", "cancel", "stop", "leave", "it", "don't", "dont",
            "do", "not", "forget", "scratch", "that", "never", "mind", "bother",
            "wait", "hold", "scrap", "actually", "wrong", "rubbish",
        )
    }
}
