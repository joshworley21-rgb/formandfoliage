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
        // Speech recognisers sprinkle commas into "What, can you do" and the
        // rules are word matches. Drop sentence punctuation before matching;
        // the runner still receives the original transcript for amounts/names.
        val text = transcript.lowercase()
            .replace(Regex("[,\\.!?;:]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val completionQuestion = Regex(
            "\\b(?:did|have|has|was|were|is|are)\\b.*" +
                "\\b(?:mark|marked|finish|finished|complete|completed|done)\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)
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

            // Capabilities, not services. Kept separate from "what do you do",
            // which means the gardening business and goes to BusinessInfo.
            text.trim() in setOf("what can you do", "what could you do", "help") ||
                text.containsAny(
                    "what can you help",
                    "what could you help",
                    "how can you help",
                    "what are you able to do",
                    "what do you help with",
                    "what commands",
                    "list your commands",
                    "what can i ask",
                    "what can i say",
                    "what should i say",
                    "give me an example",
                ) -> SpokenIntent.Help

            // Questions about the business itself are answered from the stored
            // profile on the phone. Checked before the generic "look up" and
            // "what is" rules, which would otherwise send them to the web.
            text.containsAny(
                "about the business",
                "about my business",
                "what services",
                "what do you do",
                "services do you",
                "do you do ",
                "do you offer",
                "do you cover",
                "do you work in",
                "areas do you cover",
                "what areas",
                "where are you based",
                "where is your business",
                "are you insured",
                "are you qualified",
                "gardeners guild",
                "city & guilds",
                "opening hours",
                "what time do you open",
                "how far do you travel",
            ) -> SpokenIntent.BusinessInfo

            // Money the owner can answer without the cloud: what the business
            // costs, what is left, and how healthy it looks.
            text.containsAny(
                "running costs",
                "overheads",
                "what do i spend",
                "what are my costs",
                "monthly costs",
                "cost breakdown",
                "biggest cost",
                "largest cost",
                "cost to run",
            ) -> SpokenIntent.RunningCosts

            text.containsAny(
                "break even",
                "break-even",
                "breaking even",
                "cover my costs",
                "cover the costs",
                "cover its costs",
            ) -> SpokenIntent.BreakEven

            text.containsAny(
                "least profitable",
                "worst client",
                "lowest earning",
                "least valuable",
                "lowest paying",
                "smallest client",
            ) -> SpokenIntent.LeastProfitable

            text.containsAny(
                "chase first",
                "who should i chase",
                "what should i chase",
                "who needs chasing",
                "what needs attention",
                "what should i do first",
                "where do i start",
            ) -> SpokenIntent.ChaseFirst

            text.containsAny(
                "health score",
                "business health",
                "how healthy",
                "how's business",
                "hows business",
                "business score",
            ) -> SpokenIntent.Health

            text.containsAny(
                "net profit",
                "profit",
                "margin",
            ) -> SpokenIntent.Profit

            // Free lookups: weather and facts need no cloud key. Placed before
            // the diary rules so "what's the forecast" is not a schedule query.
            text.containsAny("weather", "forecast") -> SpokenIntent.Weather
            text.startsWithAny(
                "look up ", "search for ", "search the web for ", "google ",
                "tell me about ", "find out about ", "who is ", "who's ", "where is ",
            ) -> SpokenIntent.Lookup
            text.startsWithAny("what is ", "what's ") &&
                !text.containsAny(
                    "on today", "on tomorrow", "on tonight", "booked", "schedule", "diary",
                    "next", "my ", "owing", "unpaid", "invoice",
                    // A day of the week is a diary question, not a web search.
                    "on monday", "on tuesday", "on wednesday", "on thursday", "on friday",
                    "on saturday", "on sunday", "this week", "this month",
                ) -> SpokenIntent.Lookup

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

            // Changes to the client list and the diary. Every one of these is read
            // back and confirmed before anything is saved, so being generous in
            // what counts here is safe. They come before the next-visit reminder
            // rule, which would otherwise take "move the Smiths' next visit".
            text.containsAny(
                "new client", "new customer", "add a client", "add a customer",
                "add client", "add customer", "create a client", "create a customer",
            ) -> SpokenIntent.AddClient

            text.containsAny("backdate", "back date", "backfill", "catch up", "fill in", "fill them in") &&
                text.containsAny("visit", "job", "hours", "diary") -> SpokenIntent.BackfillVisits
            Regex("\\b(?:mark|set|make)\\s+(?:all|every)\\b.*\\b(?:visits?|jobs?)\\b.*" +
                "\\b\\d+(?:\\.\\d+)?\\s*(?:hours?|hrs?)\\b").containsMatchIn(text) ->
                SpokenIntent.BackfillVisits

            Regex("\\b(?:haven't|havent|have not|hasn't|hasnt|has not)\\b.*\\blogged\\b").containsMatchIn(text) ||
                text.containsAny(
                    "not logged", "unlogged", "not been logged", "yet to log", "still to log",
                    "need logging", "need to log", "forget to log", "forgotten to log",
                ) -> SpokenIntent.UnloggedJobs

            // "Add a task to Simon's job: buy bark", "put strim the edges on the Parkers' checklist".
            Regex("\\b(?:task|to-?do|checklist|check list)\\b").containsMatchIn(text) &&
                Regex(
                    "^(?:(?:can|could|would|will) you |please |i (?:need|want) to |i'd like to |let's |lets )*" +
                        "(?:add|put|create|make)\\b",
                ).containsMatchIn(text) -> SpokenIntent.AddJobTask

            // "Delete the last visit for the Parkers" removes a logged visit, never a booking.
            Regex("\\b(?:delete|remove|scrap|get rid of|take off)\\b").containsMatchIn(text) &&
                text.containsAny("visit", "time entry") &&
                !text.containsAny("booking", "appointment", "cancel") &&
                !Regex("^(?:who|what|which|how|when|did|has|have)\\b").containsMatchIn(text) ->
                SpokenIntent.DeleteVisit

            Regex("\\b(?:move|reschedule|rebook|push|bring forward|swap)\\b").containsMatchIn(text) &&
                text.containsAny(" to ", " for ", "later", "earlier", "back", "forward") &&
                (text.containsAny("job", "visit", "booking", "appointment") || mentionsDay(text)) ->
                SpokenIntent.MoveJob

            text.containsAny("cancel", "call off") &&
                (text.containsAny("job", "visit", "booking", "appointment") || mentionsDay(text)) ->
                SpokenIntent.CancelJob

            (Regex("\\b(?:has|have)\\s+paid\\b|\\bpaid\\s+(?:me|up|the invoice|their invoice)\\b|\\bsettled\\b")
                .containsMatchIn(text) ||
                Regex("^(?:please |can you |could you )*mark\\b.*\\bpaid\\b").containsMatchIn(text)) &&
                !Regex("^(?:who|what|which|how|has|have|did|is|are|when)\\b").containsMatchIn(text) &&
                !text.containsAny(
                    "who owes", "owe me", "unpaid", "not paid", "hasn't paid", "hasnt paid",
                    "haven't paid", "havent paid",
                ) -> SpokenIntent.MarkPaid

            Regex("\\b(?:change|update|set|new)\\b.*\\b(?:phone|mobile|number)\\b").containsMatchIn(text) &&
                Regex("\\d").containsMatchIn(text) -> SpokenIntent.UpdateClient

            text.contains("quote") &&
                text.containsAny(
                    "accepted", "declined", "approved", "turned down", "said yes", "said no",
                    "agreed", "gone ahead", "not going ahead", "rejected",
                ) && !Regex("^(?:has|have|did|is|are|who|what|which|how)\\b").containsMatchIn(text) ->
                SpokenIntent.QuoteStatus

            text.containsAny(
                "any quotes", "quotes waiting", "quotes outstanding", "quotes pending",
                "unanswered quotes", "outstanding quotes", "pending quotes", "waiting on quotes",
                "quotes haven't", "quotes havent",
            ) -> SpokenIntent.QuotesWaiting

            text.containsAny("enquiry", "enquiries", "inquiry", "inquiries") &&
                text.containsAny(
                    "contacted", "called", "rang", "spoke to", "close", "not interested",
                    "convert", "make them a client", "as a client", "dismiss",
                ) -> SpokenIntent.EnquiryStatus
            text.containsAny("enquiry", "enquiries", "inquiry", "inquiries") -> SpokenIntent.Enquiries

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

            // Offline invoice edits must be matched before the read-only status
            // rules, otherwise "mark it paid" becomes a lookup.
            text.containsAny("mark", "set", "change", "move", "add a note") &&
                text.containsAny("invoice", "bill") &&
                (text.containsAny("paid", "due", "note", "notes", "due date")) ->
                SpokenIntent.InvoiceEdit

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
                "sort out an invoice",
                "sort an invoice",
                "invoice for",
                "raise an invoice",
                "raise a invoice",
                "raise invoice",
                "raise invoices",
                "create an invoice",
                "create invoice",
                "create invoices",
                "make an invoice",
                "draft invoice",
                "draft invoices",
                "start an invoice",
                "invoice all",
                "invoice the unbilled",
                "bill all",
                "bill the unbilled",
            ) -> SpokenIntent.RaiseInvoice

            // Corrections to the visit just logged: "add £40 for green
            // waste", "change the rate to £45 an hour". The £ comes from the
            // normaliser; the plain-digit form covers unnormalised input.
            (Regex("\\badd\\s+£?\\d").containsMatchIn(text) &&
                // Full stops are already stripped, so "2.5" reads "2 5" here.
                !Regex("\\badd\\s+(?:\\d+\\s+)*\\d+\\s*(?:hours?|hrs?)\\b").containsMatchIn(text)) ||
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
            // Word-boundary "mark" matters: "Markoff" and "Billy" are client
            // names, not commands. A question about completion is also a
            // status question, never an instruction to change the record.
            (text.containsWord("mark") ||
                text.containsAny("finished", "complete", "completed", "done with")) &&
                !completionQuestion &&
                !text.containsAny("how many", "hours", "hrs") -> SpokenIntent.MarkJobDone
            // "I can see there's three visits logs for Louisa" is the owner
            // reading something out, not an instruction to log hours.
            text.containsAny("hour", "hours", "hrs", "log", "worked", "work for") &&
                !Regex(
                    "^(?:i can see|i see|i've seen|i noticed|there(?:'s| is| are)|it looks like|" +
                        "looks like|it says|i think|i can tell)\\b",
                ).containsMatchIn(text.trim()) -> SpokenIntent.LogHours
            // "Book the Smiths in for Friday" is a booking, not a diary question;
            // reading the diary back for it would be a confident wrong answer.
            Regex(
                "^(?:(?:can|could|would|will) you |please |i (?:need|want) to |i'd like to |let's |lets )*" +
                    "(?:add|create|book|schedule|set up|put|make)\\b",
            ).containsMatchIn(text) &&
                (text.containsAny(" client", " customer", " job", " visit", "appointment", "booking") ||
                    text.contains(" for ")) -> SpokenIntent.BookJob
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
        // Weather and facts have a free, keyless path; marking them as cloud
        // made the assistant refuse with "online answers are switched off"
        // before it ever tried Open-Meteo or Wikipedia.
        val needsCloud = when (kind) {
            SpokenIntent.Weather,
            SpokenIntent.Lookup,
            SpokenIntent.BusinessInfo,
            SpokenIntent.RunningCosts,
            SpokenIntent.Profit,
            SpokenIntent.BreakEven,
            SpokenIntent.LeastProfitable,
            SpokenIntent.ChaseFirst,
            SpokenIntent.Health,
            SpokenIntent.Help -> false
            else -> kind == SpokenIntent.Quote || text.containsAny(
                "traffic",
                "supplier",
                "live price",
                "current price",
                "price list",
                "website",
                "online",
            )
        }
        return IntentResult(kind, transcript.take(MAX_TOPIC), needsCloud)
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    private val dayWords = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "tomorrow", "today", "next week", "this week",
    )

    private fun mentionsDay(text: String): Boolean = dayWords.any { text.contains(it) }

    private fun String.containsWord(word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(this)

    private fun String.startsWithAny(vararg prefixes: String): Boolean =
        prefixes.any { startsWith(it) }
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
        SpokenIntent.AddJobTask,
        SpokenIntent.DeleteVisit,
        SpokenIntent.BusinessInfo,
        SpokenIntent.RunningCosts,
        SpokenIntent.Profit,
        SpokenIntent.BreakEven,
        SpokenIntent.LeastProfitable,
        SpokenIntent.ChaseFirst,
        SpokenIntent.Health,
        SpokenIntent.Help,
        SpokenIntent.AddClient,
        SpokenIntent.BookJob,
        SpokenIntent.MoveJob,
        SpokenIntent.CancelJob,
        SpokenIntent.AddJobTask,
        SpokenIntent.DeleteVisit,
        SpokenIntent.BackfillVisits,
        SpokenIntent.MarkPaid,
        SpokenIntent.UpdateClient,
        SpokenIntent.QuoteStatus,
        SpokenIntent.EnquiryStatus,
        SpokenIntent.Enquiries,
        SpokenIntent.QuotesWaiting,
        SpokenIntent.UnloggedJobs,
        SpokenIntent.SayAgain -> true

        else -> false
    }

}
