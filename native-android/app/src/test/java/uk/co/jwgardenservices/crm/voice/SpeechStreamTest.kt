package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechStreamTest {
    private fun collect(chunks: List<String>): List<String> {
        val stream = SpeechStream()
        val out = mutableListOf<String>()
        chunks.forEach { out += stream.push(it) }
        stream.finish()?.let { out += it }
        return out
    }

    @Test
    fun `delta chunks become sentences as they complete`() {
        val stream = SpeechStream()
        val heard = mutableListOf<String>()
        listOf("You've got the ", "Hendersons at nine", " tomorrow. ", "Then Rose Cottage", " after lunch.")
            .forEach { heard += stream.push(it) }
        assertEquals(listOf("You've got the Hendersons at nine tomorrow."), heard)
        assertEquals("Then Rose Cottage after lunch.", stream.finish())
    }

    @Test
    fun `cumulative chunks give the same sentences as deltas`() {
        val deltas = collect(listOf("Right, ", "that's ", "booked in. ", "Anything else?"))
        val cumulative = collect(
            listOf("Right, ", "Right, that's ", "Right, that's booked in. ", "Right, that's booked in. Anything else?"),
        )
        assertEquals(deltas, cumulative)
        assertEquals(listOf("Right, that's booked in.", "Anything else?"), cumulative)
    }

    @Test
    fun `amounts and abbreviations are not sentence ends`() {
        val out = collect(listOf("Mr. Parker owes you £1.50 for the hedge. ", "That is all."))
        assertEquals(listOf("Mr. Parker owes you £1.50 for the hedge.", "That is all."), out)
    }

    @Test
    fun `a very short first sentence joins the next one`() {
        val out = collect(listOf("Right. ", "You are at the Hendersons at nine tomorrow morning."))
        assertEquals(listOf("Right. You are at the Hendersons at nine tomorrow morning."), out)
    }

    @Test
    fun `reasoning blocks and control tokens are never spoken`() {
        val out = collect(listOf("<think>work it out first</think>", "It is half past ten.<|im_end|>"))
        assertEquals(listOf("It is half past ten."), out)
    }

    @Test
    fun `an unfinished reasoning block says nothing`() {
        val stream = SpeechStream()
        assertEquals(emptyList<String>(), stream.push("<think>hmm this is a long one. And more. "))
        assertNull(stream.finish())
    }

    @Test
    fun `a half written tag is held back`() {
        val stream = SpeechStream()
        assertEquals(emptyList<String>(), stream.push("Sorted for tomorrow, see you then <|im"))
        assertEquals("Sorted for tomorrow, see you then", stream.finish()?.trim())
    }

    @Test
    fun `a long run without punctuation is cut at a comma`() {
        val long = "First there is the front lawn which wants a good cut, then the borders need weeding and " +
            "edging all the way along, and after that the hedge at the back needs a trim and tidy before lunch"
        val out = collect(listOf(long))
        assertEquals(true, out.size >= 2)
        assertEquals(long, out.joinToString(" ").replace("  ", " "))
    }

    @Test
    fun `markdown is stripped`() {
        assertEquals(listOf("Two jobs today."), collect(listOf("**Two** jobs `today`.")))
    }

    @Test
    fun `text reports everything cleaned so far`() {
        val stream = SpeechStream()
        stream.push("<think>x</think>Hello there, ")
        stream.push("how are you")
        assertEquals("Hello there, how are you", stream.text)
    }
}
