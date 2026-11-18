package uk.co.jwgardenservices.crm.voice

/**
 * The deterministic fallback used when a sentence is not sent to the cloud,
 * or when the cloud returns something that does not fit the schema.
 *
 * It is deliberately rule-based and boring: it must never invent a client or
 * an amount. It lives in its own file so the evaluation set can score it on a
 * plain JVM, and so tightening a keyword is a visible, testable change.
 */
object IntentRules {

    private const val MAX_TOPIC = 120

    fun classify(transcript: String): IntentResult {
        val text = transcript.lowercase()
        val kind = when {
            // "Say again" must win over every schedule/word match below.
            text.containsAny(
                "say again", "say that again", "repeat that", "repeat it",
                "what was that", "didn't catch", "didnt catch", "come again",
                "pardon",
            ) -> SpokenIntent.SayAgain

            // Open-handoff fillers. These keep the floor with the assistant
            // instead of being mistaken for a new command or a client name.
            text.trim().trimEnd('.', '!', '?', ',') in setOf(
                "go on", "go on then", "carry on", "keep going", "continue",
                "what else", "what next", "and then", "then what",
            ) -> SpokenIntent.Continue

            // Deliberately narrow: "just wrapping up at the Smiths" is the
            // answer to the call briefing, not a request to reconcile the day.
            text.containsAny(
                "end of day",
                "end-of-day",
                "wrap up today",
                "wrap up the day",
                "wrap today up",
                "run through today",
                "run through the day",
                "run through my jobs",
                "run through the jobs",
                "go through today",
                "go through the day",
                "timesheet",
                "log the day",
                "log today's jobs",
                "batch wrap",
                "reconcile today",
            ) -> SpokenIntent.DayWrapUp

            // "Remind me to get compost for the next visit to the Smiths" —
            // must be checked before AddNote, whose "note for" keywords it
            // also contains.
            text.containsAny("next visit") -> SpokenIntent.NextVisitNote

            text.containsAny("note for", "add a note", "note that", "make a note") ->
                SpokenIntent.AddNote

            text.containsAny(
                "ready to invoice",
                "what's unbilled",
                "whats unbilled",
                "anything to invoice",
                "who can i invoice",
                "unbilled visits",
            ) -> SpokenIntent.Unbilled

            text.containsAny("who are my clients", "list my clients", "client list") ||
                text.trim() == "my clients" -> SpokenIntent.ClientList

            text.containsAny("next job for", "next booking for") ||
                ((text.contains("when is") || text.contains("when's")) &&
                    text.contains("next") &&
                    !text.containsAny("my next", "next job", "next appointment", "next visit")) ->
                SpokenIntent.NextJobForClient

            text.containsAny(
                "earn today",
                "earned today",
                "made today",
                "takings today",
                "today's takings",
                "today's earnings",
                "taken today",
                "took today",
            ) -> SpokenIntent.TodayEarnings

            text.containsAny("best client", "top client", "biggest client") ->
                SpokenIntent.BestClient

            text.containsAny("last visit", "last saw", "when did i last", "last time i saw", "when was i last") ->
                SpokenIntent.LastVisit

            text.containsAny(
                "where's my next",
                "where is my next",
                "next job address",
                "address for my next",
                "address of my next",
                "address for the next",
            ) -> SpokenIntent.NextJobAddress

            text.containsAny(
                "phone number",
                "what's their number",
                "contact details",
                "email address",
                "what's the address",
                "address for",
                "email for",
                "phone for",
            ) -> SpokenIntent.ClientInfo

            text.containsAny("hours this week", "hours today", "hours have i worked") ||
                (text.containsAny("how many hours") && text.containsAny("this week", "today")) ->
                SpokenIntent.HoursThisWeek

            text.containsAny("last month", "this month") &&
                text.containsAny("earn", "made", "make", "taken", "income") ->
                SpokenIntent.MonthEarnings

            text.containsAny("invoice status", "invoice been paid", "invoice paid", "been paid") ||
                Regex(
                    "\\b(?:has|have|did)\\s+(?:the\\s+|my\\s+)?[a-z0-9'& ]+\\s+(?:paid|pay)\\b",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(text) ->
                SpokenIntent.InvoiceStatus

            // Drafting money: explicit verbs only, and before the catch-all
            // "invoice" rule below. The draft is always read back for review;
            // nothing is emailed by voice.
            text.containsAny(
                "raise an invoice",
                "raise a invoice",
                "raise invoice",
                "create an invoice",
                "create invoice",
                "make an invoice",
                "draft invoice",
                "start an invoice",
            ) -> SpokenIntent.RaiseInvoice

            // Corrections to the visit just logged: "add £40 for green
            // waste", "change the rate to £45 an hour". The £ comes from the
            // normaliser; the plain-digit form covers unnormalised input.
            Regex("\\badd\\s+£?\\d").containsMatchIn(text) ||
                text.contains("change the rate") ||
                Regex("\\brate\\s+(?:to|of|at)\\s*£?\\d").containsMatchIn(text) ->
                SpokenIntent.AdjustCharge

            // Trade-counter receipt: money plus the materials word, before
            // the "log" catch-all turns it into an hours command.
            text.containsAny("materials", "receipt", "parts") &&
                Regex("£\\s*\\d|\\b\\d+(?:\\.\\d+)?\\b").containsMatchIn(text) ->
                SpokenIntent.AddExpense

            text.containsAny("owe me", "owes", "owed", "unpaid", "outstanding", "invoice") ->
                SpokenIntent.CheckDebtors
            text.containsAny("earn", "earned", "earnings", "takings", "taken this week", "made this week", "make this week", "income") ->
                SpokenIntent.WeeklyEarnings
            text.containsAny(
                "next job",
                "next appointment",
                "next visit",
                "who's next",
                "what's next",
                "whats next",
            ) -> SpokenIntent.NextJob
            text.containsAny("mark", "finished", "complete", "completed", "done with") &&
                !text.containsAny("how many", "hours", "hrs") -> SpokenIntent.MarkJobDone
            text.containsAny("hour", "hours", "hrs", "log", "worked", "work for") ->
                SpokenIntent.LogHours
            // Live data is never a diary lookup, even when it mentions a day.
            text.containsAny("weather", "forecast", "traffic") -> SpokenIntent.Other
            text.containsAny(
                "schedule",
                "diary",
                "booked",
                "anything on",
                "what's on",
                "whats on",
                "calendar",
                "going on",
                "have on",
                "got on",
                "have i got",
                "what have i",
                "what do i have",
                "tomorrow",
                "next week",
                "this week",
                "next month",
                "week after",
                "monday",
                "tuesday",
                "wednesday",
                "thursday",
                "friday",
                "saturday",
                "sunday",
            ) -> SpokenIntent.CheckSchedule
            text.containsAny("quote", "price", "cost", "charge", "rate", "how much") ->
                SpokenIntent.Quote
            else -> SpokenIntent.Other
        }

        // Only a live quote/pricing question leaves the phone. Everything the
        // CRM already knows is answered locally, even when the same words
        // ("how much do they owe") sound like a price question.
        val needsCloud = kind == SpokenIntent.Quote || text.containsAny(
            "weather",
            "forecast",
            "traffic",
            "supplier",
            "live price",
            "current price",
            "price list",
            "website",
            "online",
        )
        return IntentResult(kind, transcript.take(MAX_TOPIC), needsCloud)
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }
    /**
     * Kinds the rules are reliably right about, used as a fast path so common
     * commands do not wait for the language model. Mark-done and quote are
     * excluded: "did you finish the quote?" is too easy to misread.
     */
    fun isConfident(kind: SpokenIntent): Boolean = when (kind) {
        SpokenIntent.LogHours,
        SpokenIntent.CheckSchedule,
        SpokenIntent.NextJob,
        SpokenIntent.CheckDebtors,
        SpokenIntent.WeeklyEarnings,
        SpokenIntent.AddNote,
        SpokenIntent.Unbilled,
        SpokenIntent.ClientList,
        SpokenIntent.InvoiceStatus,
        SpokenIntent.NextJobForClient,
        SpokenIntent.TodayEarnings,
        SpokenIntent.DayWrapUp,
        SpokenIntent.RaiseInvoice,
        SpokenIntent.AdjustCharge,
        SpokenIntent.AddExpense,
        SpokenIntent.NextVisitNote,
        SpokenIntent.SayAgain -> true

        else -> false
    }

}
