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

    /** Phonon-only: em-dash breaths and trade-counter money. */
    fun forPhonon(text: String): String =
        money.replace(commaRun.replace(smooth(text), " — ")) { match ->
            spokenMoney(match.groupValues[1])
        }

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
