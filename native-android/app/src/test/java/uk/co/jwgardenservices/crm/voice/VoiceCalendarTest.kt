package uk.co.jwgardenservices.crm.voice

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCalendarTest {
    // Thursday 26 November 2026.
    private val today = LocalDate.of(2026, 11, 26)

    private fun date(text: String, past: Boolean = false) = VoiceCalendar.dateIn(text, today, past)

    @Test
    fun `relative days`() {
        assertEquals(today, date("do it today"))
        assertEquals(today, date("tonight"))
        assertEquals(LocalDate.of(2026, 11, 27), date("book Smith for tomorrow"))
        assertEquals(LocalDate.of(2026, 11, 28), date("the day after tomorrow"))
        assertEquals(LocalDate.of(2026, 11, 25), date("yesterday"))
        assertEquals(LocalDate.of(2026, 11, 24), date("the day before yesterday"))
    }

    @Test
    fun `a weekday is the next one, or the last one when logging work already done`() {
        assertEquals(LocalDate.of(2026, 12, 1), date("Tuesday morning"))
        assertEquals(LocalDate.of(2026, 12, 1), date("next tuesday"))
        assertEquals(LocalDate.of(2026, 11, 20), date("last friday"))
        assertEquals(LocalDate.of(2026, 11, 20), date("on friday", past = true))
        assertEquals(today, date("this thursday"))
        assertEquals(LocalDate.of(2026, 12, 3), date("next thursday"))
        assertEquals(LocalDate.of(2026, 11, 19), date("last thursday"))
        assertEquals(today, date("on thursday", past = true))
    }

    @Test
    fun `dates with a day of the month`() {
        assertEquals(LocalDate.of(2026, 12, 3), date("the 3rd of December"))
        assertEquals(LocalDate.of(2026, 12, 3), date("december 3rd"))
        assertEquals(LocalDate.of(2027, 1, 2), date("2 January"))
        assertEquals(LocalDate.of(2026, 12, 14), date("the 14th"))
        assertEquals(LocalDate.of(2026, 11, 14), date("the 14th", past = true))
        assertEquals(LocalDate.of(2025, 12, 3), date("the 3rd of December", past = true))
        assertNull(date("the 31st of february"))
    }

    @Test
    fun `no date in the sentence`() {
        assertNull(date("add a client called Larry"))
        assertNull(date("log 3 hours"))
    }

    @Test
    fun `start times`() {
        fun time(text: String) = VoiceCalendar.timeIn(text)
        assertEquals(LocalTime.of(9, 0), time("book Smith at 9"))
        assertEquals(LocalTime.of(14, 0), time("at 2"))
        assertEquals(LocalTime.of(7, 0), time("at 7"))
        assertEquals(LocalTime.of(9, 0), time("9am"))
        assertEquals(LocalTime.of(14, 30), time("2:30pm"))
        assertEquals(LocalTime.of(8, 30), time("half past eight"))
        assertEquals(LocalTime.of(9, 45), time("quarter to ten"))
        assertEquals(LocalTime.of(10, 0), time("ten o'clock"))
        assertEquals(LocalTime.of(9, 30), time("at nine thirty"))
        assertEquals(LocalTime.of(9, 30), time("at 9 30"))
        assertEquals(LocalTime.of(9, 0), time("Tuesday morning"))
        assertEquals(LocalTime.of(8, 0), time("first thing tomorrow"))
        assertEquals(LocalTime.of(13, 0), time("in the afternoon"))
        assertEquals(LocalTime.NOON, time("at lunchtime"))
    }

    @Test
    fun `things that are not start times`() {
        fun time(text: String) = VoiceCalendar.timeIn(text)
        assertNull(time("for 2 hours"))
        assertNull(time("eight hours"))
        assertNull(time("at 8 hours"))
        assertNull(time("that is £12.50 for materials"))
        assertNull(time("log 2.5 hours"))
    }

    @Test
    fun `durations`() {
        fun minutes(text: String) = VoiceCalendar.durationMinutesIn(text)
        assertEquals(120, minutes("for 2 hours"))
        assertEquals(150, minutes("two and a half hours"))
        assertEquals(150, minutes("2.5 hrs"))
        assertEquals(90, minutes("90 minutes"))
        assertEquals(30, minutes("half an hour"))
        assertEquals(240, minutes("half a day"))
        assertEquals(480, minutes("all day"))
        assertEquals(90, minutes("an hour and a half"))
        assertEquals(120, minutes("a couple of hours"))
        assertEquals(480, minutes("make it eight hours"))
        assertEquals(60, minutes("for an hour"))
        assertNull(minutes("at 9"))
        assertNull(minutes("book Smith for Tuesday"))
    }

    @Test
    fun `periods`() {
        val month = VoiceCalendar.periodIn("this month", today)!!
        assertEquals(LocalDate.of(2026, 11, 1), month.from)
        assertEquals(LocalDate.of(2026, 11, 30), month.to)
        val last = VoiceCalendar.periodIn("last month", today)!!
        assertEquals(LocalDate.of(2026, 10, 1), last.from)
        assertEquals(LocalDate.of(2026, 10, 31), last.to)
        val week = VoiceCalendar.periodIn("this week", today)!!
        assertEquals(LocalDate.of(2026, 11, 23), week.from)
        assertEquals(LocalDate.of(2026, 11, 29), week.to)
        val lastWeek = VoiceCalendar.periodIn("last week", today)!!
        assertEquals(LocalDate.of(2026, 11, 16), lastWeek.from)
        assertEquals(LocalDate.of(2026, 11, 22), lastWeek.to)
        val soFar = VoiceCalendar.periodIn("so far this month", today)!!
        assertEquals(today, soFar.to)
        val yesterday = VoiceCalendar.periodIn("yesterday", today)!!
        assertEquals(LocalDate.of(2026, 11, 25), yesterday.from)
        assertEquals(yesterday.from, yesterday.to)
        assertNull(VoiceCalendar.periodIn("for Smith", today))
        assertEquals(true, LocalDate.of(2026, 11, 15) in month)
        assertEquals(false, LocalDate.of(2026, 12, 1) in month)
    }

    @Test
    fun `read backs say the exact day`() {
        assertEquals("today", VoiceCalendar.describeDate(today, today))
        assertEquals("tomorrow", VoiceCalendar.describeDate(today.plusDays(1), today))
        assertEquals("yesterday", VoiceCalendar.describeDate(today.minusDays(1), today))
        assertEquals("Tuesday 1 December", VoiceCalendar.describeDate(LocalDate.of(2026, 12, 1), today))
        assertEquals("Saturday 2 January 2027", VoiceCalendar.describeDate(LocalDate.of(2027, 1, 2), today))
        assertEquals("9 in the morning", VoiceCalendar.describeTime(LocalTime.of(9, 0)))
        assertEquals("1 in the afternoon", VoiceCalendar.describeTime(LocalTime.of(13, 0)))
        assertEquals("midday", VoiceCalendar.describeTime(LocalTime.NOON))
        assertEquals("half past 9 in the morning", VoiceCalendar.describeTime(LocalTime.of(9, 30)))
        assertEquals("2:45 in the afternoon", VoiceCalendar.describeTime(LocalTime.of(14, 45)))
        assertEquals("5 in the evening", VoiceCalendar.describeTime(LocalTime.of(17, 0)))
        assertEquals("an hour", VoiceCalendar.describeDuration(60))
        assertEquals("an hour and a half", VoiceCalendar.describeDuration(90))
        assertEquals("2 hours", VoiceCalendar.describeDuration(120))
        assertEquals("2 and a half hours", VoiceCalendar.describeDuration(150))
        assertEquals("45 minutes", VoiceCalendar.describeDuration(45))
    }
}
