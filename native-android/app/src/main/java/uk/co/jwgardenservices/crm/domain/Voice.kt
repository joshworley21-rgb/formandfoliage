package uk.co.jwgardenservices.crm.domain

/**
 * Turning what was said into something the app can do, without any network or
 * model. A deterministic parser keeps driving commands instant and
 * predictable: the same phrase always means the same action, and money
 * actions can be confirmed before anything is written.
 */
sealed interface VoiceIntent {
    /** What's on today. */
    data object TodayRound : VoiceIntent

    /** The next thing after right now. */
    data object NextJob : VoiceIntent

    /** Unpaid money summary. */
    data object OutstandingSummary : VoiceIntent

    /** Which invoices are overdue and by how much. */
    data object ChaseOverdue : VoiceIntent

    /** What the assistant can do. */
    data object Help : VoiceIntent

    /** End the call. */
    data object Stop : VoiceIntent

    /** Yes to a pending money action. */
    data object Confirm : VoiceIntent

    /** No / cancel a pending action. */
    data object Cancel : VoiceIntent

    /** Tick off a job for a named client. */
    data class MarkJobDone(val who: String) : VoiceIntent

    /** Log hours against a client, optionally with materials. */
    data class LogHours(val hours: Double?, val who: String) : VoiceIntent

    /** Mark an invoice paid by its number. */
    data class InvoicePaid(val number: String) : VoiceIntent

    /** Work out a price from hours, rate and materials. */
    data class PriceJob(val hours: Double, val rate: Double?, val materials: Double?) : VoiceIntent

    /** Nothing matched. */
    data class Unknown(val raw: String) : VoiceIntent
}

/** Words that mean numbers while driving: "three", "two and a half", "3.5". */
private val numberWords = mapOf(
    "zero" to 0.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
    "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0,
    "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "thirteen" to 13.0,
    "fourteen" to 14.0, "fifteen" to 15.0, "sixteen" to 16.0, "seventeen" to 17.0,
    "eighteen" to 18.0, "nineteen" to 19.0, "twenty" to 20.0, "thirty" to 30.0,
    "forty" to 40.0, "fifty" to 50.0, "sixty" to 60.0, "seventy" to 70.0,
    "eighty" to 80.0, "ninety" to 90.0, "hundred" to 100.0,
)

/** "three and a half" -> 3.5, "three point five" -> 3.5, "2.5" -> 2.5. */
fun parseSpokenNumber(text: String): Double? {
    val cleaned = text.trim().lowercase()
    runCatching { cleaned.toDouble() }.getOrNull()?.let { return it }

    val andHalf = Regex("(\\w+)\\s+and\\s+a\\s+half")
    andHalf.find(cleaned)?.let { match ->
        val base = numberWords[match.groupValues[1]] ?: match.groupValues[1].toDoubleOrNull()
        if (base != null) return base + 0.5
    }
    val point = Regex("(\\w+)\\s+point\\s+(\\w+)")
    point.find(cleaned)?.let { match ->
        val whole = numberWords[match.groupValues[1]] ?: match.groupValues[1].toDoubleOrNull() ?: return@let
        val fraction = match.groupValues[2].mapNotNull { numberWords[it.toString()] }
            .joinToString("") { it.toInt().toString() }.toDoubleOrNull()
            ?: match.groupValues[2].toDoubleOrNull()
            ?: return@let
        return whole + fraction / powersOfTen(fraction.toString().length)
    }
    return cleaned.split(Regex("\\s+"))
        .mapNotNull { numberWords[it] }
        .reduceOrNull { acc, value -> acc + value }
}

private fun powersOfTen(digits: Int): Double = when (digits) {
    1 -> 10.0
    2 -> 100.0
    3 -> 1000.0
    else -> 1.0
}

/** The digits inside a phrase like "mark invoice 12 paid" -> "12". */
fun digitsIn(text: String): String = text.filter { it.isDigit() }

