package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is pipecat's frames.proto, verified field-for-field against
 * the server's ProtobufFrameSerializer. These tests keep the hand-rolled
 * encoder honest: a wrong field number here would produce a silent, dead
 * call with no error on either side.
 */
class PipecatFramesTest {
    @Test
    fun `audio frame encodes the audio field of Frame`() {
        val pcm = ByteArray(640) // 20 ms of 16 kHz mono 16-bit
        val bytes = PipecatFrames.encodeAudio(pcm)
        // Outer: Frame.audio = field 2, wire type 2.
        assertEquals(0x12, bytes[0].toInt())
        // Round trip through the decoder proves the whole envelope.
        val decoded = PipecatFrames.decode(bytes) as PipecatFrames.Frame.Audio
        assertTrue(decoded.audio.contentEquals(pcm))
    }

    @Test
    fun `audio frame round trips sample rate and channels`() {
        val pcm = ByteArray(320) { i -> (i % 97).toByte() }
        val decoded = PipecatFrames.decode(PipecatFrames.encodeAudio(pcm, 16_000, 1))
            as PipecatFrames.Frame.Audio
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(1, decoded.numChannels)
        assertTrue(decoded.audio.contentEquals(pcm))
    }

    @Test
    fun `text frames decode`() {
        val inner = PipecatFrames.byteFrame(0x1A, "Hello from the bot".toByteArray(Charsets.UTF_8))
        val outer = PipecatFrames.messageFrame(0x0A, inner)
        val decoded = PipecatFrames.decode(outer) as PipecatFrames.Frame.Text
        assertEquals("Hello from the bot", decoded.text)
    }

    @Test
    fun `transcription frames decode with user and timestamp`() {
        val inner = PipecatFrames.byteFrame(0x1A, "mark louisa done".toByteArray(Charsets.UTF_8)) +
            PipecatFrames.byteFrame(0x22, "user-1".toByteArray(Charsets.UTF_8)) +
            PipecatFrames.byteFrame(0x2A, "2026-11-12".toByteArray(Charsets.UTF_8))
        val outer = PipecatFrames.messageFrame(0x1A, inner)
        val decoded = PipecatFrames.decode(outer) as PipecatFrames.Frame.Transcription
        assertEquals("mark louisa done", decoded.text)
        assertEquals("user-1", decoded.userId)
        assertEquals("2026-11-12", decoded.timestamp)
    }

    @Test
    fun `interruption frames decode`() {
        val decoded = PipecatFrames.decode(byteArrayOf(0x2A, 0x00))
        assertTrue(decoded is PipecatFrames.Frame.Interruption)
    }
}
