package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normaliser is the cheap half of offline accuracy: it turns what the
 * recogniser heard into the shapes the rules already understand. These cases
 * are the exact phrasings that used to slip through.
 */
class SpeechNormalizerTest {

    private fun normalized(raw: String) = SpeechNormalizer.normalize(raw)

    @Test
    fun `spoken fractions become decimals`() {
        assertEquals(
            "log 3.5 hours for the Smiths",
            normalized("log three and a half hours for the Smiths"),
        )
        assertEquals("0.5 hours at the Joneses", normalized("half an hour at the Joneses"))
        assertEquals("1.5 hours", normalized("an hour and a half"))
        assertEquals("0.25 hours", normalized("a quarter of an hour"))
        assertEquals("2.25 hours", normalized("two and a quarter hours"))
        assertEquals("1.75 hours", normalized("one and three quarters hours"))
    }

    @Test
    fun `trade money slang becomes pounds`() {
        assertEquals("£180", normalized("one hundred and eighty quid"))
        assertEquals("£80 for the hedge", normalized("eighty pounds for the hedge"))
        assertEquals("£70", normalized("seventy bucks"))
    }

    @Test
    fun `minutes keep their unit`() {
        assertEquals("45 minutes", normalized("forty five minutes"))
    }

    @Test
    fun `filler is removed from the front only`() {
        assertEquals(
            "log 3.5 hours for the Smiths",
            normalized("yeah can you just log three and a half hours for the Smiths"),
        )
        assertEquals("£100 for the turf", normalized("erm right so one hundred quid for the turf"))
        assertEquals("what's on tomorrow", normalized("so what's on tomorrow"))
    }

    @Test
    fun `postcodes get their space back`() {
        assertEquals("HP9 2JZ", normalized("hp92jz"))
    }

    @Test
    fun `client names that start with number words are left alone`() {
        assertEquals("one stop gardens", normalized("one stop gardens"))
        assertEquals("three sisters landscaping", normalized("three sisters landscaping"))
    }

    @Test
    fun `a mid-sentence self correction wins`() {
        assertEquals(
            "make it 3 hours for the Smiths",
            normalized("log two hours actually make it three hours for the Smiths"),
        )
        assertEquals(
            "log 4 hours for the Joneses",
            normalized("no wait log four hours for the Joneses"),
        )
        assertEquals(
            "stick £40 onto the Smiths",
            normalized("erm stick £30 sorry I meant stick £40 onto the Smiths"),
        )
    }

    @Test
    fun `a trailing correction phrase does not eat the sentence`() {
        assertEquals("log 2 hours actually", normalized("log two hours actually"))
    }

    @Test
    fun `relative dates resolve to concrete days`() {
        val monday = java.time.LocalDate.of(2026, 11, 16) // a Monday
        assertEquals(monday, SpeechNormalizer.relativeDate("what's on today", monday))
        assertEquals(
            monday.plusDays(1),
            SpeechNormalizer.relativeDate("what have I got tomorrow", monday),
        )
        assertEquals(
            monday.plusDays(2),
            SpeechNormalizer.relativeDate("the day after tomorrow", monday),
        )
        // "next Monday" said on a Monday means the Monday after this one.
        assertEquals(
            monday.plusWeeks(1),
            SpeechNormalizer.relativeDate("next Monday", monday),
        )
        // A bare weekday is the nearest one, today included.
        assertEquals(
            monday,
            SpeechNormalizer.relativeDate("anything on Monday", monday),
        )
        assertEquals(
            monday.plusDays(4),
            SpeechNormalizer.relativeDate("who's in on Friday", monday),
        )
        assertNull(SpeechNormalizer.relativeDate("who owes me money", monday))
    }

    @Test
    fun `noise rejection`() {
        assertTrue(SpeechNormalizer.isNoise(""))
        assertTrue(SpeechNormalizer.isNoise("..."))
        assertTrue(SpeechNormalizer.isNoise("um"))
        assertTrue(SpeechNormalizer.isNoise("the"))
        assertTrue(SpeechNormalizer.isNoise("er."))
        // A bare acknowledgement with no question pending is noise; the same
        // word while a wrap-up prompt is up is an answer, and the caller
        // bypasses this check for it.
        assertTrue(SpeechNormalizer.isNoise("Yep"))
        assertTrue(SpeechNormalizer.isNoise("no"))
        assertTrue(!SpeechNormalizer.isNoise("undo"))
        assertTrue(!SpeechNormalizer.isNoise("help"))
        assertTrue(!SpeechNormalizer.isNoise("log two hours at the Smiths"))
        assertTrue(!SpeechNormalizer.isNoise("who owes me money"))
    }
}
