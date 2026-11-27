package uk.co.jwgardenservices.crm.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetAudioTest {
    private val frame = 1_600

    private fun tone(rms: Float, from: Int = 0): FloatArray {
        val amplitude = rms * sqrt(2f)
        return FloatArray(frame) { i -> amplitude * sin(2.0 * PI * 220.0 * (from + i) / 16_000.0).toFloat() }
    }

    private fun rms(samples: FloatArray): Float = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()

    private fun settle(audio: HeadsetAudio, level: Float, frames: Int): FloatArray {
        var out = FloatArray(0)
        repeat(frames) { out = audio.process(tone(level, it * frame)) }
        return out
    }

    @Test
    fun `a very quiet headset is brought up to a level the recogniser can use`() {
        // From a real call: level 0.003 against 0.048 on the phone mic. The old 2.6x
        // limit left this near 0.008.
        val out = settle(HeadsetAudio(), level = 0.003f, frames = 8)
        val level = rms(out)
        assertTrue("level was $level", level in 0.03f..0.2f)
    }

    @Test
    fun `a normal headset keeps about the same boost as before`() {
        val out = settle(HeadsetAudio(), level = 0.05f, frames = 8)
        val level = rms(out)
        assertTrue("level was $level", level in 0.09f..0.16f)
    }

    @Test
    fun `loud audio is never boosted past full scale`() {
        val audio = HeadsetAudio()
        settle(audio, level = 0.003f, frames = 8)
        val burst = audio.process(tone(0.35f))
        assertTrue(burst.all { abs(it) <= 1.0f })
        val later = settle(audio, level = 0.35f, frames = 3)
        assertTrue("level was ${rms(later)}", rms(later) < 0.7f)
    }

    @Test
    fun `a pause after quiet speech is not pumped up into speech level`() {
        val audio = HeadsetAudio()
        settle(audio, level = 0.003f, frames = 8)
        val pause = settle(audio, level = 0.0008f, frames = 10)
        assertTrue("pause level was ${rms(pause)}", rms(pause) < 0.01f)
    }

    @Test
    fun `the first word after a pause is not left at a low gain`() {
        val audio = HeadsetAudio()
        settle(audio, level = 0.003f, frames = 8)
        settle(audio, level = 0.0008f, frames = 10)
        val first = audio.process(tone(0.003f))
        assertTrue("first frame level was ${rms(first)}", rms(first) > 0.015f)
    }

    @Test
    fun `reset forgets what it learned`() {
        val audio = HeadsetAudio()
        settle(audio, level = 0.003f, frames = 8)
        audio.reset()
        val first = rms(audio.process(tone(0.003f)))
        val fresh = rms(HeadsetAudio().process(tone(0.003f)))
        assertTrue(abs(first - fresh) < 1e-6f)
    }

    @Test
    fun `empty input is returned as it is`() {
        assertTrue(HeadsetAudio().process(FloatArray(0)).isEmpty())
    }
}
