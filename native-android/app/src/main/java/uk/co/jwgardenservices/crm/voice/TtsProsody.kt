package uk.co.jwgardenservices.crm.voice

/**
 * Prosody shaping for everything the assistant says, applied just before a
 * speech engine sees the text.
 *
 * Three layers:
 *
 *  - **Pace markers.** The runner knows what kind of reply it just built, so
 *    it prefixes short action confirmations with [FAST] (spoken ~10% brisker)
 *    and wrap-up or invoice read-backs with [SLOW] (numbers stay clear). The
 *    marker is invisible, stripped here, and never reaches audio or storage.
 *  - **Lead-ins.** "Right then" / "No worries" get an ellipsis, which every
 *    engine renders as a micro-pause with a falling tail — how a person
 *    picks up.
 *  - **Phonon-only shaping.** Commas become spaced em-dashes, which Phonon's
 *    fast short clauses render as a sharper breath, and money is spelled the
 *    way a tradesperson says it: "£180" becomes "one hundred and eighty
 *    quid", "£87.50" becomes "eighty seven pounds fifty", and larger totals
 *    become "845 pounds". The £ symbol is stripped entirely on this path so
 *    Phonon cannot double-pronounce it. Kokoro keeps commas and reads "£"
 *    itself, so this half is applied only in [PhononSpeech].
 *
 * Speak-only: the recogniser and the CRM never see any of it.
 */
object TtsProsody {

    /** Runner prefix: short, deterministic action reply — spoken briskly. */
    const val FAST = '\u0001'

    /** Runner prefix: wrap-up or invoice read-back — spoken slowly. */
    const val SLOW = '\u0002'

    private val leadIn = Regex(
        "^(right then|no worries|no problem|all sorted|sorted|alright|okay|ok|" +
            "cheers|now then|good stuff|right|morning)\\b[.,!?]*\\s*",
        RegexOption.IGNORE_CASE,
    )

    private val money = Regex("£(\\d+(?:\\.\\d+)?)")
    private val commaRun = Regex("\\s*,\\s*")

    /** Full month names or their usual abbreviations, longest form first. */
    private const val MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|" +
        "june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    private val slashDate = Regex("\\b(\\d{1,2})/(\\d{1,2})\\b(?:/(\\d{4})\\b)?")
    private val dayMonthDate = Regex(
        "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTH)\\b\\.?(?:\\s+(\\d{4})\\b)?",
        RegexOption.IGNORE_CASE,
    )

    private val monthAbbreviation = Regex(
        "\\b(jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\b\\.?",
        RegexOption.IGNORE_CASE,
    )

    private val weekdayAbbreviation = Regex(
        "\\b(mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)\\b\\.?",
        RegexOption.IGNORE_CASE,
    )

    private val ordinals = listOf(
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh",
        "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth",
        "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
        "nineteenth", "twentieth",
    )

    private val weekdayNames = mapOf(
        "mon" to "Monday", "tue" to "Tuesday", "tues" to "Tuesday",
        "wed" to "Wednesday", "thu" to "Thursday", "thur" to "Thursday",
        "thurs" to "Thursday", "fri" to "Friday", "sat" to "Saturday",
        "sun" to "Sunday",
    )

