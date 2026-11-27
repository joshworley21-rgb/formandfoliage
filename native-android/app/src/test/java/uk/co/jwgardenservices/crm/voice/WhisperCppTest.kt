package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WhisperCppTest {
    @Test
    fun `the encoder window fits the clip with a floor and a ceiling`() {
        assertEquals(500, WhisperCpp.audioContextFor(1f))
        assertEquals(550, WhisperCpp.audioContextFor(8f))
        assertEquals(1500, WhisperCpp.audioContextFor(30f))
        assertEquals(1500, WhisperCpp.audioContextFor(120f))
    }

    @Test
    fun `the final pass choice round trips and defaults to Moonshine`() {
        assertEquals(FinalPass.WhisperCpp, FinalPass.fromId("whisper_cpp"))
        assertEquals(FinalPass.Whisper, FinalPass.fromId("whisper"))
        assertEquals(FinalPass.Moonshine, FinalPass.fromId(null))
        assertEquals(FinalPass.Moonshine, FinalPass.fromId("nonsense"))
    }

    @Test
    fun `without the native library whisper cpp is simply unavailable`() {
        // Unit tests run on the build machine, where libwhisper_jni.so does not exist.
        assertFalse(WhisperCpp.available)
        assertEquals(null, WhisperCpp.open(java.io.File("missing.bin"), null))
    }
}
