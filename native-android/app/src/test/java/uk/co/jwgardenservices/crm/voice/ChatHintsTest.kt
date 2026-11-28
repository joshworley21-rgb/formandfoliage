package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHintsTest {
    @Test
    fun `a confirmation question offers yes and no`() {
        assertEquals(listOf("Yes", "No"), ChatHints.quickReplies("Log 1 job for £270.00 — shall I go ahead?"))
        assertEquals(listOf("Yes", "No"), ChatHints.quickReplies("Want me to flag that?"))
    }

    @Test
    fun `other replies offer nothing`() {
        assertEquals(emptyList<String>(), ChatHints.quickReplies("Which client was that for?"))
        assertEquals(emptyList<String>(), ChatHints.quickReplies("All sorted."))
        assertEquals(emptyList<String>(), ChatHints.quickReplies(null))
    }

    @Test
    fun `typed text is tidied and capped`() {
        assertEquals("log 2 hours for Smith", ChatHints.clean("  log   2 hours\nfor Smith "))
        assertEquals(500, ChatHints.clean("a".repeat(900)).length)
    }
}
