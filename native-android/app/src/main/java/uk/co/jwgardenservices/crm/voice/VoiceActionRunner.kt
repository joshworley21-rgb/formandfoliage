package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.model.NewInvoiceInput
import uk.co.jwgardenservices.crm.data.model.RunningCost
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.model.QuoteDraft
import uk.co.jwgardenservices.crm.data.model.QuoteItemDraft
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.data.repository.createInvoice
import uk.co.jwgardenservices.crm.data.repository.createQuote
import uk.co.jwgardenservices.crm.data.repository.deleteQuote
import uk.co.jwgardenservices.crm.data.repository.createLocalInvoice
import uk.co.jwgardenservices.crm.data.repository.deleteInvoice
import uk.co.jwgardenservices.crm.data.repository.convertEnquiryToClient
import uk.co.jwgardenservices.crm.data.repository.markInvoicePaid
import uk.co.jwgardenservices.crm.data.repository.setQuoteStatus
import uk.co.jwgardenservices.crm.data.repository.updateEnquiryStatus
import uk.co.jwgardenservices.crm.data.repository.updateInvoiceDraft
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.atTime
import uk.co.jwgardenservices.crm.domain.BusinessSnapshot
import uk.co.jwgardenservices.crm.domain.buildBusinessSnapshot
import uk.co.jwgardenservices.crm.domain.coachInsights
import uk.co.jwgardenservices.crm.domain.coachScore
import uk.co.jwgardenservices.crm.domain.costBreakdown
import uk.co.jwgardenservices.crm.domain.daysOverdue
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.formatShortWeekday
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.moneyCompact
import uk.co.jwgardenservices.crm.domain.monthlyRunningCosts
import uk.co.jwgardenservices.crm.domain.overdueInvoices
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseSpokenNumber
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.priceVisit
import uk.co.jwgardenservices.crm.domain.topClients
import uk.co.jwgardenservices.crm.domain.round2
import uk.co.jwgardenservices.crm.domain.scoreLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** Words that carry no business meaning when matching a profile section. */
private val BUSINESS_STOP_WORDS = setOf(
    "about", "after", "again", "also", "and", "are", "before", "being", "business",
    "could", "cover", "does", "doing", "from", "have", "into", "just", "like", "many",
    "much", "offer", "please", "really", "should", "that", "their", "them", "then",
    "there", "these", "they", "thing", "this", "those", "what", "when", "where",
    "which", "while", "with", "would", "your", "yours",
)

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
    private val sendInvoice: (suspend (String, String?) -> String)? = null,
    private val shareWhatsApp: (suspend (String, String?) -> String)? = null,
    /** The bundled profile, used when the owner has not scanned a website yet. */
    private val bundledBusinessProfile: () -> String? = { null },
) {

    private data class PendingLog(
        val client: Client?,
        val date: LocalDate,
        val hours: Double?,
        /** Waiting for just the name of a new client. */
        val creatingClient: Boolean = false,
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

        data class AddedClient(val clientId: String, val name: String) : Undo

        data class BookedJob(val jobId: String?, val clientName: String) : Undo

        data class MovedJob(val before: JobDraft, val clientName: String) : Undo

        data class CancelledJob(val before: JobDraft, val clientName: String) : Undo
    }

    private var lastUndo: Undo? = null

    /** A debt follow-up waiting for "flag it" or "leave it". */
    private data class PendingChase(
        val clientId: String,
        val clientName: String,
        val amount: Double,
    )

    private var pendingChase: PendingChase? = null

    /** Holds every change until the owner says yes (see [ChangeGate]). */
    private val gate = ChangeGate()

    /** Ask before saving a change. On by default; a setting turns it off. */
    var confirmChanges: Boolean
        get() = gate.enabled
        set(value) {
            gate.enabled = value
        }

    fun confirmPending(): Boolean = gate.isPending

    private suspend fun commit(summary: String, block: suspend () -> String): String =
        gate.commit(summary, block)

    /** The answer to "shall I go ahead?"; null when the sentence is something else. */
    suspend fun continueConfirm(transcript: String): String? =
        pivot(gate.answer(transcript) { SpeechNormalizer.isNoise(it) })

    /** Two clients matched too closely: the next sentence picks one. */
    private data class PendingClarification(
        val transcript: String,
        val intent: IntentResult,
        val candidates: List<String>,
    )

    private var pendingClarification: PendingClarification? = null

    /** Set while re-running an intent for a client the clarification chose. */
    private var resolvedClientId: String? = null

    /** The last client named or resolved in this call, for "it/that/them". */
    private var lastMentionedClientId: String? = null

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
                slow("Right — ${spokenJoin(items)}.")
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
        val message: String? = null,
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
            "Next up ${VoiceLines.OWNER_NAME} — ",
            "Okay ${VoiceLines.OWNER_NAME}, ",
            "Right then, ",
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
    private fun tradeAck(): String = VoiceLines.pick(
        "Sorted, ${VoiceLines.OWNER_NAME}. Logged.",
        "Righto, that's in.",
        "Spot on — consider it done.",
        "All sorted.",
        "Bosh. Saved that for you.",
        "Lovely — logged.",
        "That's tucked away.",
        "Done and dusted.",
        "Nice one — that's on the sheet.",
        "Consider it logged.",
    )

    /** Friday-afternoon sign-off, only when a job is being closed. */
    private fun fridaySignOff(): String {
        val now = LocalDateTime.now()
        if (now.dayOfWeek != DayOfWeek.FRIDAY || now.toLocalTime() < LocalTime.of(14, 0)) {
            return ""
        }
        val line = VoiceLines.pick(
            "Almost time to down tools, ${VoiceLines.OWNER_NAME}.",
            "Ha — must be pub time soon, surely?",
            "Last push for the week!",
            "Nearly the weekend, ${VoiceLines.OWNER_NAME}.",
            "One more and then that's your lot.",
        )
        return " $line"
    }

    /** Human reaction when something has to be taken back out. */
    private fun undoBanter(): String = VoiceLines.pick(
        "No worries, scrubbed that.",
        "Whoops. Deleted.",
        "Right, wiped that from the record, ${VoiceLines.OWNER_NAME}.",
        "Done — that never happened.",
        "Taken back out. No harm done.",
    )

    /** Pace markers — the runner knows the kind of reply; TTS honours it. */
    private fun fast(text: String): String = "${TtsProsody.FAST}$text"

    private fun slow(text: String): String = "${TtsProsody.SLOW}$text"

    fun hasPending(): Boolean = pending != null || pendingSlot != null

    /**
     * True while the assistant has asked "Which client was that for?" or
     * "Which one?" — the next transcript is a name, so the ASR corrector can
     * be more forgiving than it would be for an ordinary command.
     */
    fun awaitingClientName(): Boolean =
        (pending != null && pending?.client == null) || pendingClarification != null

    fun clearPending() {
        pending = null
        pendingSlot = null
        gate.clear()
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
            SpokenIntent.InvoiceEdit -> editInvoice(text)
            SpokenIntent.NextJobForClient -> nextJobForClient(text)
            SpokenIntent.TodayEarnings -> todayEarnings()
            SpokenIntent.BusinessInfo -> businessInfo(text)
            SpokenIntent.RunningCosts -> runningCosts()
            SpokenIntent.Profit -> profit()
            SpokenIntent.BreakEven -> breakEven()
            SpokenIntent.LeastProfitable -> leastProfitableClient()
            SpokenIntent.ChaseFirst -> chaseFirst()
            SpokenIntent.Health -> health()
            SpokenIntent.Help -> help()
            SpokenIntent.DayWrapUp -> startWrapUp()
            SpokenIntent.RaiseInvoice -> raiseInvoice(text)
            SpokenIntent.AdjustCharge -> adjustCharge(text)
            SpokenIntent.AddExpense -> addExpense(text)
            SpokenIntent.NextVisitNote -> nextVisitNote(text)
            SpokenIntent.AddClient -> addClient(text)
            SpokenIntent.BookJob -> bookJob(text)
            SpokenIntent.MoveJob -> moveJob(text)
            SpokenIntent.CancelJob -> cancelJob(text)
            SpokenIntent.AddJobTask -> addJobTask(text)
            SpokenIntent.DeleteVisit -> deleteVisit(text)
            SpokenIntent.CreateQuote -> createQuote(text)
            SpokenIntent.BackfillVisits -> backfillVisits(text)
            SpokenIntent.MarkPaid -> markPaid(text)
            SpokenIntent.UpdateClient -> changeClientDetails(text)
            SpokenIntent.QuoteStatus -> quoteStatus(text)
            SpokenIntent.EnquiryStatus -> enquiryStatus(text)
            SpokenIntent.Enquiries -> enquiriesRead()
            SpokenIntent.QuotesWaiting -> quotesWaiting()
            SpokenIntent.UnloggedJobs -> unloggedJobs(text)
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
        pendingSlot?.let { return continueSlot(it, transcript) }
        val pending = pending ?: return null
        val text = transcript.lowercase()
        if (
            text.contains("cancel") || text.contains("forget it") ||
            text.contains("stop that") || text.trim() == "stop"
        ) {
            this.pending = null
            return "Right, dropped that one."
        }

        if (pending.creatingClient) {
            val name = newClientName(transcript)
            if (name.isBlank()) {
                this.pending = pending.copy(creatingClient = true)
                return "What's the client's name?"
            }
            val draft = ClientDraft(name = name)
            repository.createClient(draft)
            val created = repository.observeClients().first()
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
            this.pending = null
            if (created == null) {
                return "I couldn't save $name. Try again from the Clients tab?"
            }
            val hours = pending.hours ?: spokenHours(text)
            if (hours == null) {
                this.pending = PendingLog(created, pending.date, null)
                return "How many hours for $name?"
            }
            return saveHours(created, hours, pending.date, transcript)
        }

        val hours = spokenHours(text) ?: pending.hours
        val clients = repository.observeClients().first()
        val named = if (pending.client == null) clientsFor(clients, text) else emptyList()
        val client = pending.client ?: named.firstOrNull()

        if (hours == null && client == null) {
            this.pending = null
            return null
        }
        if (hours == null) {
            this.pending = pending.copy(client = client)
            return "How many hours for ${client!!.name}?"
        }
        if (client == null && (
                text.contains("new client") || text.contains("add a client") ||
                    text.contains("create a client")
                )
        ) {
            this.pending = pending.copy(creatingClient = true)
            return "What's the client's name?"
        }
        if (client == null) {
            this.pending = pending.copy(hours = hours)
            return "Which client was that for? Say a name, or 'new client'."
        }
        this.pending = null
        if (pending.client == null && named.size > 1) {
            return saveHoursFor(named, hours, pending.date, transcript)
        }
        return saveHours(client, hours, pending.date, transcript)
    }

    /** A spoken new-client name, with the command words removed. */
    private fun newClientName(transcript: String): String {
        var name = transcript.trim().trimEnd('.', '!', '?', ',')
        val marker = listOf("called ", "named ", "call it ", "name is ")
            .firstOrNull { name.lowercase().contains(it) }
        if (marker != null) {
            name = name.substringAfter(marker, "")
        }
        val fillers = setOf(
            "can", "you", "could", "please", "add", "a", "an", "new", "client",
            "create", "it", "is", "the", "for", "my", "name", "called", "call",
            "um", "uh", "or", "and", "just",
        )
        return name.split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.lowercase() !in fillers }
            .joinToString(" ")
            .trim()
            .take(60)
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
        lastMentionedClientId = null
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
                "Right, ${hoursLabel(safeHours)} at ${money(visit.amount)}.",
                "${hoursLabel(safeHours)} logged — ${money(visit.amount)}.",
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
        // A joint record ("Louisa & George") wins outright; otherwise every
        // separately named client in the sentence is logged.
        val named = clientsFor(clients, text)
        // No name spoken: the call briefing just said where the owner is, so
        // use the job on the schedule right now. Today only — "log four hours
        // yesterday" must not land on this afternoon's client.
        val client = named.firstOrNull()
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
        if (named.size > 1) return saveHoursFor(named, hours, date, text)
        return saveHours(client, hours, date, text)
    }

    /** Log the same duration against every client named in one sentence. */
    private suspend fun saveHoursFor(
        clients: List<Client>,
        hours: Double,
        date: LocalDate,
        source: String = "",
    ): String {
        if (clients.size == 1) return saveHours(clients.first(), hours, date, source)
        val names = clients.joinToString(" and ") { it.name }
        val onDay = VoiceCalendar.describeDate(date, LocalDate.now())
        return commit("Log ${hoursLabel(hours)} for each of $names $onDay") {
            clients.forEach { saveHours(it, hours, date, source) }
            fast("${tradeAck()} ${hoursLabel(hours)} for $names.")
        }
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
        val onDay = VoiceCalendar.describeDate(date, LocalDate.now())
        return commit("Log ${hoursLabel(hours)} for ${client.name} $onDay, ${money(draft.amount)}") {
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
            fast(reply + (debt ?: ""))
        }
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
        // Google/device calendars are read live. Business events keep their
        // titles; personal calendars only contribute anonymous busy blocks.
        val calendarEvents = if (ServiceLocator.isInitialised()) {
            runCatching {
                CalendarSync.events(ServiceLocator.appContext, range.from, range.to)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (jobs.isEmpty() && calendarEvents.isEmpty()) return VoiceLines.pick(
            "Nothing booked ${range.label}, ${VoiceLines.OWNER_NAME}.",
            "Clear diary ${range.label}.",
            "Nothing on ${range.label} — free run at it.",
            "You're clear ${range.label}. Have a breather.",
            "Nowt in the diary ${range.label}.",
        )

        val clients = repository.observeClients().first()
        val zone = ZoneId.systemDefault()
        val items = jobs.map { (job, starts) ->
            val name = clients.firstOrNull { it.id == job.clientId }?.name ?: "a client"
            val time = spokenClock(starts.atZone(zone).toLocalTime())
            if (range.from == range.to) {
                "you're at $name at $time"
            } else {
                val day = starts.atZone(zone).dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)
                "$day you're at $name at $time"
            }
        }
        // A Google event and an imported CRM job are often the same visit.
        // Drop the calendar copy when the client name and start time match.
        val filteredEvents = calendarEvents
            .distinctBy { it.eventId to it.start }
            .filterNot { event ->
                jobs.any { (job, starts) ->
                    starts == event.start &&
                        clients.firstOrNull { it.id == job.clientId }?.name?.let {
                            event.title.contains(it, ignoreCase = true)
                        } == true
                }
            }
        val calendarItems = filteredEvents.map { event ->
            val start = event.start.atZone(zone)
            val day = if (start.toLocalDate() == LocalDate.now()) {
                ""
            } else {
                start.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK) +
                    " "
            }
            val whenText = if (event.allDay) "all day" else "at ${spokenClock(start.toLocalTime())}"
            val what = if (event.business) event.title else "personal, busy"
            "$day$whenText — $what"
        }
        val allItems = items + calendarItems
        val intro = when {
            jobs.isEmpty() -> "Your calendar ${range.label}, Josh"
            jobs.size == 1 && calendarItems.isEmpty() -> "Just the one ${range.label}, Josh"
            else -> "${numberWord(allItems.size)} ${range.label}, Josh"
        }
        // Never read nine entries aloud. chunkedList stores the rest and asks
        // "want me to read them all?", which is a single yes to continue.
        return slow(chunkedList("$intro —", allItems))
    }

    /** "eight", "half past nine", "quarter to five": how a person says a slot. */
    private fun spokenClock(time: LocalTime): String {
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

    private fun numberWord(count: Int): String = when (count) {
        2 -> "Two"
        3 -> "Three"
        4 -> "Four"
        5 -> "Five"
        6 -> "Six"
        else -> "$count"
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
        // Changes are confirmed first (a setting); "undo that" and the post-call
        // summary stay as the safety net afterwards.
        val plannedHours = round2((job.durationMinutes / 60.0).coerceAtLeast(0.5))
        val summary = if (todaysVisit != null) {
            "Mark $name's job done"
        } else {
            "Mark $name's job done and log ${hoursLabel(plannedHours)}"
        }
        return commit(summary) {
        if (todaysVisit != null) {
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
    }

    // ---------------------------------------------------------------------
    // Changes to the client list, the diary and the books. Each is read back in
    // full and waits for a yes (see commit). The day, time and length come from
    // the words actually spoken, through the tested parsers in VoiceCalendar and
    // VoiceSlots, never from a model's guess.
    // ---------------------------------------------------------------------

    /** A change that needs one more detail: the next sentence supplies it. */
    private data class PendingSlot(val intent: SpokenIntent, val text: String, val join: String)

    private var pendingSlot: PendingSlot? = null

    /** True while a sentence is being tried as the answer to a pending question. */
    private var answeringSlot = false

    /** Set when that sentence did not supply the detail, so it is a new command instead. */
    private var slotMissed = false

    private val localZone: ZoneId get() = ZoneId.systemDefault()

    private fun askFor(intent: SpokenIntent, text: String, join: String, question: String): String {
        if (answeringSlot) {
            slotMissed = true
            return question
        }
        pendingSlot = PendingSlot(intent, text, join)
        return question
    }

    private suspend fun continueSlot(slot: PendingSlot, transcript: String): String? {
        pendingSlot = null
        val lower = transcript.lowercase()
        if (
            lower.contains("cancel") || lower.contains("forget it") ||
            lower.contains("never mind") || lower.trim() == "stop"
        ) {
            return "Right, dropped that one."
        }
        answeringSlot = true
        slotMissed = false
        val reply = try {
            execute(slot.text + slot.join + transcript, IntentResult(slot.intent, "", false))
        } finally {
            answeringSlot = false
        }
        if (slotMissed) {
            slotMissed = false
            return null
        }
        return reply
    }

    private fun hhmm(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    private fun done(): String = VoiceLines.pick("Done —", "Sorted —", "Right —", "Lovely —")

    private fun plural(count: Int, word: String): String = if (count == 1) "1 $word" else "$count ${word}s"

    private suspend fun bookedJob(clientId: String, date: LocalDate, time: LocalTime): ScheduledJob? {
        val startsAt = atTime(date, hhmm(time)).toString()
        return repository.observeJobs().first().firstOrNull { it.clientId == clientId && it.startsAt == startsAt }
    }

    private fun slotDescription(date: LocalDate, time: LocalTime, today: LocalDate): String =
        "${VoiceCalendar.describeDate(date, today)} at ${VoiceCalendar.describeTime(time)}"

    /** "Add a new client for Tuesday morning called Larry": the client, and the booking if a day was named. */
    private suspend fun addClient(text: String): String {
        val name = VoiceSlots.newClientName(text)
            ?: return askFor(SpokenIntent.AddClient, text, " called ", "What's the new client called?")
        val clients = repository.observeClients().first()
        clients.firstOrNull { normalizeMatchText(it.name) == normalizeMatchText(name) }?.let {
            return "You already have a client called ${it.name}."
        }
        val today = LocalDate.now()
        val date = VoiceCalendar.dateIn(text, today)
        if (date != null && date.isBefore(today)) return "That day has already gone. When should I book them?"
        val time = VoiceCalendar.timeIn(text) ?: LocalTime.of(9, 0)
        val minutes = (VoiceCalendar.durationMinutesIn(text) ?: 60).coerceIn(15, 24 * 60)
        val phone = VoiceSlots.phone(text)
        val booking = date?.let {
            "${slotDescription(it, time, today)} for ${VoiceCalendar.describeDuration(minutes)}"
        }
        val summary = buildString {
            append("Add a new client called $name")
            if (phone != null) append(", number ${VoiceSlots.spokenDigits(phone)}")
            if (booking != null) append(", and book them in for $booking")
        }
        return commit(summary) {
            repository.createClient(ClientDraft(name = name, phone = phone))
            val created = repository.observeClients().first()
                .firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return@commit "I couldn't save $name. Try again from the Clients tab?"
            lastMentionedClientId = created.id
            if (date != null) {
                repository.saveJob(
                    JobDraft(
                        clientId = created.id,
                        date = date.toString(),
                        time = hhmm(time),
                        durationMinutes = minutes,
                    ),
                )
            }
            lastUndo = Undo.AddedClient(created.id, name)
            CallLedger.record("Added client $name") {
                runCatching { repository.deleteClient(created.id) }
                "Removed $name."
            }
            fast("${done()} $name is added" + (if (booking != null) " and booked for $booking." else "."))
        }
    }

    private suspend fun bookJob(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.BookJob, text, " for ", "Which client shall I book?")
        val today = LocalDate.now()
        val date = VoiceCalendar.dateIn(text, today)
            ?: return askFor(SpokenIntent.BookJob, text, " on ", "Which day for ${client.name}?")
        if (date.isBefore(today)) return "That day has already gone. Which day should I book ${client.name} for?"
        val time = VoiceCalendar.timeIn(text) ?: LocalTime.of(9, 0)
        val minutes = (VoiceCalendar.durationMinutesIn(text) ?: 60).coerceIn(15, 24 * 60)
        val clash = VoicePlans.clashes(repository.observeJobs().first(), date, time, minutes, zone = localZone)
            .firstOrNull()
            ?.let { job -> clients.firstOrNull { it.id == job.clientId }?.name }
        val whenText = "${slotDescription(date, time, today)} for ${VoiceCalendar.describeDuration(minutes)}"
        val summary = "Book ${client.name} for $whenText" + (clash?.let { ", though you already have $it then" } ?: "")
        return commit(summary) {
            repository.saveJob(
                JobDraft(clientId = client.id, date = date.toString(), time = hhmm(time), durationMinutes = minutes),
            )
            val job = bookedJob(client.id, date, time)
            lastUndo = Undo.BookedJob(job?.id, client.name)
            CallLedger.record("Booked ${client.name} for ${VoiceCalendar.describeDate(date, today)}") {
                job?.let { runCatching { repository.deleteJob(it.id) } }
                "Took that booking back out."
            }
            fast("${done()} ${client.name} is booked for $whenText.")
        }
    }

    private suspend fun moveJob(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.MoveJob, text, " for ", "Whose booking shall I move?")
        val today = LocalDate.now()
        val move = VoiceSlots.moveTargets(text, today)
        val jobs = repository.observeJobs().first()
        val job = VoicePlans.findJob(jobs, client.id, move.from, today, localZone)
            ?: return "I can't see a booking for ${client.name}" +
                (move.from?.let { " on ${VoiceCalendar.describeDate(it, today)}" } ?: "") + "."
        val start = VoicePlans.startOf(job, localZone) ?: return "I couldn't read that booking's time."
        val toDate = move.to
            ?: if (move.time != null) {
                start.toLocalDate()
            } else {
                return askFor(SpokenIntent.MoveJob, text, " to ", "Move ${client.name} to when?")
            }
        if (toDate.isBefore(today)) return "That day has already gone. Move ${client.name} to when?"
        val toTime = move.time ?: start.toLocalTime()
        val clash = VoicePlans.clashes(jobs, toDate, toTime, job.durationMinutes, job.id, localZone)
            .firstOrNull()
            ?.let { other -> clients.firstOrNull { it.id == other.clientId }?.name }
        val from = slotDescription(start.toLocalDate(), start.toLocalTime(), today)
        val to = slotDescription(toDate, toTime, today)
        val summary = "Move ${client.name} from $from to $to" + (clash?.let { ", though you already have $it then" } ?: "")
        return commit(summary) {
            val before = JobDraft(
                id = job.id,
                clientId = job.clientId,
                date = start.toLocalDate().toString(),
                time = hhmm(start.toLocalTime()),
                durationMinutes = job.durationMinutes,
                notes = job.notes,
            )
            repository.saveJob(before.copy(date = toDate.toString(), time = hhmm(toTime)))
            lastUndo = Undo.MovedJob(before, client.name)
            CallLedger.record("Moved ${client.name} to ${VoiceCalendar.describeDate(toDate, today)}") {
                repository.saveJob(before)
                "Put that booking back."
            }
            fast("${done()} ${client.name} is now $to.")
        }
    }

    private suspend fun cancelJob(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.CancelJob, text, " for ", "Whose booking shall I cancel?")
        val today = LocalDate.now()
        val on = VoiceCalendar.dateIn(text, today)
        val job = VoicePlans.findJob(repository.observeJobs().first(), client.id, on, today, localZone)
            ?: return "I can't see a booking for ${client.name}" +
                (on?.let { " on ${VoiceCalendar.describeDate(it, today)}" } ?: "") + "."
        val start = VoicePlans.startOf(job, localZone) ?: return "I couldn't read that booking's time."
        val repeats = if (job.seriesId != null) ", just that one visit and not the whole run" else ""
        val whenText = slotDescription(start.toLocalDate(), start.toLocalTime(), today)
        return commit("Cancel ${client.name}'s booking on $whenText$repeats") {
            val before = JobDraft(
                id = job.id,
                clientId = job.clientId,
                date = start.toLocalDate().toString(),
                time = hhmm(start.toLocalTime()),
                durationMinutes = job.durationMinutes,
                notes = job.notes,
            )
            repository.deleteJob(job.id)
            lastUndo = Undo.CancelledJob(before, client.name)
            CallLedger.record("Cancelled ${client.name} on ${VoiceCalendar.describeDate(start.toLocalDate(), today)}") {
                repository.saveJob(before.copy(id = null))
                "Put that booking back."
            }
            fast("${done()} ${client.name}'s booking on $whenText is cancelled.")
        }
    }

    /** "Add a task to Simon's job: buy bark": a checklist item on the client's next booking. */
    private suspend fun addJobTask(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.AddJobTask, text, " for ", "Which client's job is that for?")
        val today = LocalDate.now()
        val on = VoiceCalendar.dateIn(text, today)
        val job = VoicePlans.findJob(repository.observeJobs().first(), client.id, on, today, localZone)
            ?: return "I can't see an upcoming job for ${client.name}" +
                (on?.let { " on ${VoiceCalendar.describeDate(it, today)}" } ?: "") + " to put that on."
        val title = VoiceSlots.taskTitle(text, client.name)
            ?: return "What's the task for ${client.name}'s job?"
        val start = VoicePlans.startOf(job, localZone)
        val whenText = start?.let { slotDescription(it.toLocalDate(), it.toLocalTime(), today) } ?: "the next job"
        return commit("Add the task \"$title\" to ${client.name}'s job on $whenText") {
            val position = repository.observeJobTasks(job.id).first().size
            repository.addJobTask(job.id, title, position)
            CallLedger.record("Task added for ${client.name}: $title") {
                val added = repository.observeJobTasks(job.id).first().lastOrNull { it.title == title }
                if (added != null) {
                    repository.deleteJobTask(added.id)
                    "Taken that task off."
                } else {
                    "That task is already gone."
                }
            }
            fast("${done()} I've added \"$title\" to ${client.name}'s job on $whenText.")
        }
    }

    /** "Quote the Smiths £300 for cutting the hedge": a draft quote, read back first, never sent from here. */
    private suspend fun createQuote(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.CreateQuote, text, " for ", "Which client is the quote for?")
        val (price, work) = VoiceSlots.quoteParts(text, client.name)
        if (price == null || price <= 0) return "How much is the quote for ${client.name}?"
        if (work == null) return "What's the work on that quote for ${client.name}?"
        val vat = runCatching { repository.getSettings() }.getOrNull()?.takeIf { it.vatRegistered }
        val vatNote = if (vat != null) " plus VAT" else ""
        return commit("Draft a quote for ${client.name}: $work, ${money(price)}$vatNote") {
            val id = try {
                repository.createQuote(
                    QuoteDraft(
                        clientId = client.id,
                        contactName = client.name,
                        contactEmail = client.email,
                        contactPhone = client.phone,
                        contactAddress = client.address,
                        items = listOf(QuoteItemDraft(description = work, quantity = 1.0, unitPrice = price)),
                    ),
                )
            } catch (error: Exception) {
                return@commit "I couldn't make that quote. A new quote needs signal to get its number, " +
                    "so try again when you're connected."
            }
            CallLedger.record("Quote drafted for ${client.name}: $work") {
                repository.deleteQuote(id)
                "Removed that draft quote."
            }
            fast("${done()} Draft quote for ${client.name}: $work, ${money(price)}$vatNote. It's in Quotes to check and send.")
        }
    }

    /** "Delete the last visit for the Parkers": removes a logged visit that is not on an invoice yet. */
    private suspend fun deleteVisit(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.DeleteVisit, text, " for ", "Whose visit shall I delete?")
        val today = LocalDate.now()
        val on = VoiceCalendar.dateIn(text, today)
        val mine = repository.observeVisits().first().filter { it.clientId == client.id }
        val visit = (if (on != null) mine.filter { parseDate(it.visitDate) == on } else mine)
            .maxByOrNull { it.visitDate + (it.createdAt ?: "") }
            ?: return "I can't see a visit for ${client.name}" +
                (on?.let { " on ${VoiceCalendar.describeDate(it, today)}" } ?: "") + "."
        if (visit.billed) return "That visit is already on an invoice, so I can't delete it from here."
        val day = parseDate(visit.visitDate)?.let { VoiceCalendar.describeDate(it, today) } ?: visit.visitDate
        return commit(
            "Delete ${client.name}'s visit on $day (${hoursLabel(visit.hours)}, ${money(visit.amount)})",
        ) {
            val draft = VisitDraft(
                clientId = visit.clientId,
                visitDate = visit.visitDate,
                hours = visit.hours,
                materialsCost = visit.materialsCost,
                materialsNote = visit.materialsNote,
                description = visit.description,
                amount = visit.amount,
                missed = visit.missed,
            )
            repository.deleteVisit(visit.id)
            CallLedger.record("Deleted ${client.name}'s visit on $day") {
                repository.saveVisit(draft)
                "Put that visit back."
            }
            fast("${done()} ${client.name}'s visit on $day is deleted.")
        }
    }

    /** "Backdate Louisa and George's visits this month, eight hours each": log, link and correct in one go. */
    private suspend fun backfillVisits(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.BackfillVisits, text, " for ", "Whose visits shall I fill in?")
        val today = LocalDate.now()
        val period = VoiceCalendar.periodIn(text, today)
            ?: VoiceCalendar.periodIn("so far this month", today)!!
        val hours = VoiceCalendar.durationMinutesIn(text)?.let { it / 60.0 }
        val plan = VoicePlans.backfill(
            repository.observeJobs().first(),
            repository.observeVisits().first(),
            client.id,
            period,
            Instant.now(),
            hours,
            localZone,
        )
        if (plan.isEmpty) return "There's nothing to fill in for ${client.name} ${period.label}."
        val count = plan.toLog.size + plan.toLink.size + plan.toFix.size
        if (count > 60) return "That's $count visits, so let's do it a month at a time."
        val source = BillingSource(client.billingMode, client.hourlyRate, client.fixedPrice)
        fun hoursFor(job: ScheduledJob) = round2(hours ?: (job.durationMinutes / 60.0).coerceAtLeast(0.5))
        val parts = mutableListOf<String>()
        if (plan.toLog.isNotEmpty()) {
            val total = plan.toLog.sumOf { round2(priceVisit(source, hoursFor(it), 0.0)) }
            val each = hours?.let { " at ${hoursLabel(it)} each" } ?: ""
            parts += "log ${plural(plan.toLog.size, "job")} that never got a visit$each, ${money(total)} in all"
        }
        if (plan.toLink.isNotEmpty()) {
            parts += "mark ${plural(plan.toLink.size, "job")} done against visits you already have"
        }
        if (plan.toFix.isNotEmpty() && hours != null) {
            parts += "change ${plural(plan.toFix.size, "visit")} to ${hoursLabel(hours)}"
        }
        return commit("For ${client.name} ${period.label}: ${spokenJoin(parts)}") {
            val created = mutableListOf<String>()
            val relinked = mutableListOf<ScheduledJob>()
            val originals = mutableListOf<Visit>()
            for (job in plan.toLog) {
                val day = VoicePlans.startOf(job, localZone)?.toLocalDate() ?: continue
                val logged = hoursFor(job)
                created += repository.saveVisit(
                    VisitDraft(
                        clientId = client.id,
                        visitDate = day.toString(),
                        hours = logged,
                        materialsCost = 0.0,
                        description = "Filled in by voice",
                        amount = round2(priceVisit(source, logged, 0.0)),
                        completesJobId = job.id,
                        jobOutcome = "done",
                    ),
                )
            }
            for ((job, visit) in plan.toLink) {
                repository.completeJobWithVisit(job.id, visit.id)
                relinked += job
            }
            if (hours != null) {
                for (visit in plan.toFix) {
                    originals += visit
                    repository.saveVisit(
                        VisitDraft(
                            id = visit.id,
                            clientId = visit.clientId,
                            visitDate = visit.visitDate,
                            hours = round2(hours),
                            materialsCost = visit.materialsCost,
                            materialsNote = visit.materialsNote,
                            description = visit.description,
                            amount = round2(priceVisit(source, hours, visit.materialsCost)),
                            missed = visit.missed,
                        ),
                    )
                }
            }
            CallLedger.record("Filled in ${client.name}'s visits ${period.label}") {
                created.forEach { runCatching { repository.deleteVoiceVisit(it) } }
                relinked.forEach { runCatching { repository.reopenJob(it.id) } }
                originals.forEach { visit ->
                    runCatching {
                        repository.saveVisit(
                            VisitDraft(
                                id = visit.id,
                                clientId = visit.clientId,
                                visitDate = visit.visitDate,
                                hours = visit.hours,
                                materialsCost = visit.materialsCost,
                                materialsNote = visit.materialsNote,
                                description = visit.description,
                                amount = visit.amount,
                                missed = visit.missed,
                            ),
                        )
                    }
                }
                "Put ${client.name}'s visits back as they were."
            }
            val did = mutableListOf<String>()
            if (created.isNotEmpty()) did += "${plural(created.size, "visit")} logged"
            if (relinked.isNotEmpty()) did += "${plural(relinked.size, "job")} closed off"
            if (originals.isNotEmpty()) did += "${plural(originals.size, "visit")} corrected"
            fast("${done()} ${client.name} ${period.label}: ${spokenJoin(did)}.")
        }
    }

    private suspend fun markPaid(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.MarkPaid, text, " for ", "Whose invoice has been paid?")
        val unpaid = VoicePlans.unpaid(repository.observeInvoices().first(), client.id)
        if (unpaid.isEmpty()) return "${client.name} has nothing outstanding."
        val summary = if (unpaid.size == 1) {
            "Mark ${client.name}'s invoice ${unpaid[0].number} for ${money(unpaid[0].total)} as paid"
        } else {
            "Mark all ${unpaid.size} of ${client.name}'s unpaid invoices, ${money(unpaid.sumOf { it.total })} in all, as paid"
        }
        return commit(summary) {
            unpaid.forEach { repository.markInvoicePaid(it.id) }
            CallLedger.record("${client.name}'s ${if (unpaid.size == 1) "invoice" else "invoices"} marked paid")
            fast("${done()} ${client.name} is paid up.")
        }
    }

    private suspend fun changeClientDetails(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text)
            ?: return askFor(SpokenIntent.UpdateClient, text, " for ", "Which client's details?")
        val phone = VoiceSlots.phone(text)
        val rate = VoiceSlots.rateIn(text)?.takeIf { it > 0.0 && it <= 1000.0 }
        val hourly = client.billingMode == "hourly"
        if (phone == null && rate == null) {
            return "What should I change for ${client.name}, the phone number or the hourly rate?"
        }
        if (phone == null && !hourly) {
            return "${client.name} is billed at a fixed price, so there's no hourly rate to change."
        }
        val changes = mutableListOf<String>()
        if (phone != null) changes += "number to ${VoiceSlots.spokenDigits(phone)}"
        if (rate != null && hourly) changes += "hourly rate from ${money(client.hourlyRate)} to ${money(rate)}"
        return commit("Change ${client.name}'s ${spokenJoin(changes)}") {
            val before = ClientDraft(
                name = client.name,
                address = client.address,
                phone = client.phone,
                email = client.email,
                notes = client.notes,
                billingMode = client.billingMode,
                hourlyRate = client.hourlyRate,
                fixedPrice = client.fixedPrice,
                paymentReminders = client.paymentReminders,
            )
            repository.updateClient(
                client,
                before.copy(
                    phone = phone ?: client.phone,
                    hourlyRate = if (rate != null && hourly) rate else client.hourlyRate,
                ),
            )
            CallLedger.record("Changed ${client.name}'s details") {
                val current = repository.client(client.id)
                if (current != null) {
                    repository.updateClient(current, before)
                    "Put ${client.name}'s details back."
                } else {
                    "That client is already gone."
                }
            }
            fast("${done()} ${client.name}'s details are updated.")
        }
    }

    private suspend fun quoteStatus(text: String): String {
        val lower = text.lowercase()
        val status = when {
            listOf("declined", "turned down", "said no", "not going ahead", "rejected", "doesn't want", "doesnt want")
                .any { it in lower } -> "declined"
            listOf("accepted", "approved", "said yes", "agreed", "gone ahead", "going ahead")
                .any { it in lower } -> "accepted"
            else -> return "Was the quote accepted or declined?"
        }
        val clients = repository.observeClients().first().associateBy { it.id }
        val open = repository.observeQuotes().first().filter { it.status == "sent" || it.status == "draft" }
        val scored = open.map { quote ->
            val linked = quote.clientId?.let { clients[it]?.name }?.let { matchScore(it, lower) } ?: 0.0
            quote to maxOf(matchScore(quote.contactName, lower), linked)
        }.filter { (_, score) -> score > 0.0 }
        val best = scored.maxOfOrNull { (_, score) -> score }
        // Of the quotes that match best, the most recent is the one meant; its
        // total is read back, so a wrong guess is caught before anything saves.
        val quote = scored.filter { (_, score) -> score == best }
            .map { (quote, _) -> quote }
            .maxByOrNull { it.issueDate }
            ?: return "I can't find an open quote for that."
        val before = quote.status
        return commit("Mark the quote for ${quote.contactName}, ${money(quote.total)}, as $status") {
            repository.setQuoteStatus(quote.id, status)
            CallLedger.record("Quote for ${quote.contactName} marked $status") {
                repository.setQuoteStatus(quote.id, before)
                "Put that quote back."
            }
            fast("${done()} the ${quote.contactName} quote is $status.")
        }
    }

    private suspend fun enquiryStatus(text: String): String {
        val lower = text.lowercase()
        val open = repository.observeEnquiries().first().filter { it.status == "new" || it.status == "contacted" }
        val enquiry = open
            .map { it to matchScore(it.name, lower) }
            .filter { (_, score) -> score > 0.0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?: return "I can't find an open enquiry for that."
        val action = when {
            lower.contains("client") && listOf("make", "add", "convert", "turn").any { it in lower } -> "convert"
            listOf("not interested", "close", "dismiss", "no longer", "spam").any { it in lower } -> "closed"
            listOf("called", "rang", "spoke", "contacted", "texted", "emailed", "replied", "got back")
                .any { it in lower } -> "contacted"
            else -> return "What's happened with ${enquiry.name}'s enquiry: contacted, closed, or make them a client?"
        }
        val summary = when (action) {
            "convert" -> "Turn ${enquiry.name}'s enquiry into a client"
            "closed" -> "Close ${enquiry.name}'s enquiry"
            else -> "Mark ${enquiry.name}'s enquiry as contacted"
        }
        val before = enquiry.status
        return commit(summary) {
            if (action == "convert") {
                repository.convertEnquiryToClient(enquiry)
                CallLedger.record("${enquiry.name} made a client")
                fast("${done()} ${enquiry.name} is now a client.")
            } else {
                repository.updateEnquiryStatus(enquiry.id, action)
                CallLedger.record("${enquiry.name}'s enquiry marked $action") {
                    repository.updateEnquiryStatus(enquiry.id, before)
                    "Put that enquiry back."
                }
                fast("${done()} ${enquiry.name}'s enquiry is $action.")
            }
        }
    }

    private suspend fun enquiriesRead(): String {
        val open = repository.observeEnquiries().first()
            .filter { it.status == "new" }
            .sortedByDescending { it.createdAt ?: "" }
        if (open.isEmpty()) return "No new enquiries."
        val names = spokenJoin(open.take(3).map { it.name })
        val more = if (open.size > 3) " and ${open.size - 3} more" else ""
        return "You've got ${plural(open.size, "new enquiry").replace("enquirys", "enquiries")}: $names$more."
    }

    private suspend fun quotesWaiting(): String {
        val sent = repository.observeQuotes().first().filter { it.status == "sent" }
            .sortedBy { it.sentAt ?: it.issueDate }
        if (sent.isEmpty()) return "No quotes waiting on a reply."
        val total = money(sent.sumOf { it.total })
        val oldest = sent.take(3).joinToString(", ") { "${it.contactName} ${money(it.total)}" }
        return "${plural(sent.size, "quote")} waiting on a reply, $total in all. Oldest first: $oldest."
    }

    private suspend fun unloggedJobs(text: String): String {
        val today = LocalDate.now()
        val period = VoiceCalendar.periodIn(text, today)
        val jobs = VoicePlans.unlogged(repository.observeJobs().first(), Instant.now(), period, localZone)
        if (jobs.isEmpty()) return "Everything's logged ${period?.label ?: "so far"}."
        val names = repository.observeClients().first().associate { it.id to it.name }
        val byClient = jobs.groupBy { it.clientId }
            .map { (id, list) -> (names[id] ?: "someone") to list.size }
            .sortedByDescending { it.second }
        val spoken = spokenJoin(byClient.take(4).map { (name, n) -> "$n for $name" })
        val more = if (byClient.size > 4) " and ${byClient.size - 4} more clients" else ""
        return "${plural(jobs.size, "job")} not logged yet: $spoken$more. " +
            "Say fill in, with a client's name, and I'll do them."
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

            is Undo.AddedClient -> {
                runCatching { repository.deleteClient(action.clientId) }
                    .getOrElse { return "I couldn't remove ${action.name}." }
                "${undoBanter()} ${action.name} is gone again."
            }

            is Undo.BookedJob -> {
                action.jobId?.let { runCatching { repository.deleteJob(it) } }
                "${undoBanter()} That booking for ${action.clientName} is gone."
            }

            is Undo.MovedJob -> {
                runCatching { repository.saveJob(action.before) }
                    .getOrElse { return "I couldn't move that back." }
                "${undoBanter()} ${action.clientName}'s booking is back where it was."
            }

            is Undo.CancelledJob -> {
                runCatching { repository.saveJob(action.before.copy(id = null)) }
                    .getOrElse { return "I couldn't put that booking back." }
                "${undoBanter()} ${action.clientName}'s booking is back on."
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
        return commit("Add a note to ${client.name}: $note") {
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
            VoiceLines.pick(
                "Noted for ${client.name}.",
                "Got it — that's on ${client.name}'s notes.",
                "Written down for ${client.name}.",
                "That's saved to ${client.name}.",
                "Right, added to ${client.name}'s file.",
            )
        }
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

        // If there is already a visit today for this client, attach the
        // expense to it rather than creating a separate zero-hour visit.
        val existing = repository.observeVisits().first()
            .filter {
                it.clientId == client.id &&
                    it.visitDate == LocalDate.now().toString() &&
                    !it.billed &&
                    it.invoiceId == null
            }
            .maxByOrNull { it.createdAt ?: it.visitDate }
        if (existing != null) {
            val last = VoiceVisit(
                id = existing.id,
                clientId = existing.clientId,
                clientName = client.name,
                materials = existing.materialsCost,
            )
            return repriced(last, existing.materialsCost + amount, note)
        }

        return commit("Add £${format(amount)} of $note for ${client.name}, as materials on today's visit") {
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
            fast(
                VoiceLines.pick(
                    "£${format(amount)} of $note down for ${client.name} — " +
                        "it'll be on their next invoice.",
                    "Sorted, £${format(amount)} of $note on ${client.name}'s account. " +
                        "Next invoice picks it up.",
                    "Right, ${money(amount)} for $note added to ${client.name}.",
                    "That's ${money(amount)} of $note against ${client.name}. On the next bill.",
                ),
            )
        }
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
                commit("Clear the next-visit note for ${target.name}") {
                    repository.saveNextVisitNote(target.id, "")
                    "Cleared the next-visit note for ${target.name}."
                }
            }
            else -> {
                val target = client ?: return "Which client is that for?"
                val note = extractVisitNote(text, target)
                if (note.isBlank()) return "What should I remind you about?"
                commit("Remind you to $note on ${target.name}'s next visit") {
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
        val lower = text.lowercase()
        val named = clientFor(clients, text)
        val wantsAll = lower.contains("all") || lower.contains("unbilled") ||
            lower.contains("invoices")
        if (named == null && wantsAll) {
            return raiseAllUnbilled(clients)
        }
        val client = named ?: inferredClient(clients)
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
        val input = NewInvoiceInput(clientId = client.id, visitIds = visits.map { it.id })
        val made = runCatching { repository.createInvoice(input) }.getOrNull()
            ?: repository.createLocalInvoice(input, repository.getSettings())
            ?: return "I couldn't raise that: the work is still waiting in ready to bill."

        val visitsText = if (visits.size == 1) "one visit" else "${visits.size} visits"
        val totalValue = if (made.total > 0.0) made.total else expected
        val lowerText = text.lowercase()
        val wantsMessage = lowerText.contains("message") || lowerText.contains("email") ||
            lowerText.contains("drop them a line") || lowerText.contains("write to them")
        val drafted = if (wantsMessage) {
            draftInvoiceMessage(client, visits.size, totalValue)
        } else {
            null
        }
        pendingSend = PendingSend(made.invoiceId, client.name, totalValue, drafted)
        CallLedger.record("Draft invoice for ${client.name}, ${money(totalValue)}") {
            runCatching { repository.deleteInvoice(made.invoiceId) }
            pendingSend = null
            "That draft invoice is gone."
        }
        if (drafted != null) {
            return slow(
                "Draft invoice for ${client.name} — $visitsText, ${money(totalValue)}. " +
                    "Message: \"$drafted\". Send it, or leave it in drafts?",
            )
        }
        return slow(
            VoiceLines.pick(
                "Draft invoice for ${client.name} — $visitsText, ${money(totalValue)}. " +
                    "Send it, or leave it in drafts?",
                "That's a draft for ${client.name}, $visitsText, ${money(totalValue)}. " +
                    "Send it now, or leave it?",
                "In drafts: ${client.name}, $visitsText, ${money(totalValue)}. " +
                    "Send it, or leave it there?",
                "Ready to go for ${client.name} — $visitsText, ${money(totalValue)}. " +
                    "Send it, or hold it?",
                "Done — ${client.name}, $visitsText, ${money(totalValue)}. " +
                    "Drafts for now, or send it?",
            ),
        )
    }

    /** A short, human email body for the invoice read-back. */
    private suspend fun draftInvoiceMessage(
        client: Client,
        visits: Int,
        total: Double,
    ): String {
        // Personal sign-off from Josh, not the app/business branding. The
        // formal invoice PDF still carries the business name.
        val firstName = client.name.substringBefore(' ').ifBlank { client.name }
        val work = if (visits == 1) "last week's visit" else "the last $visits visits"
        return "Hi $firstName, here's the invoice for $work — ${money(total)} in total. " +
            "Give me a shout if anything needs checking. Thanks, ${VoiceLines.OWNER_NAME}."
    }

    /**
     * "Raise invoices for all the unbilled work" — one draft per client. A
     * single client uses the normal read-back/send flow above.
     */
    private suspend fun raiseAllUnbilled(clients: List<Client>): String {
        val visits = repository.observeVisits().first()
            .filter { !it.missed && !it.billed && it.invoiceId == null && it.hours > 0.0 }
        if (visits.isEmpty()) return "Nothing unbilled to invoice."
        val groups = visits.groupBy { it.clientId }
            .toList()
            .sortedByDescending { (_, rows) -> rows.sumOf { it.amount } }
        val made = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var total = 0.0
        for ((clientId, rows) in groups) {
            val name = clients.firstOrNull { it.id == clientId }?.name ?: "a client"
            val input = NewInvoiceInput(clientId = clientId, visitIds = rows.map { it.id })
            val result = runCatching { repository.createInvoice(input) }.getOrNull()
                ?: repository.createLocalInvoice(input, repository.getSettings())
            if (result == null) {
                failures += name
            } else {
                made += name
                total += result.total.takeIf { it > 0.0 } ?: rows.sumOf { it.amount }
            }
        }
        if (made.isEmpty()) {
            return "I couldn't raise them without signal. The work is still waiting in " +
                "ready to bill."
        }
        val draftWord = if (made.size == 1) "draft invoice" else "draft invoices"
        val tail = if (failures.isEmpty()) {
            ""
        } else {
            " ${failures.size} couldn't be raised yet: ${failures.joinToString(", ")}."
        }
        return slow(
            "Raised ${made.size} $draftWord totalling ${money(total)}: " +
                "${made.joinToString(", ")}.$tail Review them in Invoices.",
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
        if (lower.contains("whatsapp") || lower.contains("whats app") ||
            lower.contains("what's app")
        ) {
            pendingSend = null
            val share = shareWhatsApp
                ?: return "Open the invoice to share it on WhatsApp."
            return share(pending.invoiceId, pending.message)
        }
        return when {
            yes -> {
                pendingSend = null
                val sender = sendInvoice ?: return "Open the invoice to send it — I can't attach the PDF from here."
                sender(pending.invoiceId, pending.message)
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
        return commit("Set ${client.name}'s rate to £${format(rate)} an hour") {
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
            "$line ${client.name}'s rate is £${format(rate)} an hour from now on."
        }
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
        return commit("Change ${last.clientName}'s visit to ${money(amount)}") {
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
            VoiceLines.pick(
                "Sorted — that visit is ${money(amount)} now$extra.",
                "Added. It comes to ${money(amount)}$extra.",
                "Right, that one's ${money(amount)}$extra.",
                "Updated — ${money(amount)}$extra.",
            )
        }
    }

    /** "a, b and c": the way a person reads a short list aloud. */
    private fun spokenJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
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
        if (items.size <= 3) return "$lead ${spokenJoin(items)}."
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

    /** Offline invoice edits: due date, internal note, mark paid. */
    private suspend fun editInvoice(text: String): String {
        val clients = repository.observeClients().first()
        val client = clientFor(clients, text) ?: inferredClient(clients)
            ?: return "Which client's invoice?"
        val invoice = repository.observeInvoices().first()
            .filter { it.clientId == client.id }
            .maxByOrNull { parseDate(it.issueDate) ?: LocalDate.MIN }
            ?: return "I have no invoices for ${client.name}."
        val lower = text.lowercase()
        if (lower.contains("paid")) {
            if (invoice.status == "paid") return "${client.name}'s invoice is already paid."
            return commit("Mark ${client.name}'s invoice ${invoice.number} for ${money(invoice.total)} as paid") {
                repository.markInvoicePaid(invoice.id)
                CallLedger.record("Invoice ${invoice.number} for ${client.name} marked paid")
                fast("Marked ${client.name}'s invoice ${invoice.number} paid.")
            }
        }
        if (lower.contains("due")) {
            val date = SpeechNormalizer.relativeDate(text, LocalDate.now())
                ?: return "What date should ${client.name}'s invoice be due?"
            return commit(
                "Make ${client.name}'s invoice ${invoice.number} due on " +
                    VoiceCalendar.describeDate(date, LocalDate.now()),
            ) {
                repository.updateInvoiceDraft(invoice.id, date.toString(), invoice.notes)
                val before = invoice.dueDate
                CallLedger.record("Invoice ${invoice.number} due date changed") {
                    repository.updateInvoiceDraft(invoice.id, before, invoice.notes)
                    "Put the due date back."
                }
                fast(
                    "${client.name}'s invoice ${invoice.number} is now due " +
                        "${date.dayOfMonth} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }}.",
                )
            }
        }
        val note = text
            .substringAfter("note", "")
            .removePrefix("s")
            .removePrefix(":")
            .replace(Regex("\\b(?:on|for|to)\\s+the\\s+invoice.*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .trimEnd('.', '!', '?')
            .ifBlank { return "What note should go on ${client.name}'s invoice?" }
        return commit("Put this note on ${client.name}'s invoice ${invoice.number}: $note") {
            repository.updateInvoiceDraft(invoice.id, invoice.dueDate, note)
            val before = invoice.notes
            CallLedger.record("Invoice ${invoice.number} note changed") {
                repository.updateInvoiceDraft(invoice.id, invoice.dueDate, before)
                "Put the invoice note back."
            }
            fast("Noted on ${client.name}'s invoice: $note.")
        }
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

    /**
     * Answer "what do you do", "do you cover Tring", "are you insured" from
     * the profile already on the phone. No model and no cloud: the profile is
     * sectioned, so this picks the section the question is about and then the
     * sentence that shares the most words with it.
     */
    private suspend fun businessInfo(text: String): String {
        val settings = runCatching { repository.getSettings() }.getOrNull()
        val profile = settings?.businessProfile?.takeIf { it.isNotBlank() }
            ?: bundledBusinessProfile()?.takeIf { it.isNotBlank() }
            ?: return "I don't have the business profile yet. Add your website in Settings, " +
                "then Business knowledge."
        val sections = when {
            text.containsAny(
                "insur", "qualif", "accredit", "guild", "city & guilds", "certified",
            ) -> listOf("CREDENTIALS")
            text.containsAny("opening", "open", "what time") -> listOf("HOURS & CONTACT")
            text.containsAny("phone", "email", "contact", "number") -> listOf("HOURS & CONTACT")
            text.containsAny("cover", "area", "based", "travel", "where") -> listOf("AREAS")
            text.containsAny(
                "service", "offer", "do you do", "patio", "lawn", "hedge", "fence",
                "turf", "plant", "maintenance", "commercial", "design",
            ) -> listOf("SERVICES")
            else -> listOf("BUSINESS", "SERVICES", "AREAS", "CREDENTIALS")
        }
        val body = sectionText(profile, sections) ?: profile
        val terms = text.lowercase()
            .split(Regex("[^a-z0-9&]+"))
            .filter { it.length >= 4 && it !in BUSINESS_STOP_WORDS }
            .distinct()
        val sentences = body
            .split(Regex("(?<=[.!?])\\s+|\\n"))
            .map { it.trim().trim('-', '*', '•') }
            .filter { it.length > 20 }
        val best = sentences.maxByOrNull { sentence ->
            terms.count { term -> sentence.contains(term, ignoreCase = true) }
        }
        val chosen = best?.takeIf { sentence ->
            terms.any { term -> sentence.contains(term, ignoreCase = true) }
        } ?: body.take(320)
        val prefix = if (
            text.startsWith("do you") || text.startsWith("are you") || text.startsWith("can you")
        ) {
            "Yes — "
        } else {
            ""
        }
        return prefix + chosen.trim().trimEnd('.') + "."
    }

    private suspend fun runningCosts(): String {
        val costs = repository.observeRunningCosts().first()
        if (costs.isEmpty()) {
            return "I don't have any running costs yet. Add them in Business, Costs, " +
                "and I can tell you what the month really costs."
        }
        val monthly = costs.monthlyRunningCosts()
        val biggest = costBreakdown(costs).firstOrNull()
        val topLine = biggest?.let {
            " The biggest is ${it.category.label.lowercase()}, ${money(it.monthly)} a month."
        } ?: ""
        return "Running costs are ${money(monthly)} a month, ${money(monthly * 12)} a year.$topLine"
    }

    private suspend fun profit(): String {
        val costs = repository.observeRunningCosts().first()
        val snapshot = businessSnapshot(costs)
        if (snapshot.monthIncome <= 0.0) {
            return "No work logged this month yet, so there is no profit figure to give you."
        }
        if (snapshot.netProfit < 0.0) {
            return "This month is down ${money(abs(snapshot.netProfit))}: " +
                "${money(snapshot.monthIncome)} of work, ${money(snapshot.monthMaterials)} " +
                "materials, ${money(snapshot.monthLabour)} labour and " +
                "${money(snapshot.monthlyCosts)} running costs."
        }
        val margin = (snapshot.netMargin * 100).toInt()
        val overheadNote = if (costs.isEmpty()) {
            " That is before overheads, because no running costs are added yet."
        } else {
            ""
        }
        return "${money(snapshot.netProfit)} left this month from " +
            "${money(snapshot.monthIncome)} of work, about $margin percent after materials, " +
            "labour and running costs.$overheadNote"
    }

    private suspend fun breakEven(): String {
        val costs = repository.observeRunningCosts().first()
        if (costs.isEmpty()) {
            return "I don't have running costs yet, so there is no break-even figure. " +
                "Add them in Business, Costs."
        }
        val monthly = costs.monthlyRunningCosts()
        val since = LocalDate.now().minusMonths(3)
        val recent = repository.observeVisits().first().filter { visit ->
            if (visit.missed) return@filter false
            parseDate(visit.visitDate)?.let { !it.isBefore(since) } == true
        }
        val average = recent.sumOf { it.amount } / 3.0
        if (average <= 0.0) {
            return "You need ${money(monthly)} a month to cover running costs, but there is " +
                "no recent work to compare it with."
        }
        return if (average >= monthly) {
            "You need ${money(monthly)} a month to cover running costs. The last three " +
                "months average ${money(average)} of work, so you are covering it."
        } else {
            "You need ${money(monthly)} a month to cover running costs. The last three " +
                "months average ${money(average)}, so you are short by " +
                "${money(monthly - average)} a month."
        }
    }

    private suspend fun leastProfitableClient(): String {
        val since = LocalDate.now().minusDays(90)
        val clients = repository.observeClients().first().associateBy { it.id }
        val rows = repository.observeVisits().first()
            .filter { visit ->
                !visit.missed &&
                    visit.hours > 0.5 &&
                    parseDate(visit.visitDate)?.let { !it.isBefore(since) } == true
            }
            .groupBy { it.clientId }
            .mapValues { (_, visits) ->
                val hours = visits.sumOf { it.hours }
                val net = visits.sumOf { it.amount - it.materialsCost }
                Triple(hours, net, if (hours > 0.0) net / hours else 0.0)
            }
            .filter { it.value.first >= 1.0 }
        val lowest = rows.minByOrNull { it.value.third }
            ?: return "No client has enough logged hours in the last three months to compare."
        val (hours, net, rate) = lowest.value
        val name = clients[lowest.key]?.name ?: "A client"
        return "$name is your lowest earner by hourly return: about ${money(rate)} an hour " +
            "across ${hoursLabel(hours)} in the last three months, ${money(net)} after materials."
    }

    private suspend fun chaseFirst(): String {
        val invoices = repository.observeInvoices().first()
        val clients = repository.observeClients().first().associateBy { it.id }
        val overdue = overdueInvoices(invoices)
            .sortedByDescending { daysOverdue(it) }
        if (overdue.isNotEmpty()) {
            val first = overdue.first()
            val days = daysOverdue(first)
            val name = clients[first.clientId]?.name ?: "a client"
            val rest = if (overdue.size > 1) {
                " There ${if (overdue.size == 2) "is" else "are"} " +
                    "${overdue.size - 1} more overdue after that."
            } else {
                ""
            }
            return "Chase $name first: ${money(first.total)}, $days " +
                "${if (days == 1L) "day" else "days"} over.$rest"
        }
        val unbilled = repository.observeVisits().first().filter { !it.billed && !it.missed }
        if (unbilled.isNotEmpty()) {
            val total = unbilled.sumOf { it.amount }
            return "Nothing is overdue. The next move is billing ${money(total)} across " +
                "${unbilled.size} ${if (unbilled.size == 1) "visit" else "visits"}."
        }
        return "Nothing is overdue and nothing is waiting to be billed. You are clear."
    }

    /**
     * A short, concrete answer to "what can you do". The local model used to
     * answer this by restating the question and wandering; these are the
     * commands it actually has, in the order they are useful in a day.
     */
    private fun help(): String =
        "I can check the diary and your next job, log hours, mark work done, add notes, " +
            "read invoices and who owes you, and tell you about running costs, profit, " +
            "how the business is doing, and the services and areas you cover. " +
            "Try: what's next, or what should I chase first."

    private suspend fun health(): String {
        val clients = repository.observeClients().first()
        val costs = repository.observeRunningCosts().first()
        val snapshot = businessSnapshot(costs)
        val score = coachScore(snapshot)
        val insights = coachInsights(snapshot, costs, clients)
        val top = insights.firstOrNull()
        val body = when {
            !snapshot.hasData ->
                "Add a few visits and running costs and I can start scoring the business."
            top != null -> " The next thing worth a look: ${top.title.lowercase()}."
            else -> " Nothing is flagging up."
        }
        return "Business health is $score out of 100, ${scoreLabel(score).lowercase()}.$body"
    }

    private suspend fun businessSnapshot(costs: List<RunningCost>) =
        buildBusinessSnapshot(
            invoices = repository.observeInvoices().first(),
            visits = repository.observeVisits().first(),
            jobs = repository.observeJobs().first(),
            clients = repository.observeClients().first(),
            costs = costs,
            labourRate = runCatching { repository.getSettings() }
                .getOrNull()
                ?.labourRate
                ?: 0.0,
        )

    private fun sectionText(profile: String, names: List<String>): String? {
        val lines = profile.lines()
        val start = lines.indexOfFirst { line ->
            names.any { name -> line.trim().uppercase().startsWith(name) }
        }
        if (start < 0) return null
        val out = mutableListOf<String>()
        for (index in start..lines.lastIndex) {
            val line = lines[index].trim()
            if (line.isBlank()) {
                if (out.isNotEmpty()) break
                continue
            }
            val heading = line.matches(Regex("^[A-Z][A-Z &]+:.*")) ||
                line.matches(Regex("^[A-Z][A-Z &]+$"))
            if (index > start && heading) break
            val content = if (index == start) line.substringAfter(':', "").trim() else line
            if (content.isNotBlank()) out += content
        }
        return out.joinToString(" ").ifBlank { null }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

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
     * Every client explicitly named in [text]. A joint record ("Louisa &
     * George") scores 1.0 and wins alone; separate Louisa and George records
     * both score 0.75 and are returned together.
     */
    private suspend fun clientsFor(clients: List<Client>, text: String): List<Client> {
        // A clarification just picked one: use it before anything re-scores.
        resolvedClientId?.let { id ->
            val chosen = clients.firstOrNull { it.id == id }
            if (chosen != null) {
                resolvedClientId = null
                lastMentionedClientId = chosen.id
                return listOf(chosen)
            }
        }
        val lower = normalizeMatchText(text)
        val aliases = runCatching { repository.allVoiceAliases() }.getOrDefault(emptyList())
        val byClient = aliases.groupBy({ it.first }, { it.second })
        val scored = clients.mapNotNull { client ->
            val nameScore = matchScore(client.name, lower)
            val aliasScore = byClient[client.id].orEmpty()
                .maxOfOrNull { matchScore(it, lower) } ?: 0.0
            val score = maxOf(nameScore, aliasScore)
            if (score > 0.0) client to score else null
        }.sortedByDescending { (_, score) -> score }
        if (scored.isEmpty()) return emptyList()
        val best = scored.first().second
        var top = scored.filter { (_, score) -> score >= best - 0.001 }
            .map { (client, _) -> client }
        if (top.size > 1) {
            // "Louisa & George" is one record containing both first names.
            // Keep the longest exact match and drop the individual records it
            // already represents, so hours are not logged three times.
            val ordered = top.sortedByDescending { normalizeMatchText(it.name).length }
            val chosen = mutableListOf<Client>()
            for (client in ordered) {
                val name = normalizeMatchText(client.name)
                if (chosen.any { normalizeMatchText(it.name).contains(name) }) continue
                chosen += client
            }
            top = chosen
        }
        lastMentionedClientId = top.first().id
        return top
    }

    /**
     * Match a spoken sentence against a client's name or any of the
     * nicknames the owner saved for it ("the rectory", "Smiths"). The alias
     * map is read once per sentence, not per client.
     */
    private suspend fun clientFor(clients: List<Client>, text: String): Client? {
        val found = clientsFor(clients, text)
        if (found.isNotEmpty()) return found.first()
        val lower = normalizeMatchText(text)
        // Conversational callback: "it", "that", "them", "their" after a
        // client was named in the previous turn. Only when no name matched.
        val pronoun = lower.split(Regex("\\s+")).any {
            it.trim('.', ',', '!', '?') in setOf(
                "it", "that", "them", "their", "they", "he", "she", "his", "her",
            )
        }
        if (pronoun) {
            return lastMentionedClientId?.let { id -> clients.firstOrNull { it.id == id } }
        }
        return null
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
        return clients.firstOrNull { it.id == job.clientId }?.also {
            lastMentionedClientId = it.id
        }
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
     * Lowercase, "&" as "and", and possessives removed so "George's" and
     * "George" are the same token to the matcher.
     */
    private fun normalizeMatchText(value: String): String = value.lowercase()
        .replace("&", " and ")
        .replace(Regex("['\u2019]s\\b"), "")
        .replace(Regex("(?<=[a-z])['\u2019](?=\\s|$)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * How strongly [clientName] appears in [text]: 1.0 for the name spoken
     * whole, 0.75 when every significant word matched exactly, 0.55 when one
     * of them only matched by prefix or a one-letter fuzzy hit.
     *
     * The scores exist for ambiguity: two clients landing within 15% of each
     * other get a question instead of the first row in the table.
     */
    private fun matchScore(clientName: String, text: String): Double {
        val name = normalizeMatchText(clientName)
        if (name.isBlank()) return 0.0
        val normalizedText = normalizeMatchText(text)
        if (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(normalizedText)) return 1.0
        val words = name.split(Regex("\\s+")).filter { it.length >= 3 && it !in STOP_WORDS }
        if (words.isEmpty()) return 0.0
        val tokens = normalizedText.split(Regex("\\s+")).filter { it.isNotBlank() }
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
        val lower = normalizeMatchText(transcript)
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
            SpokenIntent.BookJob,
            SpokenIntent.MoveJob,
            SpokenIntent.CancelJob,
            SpokenIntent.AddJobTask,
            SpokenIntent.DeleteVisit,
            SpokenIntent.CreateQuote,
            SpokenIntent.BackfillVisits,
            SpokenIntent.MarkPaid,
            SpokenIntent.UpdateClient,
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
            "carry", "continue", "keep", "on", "more",
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
