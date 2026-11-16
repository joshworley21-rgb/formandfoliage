package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
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
}