    private val units = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    )
    private val teens = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    /**
     * Split the pace marker off the front of a reply and return the pace
     * multiplier with the cleaned text.
     */
    fun extract(text: String): Pair<Float, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 1f to trimmed
        return when (trimmed[0]) {
            FAST -> 1.1f to trimmed.substring(1).trim()
            SLOW -> 0.95f to trimmed.substring(1).trim()
            else -> 1f to trimmed
        }
    }

    /** Lead-in shaping, safe for every engine. */
    fun smooth(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val match = leadIn.find(trimmed) ?: return trimmed
        val rest = trimmed.substring(match.range.last + 1).trimStart()
        if (rest.isEmpty()) return trimmed
        val lead = match.value.trimEnd(' ', '.', ',', '!', '?')
        return "$lead... $rest"
    }

    /**
     * Phonon-only: dates said the way people say them, em-dash breaths and
     * trade-counter money. Runs on the finished reply, after the database
     * values are already in the template and before any audio is generated.
     */
    fun forPhonon(text: String): String {
        var shaped = smooth(text)
        shaped = spokenDates(shaped)
        shaped = weekdayAbbreviation.replace(shaped) { match ->
            weekdayNames[match.groupValues[1].lowercase()] ?: match.value
        }
        shaped = monthAbbreviation.replace(shaped) { match ->
            monthName(match.groupValues[1])
        }
        shaped = commaRun.replace(shaped, " — ")
        return money.replace(shaped) { match -> spokenMoney(match.groupValues[1]) }
    }

    /** "14/11" / "14 Nov" / "14th November" -> "the fourteenth of November". */
    private fun spokenDates(text: String): String {
        var shaped = slashDate.replace(text) { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val month = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            if (day !in 1..31 || month !in 1..12) return@replace match.value
            val year = match.groupValues[3]
            "the ${ordinal(day)} of ${monthNameByNumber(month)}" + yearPart(year)
        }
        shaped = dayMonthDate.replace(shaped) { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            if (day !in 1..31) return@replace match.value
            "the ${ordinal(day)} of ${monthName(match.groupValues[2])}" +
                yearPart(match.groupValues[3])
        }
        return shaped
    }

    private fun yearPart(raw: String): String = raw.takeIf { it.isNotBlank() }
        ?.let { " " + yearWords(it) }
        .orEmpty()

    private fun ordinal(day: Int): String = when {
        day in 1..20 -> ordinals[day - 1]
        day == 30 -> "thirtieth"
        day == 31 -> "thirty first"
        day in 21..29 -> "twenty ${ordinals[day - 21]}"
        else -> day.toString()
    }

    /** 2025 -> "twenty twenty five"; 2005 -> "two thousand and five". */
    private fun yearWords(raw: String): String {
        val year = raw.toIntOrNull() ?: return raw
        val high = year / 100
        val low = year % 100
        return when {
            low == 0 -> "${words(high)} hundred"
            year in 2000..2009 -> "two thousand and ${words(low)}"
            low < 10 -> "${words(high)} oh ${words(low)}"
            else -> "${words(high)} ${words(low)}"
        }
    }

    private fun monthName(matched: String): String = when (matched.take(3).lowercase()) {
        "jan" -> "January"
        "feb" -> "February"
        "mar" -> "March"
        "apr" -> "April"
        "may" -> "May"
        "jun" -> "June"
        "jul" -> "July"
        "aug" -> "August"
        "sep" -> "September"
        "oct" -> "October"
        "nov" -> "November"
        else -> "December"
    }

    private fun monthNameByNumber(month: Int): String = monthName(
        listOf(
            "jan", "feb", "mar", "apr", "may", "jun",
            "jul", "aug", "sep", "oct", "nov", "dec",
        ).getOrElse(month - 1) { "jan" },
    )

    /** "£180" -> "one hundred and eighty quid"; "£845" -> "845 pounds". */
    private fun spokenMoney(raw: String): String {
        val value = raw.toDoubleOrNull() ?: return "$raw pounds"
        val pounds = value.toInt()
        val pence = ((value - pounds) * 100).toInt().coerceIn(0, 99)
        return when {
            pounds >= 200 -> "$raw pounds"
            pence > 0 -> "${words(pounds)} pounds ${penceWords(pence)}"
            else -> "${words(pounds)} quid"
        }
    }

    private fun penceWords(pence: Int): String =
        if (pence < 10) "oh ${words(pence)}" else words(pence)

    /** Words for 0-199, British style with the "and". */
    private fun words(value: Int): String = when {
        value == 0 -> "zero"
        value < 10 -> units[value]
        value < 20 -> teens[value - 10]
        value < 100 -> {
            val rest = value % 10
            if (rest == 0) tens[value / 10] else "${tens[value / 10]} ${units[rest]}"
        }
        else -> {
            val rest = value % 100
            if (rest == 0) "${units[value / 100]} hundred"
            else "${units[value / 100]} hundred and ${words(rest)}"
        }
    }
}
