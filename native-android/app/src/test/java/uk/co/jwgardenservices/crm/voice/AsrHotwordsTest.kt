package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure half of client-name biasing: the hotword lines and the correction. */
class AsrHotwordsTest {

    @Test
    fun `names become scored hotword lines`() {
        assertEquals(
            "SMITHS :2.0\nMRS JONES :2.0",
            AsrHotwords.buildBias(listOf("SMITHS", "MRS JONES")),
        )
        assertEquals("", AsrHotwords.buildBias(emptyList()))
    }

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
    fun `an exact name wins over a similar shorter name`() {
        assertEquals("SMITHS", AsrHotwords.correct("SMITHS", listOf("SMITHS", "SMITH")))
        assertEquals("SMITH", AsrHotwords.correct("SMITH", listOf("SMITHS", "SMITH")))
    }
}
