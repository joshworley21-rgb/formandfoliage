package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The post-ASR corrector: near misses become canonical client names. */
class AsrHotwordsTest {

    @Test
    fun `a near miss is replaced with the client name`() {
        assertEquals(
            "LOG THREE HOURS AT SMITHS",
            AsrHotwords.correct("LOG THREE HOURS AT SMITH", listOf("SMITHS")),
        )
        assertEquals(
            "MARK MRS JONES DONE",
            AsrHotwords.correct("MARK MRS JONS DONE", listOf("MRS JONES")),
        )
    }

    @Test
    fun `longer names allow two edits`() {
        assertEquals(
            "CAERNARVON",
            AsrHotwords.correct("CAERNARVAN", listOf("CAERNARVON")),
        )
    }

    @Test
    fun `unrelated text is left alone`() {
        assertEquals(
            "WHAT IS ON TODAY",
            AsrHotwords.correct("WHAT IS ON TODAY", listOf("SMITHS")),
        )
    }

    @Test
    fun `punctuation is kept`() {
        assertEquals(
            "FINISHED AT SMITHS.",
            AsrHotwords.correct("FINISHED AT SMITH.", listOf("SMITHS")),
        )
    }

    @Test
    fun `two word names replace once and terminate`() {
        // Regression: the blank token left by a multi-word replacement used to
        // make the same span match again on every pass, hanging the thread.
        assertEquals(
            "MARK MRS JONES DONE",
            AsrHotwords.correct("MARK MRS JONS DONE", listOf("MRS JONES")),
        )
        assertEquals("MRS JONES", AsrHotwords.correct("MRS JONS", listOf("MRS JONES")))
    }

    @Test
    fun `a dropped leading consonant still finds the first name`() {
        // Narrowband Bluetooth turns "Louisa" into "weas": W200 and L200
        // share the same Soundex tail, so the first-name index recovers it.
        assertEquals(
            "MARK LOUISA TODAY",
            AsrHotwords.correct("MARK WEAS TODAY", listOf("LOUISA")),
        )
    }

    @Test
    fun `an exact name wins over a similar shorter name`() {
        assertEquals("SMITHS", AsrHotwords.correct("SMITHS", listOf("SMITHS", "SMITH")))
        assertEquals("SMITH", AsrHotwords.correct("SMITH", listOf("SMITHS", "SMITH")))
    }

    // From a real call: Moonshine heard this correctly, then the corrector turned
    // "can" into SUNNY, "add" into GTA, "eight" and "visit" into TEST, "hours"
    // into BARK and "today" into GTA, because the common-word guard compared
    // lower case with upper case and so never protected anything.
    private val callNames = listOf(
        "SUNNY", "GTA", "TEST", "BARK", "LOUISA AND GEORGE", "LOUISA", "GEORGE", "MARKOFF LEWITA",
    )

    @Test
    fun `ordinary words are never rewritten into short names`() {
        val said = "Can you add eight hours to Louisa and George's visit today?"
        val fixed = AsrHotwords.correct(said, callNames)
        assertTrue("was: $fixed", fixed.startsWith("Can you add eight hours to"))
        assertTrue("was: $fixed", fixed.endsWith("visit today?"))
        listOf("SUNNY", "GTA", "TEST", "BARK").forEach {
            assertTrue("$it appeared in: $fixed", !fixed.contains(it))
        }
    }

    @Test
    fun `short names are only matched exactly`() {
        assertEquals("Mark Sunny today", AsrHotwords.correct("Mark Sunny today", listOf("SUNNY")))
        assertEquals("Mark Kenny today", AsrHotwords.correct("Mark Kenny today", listOf("SUNNY")))
        assertEquals("add hours today", AsrHotwords.correct("add hours today", listOf("GTA", "BARK")))
    }

    @Test
    fun `a dropped first letter still works for a long name when the word is not common`() {
        assertEquals(
            "MARK LOUISA TODAY",
            AsrHotwords.correct("MARK WEAS TODAY", listOf("LOUISA")),
        )
    }

    @Test
    fun `common words survive even against a long sound alike name`() {
        // TODAY and TODAYS share a Soundex tail with plenty of names.
        assertEquals("WHAT IS ON TODAY", AsrHotwords.correct("WHAT IS ON TODAY", listOf("GATESIA", "KODAYA")))
    }
}
