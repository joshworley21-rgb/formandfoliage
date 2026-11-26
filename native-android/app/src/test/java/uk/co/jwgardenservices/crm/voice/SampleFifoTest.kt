package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleFifoTest {
    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }

    @Test
    fun `always returns exactly the requested length`() {
        val fifo = SampleFifo(prime = 1_024, maxBuffered = 8_192)
        // A frame-based processor emits 1,536 or 1,792 samples for 1,600 in.
        listOf(1_536, 1_792, 1_536, 1_536, 1_792, 0, 1_600).forEach { produced ->
            assertEquals(1_600, fifo.exchange(ramp(0, produced), 1_600).size)
        }
    }

    @Test
    fun `audio keeps its order and is only delayed by the priming`() {
        val fifo = SampleFifo(prime = 4, maxBuffered = 1_000)
        val heard = mutableListOf<Float>()
        var next = 1
        // The processor returns slightly fewer samples than it is given.
        repeat(6) {
            val given = 10
            val produced = ramp(next, given - (it % 2))
            next += produced.size
            heard += fifo.exchange(produced, given).toList()
        }
        val expected = List(4) { 0f } + ramp(1, next - 1).toList()
        assertEquals(expected.take(heard.size), heard)
    }

    @Test
    fun `running dry pads the front with silence and never reorders`() {
        val fifo = SampleFifo(prime = 0, maxBuffered = 100)
        val out = fifo.exchange(floatArrayOf(7f, 8f), want = 5)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 7f, 8f), out, 0f)
    }

    @Test
    fun `backing up drops the oldest audio`() {
        val fifo = SampleFifo(prime = 0, maxBuffered = 4)
        val out = fifo.exchange(ramp(1, 10), want = 4)
        assertArrayEquals(floatArrayOf(7f, 8f, 9f, 10f), out, 0f)
    }

    @Test
    fun `reset goes back to the primed state`() {
        val fifo = SampleFifo(prime = 3, maxBuffered = 100)
        fifo.exchange(ramp(1, 20), want = 5)
        fifo.reset()
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 9f, 9f), fifo.exchange(floatArrayOf(9f, 9f), want = 5), 0f)
    }
}
