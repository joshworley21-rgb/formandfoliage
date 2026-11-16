package uk.co.jwgardenservices.crm.voice

import kotlin.math.sqrt

/**
 * A small, offline semantic fallback between the deterministic rules and the
 * cloud classifier.
 *
 * The rules are word-for-word, so British slang ("stick Simon down for a half
 * day") misses them and the turn falls through to a cloud call. This matcher
 * expands each sentence into synonym groups and compares it with a curated
 * corpus of trade phrasings, scoring set overlap. It is not an LLM: it is a
 * weighted phrase matcher that costs well under a millisecond, needs no model
 * download, and only answers when the match is clear and ahead of the
 * runner-up. Everything else still goes to the rules' help line or the cloud.
 */
object SemanticMatcher {

    /** Reads are allowed at this score; writes need the higher bar below. */
    private const val READ_THRESHOLD = 0.60
    private const val WRITE_THRESHOLD = 0.70
    private const val MIN_MARGIN = 0.10

    private val WRITE_INTENTS = setOf(
        SpokenIntent.LogHours,
        SpokenIntent.AddNote,
        SpokenIntent.MarkJobDone,
    )

    /** Live data or a quote always goes to the cloud, never this matcher. */
    private val LIVE = setOf(
        "weather", "forecast", "traffic", "supplier", "online", "website",
        "price", "pricing", "quote", "cost", "costs", "charge", "rate", "rates",
    )

    private val STOP = setOf(
        "the", "a", "an", "at", "for", "on", "in", "to", "of", "my", "me", "i",
        "is", "it", "and", "that", "this", "with", "has", "have", "had", "did",
        "do", "does", "was", "were", "are", "you", "your", "we", "us", "any",
        "what", "whats", "when", "who", "how", "much", "many", "got", "get",
        "be", "been", "am", "just", "about", "then", "please", "can", "could",
        "would", "should", "all", "still", "up", "there", "here", "s", "am",
        // Numbers add no routing meaning; "half day" and "half hour" still do.
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "fifteen", "twenty", "thirty", "forty",
        "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
    )

    /** Word → shared concept. Unknown words stand for themselves. */
    private val SYNONYMS = buildMap<String, String> {
        listOf("hours", "hour", "hrs", "hr").forEach { put(it, "hours") }
        listOf(
            "log", "logged", "logging", "stick", "stuck", "put", "chalk",
            "book", "write", "wrote", "down", "bang", "slap",
        ).forEach { put(it, "log") }
        listOf(
            "done", "finished", "finish", "completed", "complete", "sorted",
            "squared", "closed", "wrapped", "wrappedup",
        ).forEach { put(it, "done") }
        listOf("owe", "owes", "owed", "owing", "outstanding", "unpaid", "due", "debt")
            .forEach { put(it, "owe") }
        listOf("money", "pounds", "quid", "cash", "paid", "pay", "paying")
            .forEach { put(it, "money") }
        listOf(
            "earn", "earned", "earning", "earnings", "made", "make", "taking",
            "takings", "taken", "wage", "income", "pocketed",
        ).forEach { put(it, "earn") }
        listOf("diary", "calendar", "booked", "bookings").forEach { put(it, "schedule") }
        listOf("job", "jobs", "appointment", "appointments").forEach { put(it, "job") }
        listOf("client", "clients", "customer", "customers").forEach { put(it, "client") }
        listOf("invoice", "invoices", "bill", "bills", "billed", "billing")
            .forEach { put(it, "invoice") }
        listOf("unbilled", "ready", "waiting").forEach { put(it, "unbilled") }
        listOf("quote", "quotes", "price", "pricing", "estimate").forEach { put(it, "quote") }
        listOf("address", "addresses", "where").forEach { put(it, "address") }
        listOf("phone", "number", "telephone", "mobile", "ring").forEach { put(it, "phone") }
        listOf("last", "previous").forEach { put(it, "last") }
        listOf("seen", "saw", "went").forEach { put(it, "seen") }
        listOf("best", "top", "biggest").forEach { put(it, "best") }
        listOf("list", "everyone").forEach { put(it, "list") }
        listOf("note", "noted", "notes", "memo").forEach { put(it, "note") }
        listOf("today", "tonight").forEach { put(it, "today") }
        listOf("week", "weeks", "weekly").forEach { put(it, "week") }
        listOf("month", "months", "monthly").forEach { put(it, "month") }
    }

