package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies should not repeat the same phrasing back to back. */
class VoiceLinesTest {

    @Test
    fun `rotation covers every option before repeating`() {
        val seen = mutableListOf<String>()
        repeat(6) { seen += VoiceLines.pick("A", "B", "C") }
        assertEquals(setOf("A", "B", "C"), seen.toSet())
    }

    @Test
    fun `consecutive replies differ`() {
        var previous = VoiceLines.pick("one", "two", "three")
        repeat(8) {
            val next = VoiceLines.pick("one", "two", "three")
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun `a single option is stable`() {
        assertTrue((1..3).all { VoiceLines.pick("only") == "only" })
    }
}
