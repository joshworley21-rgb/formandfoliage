package uk.co.jwgardenservices.crm.voice

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallAudioTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun int(bytes: ByteArray, at: Int) =
        (0..3).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }

    private fun short(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()

    @Test
    fun `a wav file has a correct header and little endian samples`() {
        val wav = WavFile.encode(floatArrayOf(0f, 1f, -1f, 2f, 0.5f), 16_000)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(44 + 10, wav.size)
        assertEquals(36 + 10, int(wav, 4))
        assertEquals(16, int(wav, 16))
        assertEquals(1, short(wav, 20))
        assertEquals(1, short(wav, 22))
        assertEquals(16_000, int(wav, 24))
        assertEquals(32_000, int(wav, 28))
        assertEquals(2, short(wav, 32))
        assertEquals(16, short(wav, 34))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(10, int(wav, 40))
        assertEquals(0, short(wav, 44))
        assertEquals(32_767, short(wav, 46))
        assertEquals(-32_767, short(wav, 48))
        // Out of range is clamped, not wrapped.
        assertEquals(32_767, short(wav, 50))
        assertEquals(16_384, short(wav, 52))
    }

    @Test
    fun `an empty recording is still a valid file`() {
        val wav = WavFile.encode(FloatArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals(0, int(wav, 40))
    }

    @Test
    fun `file names sort in time order`() {
        val a = CallAudio.stem(1_700_000_000_000L)
        val b = CallAudio.stem(1_700_000_005_000L)
        assertTrue(a < b)
        assertEquals(a.length, b.length)
    }

    @Test
    fun `only the newest recordings are kept, with their notes`() {
        val dir = folder.newFolder("audio")
        listOf("20261126-090000-000", "20261126-090100-000", "20261126-090200-000").forEach {
            File(dir, "$it.wav").writeText("w")
            File(dir, "$it.txt").writeText("n")
        }
        CallAudio.prune(dir, keep = 2)
        assertFalse(File(dir, "20261126-090000-000.wav").exists())
        assertFalse(File(dir, "20261126-090000-000.txt").exists())
        assertTrue(File(dir, "20261126-090100-000.wav").exists())
        assertTrue(File(dir, "20261126-090200-000.txt").exists())
    }

    @Test
    fun `a zip holds every file under its own name`() {
        val a = folder.newFile("a.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = folder.newFile("a.txt").apply { writeText("hello") }
        val target = File(folder.root, "out.zip")
        CallAudio.zip(listOf(a, b), target)
        ZipFile(target).use { zip ->
            assertEquals(setOf("a.wav", "a.txt"), zip.entries().asSequence().map { it.name }.toSet())
            assertArrayEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("a.wav")).readBytes())
            assertEquals("hello", zip.getInputStream(zip.getEntry("a.txt")).readBytes().decodeToString())
        }
    }
}
