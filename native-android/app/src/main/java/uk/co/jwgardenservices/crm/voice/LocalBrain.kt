package uk.co.jwgardenservices.crm.voice

/**
 * Understanding for the sentences the rules and the phrase matcher let
 * through, done by the on-device model so it works with no signal.
 *
 * A 0.6B model is unreliable at open-ended JSON but good at picking one label
 * from a short list, so it answers a single line, `label|client`. Anything it
 * returns is checked before the action runner sees it: the label must be one
 * of ours, the client must be someone the owner actually said, and an action
 * that changes records needs a word in the sentence that supports it.
 */
object LocalBrain {
    /** The labels the model may choose, in the order the prompt lists them. */
    val labels: List<SpokenIntent> = listOf(
        SpokenIntent.LogHours,
        SpokenIntent.CheckSchedule,
        SpokenIntent.NextJob,
        SpokenIntent.NextJobAddress,
        SpokenIntent.MarkJobDone,
        SpokenIntent.CheckDebtors,
        SpokenIntent.TodayEarnings,
        SpokenIntent.WeeklyEarnings,
        SpokenIntent.MonthEarnings,
        SpokenIntent.HoursThisWeek,
        SpokenIntent.ClientInfo,
        SpokenIntent.LastVisit,
        SpokenIntent.BestClient,
        SpokenIntent.Profit,
        SpokenIntent.RunningCosts,
        SpokenIntent.AddNote,
        SpokenIntent.AddExpense,
        SpokenIntent.Unbilled,
        SpokenIntent.ClientList,
        SpokenIntent.InvoiceStatus,
        SpokenIntent.NextJobForClient,
        SpokenIntent.Weather,
        SpokenIntent.Other,
    )

    private const val MAX_CLIENTS = 24
    private const val LEAN_CLIENTS = 10
    private const val MAX_NAME = 24

    /**
     * Kept short: the small model's whole context is 1,280 tokens. [lean] is
     * for the slower models, where time to the first word is mostly prefill:
     * labels and four examples, about 220 tokens.
     */
    fun intentPrompt(clientNames: List<String>, lean: Boolean = false): String {
        val clients = clientNames
            .map { it.trim().take(MAX_NAME) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(if (lean) LEAN_CLIENTS else MAX_CLIENTS)
            .joinToString(", ")
        if (lean) {
            return buildString {
                appendLine("Label a gardener's spoken sentence. Reply with one line like log_hours|Simon")
                appendLine(
                    "log_hours time worked, check_schedule diary, next_job, next_job_address, " +
                        "mark_job_done, who_owes_me unpaid invoices, today_earnings, " +
                        "weekly_earnings, month_earnings, hours_this_week, client_info, last_visit, " +
                        "best_client, profit, running_costs, add_note, add_expense, unbilled, " +
                        "client_list, invoice_status, next_job_for_client, weather, other chat",
                )
                appendLine("stick Simon down for a half day => log_hours|Simon")
                appendLine("who's not paid me => who_owes_me|")
                appendLine("the Hendersons are done => mark_job_done|Hendersons")
                appendLine("how are you => other|")
                if (clients.isNotEmpty()) append("Known customer names: $clients")
            }.trimEnd()
        }
        return buildString {
            appendLine("Label one spoken sentence from a UK self-employed gardener.")
            appendLine("Reply with ONE line only: label|client")
            appendLine("client is the customer named in the sentence, or nothing.")
            appendLine("Labels:")
            appendLine("log_hours: time worked at a customer's")
            appendLine("check_schedule: diary, what is booked, any day")
            appendLine("next_job: the next job. next_job_address: where it is")
            appendLine("mark_job_done: a job is finished")
            appendLine("who_owes_me: unpaid invoices")
            appendLine("today_earnings, weekly_earnings, month_earnings: money taken")
            appendLine("hours_this_week: hours worked")
            appendLine("client_info: details about a customer")
            appendLine("last_visit: when a customer was last seen")
            appendLine("best_client, profit, running_costs: business figures")
            appendLine("add_note: save a note about a customer")
            appendLine("add_expense: materials bought for a customer's job")
            appendLine("unbilled: work not yet invoiced")
            appendLine("client_list: list the customers")
            appendLine("invoice_status: has a customer paid")
            appendLine("next_job_for_client: when a customer is next booked")
            appendLine("weather: the forecast")
            appendLine("other: chat, advice, anything else")
            appendLine("Examples:")
            appendLine("stick Simon down for a half day => log_hours|Simon")
            appendLine("who's still not paid me => who_owes_me|")
            appendLine("where am I off to after this => next_job_address|")
            appendLine("the Hendersons are all done => mark_job_done|Hendersons")
            appendLine("when are we back at Rose Cottage => next_job_for_client|Rose Cottage")
            appendLine("what's my diary like tomorrow => check_schedule|")
            appendLine("remember the Parkers' gate sticks => add_note|Parkers")
            appendLine("bought thirty quid of compost for the Parkers => add_expense|Parkers")
            appendLine("how are you doing => other|")
            if (clients.isNotEmpty()) append("Known customer names: $clients")
        }.trimEnd()
    }

    /** The validated reading of the model's reply, or null when it is not usable. */
    fun parseIntent(raw: String, utterance: String, clientNames: List<String>): IntentResult? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = line.replace(Regex("[`\"]"), "").replace("=>", "|").replace("->", "|")
            .split('|').map { it.trim() }
        // A small model sometimes echoes the sentence first; take the first part that is a label.
        val at = parts.indexOfFirst { part -> labels.any { it.wire == part.lowercase().trimEnd('.', ':', ',') } }
        if (at < 0) return null
        val label = parts[at].lowercase().trimEnd('.', ':', ',')
        val intent = labels.first { it.wire == label }
        if (intent == SpokenIntent.Other) return null
        if (!plausible(intent, utterance)) return null
        val said = parts.getOrNull(at + 1).orEmpty()
        return IntentResult(intent, verifiedClient(said, utterance, clientNames), false)
    }

