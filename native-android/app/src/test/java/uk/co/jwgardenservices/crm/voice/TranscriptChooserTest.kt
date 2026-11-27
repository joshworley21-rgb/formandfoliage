package uk.co.jwgardenservices.crm.voice

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptChooserTest {
    private val today = LocalDate.of(2026, 11, 26)
    private val names = listOf("LOUISA", "GEORGE", "SMITH")

    private fun choose(streaming: String, offline: String) =
        TranscriptChooser.choose(streaming, offline, names, today)

    @Test
    fun `an empty transcript yields to the other one`() {
        assertEquals("At least I had those things.", choose("", "At least I had those things."))
        assertEquals("Mark a visit Tuesday, Tuesday", choose("Mark a visit Tuesday, Tuesday", ""))
        assertEquals("", choose("", ""))
    }

    @Test
    fun `identical transcripts are left alone`() {
        assertEquals("what's on tomorrow", choose("what's on tomorrow", "What's on tomorrow"))
    }

    @Test
    fun `a tie keeps the streaming transcript`() {
        // From a real call: both read as an action with a day and a time.
        val live = "Can you add a new client for Tuesday morning called Larry"
        val whole = "Can you add any clients for Tuesday morning? Good Larry."
        assertEquals(live, choose(live, whole))
    }

    @Test
    fun `the one that makes sense wins over a garbled one`() {
        val live = "Uh, can we backtrack backdate all of Louisa and George's visits for this month " +
            "and make sure they've been marked eight hours"
        val whole = "A can we backtrack, backdate all of Louise and George's visits for this month " +
            "for most of the Primark Hours?"
        assertEquals(live, choose(live, whole))
    }

    @Test
    fun `a cut off streaming transcript loses to the complete one`() {
        assertEquals("log three hours for Smith", choose("log three hours for", "log three hours for Smith"))
    }

    @Test
    fun `an offline hallucination does not beat a clear command`() {
        assertEquals("what's on tomorrow", choose("what's on tomorrow", "thank you for watching"))
    }

    @Test
    fun `scores reward commands, names, days, times and lengths`() {
        val plain = TranscriptChooser.score("lovely weather", names, today)
        val command = TranscriptChooser.score("book Smith in for Tuesday at 9 for 2 hours", names, today)
        assertEquals(true, command > plain + 5)
    }

    @Test
    fun `junk is marked down`() {
        val clean = TranscriptChooser.score("log three hours for Smith", names, today)
        val doubled = TranscriptChooser.score("log three hours for Smith Smith", names, today)
        val cut = TranscriptChooser.score("log three hours for Smith h", names, today)
        assertEquals(true, doubled < clean)
        assertEquals(true, cut < clean)
    }
}
