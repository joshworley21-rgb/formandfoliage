package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.jwgardenservices.crm.voice.ConfirmationGate.Answer

class ConfirmationGateTest {
    private fun answer(text: String) = ConfirmationGate.interpret(text)

    @Test
    fun `plain agreement is a yes`() {
        listOf(
            "yes", "Yes.", "yeah", "yep", "yes please", "yeah go ahead", "go ahead", "go ahead then",
            "okay", "ok then", "sure", "correct", "that's right", "sounds good", "do it", "please do",
            "yes please josh", "that's fine thanks", "absolutely", "perfect",
        ).forEach { assertEquals("'$it'", Answer.Yes, answer(it)) }
    }

    @Test
    fun `a short refusal is a no`() {
        listOf(
            "no", "No.", "nope", "no thanks", "cancel", "cancel that", "stop", "don't", "that's wrong",
            "no no no", "not that", "scrap that", "forget it", "never mind", "wait",
        ).forEach { assertEquals("'$it'", Answer.No, answer(it)) }
    }

    @Test
    fun `a sentence that merely contains yes is never approval`() {
        listOf(
            "yeah and also add a note for Smith",
            "yes but make it three hours",
            "okay what's on tomorrow",
            "right what's next",
            "sure log two hours for Jones",
        ).forEach { assertEquals("'$it'", Answer.Other, answer(it)) }
    }

    @Test
    fun `a correction goes through as a new command`() {
        assertEquals(Answer.Other, answer("no make it three hours instead for the Smiths"))
    }

    @Test
    fun `mixed or empty answers are not a yes or a no`() {
        assertEquals(Answer.Other, answer("yes no"))
        assertEquals(Answer.Other, answer("that's not right"))
        assertEquals(Answer.Other, answer(""))
        assertEquals(Answer.Other, answer("um"))
        assertEquals(Answer.Other, answer("I think so"))
        assertEquals(Answer.Other, answer("good morning how are you"))
    }

    @Test
    fun `weak agreement only counts when the answer is very short`() {
        assertEquals(Answer.Yes, answer("right"))
        assertEquals(Answer.Yes, answer("fine"))
        assertEquals(Answer.Other, answer("fine that is it for now"))
    }
}
