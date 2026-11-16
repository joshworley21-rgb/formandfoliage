package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Adaptive TTS gain: louder over noise, normal in a quiet garden. */
class TtsGainTest {

    @Test
    fun `quiet garden plays at normal volume`() {
        TtsGain.updateFromAmbient(0.02f)
        assertEquals(1f, TtsGain.current, 0.001f)
    }

    @Test
    fun `moderate road noise lifts the voice slightly`() {
        TtsGain.updateFromAmbient(0.15f)
        assertEquals(1.08f, TtsGain.current, 0.001f)
    }

    @Test
    fun `mower and motorway lift it further`() {
        TtsGain.updateFromAmbient(0.30f)
        assertEquals(1.15f, TtsGain.current, 0.001f)
    }

    @Test
    fun `idle reset returns to unity`() {
        TtsGain.updateFromAmbient(0.40f)
        TtsGain.reset()
        assertEquals(1f, TtsGain.current, 0.001f)
    }
}
