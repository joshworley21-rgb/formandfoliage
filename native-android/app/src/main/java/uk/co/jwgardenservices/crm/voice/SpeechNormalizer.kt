package uk.co.jwgardenservices.crm.voice

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Squeezes spoken English into the shapes the offline rules expect, before
 * IntentRules ever sees it. Three passes, in order:
 *
 *  - fractions: "three and a half hours" / "half an hour" -> "3.5 hours"
 *  - numbers and money: "one hundred and eighty quid" -> "£180"
 *  - filler: "yeah can you just" / "erm right so" -> removed from the front
 *
 * Only numbers attached to a unit (hours, minutes, quid, pounds, bucks) are
 * rewritten, so a client called "One Stop Gardens" is left exactly as it was
 * heard. Postcodes read as one token ("hp92jz") get their usual space back.
 *
 * This runs on every ASR sentence, so it has to be cheap and boring: no
 * model, no I/O, just a handful of regexes over a short string.
 */
object SpeechNormalizer {

    /** Up to five words, long enough for "three hundred and twenty five". */
    private const val NUMBER_RUN = "([a-z]+(?:[\\s-]+[a-z]+){0,4})"

    private val hourAndAHalf = Regex(
        "\\b(?:an?|one)\\s+hours?\\s+and\\s+a\\s+(half|quarter)\\b(?:\\s+(?:hours?|hrs?))?",
        RegexOption.IGNORE_CASE,
    )

