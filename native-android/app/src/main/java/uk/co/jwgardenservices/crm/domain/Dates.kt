package uk.co.jwgardenservices.crm.domain

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayMonthFormat = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val dayMonthYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private val weekdayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val shortWeekdayFormat = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val monthYearFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

/** PostgREST returns timestamptz with an offset; accept both that and a bare Z. */
fun parseTimestamp(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrNull()
}

fun parseDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
}

fun Instant.localDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    atZone(zone).toLocalDate()

fun Instant.formatTime(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(timeFormat)

fun Instant.formatDayMonth(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(dayMonthFormat)

fun Instant.formatDayMonthYear(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(dayMonthYearFormat)

fun Instant.formatWeekday(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(weekdayFormat)

fun Instant.formatShortWeekday(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(shortWeekdayFormat)

fun Instant.formatDateTime(zone: ZoneId = ZoneId.systemDefault()): String =
    atZone(zone).format(dateTimeFormat)

fun LocalDate.formatWeekday(): String = format(weekdayFormat)

fun LocalDate.formatDayMonth(): String = format(dayMonthFormat)

fun LocalDate.formatDayMonthYear(): String = format(dayMonthYearFormat)

fun LocalDate.formatMonthYearHeading(): String = format(monthYearFormat)