    /**
     * A client name only counts if the owner actually said it. A first name or
     * a short form resolves to the one customer it fits; if it fits several,
     * it is kept as said so the action runner can ask which one.
     */
    fun verifiedClient(candidate: String, utterance: String, clientNames: List<String>): String {
        val said = candidate.trim()
        if (said.isEmpty()) return ""
        val heard = utterance.lowercase()
        val heardWords = words(heard.replace("'s", "")).toSet()
        val want = words(said)
        val known = clientNames.map { it.trim() }.filter { it.isNotEmpty() }

        known.firstOrNull { it.equals(said, ignoreCase = true) }?.let { exact ->
            return if (words(exact).any { it in heardWords }) exact else ""
        }
        if (want.isNotEmpty() && want.all { it in heardWords }) {
            val fits = known.filter { name -> words(name).toSet().containsAll(want) }
            if (fits.size == 1) return fits.first()
        }
        return if (heard.contains(said.lowercase())) said else ""
    }

    private fun words(text: String): List<String> = text.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in STOP_WORDS }

    private val STOP_WORDS = setOf("the", "and", "mrs", "mr", "ltd")

    /** Whether a records-changing or live-data label has support in what was said. */
    fun plausible(intent: SpokenIntent, utterance: String): Boolean {
        val lower = utterance.lowercase()
        val evidence = when (intent) {
            SpokenIntent.LogHours -> LOG_EVIDENCE
            SpokenIntent.MarkJobDone -> DONE_EVIDENCE
            SpokenIntent.AddNote -> NOTE_EVIDENCE
            SpokenIntent.AddExpense -> EXPENSE_EVIDENCE
            SpokenIntent.Weather -> WEATHER_EVIDENCE
            else -> return true
        }
        return evidence.containsMatchIn(lower)
    }

    private val LOG_EVIDENCE = Regex(
        "\\d|\\b(half|full|whole|couple|few|quarter|hours?|hrs?|day|morning|afternoon)\\b|all day",
    )
    private val DONE_EVIDENCE = Regex(
        "\\b(done|finish(ed)?|complete(d)?|wrapped|sorted|knocked|cleared)\\b|all set|that's it",
    )
    private val NOTE_EVIDENCE = Regex(
        "\\b(note|remember|jot|write|mention|tell|remind(er)?|put|add|says?|keep|log)\\b",
    )
    private val EXPENSE_EVIDENCE = Regex(
        "£|\\d|\\b(pounds?|quid|spent|spend|bought|buy|cost|paid|diesel|fuel|petrol)\\b",
    )
    private val WEATHER_EVIDENCE = Regex(
        "\\b(weather|rain\\w*|forecast|sun\\w*|cold|warm|wind\\w*|hot|snow\\w*|frost\\w*|" +
            "temperature|dry|wet|shower\\w*|storm\\w*)\\b",
    )

    /** The system prompt for answers, in the full and the short form. */
    class Prompts(val full: String, val lean: String) {
        fun forModel(model: LlmModel): String = if (model.leanPrompts) lean else full
    }

    /**
     * About 200 tokens: who it is, the clock, a few facts and one line of the
     * business profile, so a slow model reaches its first word sooner.
     */
    fun leanPrompt(owner: String, clock: String, facts: List<String>, profile: String, style: String): String {
        val known = facts.map { it.trim().take(80) }.filter { it.isNotEmpty() }.take(3)
            .joinToString("; ").ifBlank { "a UK gardening business" }
        return buildString {
            appendLine("You are $owner's secretary for a UK gardening business, on the phone.")
            appendLine("Calm, warm, British, with contractions. Answer in one or two short spoken")
            appendLine("sentences. Never repeat the question or say you are an AI. If unsure, say so briefly.")
            appendLine("It is $clock. Facts: $known.")
            val about = profile.replace(Regex("\\s+"), " ").trim().take(220)
            if (about.isNotEmpty()) appendLine("Business: $about")
            append(style.take(160))
        }.trimEnd()
    }
}