    private val fractionHours = Regex(
        "\\b$NUMBER_RUN\\s+and\\s+(?:a\\s+)?(half|quarter|three\\s+quarters?)\\s+" +
            "(?:hours?|hrs?)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val halfHour = Regex("\\bhalf\\s+(?:an?\\s+)?hours?\\b", RegexOption.IGNORE_CASE)

    private val quarterHour = Regex(
        "\\b(?:a\\s+)?quarter\\s+(?:of\\s+)?(?:an?\\s+)?hours?\\b",
        RegexOption.IGNORE_CASE,
    )

    private val numberWithUnit = Regex(
        "\\b$NUMBER_RUN\\s+(hours?|hrs?|minutes?|mins?|quid|pounds?|bucks?)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val compactPostcode = Regex(
        "\\b([a-z]{1,2}\\d{1,2}[a-z]?\\d[a-z]{2})\\b",
        RegexOption.IGNORE_CASE,
    )

    private val moneyUnits = setOf("quid", "pound", "pounds", "buck", "bucks")
    private val minuteUnits = setOf("minute", "minutes", "min", "mins")

    private val fillerPatterns = listOf(
        "yeah", "yeh", "yep", "yup", "okay", "ok", "right", "so", "well",
        "erm", "er", "um", "uh", "ah", "oh", "now", "then", "and", "but",
        "just", "please", "can you", "could you", "would you", "will you",
        "i want to", "i need to", "let's", "lets", "go on then",
    ).map { Regex("^${Regex.escape(it)}\\b[\\s,]+", RegexOption.IGNORE_CASE) }

    private val numberWords = mapOf(
        "zero" to 0.0, "oh" to 0.0, "nought" to 0.0,
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0,
        "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
        "thirteen" to 13.0, "fourteen" to 14.0, "fifteen" to 15.0,
        "sixteen" to 16.0, "seventeen" to 17.0, "eighteen" to 18.0,
        "nineteen" to 19.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0,
        "fifty" to 50.0, "sixty" to 60.0, "seventy" to 70.0,
        "eighty" to 80.0, "ninety" to 90.0,
    )

    /** "actually", "no wait", "sorry I meant": everything after is the real instruction. */
    private val correction = Regex(
        "\\b(?:no\\s+wait|sorry\\s+i\\s+meant|i\\s+meant|i\\s+mean|actually)\\b[,.!]?\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        // A self-correction supersedes everything said before it — "log two
        // hours actually make it three" is one command for three hours.
        var input = trimmed
        val corrections = correction.findAll(trimmed).toList()
        if (corrections.isNotEmpty()) {
            val after = trimmed.substring(corrections.last().range.last + 1).trim()
            if (after.isNotEmpty()) input = after
        }
        var text = stripFillers(input)

        text = hourAndAHalf.replace(text) { match ->
            val quarter = match.groupValues[1].startsWith("q", ignoreCase = true)
            "${format(if (quarter) 1.25 else 1.5)} hours"
        }
        text = fractionHours.replace(text) { match ->
            val base = numberIn(match.groupValues[1]) ?: return@replace match.value
            val fraction = when {
                match.groupValues[2].startsWith("half", ignoreCase = true) -> 0.5
                match.groupValues[2].startsWith("quarter", ignoreCase = true) -> 0.25
                else -> 0.75
            }
            "${base.first}${format(base.second + fraction)} hours"
        }
        text = halfHour.replace(text, "0.5 hours")
        text = quarterHour.replace(text, "0.25 hours")

        text = numberWithUnit.replace(text) { match ->
            val number = numberIn(match.groupValues[1]) ?: return@replace match.value
            val unit = match.groupValues[2].lowercase(Locale.UK)
            when {
                unit in moneyUnits -> "${number.first}£${format(number.second)}"
                unit in minuteUnits -> {
                    val minutes = format(number.second)
                    "${number.first}$minutes ${if (number.second == 1.0) "minute" else "minutes"}"
                }
                else -> {
                    val suffix = if (number.second == 1.0) "hour" else "hours"
                    "${number.first}${format(number.second)} $suffix"
                }
            }
        }

        text = compactPostcode.replace(text) { match ->
            val code = match.value.uppercase(Locale.UK)
            if (code.length <= 3) code else code.dropLast(3) + " " + code.takeLast(3)
        }

        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Strip spoken lead-ins, but never eat the whole sentence. */
    private fun stripFillers(text: String): String {
        var out = text.trimStart()
        repeat(8) {
            var matched = false
            for (pattern in fillerPatterns) {
                val match = pattern.find(out) ?: continue
                val rest = out.substring(match.range.last + 1).trimStart()
                if (rest.isEmpty()) continue
                out = rest
                matched = true
                break
            }
            if (!matched) return out
        }
        return out
    }

    /**
     * Find the number phrase at the end of [wordText], keeping any words in
     * front of it: "did three" -> ("did ", 3).
     */
    private fun numberIn(wordText: String): Pair<String, Double>? {
        val words = wordText.trim().split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        for (start in words.indices) {
            val value = numberValue(words.subList(start, words.size)) ?: continue
            val prefix = if (start == 0) "" else words.subList(0, start).joinToString(" ") + " "
            return prefix to value
        }
        return null
    }

    /** Place-value aware, unlike the simple sum in [parseSpokenNumber]. */
    private fun numberValue(words: List<String>): Double? {
        var total = 0.0
        var current = 0.0
        var seen = false
        for (raw in words) {
            val word = raw.lowercase(Locale.UK)
            when {
                word == "and" -> Unit
                word == "a" || word == "an" -> {
                    current += 1.0
                    seen = true
                }
                numberWords.containsKey(word) -> {
                    current += numberWords.getValue(word)
                    seen = true
                }
                word == "hundred" -> {
                    current = (if (current == 0.0) 1.0 else current) * 100
                    seen = true
                }
                word == "thousand" -> {
                    total += (if (current == 0.0) 1.0 else current) * 1000
                    current = 0.0
                    seen = true
                }
                word.toDoubleOrNull() != null -> {
                    current += word.toDouble()
                    seen = true
                }
                else -> return null
            }
        }
        return if (seen) total + current else null
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private val weekdayWords = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val nextWeekday = Regex(
        "\\bnext\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val bareWeekday = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Resolve a relative spoken phrase to the concrete day it means, so the
     * Room query runs on a date rather than a string:
     *
     *   "today" / "tonight" -> today
     *   "tomorrow" -> today + 1
     *   "next Monday" -> the coming Monday, always strictly in the future
     *   "Friday" -> the nearest Friday (today counts when it is Friday)
     *
     * Null when the sentence names no relative date.
     */
    fun relativeDate(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val lower = text.lowercase()
        return when {
            "day after tomorrow" in lower -> today.plusDays(2)
            "tomorrow" in lower -> today.plusDays(1)
            "today" in lower || "tonight" in lower -> today
            nextWeekday.containsMatchIn(lower) -> {
                val name = nextWeekday.find(lower)?.groupValues?.get(1).orEmpty()
                val day = weekdayWords[name] ?: return null
                val candidate = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
                if (candidate == today) candidate.plusWeeks(1) else candidate
            }
            bareWeekday.containsMatchIn(lower) -> {
                val name = bareWeekday.find(lower)?.groupValues?.get(1).orEmpty()
                val day = weekdayWords[name] ?: return null
                today.with(java.time.temporal.TemporalAdjusters.nextOrSame(day))
            }
            else -> null
        }
    }

    /** Filler that is never an instruction on its own. */
    private val NON_WORDS = setOf(
        "uh", "um", "er", "erm", "ah", "eh", "hmm", "huh", "oh", "ow",
        "the", "a", "an", "and", "so", "or", "but", "err",
    )

    /** Single words that are commands even though no rule names them. */
    private val KNOWN_WORDS = setOf("undo", "help", "again", "repeat")

    /**
     * True when a finalised sentence is not worth a turn: empty, punctuation
     * only, shorter than three meaningful characters, or a single word that
     * is neither filler-like nor a command. The caller stays silent and lets
     * the microphone carry on. Deliberately not applied while an answer is
     * pending — "no" and "next" are real answers there.
     */
    fun isNoise(heard: String): Boolean {
        val trimmed = heard.trim()
        if (trimmed.isEmpty()) return true
        val meaningful = trimmed.count { it.isLetterOrDigit() || it == '£' }
        if (meaningful < 3) return true
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size == 1) {
            val word = words[0].lowercase().trimEnd('.', ',', '!', '?', '—')
            if (word in NON_WORDS) return true
            if (word in KNOWN_WORDS) return false
            val intent = IntentRules.classify(word)
            return intent.intent == SpokenIntent.Other && !intent.requiresCloud
        }
        return false
    }
}