/** Strips punctuation so a spoken phrase matches reliably. */
private fun normaliseSpeech(text: String): String =
    text.lowercase()
        .replace(Regex("[’']"), "'")
        // Dots survive so spoken decimals like "two point five" and "2.5" stay intact.
        .replace(Regex("[^a-z0-9.'\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

fun parseVoiceCommand(raw: String): VoiceIntent {
    val text = normaliseSpeech(raw)
    if (text.isBlank()) return VoiceIntent.Unknown(raw)
    val words = text.split(" ")

    // Confirmations come first so "no, cancel" never becomes an action.
    if (text.matches(Regex("^(yes|yeah|yep|yup|confirm|correct|do it|go ahead|sure)\\b.*"))) {
        return VoiceIntent.Confirm
    }
    if (text.matches(Regex("^(no|nope|cancel|never mind|nevermind|don'?t|forget it)\\b.*"))) {
        return VoiceIntent.Cancel
    }

    if (text.contains(Regex("\\b(help|what can you do|what can you say|commands)\\b"))) {
        return VoiceIntent.Help
    }
    if (text.contains(Regex("\\b(that'?s all|goodbye|bye|end the call|hang up|finish up)\\b")) ||
        text == "stop" || text == "stop the call"
    ) {
        return VoiceIntent.Stop
    }

    if (text.contains(Regex("\\b(today'?s (round|jobs|schedule)|what'?s on today|what have i got (today|on)|round today|jobs today)\\b"))) {
        return VoiceIntent.TodayRound
    }
    if (text.contains(Regex("\\b(next (job|visit|up)|what'?s next|after this)\\b"))) {
        return VoiceIntent.NextJob
    }

    // Mark an invoice paid, with or without "mark": needs a number, otherwise
    // "is it paid yet" would match.
    Regex("invoice (?:number )?([0-9]+)\\b.*\\bpaid\\b|\\bmark\\s+invoice\\s+(?:number\\s+)?([0-9]+)\\b")
        .find(text)?.let { match ->
            digitsIn(match.value).takeIf { it.isNotBlank() }?.let { return VoiceIntent.InvoicePaid(it) }
        }

    // Overdue chasing before the general money summary.
    if (text.contains(Regex("\\b(overdue|late|chase)\\b"))) {
        return VoiceIntent.ChaseOverdue
    }
    if (text.contains(Regex("\\b(outstanding|unpaid|owed|who owes|what'?s owed|money owed)\\b"))) {
        return VoiceIntent.OutstandingSummary
    }

    // Log hours: "log 3 hours for louisa", "add two and a half hours for george".
    Regex("(log|add|record)\\s*(?:([0-9.]+|\\w+(?:\\s+(?:and\\s+)?a\\s+half|\\s+point\\s+\\w+)?)\\s*)?(?:hours?|hrs?)\\s+(?:for|at|on)\\s+(.+)")
        .find(text)?.let { match ->
            val amountText = match.groupValues[2].ifBlank { null }
            return VoiceIntent.LogHours(amountText?.let { parseSpokenNumber(it) }, match.groupValues[3])
        }

    // Price: "price 3 hours at 30 with 15 materials", "price three hours at thirty".
    Regex("price\\s+([0-9.]+|\\w+(?:\\s+(?:and\\s+)?a\\s+half|\\s+point\\s+\\w+)?)\\s*(?:hours?|hrs?)\\s+(?:at|for|of)\\s+([0-9.]+|\\w+(?:\\s+point\\s+\\w+)?)(?:\\s+(?:with|plus|and)\\s+([0-9.]+|\\w+)\\s*(?:of\\s+)?(?:materials?|parts?)?)?")
        .find(text)?.let { match ->
            val hours = parseSpokenNumber(match.groupValues[1])
            val rate = parseSpokenNumber(match.groupValues[2])
            val materials = match.groupValues[3].takeIf { it.isNotBlank() }?.let { parseSpokenNumber(it) }
            if (hours != null && rate != null) {
                return VoiceIntent.PriceJob(hours, rate, materials)
            }
        }

    // Job done: "mark louisa done", "louisa is done", "finish louisa".
    Regex("(?:mark|tick off|finish(ed)?|complete[d]?)\\s+(?:the\\s+)?(?:job\\s+(?:for|at)\\s+)?(.+?)\\s+(?:as\\s+)?(done|complete[d]?)\\b")
        .find(text)?.let { match ->
            return VoiceIntent.MarkJobDone(match.groupValues[2])
        }
    if (text.contains(Regex("\\b(done|finished|completed)\\b"))) {
        // "louisa and george done" without a leading verb.
        val who = text.replace(Regex("\\b(is|was|are|job|visit|for|the|done|finished|completed)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (who.isNotBlank()) return VoiceIntent.MarkJobDone(who)
    }

    return VoiceIntent.Unknown(raw)
}
