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
            AsrHotwords.correct("MARK WEAS TODAY", listOf("LOUISA"), aggressive = true),
        )
    }

    @Test
    fun `an exact name wins over a similar shorter name`() {
        assertEquals("SMITHS", AsrHotwords.correct("SMITHS", listOf("SMITHS", "SMITH")))
        assertEquals("SMITH", AsrHotwords.correct("SMITH", listOf("SMITHS", "SMITH")))
    }

    // From a real call: the offline recogniser heard this correctly, then the corrector turned
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
    fun `a dropped first letter only works when a client name was just asked for`() {
        assertEquals("MARK WEAS TODAY", AsrHotwords.correct("MARK WEAS TODAY", listOf("LOUISA")))
        assertEquals(
            "MARK LOUISA TODAY",
            AsrHotwords.correct("MARK WEAS TODAY", listOf("LOUISA"), aggressive = true),
        )
    }

    // From the second real call, after the first fix: "backtrack" became SECATEURS
    // and "make" LOUISA, because any word with the same consonant skeleton matched.
    private val secondCallNames = callNames + listOf("SECATEURS", "TUESDAY", "STIHL", "WENDOVER")

    @Test
    fun `free speech is not rewritten by sound alike matches`() {
        val said = "Uh, can we backtrack backdate all of Louisa and George's visits for this month " +
            "and make sure they've been marked eight hours"
        val fixed = AsrHotwords.correct(said, secondCallNames)
        assertTrue("was: $fixed", fixed.contains("can we backtrack backdate"))
        assertTrue("was: $fixed", fixed.contains("this month and make sure they've been marked eight hours"))
        listOf("SUNNY", "SECATEURS", "GTA", "TEST", "BARK").forEach {
            assertTrue("$it appeared in: $fixed", !fixed.contains(it))
        }
    }

    @Test
    fun `adding a client for a day is left alone`() {
        val said = "Can you add a new client for Tuesday morning called Larry"
        val fixed = AsrHotwords.correct(said, secondCallNames)
        assertTrue("was: $fixed", fixed.startsWith("Can you add a new client for"))
        assertTrue("was: $fixed", fixed.endsWith("morning called Larry"))
        assertTrue("was: $fixed", !fixed.contains("SUNNY") && !fixed.contains("GTA"))
    }

    @Test
    fun `a near miss spelling is still fixed without the sound alike rule`() {
        assertEquals(
            "log 3 hours at LOUISA today",
            AsrHotwords.correct("log 3 hours at LOUISE today", listOf("LOUISA")),
        )
    }

    @Test
    fun `common words survive even when a name was just asked for`() {
        // TODAY shares a Soundex tail with plenty of names.
        assertEquals(
            "WHAT IS ON TODAY",
            AsrHotwords.correct("WHAT IS ON TODAY", listOf("GATESIA", "KODAYA"), aggressive = true),
        )
    }
}