    private val EXAMPLES: Map<SpokenIntent, List<String>> = mapOf(
        SpokenIntent.LogHours to listOf(
            "logged three hours at the smiths",
            "put two and a half hours on the johnsons",
            "stick simon down for a half day",
            "chalk up four hours for the browns",
            "write the smiths down for three hours",
            "worked five hours at the joneses",
            "log the half day for simon",
            "log hours for mrs jones",
            "simon is all squared away for three hours",
        ),
        SpokenIntent.MarkJobDone to listOf(
            "mark the smiths job done",
            "finished the jones job",
            "the browns job is complete",
            "close off the smiths",
            "smiths sorted",
            "done with the smiths",
            "mark simon done",
        ),
        SpokenIntent.WhoOwesMe to listOf(
            "who owes me money",
            "how much is outstanding",
            "any unpaid invoices",
            "how much does the smiths owe me",
            "who still owes me",
            "what is owed to me",
            "anyone owing money",
        ),
        SpokenIntent.WeeklyEarnings to listOf(
            "what have i earned this week",
            "how much have i taken this week",
            "earnings for the week",
            "what did i make this week",
            "this week earnings",
        ),
        SpokenIntent.MonthEarnings to listOf(
            "how much did i earn last month",
            "earnings for last month",
            "what did i make this month",
            "this month earnings",
        ),
        SpokenIntent.HoursThisWeek to listOf(
            "how many hours have i worked this week",
            "hours this week",
            "how many hours today",
            "total hours this week",
        ),
        SpokenIntent.CheckSchedule to listOf(
            "what is on today",
            "read me my diary",
            "what have i got on tomorrow",
            "anything booked this week",
            "what is in the diary for next week",
            "what is on this afternoon",
            "have i got anything booked today",
        ),
        SpokenIntent.NextJob to listOf(
            "what is next",
            "who is my next job",
            "what is my next appointment",
            "what is coming up next",
        ),
        SpokenIntent.NextJobAddress to listOf(
            "where is my next job",
            "address for my next job",
            "where is the next job",
            "what address is the next job",
        ),
        SpokenIntent.ClientInfo to listOf(
            "what is the smiths phone number",
            "smiths contact details",
            "smiths email address",
            "phone number for the smiths",
        ),
        SpokenIntent.LastVisit to listOf(
            "when did i last visit the smiths",
            "when was i last at the smiths",
            "last time i saw the smiths",
            "when did i last see the smiths",
        ),
        SpokenIntent.BestClient to listOf(
            "who is my best client",
            "my top client",
            "who is my biggest client",
        ),
        SpokenIntent.AddNote to listOf(
            "note for the smiths that the gate sticks",
            "make a note about the smiths",
            "add a note for the smiths",
            "put a note on the smiths",
        ),
        SpokenIntent.Unbilled to listOf(
            "what is ready to invoice",
            "anything to invoice",
            "unbilled visits",
            "who can i invoice",
            "what is waiting to be invoiced",
        ),
        SpokenIntent.ClientList to listOf(
            "who are my clients",
            "list my clients",
            "client list",
            "read out my clients",
        ),
        SpokenIntent.InvoiceStatus to listOf(
            "has the smiths invoice been paid",
            "smiths invoice status",
            "did the smiths pay",
            "is the smiths invoice paid",
        ),
        SpokenIntent.NextJobForClient to listOf(
            "when is the smiths next in",
            "next booking for the smiths",
            "when is the smiths next booked",
            "when are the smiths next in",
        ),
        SpokenIntent.TodayEarnings to listOf(
            "what have i taken today",
            "earned today",
            "takings today",
            "what did i make today",
        ),
    )

    /** Each example as a set of concepts, built once. */
    private val exampleSets: Map<SpokenIntent, List<Set<String>>> =
        EXAMPLES.mapValues { (_, phrases) -> phrases.map { concepts(it).toSet() } }

    /**
     * Returns the best intent and its score, or null when the match is weak
     * or ambiguous. [text] should be the lowercased transcript.
     */
    fun classify(text: String): Pair<SpokenIntent, Double>? {
        val sentence = concepts(text).toSet()
        if (sentence.size < 2) return null
        if (sentence.any { it in LIVE }) return null

        var bestIntent: SpokenIntent? = null
        var best = 0.0
        var second = 0.0
        for ((intent, examples) in exampleSets) {
            var score = 0.0
            for (example in examples) {
                if (example.isEmpty()) continue
                var overlap = 0
                for (word in sentence) if (word in example) overlap += 1
                val similarity = overlap / sqrt(example.size.toDouble() * sentence.size)
                if (similarity > score) score = similarity
            }
            when {
                score > best -> {
                    second = best
                    best = score
                    bestIntent = intent
                }
                score > second -> second = score
            }
        }

        val intent = bestIntent ?: return null
        val threshold = if (intent in WRITE_INTENTS) WRITE_THRESHOLD else READ_THRESHOLD
        if (best < threshold || best - second < MIN_MARGIN) return null
        return intent to best
    }

    /** Lowercase, punctuation-free concepts, with stop words removed. */
    private fun concepts(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .split(Regex("\\s+"))
            .asSequence()
            .map { it.trim('\'') }
            .filter { it.isNotBlank() && it !in STOP }
            .filter { word -> word.any { it.isLetter() } }
            .map { SYNONYMS[it] ?: it }
            .toList()
}
