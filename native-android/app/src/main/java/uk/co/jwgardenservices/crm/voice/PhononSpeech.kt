package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    /** True when the native engine is already loaded. */
    val isReady: Boolean
        get() = engine != null

    suspend fun warmUp() {
        if (closed) return
        withContext(Dispatchers.IO) { ensureEngine() }
    }

    /**
     * Speak [text] and return once its audio has played. False means no audio
     * was produced (not downloaded, engine failed to load, or already closed),
     * so the caller can fall back to the local voice.
     */
    suspend fun speak(text: String, speed: Float = 1f): Boolean {
        // Phonon-only shaping: em-dash breaths and money said like a
        // tradesperson says it. The pace marker and caller speed decide the
        // playback rate.
        val (pace, stripped) = TtsProsody.extract(text)
        val clean = TtsProsody.forPhonon(stripped)
        if (closed || clean.isEmpty()) return false
        return speakLock.withLock {
            if (closed) return@withLock false
            val engine = ensureEngine() ?: return@withLock false
            stopped = false
            val player = withContext(Dispatchers.IO) { buildPlayer() } ?: return@withLock false
            val rate = (pace * speed).coerceIn(0.5f, 1.6f)
            if (rate != 1f) {
                // Zero-compute pacing: the sink plays the same samples at a
                // different rate, which lifts or drops the pitch a shade —
                // brisker for action confirmations, calmer for totals.
                runCatching {
                    player.playbackRate = (SAMPLE_RATE * rate).toInt()
                        .coerceIn(SAMPLE_RATE / 2, SAMPLE_RATE * 2)
                }
            }
            track = player
            val samples = AtomicInteger()
            val playStartedAt = SystemClock.elapsedRealtime()
            try {
                withContext(Dispatchers.IO) {
                    player.play()
                    engine.speak(clean) { pcm ->
                        if (stopped || closed) {
                            false
                        } else {
                            samples.addAndGet(pcm.size)
                            // Environmental gain: boost over a lawnmower,
                            // leave a quiet garden alone.
                            val gain = TtsGain.effective
                            if (gain != 1f) {
                                val scaled = FloatArray(pcm.size) {
                                    (pcm[it] * gain).coerceIn(-1f, 1f)
                                }
                                player.write(scaled, 0, scaled.size, AudioTrack.WRITE_BLOCKING)
                            } else {
                                player.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            }
                            true
                        }
                    }
                }
                if (samples.get() == 0) {
                    CrashLog.note(context, TAG, "no audio for: ${clean.take(40)}")
                }
                // engine.speak() returns when generation finishes, not when the
                // AudioTrack has played. The streaming buffer can hold around
                // two seconds, so stopping and releasing here would cut off a
                // short greeting or the end of a long reply.
                if (!stopped && !closed) {
                    val total = samples.get()
                    // Head position is the accurate way to wait, but some
                    // devices report it lazily. Cap the wait at the clip's
                    // duration plus a small tail so a stuck counter cannot
                    // hold the line (and the greeting) for seconds.
                    val audioMs = total * 1000L / SAMPLE_RATE
                    val deadline = playStartedAt + audioMs + 1_500
                    while (!stopped && !closed &&
                        player.playbackHeadPosition < total &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        delay(20)
                    }
                    delay(40)
                }
                // Some Phonon/voice combinations finish without emitting a
                // single sample. Reporting success here made the caller skip
                // the Kokoro fallback, so the line was silent.
                CrashLog.note(
                    context,
                    TAG,
                    "played samples=${samples.get()} head=${player.playbackHeadPosition} " +
                        "gain=${"%.2f".format(TtsGain.effective)}",
                )
                samples.get() > 0
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
            track?.stop()
            track?.setVolume(1f)
        }
    }

    /** Dip the voice while a possible interruption is confirmed. */
    fun duck(volume: Float = 0.32f) {
        runCatching { track?.setVolume(volume) }
    }

    /** Speaker bleed, not a person: bring the reply back up. */
    fun restore() {
        runCatching { track?.setVolume(1f) }
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
                val voices = created.voices
                val selected = runCatching {
                    AssistantPreferences.phononVoiceFlow(context).first()
                }.getOrDefault(PhononVoice.Freya.name)
                val chosen = voices.firstOrNull {
                    it.equals(selected, ignoreCase = true) ||
                        it.substringBefore('.').equals(selected, ignoreCase = true)
                }
                if (chosen != null) {
                    runCatching { created.setVoice(chosen) }
                        .onFailure { CrashLog.note(context, TAG, "voice $chosen failed: ${it.message}") }
                } else {
                    CrashLog.note(
                        context,
                        TAG,
                        "voice $selected is not downloaded; using the checkpoint default",
                    )
                }
                CrashLog.note(
                    context,
                    TAG,
                    "Phonon ready in ${System.currentTimeMillis() - started} ms " +
                        "(${voices.size} voices: ${voices.joinToString()})" +
                        (if (chosen != null) " voice=$chosen" else ""),
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
