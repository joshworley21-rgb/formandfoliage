package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCleanupTest {
    @Test
    fun `fillers are dropped but real words stay`() {
        assertEquals(
            "I on Tuesday I was at Willstone can you mark off Peter in Willstone at four hours",
            TranscriptCleanup.clean("I uh on Tuesday I was at Willstone can you mark off Peter in Willstone um at four hours"),
        )
        assertEquals("Humphrey and Herman", TranscriptCleanup.clean("Humphrey and Herman"))
        assertEquals("the umbrella and the error", TranscriptCleanup.clean("the umbrella and the error"))
        assertEquals("yes", TranscriptCleanup.clean("Um, yes"))
    }

    @Test
    fun `sound notes and clipped notes are not speech`() {
        assertEquals("", TranscriptCleanup.clean("[BL"))
        assertEquals("", TranscriptCleanup.clean("[BLANK_AUDIO]"))
        assertEquals("log two hours", TranscriptCleanup.clean("(music) log two hours *cough*"))
    }
}
