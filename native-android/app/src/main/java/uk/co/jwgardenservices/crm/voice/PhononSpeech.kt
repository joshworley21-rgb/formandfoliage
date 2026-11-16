package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.co.jwgardenservices.crm.core.CrashLog
import java.io.File

/**
 * The Phonon 31m voice: a 31M-parameter stream made by libptts_ffi, played as
 * 24 kHz float PCM while it is still being generated.
 *
 * One model handle is kept for the life of the process. Calls block on the
 * native side, so they run on Dispatchers.IO; [stop] flips a flag the audio
 * callback sees, which also stops native generation.
 */
class PhononSpeech(private val context: Context) : AutoCloseable {

    private val loadLock = Mutex()
    private val speakLock = Mutex()

    private var engine: PhononTTS? = null
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var closed = false

    suspend fun warmUp() {
        if (closed) return
        withContext(Dispatchers.IO) { ensureEngine() }
    }

    /**
     * Speak [text] and return once its audio has played. False means no audio
     * was produced (not downloaded, engine failed to load, or already closed),
     * so the caller can fall back to the local voice.
     */
    suspend fun speak(text: String): Boolean {
        val clean = text.trim()
        if (closed || clean.isEmpty()) return false
        return speakLock.withLock {
            if (closed) return@withLock false
            val engine = ensureEngine() ?: return@withLock false
            stopped = false
            val player = withContext(Dispatchers.IO) { buildPlayer() } ?: return@withLock false
            track = player
            try {
                withContext(Dispatchers.IO) {
                    player.play()
                    engine.speak(clean) { pcm ->
                        if (stopped || closed) {
                            false
                        } else {
                            player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            true
                        }
                    }
                }
                true
            } finally {
                runCatching { player.stop() }
                runCatching { player.release() }
                if (track === player) track = null
            }
        }
    }

    fun stop() {
        stopped = true
        runCatching {
            track?.pause()
            track?.flush()
        }
    }

    override fun close() {
        closed = true
        stop()
        if (speakLock.tryLock()) {
            try {
                runCatching { engine?.close() }
                engine = null
            } finally {
                speakLock.unlock()
            }
        } else {
            Log.w(TAG, "Phonon still speaking at close; leaving it to the process")
        }
        runCatching { track?.release() }
        track = null
    }

    private suspend fun ensureEngine(): PhononTTS? {
        engine?.let { return it }
        return loadLock.withLock {
            engine ?: withContext(Dispatchers.IO) {
                if (!PhononDownloader.isInstalled(context)) return@withContext null
                val model: File = PhononDownloader.dir(context)
                // Set before the first ptts_new: the default is one Rayon thread
                // per core, which is usually more than one audio stream can use.
                runCatching { Os.setenv("RAYON_NUM_THREADS", "2", true) }
                val started = System.currentTimeMillis()
                val created = runCatching {
                    PhononTTS(model.absolutePath, "en")
                }.getOrElse { error ->
                    CrashLog.note(context, TAG, "load failed: ${error.message}")
                    Log.e(TAG, "Phonon load failed", error)
                    return@withContext null
                }
                created.voices.firstOrNull()?.let { voice ->
                    runCatching { created.setVoice(voice) }
                }
                CrashLog.note(
                    context,
                    TAG,
                    "Phonon ready in ${System.currentTimeMillis() - started} ms " +
                        "(${created.voices.size} voices)",
                )
                engine = created
                created
            }
        }
    }

    private fun buildPlayer(): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bytes = maxOf(minBytes, SAMPLE_RATE * 4 * 2)
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        if (player.state != AudioTrack.STATE_INITIALIZED) {
            player.release()
            CrashLog.note(context, TAG, "AudioTrack could not be created")
            return null
        }
        return player
    }

    private companion object {
        const val TAG = "Phonon"
        const val SAMPLE_RATE = 24_000
    }
}
