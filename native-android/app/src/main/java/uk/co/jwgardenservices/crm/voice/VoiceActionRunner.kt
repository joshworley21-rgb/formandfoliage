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
import uk.co.jwgardenservices.crm.data.repository.deleteInvoice
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
import java.time.LocalDateTime
import java.time.LocalTime
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

    /** The last change that can be reversed with "undo". */
    private sealed interface Undo {
        enum class Kind { TimeLog, Expense, CompletedJob }

        data class Visit(
            val id: String,
            val clientName: String,
            val kind: Kind = Kind.TimeLog,
            /** False when the visit predates the undone action (hours stay). */
            val deleteVisit: Boolean = true,
        ) : Undo

        data class Note(val clientId: String, val previous: String?, val clientName: String) : Undo
    }

    private var lastUndo: Undo? = null

    /** A debt follow-up waiting for "flag it" or "leave it". */
    private data class PendingChase(
        val clientId: String,
        val clientName: String,
        val amount: Double,
    )

    private var pendingChase: PendingChase? = null

    /** Two clients matched too closely: the next sentence picks one. */
    private data class PendingClarification(
        val transcript: String,
        val intent: IntentResult,
        val candidates: List<String>,
    )

    private var pendingClarification: PendingClarification? = null

    /** Set while re-running an intent for a client the clarification chose. */
    private var resolvedClientId: String? = null

    /** The visit voice most recently wrote, for follow-up corrections. */
    private data class VoiceVisit(
        val id: String,
        val clientId: String,
        val clientName: String,
        val materials: Double,
    )

    private var lastVoiceVisit: VoiceVisit? = null

    /** A list answer offered in full, waiting for "read them all". */
    private var pendingReadAll: List<String>? = null

    private fun tokenWords(lower: String): List<String> = lower
        .replace(Regex("[^a-z' ]"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    /** "Read them all" / "No thanks" for a chunked list. */
    suspend fun continueReadAll(transcript: String): String? =
        pivot(continueReadAllImpl(transcript))

    private suspend fun continueReadAllImpl(transcript: String): String? {
        val items = pendingReadAll ?: return null
        val words = tokenWords(transcript.lowercase())
        val yes = words.any { it in SEND_YES } && words.none { it in SEND_NO }
        val no = words.any { it in SEND_NO }
        return when {
            yes -> {
                pendingReadAll = null
                slow("Right — ${items.joinToString("; ")}.")
            }
            no -> {
                pendingReadAll = null
                fast("No problem.")
            }
            else -> {
                pendingReadAll = null
                null
            }
        }
    }

    /** A draft invoice waiting for "send it" or "leave it". */
    private data class PendingSend(
        val invoiceId: String,
        val clientName: String,
        val total: Double,
    )

    private var pendingSend: PendingSend? = null

    /**
     * If the last reply was talked over, open this one with a small pivot so
     * the assistant acknowledges the interruption instead of barrelling on.
     * The pace marker stays first; the flag is spent exactly once.
     */
    suspend fun pivot(reply: String?): String? {
        if (reply == null) return null
        if (!AssistantMemory.wasInterrupted) return reply
        AssistantMemory.wasInterrupted = false
        val opening = VoiceLines.pick(
            "Right ${VoiceLines.OWNER_NAME}, ",
            "Yep ${VoiceLines.OWNER_NAME}, ",
            "Moving on ${VoiceLines.OWNER_NAME} — ",
            "Skipping ahead ${VoiceLines.OWNER_NAME} — ",
        )
        return when (reply.firstOrNull()) {
            TtsProsody.FAST -> "${TtsProsody.FAST}$opening${reply.substring(1)}"
            TtsProsody.SLOW -> "${TtsProsody.SLOW}$opening${reply.substring(1)}"
            else -> opening + reply
        }
    }

    /**
     * Routine-log acknowledgements, shuffled so a run of jobs does not sound
     * like a machine reading the same line.
     */
    private fun tradeAck(): String = listOf(
        "Sorted, ${VoiceLines.OWNER_NAME}. Logged.",
        "Righto, that's in.",
        "Spot on, consider it done.",
        "All sorted.",
        "Bosh. Saved that for you.",
    ).random()

    /** Friday-afternoon sign-off, only when a job is being closed. */
    private fun fridaySignOff(): String {
        val now = LocalDateTime.now()
        if (now.dayOfWeek != DayOfWeek.FRIDAY || now.toLocalTime() < LocalTime.of(14, 0)) {
            return ""
        }
        val line = listOf(
            "Almost time to down tools, ${VoiceLines.OWNER_NAME}.",
            "Must be pub time soon, surely?",
            "Last push for the week!",
        ).random()
        return " $line"
    }

    /** Human reaction when something has to be taken back out. */
    private fun undoBanter(): String = listOf(
        "No worries, scrubbed that.",
        "Whoops. Deleted.",
        "Right, wiped that from the record, ${VoiceLines.OWNER_NAME}.",
    ).random()

    /** Pace markers — the runner knows the kind of reply; TTS honours it. */
    private fun fast(text: String): String = "${TtsProsody.FAST}$text"

    private fun slow(text: String): String = "${TtsProsody.SLOW}$text"

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
        // Two clients that close together are a question, not a coin flip.
        if (pendingClarification == null && intent.intent in CLIENT_SCOPED) {
            val clients = repository.observeClients().first()
            val ambiguous = ambiguousFor(clients, transcript)
            if (ambiguous.size >= 2) {
                pendingClarification = PendingClarification(
                    transcript = transcript,
                    intent = intent,
                    candidates = ambiguous.map { it.id },
                )
                return slow(askWhich(ambiguous))
            }
        }
        return pivot(execute(transcript, intent))
    }

    /** Dispatch only; [run] wraps this with the ambiguity check. */
    private suspend fun execute(transcript: String, intent: IntentResult): String? {
        val text = transcript.lowercase()
        return when (intent.intent) {
            SpokenIntent.LogHours -> logHours(text)
            SpokenIntent.CheckSchedule -> scheduleFor(text)
            SpokenIntent.NextJob -> nextJob()
            SpokenIntent.NextJobAddress -> nextJobAddress()
            SpokenIntent.MarkJobDone -> markJobDone(text)
            SpokenIntent.CheckDebtors -> checkDebtors(text)
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
            SpokenIntent.AddExpense -> addExpense(text)
            SpokenIntent.NextVisitNote -> nextVisitNote(text)
            else -> null
        }
    }

    /**
     * Finish a half-heard "log hours" command from the previous turn. Returns
     * null when this sentence is not an answer to the pending question, in
     * which case the caller classifies it normally.
     */
    suspend fun continuePending(transcript: String): String? =
        pivot(continuePendingImpl(transcript))

    private suspend fun continuePendingImpl(transcript: String): String? {
        val pending = pending ?: return null
        val text = transcript.lowercase()
        val hours = spokenHours(text) ?: pending.hours
        val clients = repository.observeClients().first()
        val client = pending.client ?: clientFor(clients, text)

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

    /** True while a draft invoice is waiting for "send it" or "leave it". */
    fun sendPending(): Boolean = pendingSend != null

    /** True while "want me to flag that?" or "which one?" awaits an answer. */
    fun chasePending(): Boolean = pendingChase != null

    fun clarificationPending(): Boolean = pendingClarification != null

    /** True while a chunked list is waiting for "read them all". */
    fun readAllPending(): Boolean = pendingReadAll != null

    fun clearWrapUp() {
        wrapUp = null
        wrapUpBillables.clear()
        // A half-finished "log hours" question must not outlive the call or
        // hijack the first answer inside a new wrap-up.
        pending = null
        // Corrections, "send it", chase offers and "which one?" only apply
        // inside the call that raised them.
        pendingSend = null
        pendingChase = null
        pendingClarification = null
        pendingReadAll = null
        resolvedClientId = null
        lastVoiceVisit = null
        // Never let a cut-off from last night pivot tomorrow's first reply.
        AssistantMemory.wasInterrupted = false
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
            return "Nothing left on today's schedule, ${VoiceLines.OWNER_NAME} — " +
                "everything is already marked done."
        }
        val state = WrapUp(items)
        wrapUp = state
        // A proper opener before the clipboard starts: they asked for the
        // day back, so acknowledge it first.
        val opener = if (LocalTime.now().hour >= 12) {
            "Long day out there, ${VoiceLines.OWNER_NAME}. Let's run through today's jobs."
        } else {
            "Right ${VoiceLines.OWNER_NAME} — let's run through today's jobs."
        }
        return "$opener ${prompt(state, first = true)}"
    }

    /**
     * Answer a wrap-up question. Returns null when no wrap-up is running, so
     * the caller can classify the sentence normally.
     */
    suspend fun continueWrapUp(transcript: String): String? =
        pivot(continueWrapUpImpl(transcript))

    private suspend fun continueWrapUpImpl(transcript: String): String? {
        val state = wrapUp ?: return null
        val text = transcript.lowercase().trim()
        if (text.isEmpty()) return null

        if (isWrapUpStop(text)) {
            wrapUp = null
            return slow(summary(state, stopped = true))
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
            return slow("$loggedLine ${summary(state)}")
        }
        state.index++
        return slow("$loggedLine ${prompt(state, first = false)}")
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
        lastUndo = Undo.Visit(visitId, item.clientName, Undo.Kind.TimeLog)
        lastVoiceVisit = VoiceVisit(visitId, item.clientId, item.clientName, materials = 0.0)
        CallLedger.record("Logged ${hoursLabel(safeHours)} for ${item.clientName}") {
            runCatching { repository.deleteVoiceVisit(visitId) }
            if (visitId == lastVoiceVisit?.id) lastVoiceVisit = null
            "Removed that visit."
        }
        state.jobsLogged++
        state.hoursLogged = round2(state.hoursLogged + safeHours)
        state.amountLogged = round2(state.amountLogged + visit.amount)
        // Ultra-short cadence: inside a rapid loop a full read-back on every
        // job feels like a machine; the totals come once at the end.
        return fast(
            VoiceLines.pick(
                "Logged ${hoursLabel(safeHours)}, ${money(visit.amount)}.",
                "${hoursLabel(safeHours)} down, ${money(visit.amount)}.",
                "Done, ${hoursLabel(safeHours)}, ${money(visit.amount)}.",
                "That's in, ${hoursLabel(safeHours)}.",
            ),
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
        val client = clientFor(clients, text)
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
        return saveHours(client, hours, date, text)
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
        lastUndo = Undo.Visit(visitId, client.name, Undo.Kind.TimeLog)
        lastVoiceVisit = VoiceVisit(visitId, client.id, client.name, materials = 0.0)
        val debt = debtFollowUp(client.id, client.name, visitId)
        CallLedger.record("Logged ${hoursLabel(hours)} for ${client.name}") {
            runCatching { repository.deleteVoiceVisit(visitId) }
            if (visitId == lastVoiceVisit?.id) lastVoiceVisit = null
            "Removed that visit."
        }
        val whenText = if (date == LocalDate.now()) "" else " for ${date.dayOfMonth}/${date.monthValue}"
        // Time-of-day empathy, checked with two LocalDateTime reads — no
        // model, no I/O. A question ending also suppresses "anything else?".
        val detail = "${hoursLabel(hours)} for ${client.name}$whenText, ${money(draft.amount)}"
        val now = LocalDateTime.now()
        val reply = when {
            now.dayOfWeek == DayOfWeek.FRIDAY && now.toLocalTime() >= LocalTime.of(15, 0) ->
                "${tradeAck()} $detail. Last one of the week, or keeping going?"
            now.toLocalTime() < LocalTime.of(9, 30) ->
                "Morning! $detail."
            else -> "${tradeAck()} $detail."
        }
        return fast(reply + (debt ?: ""))
    }

    /** Answer "what have I got on" for today, tomorrow, a weekday or next week. */
    private suspend fun scheduleFor(text: String): String {
        // Simple relative days resolve through the normaliser into a concrete
        // LocalDate first, so the Room filter runs on a date, not a string.
        // Week/month phrases keep the wider range logic below.
        val today = LocalDate.now()
        val relative = if (text.contains("week") || text.contains("month")) {
            null
        } else {
            SpeechNormalizer.relativeDate(text, today)
        }
        val range = if (relative != null) {
            val label = when (relative) {
                today -> "today"
                today.plusDays(1) -> "tomorrow"
                else -> "on " + relative.dayOfWeek.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
            }
            DateRange(relative, relative, label)
        } else {
            rangeFor(text)
        }
        val jobs = scheduledJobs().filter { (_, starts) ->
            val day = starts.localDate()
            !day.isBefore(range.from) && !day.isAfter(range.to)
        }
        if (jobs.isEmpty()) return "Nothing booked ${range.label}."

        val clients = repository.observeClients().first()
        val items = jobs.map { (job, starts) ->
            val name = clients.firstOrNull { it.id == job.clientId }?.name ?: "a client"
            when {
                range.from == range.to -> "${starts.formatTime()} $name"
                else -> "${starts.formatShortWeekday()} ${starts.formatDayMonth()} " +
                    "at ${starts.formatTime()} $name"
            }
        }
        val count = if (jobs.size == 1) "job" else "jobs"
        return slow(chunkedList("You have ${jobs.size} $count ${range.label}:", items))
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

        val named = clientFor(clients, text)
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
        // Committed straight away — no "say yes to confirm". Corrections ride
        // on "undo that" and the post-call summary, which are faster than a
        // confirmation round-trip with greased gloves on.
        return if (todaysVisit != null) {
            repository.completeJobWithVisit(job.id, todaysVisit.id)
            // Hours logged earlier stay put; undo only reopens the job.
            lastUndo = Undo.Visit(todaysVisit.id, name, Undo.Kind.CompletedJob, deleteVisit = false)
            val debt = debtFollowUp(job.clientId, name, todaysVisit.id)
            CallLedger.record("Marked the job for $name done") {
                repository.reopenJob(job.id)
                "That job is back on the schedule."
            }
            fast("${tradeAck()} $name's job is closed off." + fridaySignOff() + (debt ?: ""))
        } else {
            val hours = round2((job.durationMinutes / 60.0).coerceAtLeast(0.5))
            val amount = client?.let {
                round2(priceVisit(BillingSource(it.billingMode, it.hourlyRate, it.fixedPrice), hours, 0.0))
            } ?: 0.0
            // One transaction logs the visit and completes the job, so the
            // undo can put both sides back.
            val visitId = repository.saveVisit(
                VisitDraft(
                    clientId = job.clientId,
                    visitDate = today.toString(),
                    hours = hours,
                    materialsCost = 0.0,
                    description = "Completed by voice",
                    amount = amount,
                    completesJobId = job.id,
                    jobOutcome = "done",
                ),
            )
            lastUndo = Undo.Visit(visitId, name, Undo.Kind.CompletedJob)
            lastVoiceVisit = VoiceVisit(visitId, job.clientId, name, materials = 0.0)
            val debt = debtFollowUp(job.clientId, name, visitId)
            CallLedger.record("Marked the job for $name done, ${hoursLabel(hours)}") {
                runCatching { repository.deleteVoiceVisit(visitId) }
                if (visitId == lastVoiceVisit?.id) lastVoiceVisit = null
                "Back on the schedule, and the visit is gone."
            }
            fast(
                "${tradeAck()} ${hoursLabel(hours)} for $name — job's closed off." +
                    fridaySignOff() +
                    (debt ?: ""),
            )
        }
    }

    /** Reverse the last reversible action: a logged visit or a client note. */
    suspend fun undoLast(): String = pivot(undoLastImpl()) ?: "Nothing to undo."

    private suspend fun undoLastImpl(): String? {
        val action = lastUndo ?: return null
        lastUndo = null
        return when (action) {
            is Undo.Visit -> when (action.kind) {
                Undo.Kind.CompletedJob -> {
                    // Reopen the job first (deleteVoiceVisit would also do it,
                    // but only when the visit itself is being removed).
                    val job = repository.observeJobs().first()
                        .firstOrNull { it.visitId == action.id }
                    if (job != null) repository.reopenJob(job.id)
                    if (action.deleteVisit) {
                        runCatching { repository.deleteVoiceVisit(action.id) }
                        if (action.id == lastVoiceVisit?.id) lastVoiceVisit = null
                    }
                    "${undoBanter()} ${action.clientName}'s job is back on the list — " +
                        "what did you actually get done?"
                }

                Undo.Kind.Expense -> {
                    runCatching { repository.deleteVoiceVisit(action.id) }
                        .getOrElse { return "I couldn't remove that charge." }
                    if (action.id == lastVoiceVisit?.id) lastVoiceVisit = null
                    "${undoBanter()} What did you actually want to charge?"
                }

                Undo.Kind.TimeLog -> {
                    runCatching { repository.deleteVoiceVisit(action.id) }
                        .getOrElse { return "I couldn't remove that visit." }
                    if (action.id == lastVoiceVisit?.id) lastVoiceVisit = null
                    "${undoBanter()} That visit for ${action.clientName} is gone — " +
                        "how long were you actually there?"
                }
            }

            is Undo.Note -> {
                val client = repository.client(action.clientId)
                    ?: return "That client is gone."
                repository.updateClient(client, clientDraft(client, action.previous))
                "${undoBanter()} That note on ${action.clientName} is gone."
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
        val client = clientFor(clients, body.lowercase())
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
        CallLedger.record("Note added for ${client.name}") {
            val current = repository.observeClients().first().firstOrNull { it.id == client.id }
            if (current != null) {
                repository.updateClient(current, clientDraft(current, previous))
                "Taken that note back off."
            } else {
                "That client is already gone."
            }
        }
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
    /**
     * Trade-counter receipt: "stick £40 of materials onto the Smiths". The
     * materials-only visit is unbilled, so it flows into ready-to-bill and
     * onto the next draft invoice without another word. Deliberately not
     * [priceVisit]d: that would add a fixed-price client's whole fixed price
     * on top of the receipt.
     */
    private suspend fun addExpense(text: String): String {
        val amount = Regex("£?(\\d+(?:\\.\\d+)?)").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return "How much was it?"
        if (amount <= 0.0 || amount > 100_000.0) return "That amount doesn't look right."

        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
        if (client == null) {
            // "add £10 of materials" straight after logging a visit belongs
            // to that visit, not to nobody.
            return if (lastVoiceVisit != null) adjustCharge(text) else "Which client was that for?"
        }

        val money = Regex("£[\\d.]+").find(text)?.value
        val note = (if (money != null) text.substringAfter(money, text) else text)
            .replaceFirst(Regex("^(?:\\s*(?:of|for)\\s+)(.*)$"), "$1")
            .split(Regex("\\s+(?:onto|on|for|at)\\s+"))
            .first()
            .replace(Regex("\\b(?:materials?|parts)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(".")
            .ifBlank { "materials" }
        val lineItem = when {
            TradeTerms.lineItemFor(note) != null -> TradeTerms.lineItemFor(note)
            note.equals("materials", ignoreCase = true) ||
                note.equals("parts", ignoreCase = true) -> "Materials"
            else -> "Materials — $note"
        }

        val draft = VisitDraft(
            clientId = client.id,
            visitDate = LocalDate.now().toString(),
            hours = 0.0,
            materialsCost = round2(amount),
            description = lineItem,
            amount = round2(amount),
        )
        val visitId = repository.saveVisit(draft)
        lastUndo = Undo.Visit(visitId, client.name, Undo.Kind.Expense)
        lastVoiceVisit = VoiceVisit(visitId, client.id, client.name, materials = round2(amount))
        CallLedger.record("£${format(amount)} materials for ${client.name}") {
            runCatching { repository.deleteVoiceVisit(visitId) }
            if (visitId == lastVoiceVisit?.id) lastVoiceVisit = null
            "Removed the materials."
        }
        return fast(
            VoiceLines.pick(
                "£${format(amount)} of $note down for ${client.name} — " +
                    "it'll be on their next invoice.",
                "Sorted, £${format(amount)} of $note on ${client.name}'s account. " +
                    "Next invoice picks it up.",
            ),
        )
    }

    /**
     * "Remind me to get compost for the next visit to the Smiths" — one
     * standing reminder per client, spoken back in the call briefing and
     * editable in the client editor. Store, read ("what's for the Smiths'
     * next visit?") and clear all live behind the one trigger phrase.
     */
    private suspend fun nextVisitNote(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
        val lower = text.lowercase()
        return when {
            lower.startsWith("what") || lower.contains("what's for") ||
                lower.contains("whats for") -> {
                val target = client ?: inferredClient(clients)
                    ?: return "Whose next visit shall I look up?"
                val note = runCatching { repository.nextVisitNoteFor(target.id) }.getOrNull()
                if (note == null) {
                    "Nothing saved for ${target.name}'s next visit."
                } else {
                    fast("For ${target.name}'s next visit: $note.")
                }
            }
            lower.startsWith("clear") || lower.contains("remove the") ||
                lower.contains("cancel the") -> {
                val target = client ?: inferredClient(clients)
                    ?: return "Whose next-visit note shall I clear?"
                repository.saveNextVisitNote(target.id, "")
                "Cleared the next-visit note for ${target.name}."
            }
            else -> {
                val target = client ?: return "Which client is that for?"
                val note = extractVisitNote(text, target)
                if (note.isBlank()) return "What should I remind you about?"
                repository.saveNextVisitNote(target.id, note)
                CallLedger.record("Next-visit note for ${target.name}") {
                    repository.saveNextVisitNote(target.id, "")
                    "Taken that reminder off."
                }
                fast(
                    VoiceLines.pick(
                        "Right — $note for ${target.name}'s next visit.",
                        "Noted. $note, next time you're at ${target.name}.",
                    ),
                )
            }
        }
    }

    /** Strip the command words, leaving what the owner actually wants done. */
    private suspend fun extractVisitNote(text: String, client: Client): String {
        var note = text
            .replaceFirst(
                Regex(
                    "^\\s*(?:please\\s+)?(?:can you\\s+)?(?:don't forget to\\s+|dont forget to\\s+)?" +
                        "remind me to\\s+",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replaceFirst(Regex("^\\s*(?:can you\\s+)?remind me\\s+"), "")
        if (note.contains(':')) note = note.substringAfter(':')
        // Drop everything from the client reference onwards; the note itself
        // always comes before "for the next visit to …".
        note = note.replaceFirst(
            Regex("\\b(?:for|at|on)\\s+the\\s+next\\s+visit\\b.*$", RegexOption.IGNORE_CASE),
            "",
        )
        note = note.replaceFirst(
            Regex("\\bnext\\s+visit\\b.*$", RegexOption.IGNORE_CASE),
            "",
        )
        note = note.replace(Regex(Regex.escape(client.name), RegexOption.IGNORE_CASE), " ")
        runCatching { repository.allVoiceAliases() }.getOrDefault(emptyList())
            .filter { it.first == client.id }
            .forEach { note = note.replace(Regex(Regex.escape(it.second), RegexOption.IGNORE_CASE), " ") }
        return note
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '-', '\u2014')
    }

    private suspend fun raiseInvoice(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text) ?: inferredClient(clients)
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
        CallLedger.record("Draft invoice for ${client.name}, ${money(totalValue)}") {
            runCatching { repository.deleteInvoice(made.invoiceId) }
            pendingSend = null
            "That draft invoice is gone."
        }
        return slow(
            VoiceLines.pick(
                "Draft invoice for ${client.name} — $visitsText, ${money(totalValue)}. " +
                    "Send it, or leave it in drafts?",
                "That's a draft for ${client.name}, $visitsText, ${money(totalValue)}. " +
                    "Send it now, or leave it?",
                "In drafts: ${client.name}, $visitsText, ${money(totalValue)}. " +
                    "Send it, or leave it there?",
            ),
        )
    }

    /**
     * The answer to "say send it". Deliberately a separate two-step: money
     * only leaves the phone after a spoken confirmation with the total in it.
     * Anything that is not a yes or a no clears the question and is
     * classified normally.
     */
    suspend fun continueSend(transcript: String): String? =
        pivot(continueSendImpl(transcript))

    private suspend fun continueSendImpl(transcript: String): String? {
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
                slow("Left it in drafts — ${pending.clientName}, ${money(pending.total)}.")
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
        val before = current
        lastVoiceVisit = last.copy(materials = safeMaterials)
        CallLedger.record(
            "Visit for ${last.clientName} adjusted to ${money(amount)}",
        ) {
            repository.saveVisit(
                VisitDraft(
                    id = before.id,
                    clientId = before.clientId,
                    visitDate = before.visitDate,
                    hours = before.hours,
                    materialsCost = before.materialsCost,
                    materialsNote = before.materialsNote,
                    description = before.description,
                    amount = before.amount,
                    missed = before.missed,
                ),
            )
            lastVoiceVisit = last.copy(materials = before.materialsCost)
            "Put that visit back to ${money(before.amount)}."
        }
        val extra = note?.takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty()
        return VoiceLines.pick(
            "Sorted — that visit is ${money(amount)} now$extra.",
            "Added. It comes to ${money(amount)}$extra.",
        )
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    /**
     * A list read-back never reads more than two items before offering the
     * rest. "Read them all" then speaks the remainder once.
     */
    private fun chunkedList(
        lead: String,
        items: List<String>,
    ): String {
        if (items.size <= 3) return "$lead ${items.joinToString("; ")}."
        pendingReadAll = items
        return "$lead Starting with ${items[0]} and ${items[1]}, plus " +
            "${items.size - 2} others. Want me to read them all?"
    }

    private suspend fun unbilled(): String {
        val clients = repository.observeClients().first()
        val groups = repository.observeVisits().first()
            .filter { !it.billed && !it.missed }
            .groupBy { it.clientId }
            .map { (id, rows) -> Triple(id, rows.size, rows.sumOf { it.amount }) }
            .sortedByDescending { it.third }
        if (groups.isEmpty()) return "Nothing is waiting to be invoiced."
        val rows = groups.map { (id, count, total) ->
            val name = clients.firstOrNull { it.id == id }?.name ?: "a client"
            "$name ${money(total)} ($count ${if (count == 1) "visit" else "visits"})"
        }
        return slow(chunkedList("${groups.size} clients with unbilled work:", rows))
    }

    private suspend fun clientList(): String {
        val clients = repository.observeClients().first()
            .filter { it.active }
            .sortedBy { it.name }
        if (clients.isEmpty()) return "No clients yet."
        return slow(chunkedList("You've got ${clients.size} clients:", clients.map { it.name }))
    }

    private suspend fun invoiceStatus(text: String): String {
        val client = clientFor(repository.observeClients().first(), text)
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
        val client = clientFor(repository.observeClients().first(), text)
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

    /**
     * The debtor picture: unpaid invoices AND completed-but-unbilled work,
     * rolled up per client. A named client answers for themselves alone;
     * otherwise the worst offenders first, three spoken, the rest counted.
     */
    private suspend fun checkDebtors(text: String): String {
        val clients = repository.observeClients().first().associateBy { it.id }
        val named = clientFor(clients.values.toList(), text)
        val invoices = repository.observeInvoices().first()
            .filter { it.status != "paid" && it.status != "draft" && it.total > 0 }
        val unbilled = repository.observeVisits().first()
            .filter { !it.billed && !it.missed && it.amount > 0 }

        val owed = HashMap<String, Double>()
        invoices.forEach { owed.merge(it.clientId, it.total, Double::plus) }
        unbilled.forEach { owed.merge(it.clientId, it.amount, Double::plus) }

        if (named != null) {
            val amount = round2(owed[named.id] ?: 0.0)
            return if (amount <= 0.0) {
                "${named.name} owes you nothing, and nothing of theirs is waiting."
            } else {
                slow("${named.name} owes you ${money(amount)}.")
            }
        }

        val total = round2(owed.values.sumOf { it })
        if (total <= 0.0) {
            return "Nobody owes you anything, and nothing is waiting to be billed."
        }
        val rows = owed.entries.sortedByDescending { it.value }.map { (id, amount) ->
            "${clients[id]?.name ?: "a client"} owes ${money(round2(amount))}"
        }
        return slow(chunkedList("You've got ${money(total)} outstanding.", rows))
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
        val client = clientFor(repository.observeClients().first(), text)
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
        val client = clientFor(repository.observeClients().first(), text)
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
     * Match a spoken sentence against a client's name or any of the
     * nicknames the owner saved for it ("the rectory", "Smiths"). The alias
     * map is read once per sentence, not per client.
     */
    private suspend fun clientFor(clients: List<Client>, text: String): Client? {
        // A clarification just picked one: use it before anything re-scores.
        resolvedClientId?.let { id ->
            val chosen = clients.firstOrNull { it.id == id }
            if (chosen != null) {
                resolvedClientId = null
                return chosen
            }
        }
        val lower = text.lowercase()
        val aliases = runCatching { repository.allVoiceAliases() }.getOrDefault(emptyList())
        val byClient = aliases.groupBy({ it.first }, { it.second })
        val scored = clients.mapNotNull { client ->
            val nameScore = matchScore(client.name, lower)
            val aliasScore = byClient[client.id].orEmpty()
                .maxOfOrNull { matchScore(it, lower) } ?: 0.0
            val score = maxOf(nameScore, aliasScore)
            if (score > 0.0) client to score else null
        }.sortedByDescending { (_, score) -> score }
        return scored.firstOrNull()?.first
    }

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
    private fun matches(clientName: String, text: String): Boolean =
        matchScore(clientName, text) > 0.0

    /**
     * How strongly [clientName] appears in [text]: 1.0 for the name spoken
     * whole, 0.75 when every significant word matched exactly, 0.55 when one
     * of them only matched by prefix or a one-letter fuzzy hit.
     *
     * The scores exist for ambiguity: two clients landing within 15% of each
     * other get a question instead of the first row in the table.
     */
    private fun matchScore(clientName: String, text: String): Double {
        val name = clientName.lowercase().trim()
        if (name.isBlank()) return 0.0
        if (text.contains(name)) return 1.0
        val words = name.split(Regex("\\s+")).filter { it.length >= 3 && it !in STOP_WORDS }
        if (words.isEmpty()) return 0.0
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        var score = 0.75
        for (word in words) {
            val exact = tokens.any { it == word }
            val loose = exact || tokens.any { token ->
                (token.length >= 3 && word.startsWith(token)) ||
                    (word.length >= 5 && token.length >= 5 &&
                        abs(word.length - token.length) <= 1 && plausible(token, word))
            }
            if (!loose) return 0.0
            if (!exact) score = minOf(score, 0.55)
        }
        return score
    }

    /** Clients matched so closely that a human would ask before acting. */
    private suspend fun ambiguousFor(clients: List<Client>, transcript: String): List<Client> {
        if (resolvedClientId != null) return emptyList()
        val lower = transcript.lowercase()
        val aliases = runCatching { repository.allVoiceAliases() }
            .getOrDefault(emptyList())
            .groupBy({ it.first }, { it.second })
        val scored = clients.mapNotNull { client ->
            val nameScore = matchScore(client.name, lower)
            val aliasScore = aliases[client.id].orEmpty()
                .maxOfOrNull { matchScore(it, lower) } ?: 0.0
            val score = maxOf(nameScore, aliasScore)
            if (score > 0.0) client to score else null
        }.sortedByDescending { (_, score) -> score }
        if (scored.size < 2) return emptyList()
        val best = scored[0].second
        if ((best - scored[1].second) / best >= 0.15) return emptyList()
        return scored.take(3).map { it.first }
    }

    /** "Which one — the rectory or Smiths farm?" */
    private suspend fun askWhich(candidates: List<Client>): String {
        val aliases = runCatching { repository.allVoiceAliases() }
            .getOrDefault(emptyList())
            .groupBy({ it.first }, { it.second })
        val labels = candidates.map { client ->
            aliases[client.id]?.firstOrNull()
                ?: client.address?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
                ?: client.name
        }
        val names = labels.take(2).joinToString(" or ")
        val shared = candidates.first().name.split(Regex("\\s+")).firstOrNull { it.length > 2 }
        return "Which ${shared ?: "one"}? $names?"
    }

    /** The answer to "which one?", executed against the original command. */
    suspend fun continueClarification(transcript: String): String? =
        pivot(continueClarificationImpl(transcript))

    private suspend fun continueClarificationImpl(transcript: String): String? {
        val waiting = pendingClarification ?: return null
        pendingClarification = null
        val clients = repository.observeClients().first()
        val aliases = runCatching { repository.allVoiceAliases() }
            .getOrDefault(emptyList())
            .groupBy({ it.first }, { it.second })
        val answer = transcript.lowercase()
        val candidates = waiting.candidates.mapNotNull { id -> clients.firstOrNull { it.id == id } }
        val scored = candidates.map { client ->
            val name = matchScore(client.name, answer)
            val alias = aliases[client.id].orEmpty()
                .maxOfOrNull { matchScore(it, answer) } ?: 0.0
            val street = client.address?.substringBefore(',')
                ?.let { matchScore(it, answer) } ?: 0.0
            client to maxOf(name, alias, street)
        }.sortedByDescending { (_, score) -> score }
        val chosen = scored.firstOrNull()?.takeIf { (_, score) -> score > 0.0 }?.first
            ?: return "Right — cancelled that. Say it again when you know which one."
        resolvedClientId = chosen.id
        return execute(waiting.transcript, waiting.intent)
            ?: "Right — ${chosen.name}, then. Say it again?"
    }

    /** The answer to "want me to flag that?". */
    suspend fun continueChase(transcript: String): String? =
        pivot(continueChaseImpl(transcript))

    private suspend fun continueChaseImpl(transcript: String): String? {
        val chasing = pendingChase ?: return null
        val words = transcript.lowercase()
            .replace(Regex("[^a-z' ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val yes = words.any { it in SEND_YES } && words.none { it in SEND_NO }
        val no = words.any { it in SEND_NO }
        return when {
            yes -> {
                pendingChase = null
                appendDatedNote(
                    chasing.clientId,
                    "Chase ${money(chasing.amount)} outstanding",
                )
                fast("Right — there's a chase note on ${chasing.clientName}'s file.")
            }
            no -> {
                pendingChase = null
                fast("Fair enough — I'll leave it.")
            }
            else -> {
                pendingChase = null
                null
            }
        }
    }

    /**
     * After a successful log, mention money already owed by that client and
     * offer to flag it. The offer is a real state — [continueChase] puts a
     * dated chase note on the file when the owner says yes.
     */
    private suspend fun debtFollowUp(
        clientId: String,
        clientName: String,
        justLoggedVisitId: String?,
    ): String? {
        val invoices = repository.observeInvoices().first()
            .filter {
                it.clientId == clientId &&
                    it.status != "paid" &&
                    it.status != "draft" &&
                    it.total > 0
            }
        val older = repository.observeVisits().first()
            .filter {
                it.clientId == clientId &&
                    !it.billed &&
                    !it.missed &&
                    it.invoiceId == null &&
                    it.amount > 0 &&
                    it.id != justLoggedVisitId
            }
        val invoiceDebt = round2(invoices.sumOf { it.total })
        val olderDebt = round2(older.sumOf { it.amount })
        val total = round2(invoiceDebt + olderDebt)
        if (total <= 0.0) return null
        pendingChase = PendingChase(clientId, clientName, total)
        return if (invoiceDebt > 0) {
            " By the way, $clientName still has an unpaid invoice for " +
                "${money(invoiceDebt)} — want me to flag that?"
        } else {
            " By the way, $clientName has ${money(olderDebt)} of older work not yet " +
                "invoiced — want me to flag that?"
        }
    }

    /** One dated line on a client's notes, the same shape as a spoken note. */
    private suspend fun appendDatedNote(clientId: String, note: String) {
        val client = repository.observeClients().first().firstOrNull { it.id == clientId } ?: return
        val combined = buildString {
            client.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append('\n') }
            append(LocalDate.now()).append(": ").append(note)
        }
        repository.updateClient(client, clientDraft(client, combined))
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

        /** Intents that act on exactly one client, so ambiguity must be asked. */
        val CLIENT_SCOPED = setOf(
            SpokenIntent.LogHours,
            SpokenIntent.MarkJobDone,
            SpokenIntent.AddNote,
            SpokenIntent.RaiseInvoice,
            SpokenIntent.AddExpense,
            SpokenIntent.NextVisitNote,
            SpokenIntent.AdjustCharge,
            SpokenIntent.ClientInfo,
            SpokenIntent.LastVisit,
            SpokenIntent.NextJobForClient,
            SpokenIntent.InvoiceStatus,
        )

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
            "the", "to", "on", "of", "for", "that", "it", "this", "visit", "invoice", "and",
        )

    }
}
