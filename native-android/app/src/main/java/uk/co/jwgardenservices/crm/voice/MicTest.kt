package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Test microphone": record five seconds with the source the assistant uses,
 * then play it straight back. A short beep goes first so the two failure modes
 * are distinguishable in one attempt — no beep means the phone's output is
 * misrouted; beep but no voice means nothing usable is reaching the mic.
 *
 * The clip is also left in the app folder so it can be shared for diagnosis.
 */
object MicTest {

    const val SAMPLE_RATE = 16_000
    const val SECONDS = 5

    private const val FILE_NAME = "mic-test.wav"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Records, writes [file], then returns its length in samples. */
    @Suppress("MissingPermission") // RECORD_AUDIO is requested before the assistant works
    suspend fun record(context: Context, source: Int): Int = withContext(Dispatchers.IO) {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffer = ShortArray(SAMPLE_RATE / 10)
        val record = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes, buffer.size * 2 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("The microphone could not be opened.")
        }
        val total = SAMPLE_RATE * SECONDS
        val samples = ShortArray(total)
        var written = 0
        try {
            record.startRecording()
            while (written < total) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val copy = min(read, total - written)
                System.arraycopy(buffer, 0, samples, written, copy)
                written += copy
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        writeWav(file(context), samples, written)
        written
    }

    /** Beep, then the recorded voice, through the media stream. */
    suspend fun play(context: Context, beep: Boolean = true): Unit = withContext(Dispatchers.IO) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            if (beep) {
                val tone = ShortArray(SAMPLE_RATE / 4)
                for (i in tone.indices) {
                    val envelope = min(1.0, min(i, tone.size - 1 - i) / (SAMPLE_RATE * 0.02))
                    tone[i] = (sin(2 * PI * 880 * i / SAMPLE_RATE) * 12000 * envelope).toInt().toShort()
                }
                track.write(tone, 0, tone.size)
                Thread.sleep(250)
            }
            val file = file(context)
            if (file.isFile) {
                val bytes = file.readBytes()
                // Skip the 44-byte WAV header.
                val data = ByteArray(bytes.size - 44)
                System.arraycopy(bytes, 44, data, 0, data.size)
                val samples = ShortArray(data.size / 2)
                for (i in samples.indices) {
                    val lo = data[i * 2].toInt() and 0xFF
                    val hi = data[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort()
                }
                track.write(samples, 0, samples.size)
            }
            Thread.sleep(300)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Plain 16-bit PCM WAV at 16 kHz, enough for playback or sharing. */
    private fun writeWav(target: File, samples: ShortArray, length: Int) {
        val dataBytes = length * 2
        val header = ByteArray(44)
        fun putString(offset: Int, value: String) {
            value.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        putString(0, "RIFF")
        putInt(4, 36 + dataBytes)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)
        putShort(32, 2)
        putShort(34, 16)
        putString(36, "data")
        putInt(40, dataBytes)

        target.outputStream().use { out ->
            out.write(header)
            val buffer = ByteArray(dataBytes)
            for (i in 0 until length) {
                val sample = samples[i].toInt()
                buffer[i * 2] = (sample and 0xFF).toByte()
                buffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            out.write(buffer)
        }
    }
}
