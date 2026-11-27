package uk.co.jwgardenservices.crm.voice

import java.time.LocalDate
import java.time.LocalTime

/**
 * Details pulled out of a spoken command: a new client's name, a phone number,
 * an hourly rate, and where a job should be moved to. Pure, like [VoiceCalendar].
 */
object VoiceSlots {
    private val connectors = setOf("and", "of", "the", "&")

    private val nameEnd = Regex(
        """\s+(?:for|on|at|with|phone|number|address|tomorrow|today|tonight|next|this|monday|tuesday|""" +
            """wednesday|thursday|friday|saturday|sunday|in the|from|please|thanks|who|and then|then)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "called Larry", "named Mrs Bell at 12 High Street", "new client Louisa and George". */
    fun newClientName(text: String): String? {
        val cleaned = text.replace(Regex("[,.!?;]+"), " ").replace(Regex("\\s+"), " ")
        val found = Regex("""\b(?:called|named|name is|by the name of)\s+(.+)""", RegexOption.IGNORE_CASE).find(cleaned)
            ?: Regex("""\bnew (?:client|customer)\s+(?!for\b|on\b|at\b|called\b|named\b)(.+)""", RegexOption.IGNORE_CASE).find(cleaned)
            ?: return null
        var tail = found.groupValues[1]
        nameEnd.find(tail)?.let { tail = tail.substring(0, it.range.first) }
        val words = tail.trim().split(' ').filter { it.isNotBlank() }.take(4)
        if (words.isEmpty()) return null
        val name = words.joinToString(" ") { word ->
            if (word.lowercase() in connectors) word.lowercase() else word.replaceFirstChar { it.uppercase() }
        }
        return name.takeIf { it.length >= 2 }
    }

    /** A UK number written as digits, returned as digits only. */
    fun phone(text: String): String? {
        val found = Regex("""(?:\+44|\b0)(?:[\s-]*\d){9,10}""").find(text) ?: return null
        val digits = found.value.filter { it.isDigit() }
        return digits.takeIf { it.length in 10..12 }
    }

    /** "£30 an hour", "30 quid an hour", "£27.50". */
    fun rateIn(text: String): Double? {
        Regex("""£\s*(\d+(?:\.\d{1,2})?)""").find(text)?.let { return it.groupValues[1].toDoubleOrNull() }
        return Regex("""(\d+(?:\.\d{1,2})?)\s*(?:pounds|quid)\b""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Digits read back so a number can be checked by ear: "07123 456789" as "0 7 1 2 3, 4 5 6 7 8 9". */
    fun spokenDigits(digits: String): String {
        val groups = if (digits.length == 11 && digits.startsWith("0")) {
            listOf(digits.take(5), digits.drop(5))
        } else {
            digits.chunked(4)
        }
        return groups.joinToString(", ") { group -> group.toList().joinToString(" ") }
    }

    data class Move(val from: LocalDate?, val to: LocalDate?, val time: LocalTime?)

    /** "move the Smiths from Tuesday to Thursday at 10": the last marker with a day or time after it wins. */
    fun moveTargets(text: String, today: LocalDate): Move {
        val lower = text.lowercase()
        val markers = Regex("""\b(?:to|onto|until|till|for)\b""").findAll(lower).toList().asReversed()
        for (marker in markers) {
            val after = lower.substring(marker.range.first)
            val to = VoiceCalendar.dateIn(after, today)
            val time = VoiceCalendar.timeIn(after)
            if (to != null || time != null) {
                val from = VoiceCalendar.dateIn(lower.substring(0, marker.range.first), today)
                return Move(from, to, time)
            }
        }
        return Move(null, VoiceCalendar.dateIn(text, today), VoiceCalendar.timeIn(text))
    }
}
