package uk.co.jwgardenservices.crm.voice

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Days, times, durations and periods as people say them, and how to read them
 * back. Everything is pure and takes "today" as a parameter, so a confirmation
 * can always state the exact date it understood and be checked by ear.
 */
object VoiceCalendar {
    data class Period(val from: LocalDate, val to: LocalDate, val label: String) {
        operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
    }

    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "weds" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
    )

    private val months = Month.entries.associateBy { it.name.lowercase() }

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    private fun clean(text: String): String =
        " " + text.lowercase().replace(Regex("[,;!?]"), " ").replace(Regex("\\s+"), " ") + " "

    private fun number(word: String): Int? = word.toIntOrNull() ?: numberWords[word]

    // ---------------------------------------------------------------- dates

    /**
     * The day named in [text], or null. A bare weekday means the next one after
     * today; with [preferPast] (logging work already done) it means the most
     * recent one, today included.
     */
    fun dateIn(text: String, today: LocalDate, preferPast: Boolean = false): LocalDate? {
        val t = clean(text)
        return when {
            " day after tomorrow " in t -> today.plusDays(2)
            " day before yesterday " in t -> today.minusDays(2)
            " tomorrow " in t -> today.plusDays(1)
            " yesterday " in t -> today.minusDays(1)
            " today " in t || " tonight " in t -> today
            else -> weekdayIn(t, today, preferPast) ?: ordinalIn(t, today, preferPast)
        }
    }

    private fun weekdayIn(t: String, today: LocalDate, preferPast: Boolean): LocalDate? {
        val match = Regex("""(?:\b(last|this|next|coming)\s+)?\b(${weekdays.keys.joinToString("|")})\b""")
            .find(t) ?: return null
        val qualifier = match.groupValues[1]
        val day = weekdays.getValue(match.groupValues[2])
        return when {
            qualifier == "last" || (preferPast && qualifier.isEmpty()) ->
                today.with(TemporalAdjusters.previousOrSame(day)).let {
                    if (qualifier == "last" && it == today) it.minusWeeks(1) else it
                }
            qualifier == "this" && today.dayOfWeek == day -> today
            else -> today.with(TemporalAdjusters.next(day))
        }
    }

    private fun ordinalIn(t: String, today: LocalDate, preferPast: Boolean): LocalDate? {
        val monthNames = months.keys.joinToString("|")
        val dayThenMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($monthNames)\b""").find(t)
        val monthThenDay = Regex("""\b($monthNames)\s+(\d{1,2})(?:st|nd|rd|th)?\b""").find(t)
        val bare = Regex("""\bthe\s+(\d{1,2})(?:st|nd|rd|th)\b""").find(t)
        return when {
            dayThenMonth != null ->
                inYear(months.getValue(dayThenMonth.groupValues[2]), dayThenMonth.groupValues[1].toInt(), today, preferPast)
            monthThenDay != null ->
                inYear(months.getValue(monthThenDay.groupValues[1]), monthThenDay.groupValues[2].toInt(), today, preferPast)
            bare != null -> bare.groupValues[1].toInt().let { day ->
                var month = today.withDayOfMonth(1)
                var candidate = month.takeIf { day <= it.lengthOfMonth() }?.withDayOfMonth(day)
                if (candidate != null && !preferPast && candidate.isBefore(today)) {
                    month = month.plusMonths(1)
                    candidate = month.takeIf { day <= it.lengthOfMonth() }?.withDayOfMonth(day)
                }
                if (candidate != null && preferPast && candidate.isAfter(today)) {
                    month = month.minusMonths(1)
                    candidate = month.takeIf { day <= it.lengthOfMonth() }?.withDayOfMonth(day)
                }
                candidate
            }
            else -> null
        }
    }

    private fun inYear(month: Month, day: Int, today: LocalDate, preferPast: Boolean): LocalDate? {
        val date = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return when {
            !preferPast && date.isBefore(today) -> date.plusYears(1)
            preferPast && date.isAfter(today) -> date.minusYears(1)
            else -> date
        }
    }

    // ---------------------------------------------------------------- times

    /** A start time, or null. Hours 1 to 6 with no am/pm are afternoons; 7 to 11 are mornings. */
    fun timeIn(text: String): LocalTime? {
        val t = clean(text)
        fun at(hour: Int, minute: Int, meridiem: String?): LocalTime? {
            if (hour !in 0..23 || minute !in 0..59) return null
            val h = when {
                meridiem == "pm" && hour < 12 -> hour + 12
                meridiem == "am" && hour == 12 -> 0
                meridiem == null && hour in 1..6 -> hour + 12
                else -> hour
            }
            return LocalTime.of(h, minute)
        }

        Regex("""(?<![£\d.])(\d{1,2})[:.](\d{2})\s*(am|pm)?\b(?!\s*(?:hours?|hrs?|pounds?|quid))""").find(t)?.let {
            at(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].ifEmpty { null })?.let { time -> return time }
        }
        Regex("""(?<![£\d.])\b(\d{1,2})\s*(am|pm)\b""").find(t)?.let {
            at(it.groupValues[1].toInt(), 0, it.groupValues[2])?.let { time -> return time }
        }
        Regex("""\bhalf past (\w+)\b""").find(t)?.let { m ->
            number(m.groupValues[1])?.let { h -> at(h, 30, null)?.let { return it } }
        }
        Regex("""\bquarter past (\w+)\b""").find(t)?.let { m ->
            number(m.groupValues[1])?.let { h -> at(h, 15, null)?.let { return it } }
        }
        Regex("""\bquarter to (\w+)\b""").find(t)?.let { m ->
            number(m.groupValues[1])?.let { h -> at(if (h == 1) 12 else h - 1, 45, null)?.let { return it } }
        }
        Regex("""\b(\w+) o'?clock\b""").find(t)?.let { m ->
            number(m.groupValues[1])?.let { h -> at(h, 0, null)?.let { return it } }
        }
        Regex("""\bat (\w+) (thirty|fifteen|forty[ -]five)\b""").find(t)?.let { m ->
            val minute = when (m.groupValues[2]) { "thirty" -> 30; "fifteen" -> 15; else -> 45 }
            number(m.groupValues[1])?.let { h -> at(h, minute, null)?.let { return it } }
        }
        Regex("""\bat (\d{1,2}) (\d{2})\b""").find(t)?.let { m ->
            at(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null)?.let { return it }
        }
        Regex("""\bat (\w+)\b(?!\s*(?:hours?|hrs?|minutes?|mins?|pounds?|quid|%))""").find(t)?.let { m ->
            number(m.groupValues[1])?.takeIf { it in 1..12 }?.let { h -> at(h, 0, null)?.let { return it } }
        }
        return when {
            " first thing " in t -> LocalTime.of(8, 0)
            " midday " in t || " noon " in t || " lunchtime " in t || " lunch time " in t -> LocalTime.NOON
            " morning " in t -> LocalTime.of(9, 0)
            " afternoon " in t -> LocalTime.of(13, 0)
            " evening " in t -> LocalTime.of(17, 0)
            else -> null
        }
    }

    // ------------------------------------------------------------- durations

    /** How long, in minutes, or null. */
    fun durationMinutesIn(text: String): Int? {
        val t = clean(text)
        Regex("""\b(\w+) and a half (?:hours?|hrs?)\b""").find(t)?.let { m ->
            number(m.groupValues[1])?.let { return it * 60 + 30 }
        }
        Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*(?:hours?|hrs?)\b""").find(t)?.let { m ->
            return Math.round(m.groupValues[1].toDouble() * 60).toInt()
        }
        Regex("""\b(\d+)\s*(?:minutes?|mins?)\b""").find(t)?.let { return it.groupValues[1].toInt() }
        return when {
            " an hour and a half " in t -> 90
            " quarter of an hour " in t -> 15
            " half an hour " in t || " half hour " in t -> 30
            " half a day " in t || " half day " in t -> 240
            " all day " in t || " full day " in t || " whole day " in t -> 480
            " couple of hours " in t -> 120
            " an hour " in t || " one hour " in t -> 60
            else -> Regex("""\b(${numberWords.keys.joinToString("|")}) (?:hours?|hrs?)\b""").find(t)
                ?.let { numberWords.getValue(it.groupValues[1]) * 60 }
        }
    }

    // --------------------------------------------------------------- periods

    /** A span like "this month", or a single day, or null. */
    fun periodIn(text: String, today: LocalDate): Period? {
        val t = clean(text)
        val firstOfMonth = today.withDayOfMonth(1)
        return when {
            " so far this month " in t -> Period(firstOfMonth, today, "so far this month")
            " this month " in t -> Period(firstOfMonth, firstOfMonth.with(TemporalAdjusters.lastDayOfMonth()), "this month")
            " last month " in t -> firstOfMonth.minusMonths(1).let {
                Period(it, it.with(TemporalAdjusters.lastDayOfMonth()), "last month")
            }
            " this week " in t -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let {
                Period(it, it.plusDays(6), "this week")
            }
            " last week " in t -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1).let {
                Period(it, it.plusDays(6), "last week")
            }
            else -> dateIn(text, today, preferPast = true)?.let { Period(it, it, describeDate(it, today)) }
        }
    }

    // ----------------------------------------------------------- read backs

    fun describeDate(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        today.minusDays(1) -> "yesterday"
        else -> {
            val day = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
            "$day ${date.dayOfMonth} $month" + if (date.year != today.year) " ${date.year}" else ""
        }
    }

    fun describeTime(time: LocalTime): String {
        if (time == LocalTime.NOON) return "midday"
        val hour12 = time.hour.let { if (it % 12 == 0) 12 else it % 12 }
        val part = when {
            time.hour < 12 -> "in the morning"
            time.hour < 17 -> "in the afternoon"
            else -> "in the evening"
        }
        return when (time.minute) {
            0 -> "$hour12 $part"
            30 -> "half past $hour12 $part"
            else -> "$hour12:${"%02d".format(time.minute)} $part"
        }
    }

    fun describeDuration(minutes: Int): String = when {
        minutes == 15 -> "a quarter of an hour"
        minutes == 30 -> "half an hour"
        minutes == 60 -> "an hour"
        minutes == 90 -> "an hour and a half"
        minutes < 60 -> "$minutes minutes"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        minutes % 60 == 30 -> "${minutes / 60} and a half hours"
        else -> "${minutes / 60} hours ${minutes % 60} minutes"
    }
}
