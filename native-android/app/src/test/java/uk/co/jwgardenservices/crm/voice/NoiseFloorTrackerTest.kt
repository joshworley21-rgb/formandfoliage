package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFloorTrackerTest {
    private fun run(frames: Int, rms: Float, vad: Boolean, inTurn: Boolean, start: Float): Float {
        var floor = start
        repeat(frames) { floor = NoiseFloorTracker.next(floor, rms, vad, inTurn) }
        return floor
    }

    @Test
    fun `steady engine noise settles into the floor within seconds`() {
        // 0.03 RMS with no voice: the old 0.1 percent creep took about 30 s to
        // pass speechFloor (floor * 2.2); this must get there in a few seconds.
        val floor = run(frames = 50, rms = 0.03f, vad = false, inTurn = false, start = 0.008f)
        assertTrue("floor was $floor", floor * 2.2f > 0.03f)
    }

    @Test
    fun `the floor falls fast when the noise stops`() {
        val floor = run(frames = 10, rms = 0.002f, vad = false, inTurn = false, start = 0.05f)
        assertTrue("floor was $floor", floor < 0.012f)
    }

    @Test
    fun `speech inside a turn barely moves the floor`() {
        val floor = run(frames = 30, rms = 0.12f, vad = true, inTurn = true, start = 0.01f)
        assertTrue("floor was $floor", floor < 0.015f)
    }

    @Test
    fun `a pause between words inside a turn does not raise the floor quickly`() {
        val floor = run(frames = 10, rms = 0.05f, vad = false, inTurn = true, start = 0.01f)
        assertTrue("floor was $floor", floor < 0.012f)
    }

    @Test
    fun `voice before a turn opens does not ratchet the floor`() {
        val floor = run(frames = 10, rms = 0.1f, vad = true, inTurn = false, start = 0.01f)
        assertTrue("floor was $floor", floor < 0.012f)
    }

    @Test
    fun `the floor is capped so loud noise cannot swallow speech`() {
        val floor = run(frames = 2_000, rms = 0.5f, vad = false, inTurn = false, start = 0.01f)
        assertEquals(NoiseFloorTracker.MAX_FLOOR, floor, 1e-6f)
    }

    @Test
    fun `a quiet room stays at the initial floor`() {
        val floor = run(frames = 100, rms = 0.004f, vad = false, inTurn = false, start = NoiseFloorTracker.INITIAL)
        assertTrue("floor was $floor", floor <= NoiseFloorTracker.INITIAL)
    }
}
