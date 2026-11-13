package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline speech output with Kokoro-82M through sherpa-onnx.
 *
 * The model, the voice bank and the espeak-ng phonemizer data are read
 * straight from `assets/kokoro-multi-lang-v1_0` (fetched by
 * `:app:downloadKokoro`). The voice profile is chosen in the assistant
 * settings (`bm_fable` by default, also `bf_emma`).
 *
 * Kokoro always synthesises at 24 kHz; [speak] plays the samples through an
 * AudioTrack and returns once playback finishes, so callers can mute the mic
 * for the whole utterance.
 */
class KokoroTts(private val context: Context) : AutoCloseable {

    private val loadLock = Mutex()
    private val speakLock = Mutex()

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    @Volatile
    private var closed = false

    /** Synthesise [text] with [speakerId] and play it, suspending until the audio has finished. */
    suspend fun speak(text: String, speakerId: Int = KokoroVoice.BmFable.speakerId) {
        val clean = text.trim()
        if (closed || clean.isEmpty()) return
        speakLock.withLock {
            if (closed) return@withLock
            val engine = ensureEngine() ?: return@withLock
            val audio = withContext(Dispatchers.Default) {
                engine.generate(clean, speakerId, SPEED)
            }
            if (!closed) play(audio)
        }
    }

    /** Cut playback short, e.g. when a phone call takes the audio focus. */
    fun stopPlayback() {
        runCatching { track?.pause() }
    }

    private suspend fun ensureEngine(): OfflineTts? {
        tts?.let { return it }
        return loadLock.withLock {
            tts ?: withContext(Dispatchers.IO) {
                if (!assetsReady()) {
                    Log.w(TAG, "Kokoro assets are missing under $ASSET_DIR")
                    return@withContext null
                }
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = "$ASSET_DIR/model.int8.onnx",
                            voices = "$ASSET_DIR/voices.bin",
                            tokens = "$ASSET_DIR/tokens.txt",
                            dataDir = "$ASSET_DIR/espeak-ng-data",
                            lexicon = "$ASSET_DIR/lexicon-us-en.txt",
                            lengthScale = 1.0f,
                        ),
                        numThreads = 2,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                )
                val created = OfflineTts(assetManager = context.assets, config = config)
                Log.i(TAG, "Kokoro ready: ${created.sampleRate()} Hz, ${created.numSpeakers()} voices")
                if (created.sampleRate() != SAMPLE_RATE) {
                    Log.w(TAG, "Expected $SAMPLE_RATE Hz but the model reports ${created.sampleRate()} Hz")
                }
                tts = created
                created
            }
        }
    }

    private suspend fun play(audio: GeneratedAudio) {
        if (audio.samples.isEmpty()) return
        val pcm = ShortArray(audio.samples.size) { index ->
            (audio.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()

        track = player
        try {
            if (player.write(pcm, 0, pcm.size) <= 0) return
            player.play()
            // Static playback has no blocking write; wait out the clip and a
            // small tail so the last syllable is not cut off.
            delay(pcm.size * 1000L / audio.sampleRate + 100)
        } finally {
            runCatching { player.stop() }
            player.release()
            if (track === player) track = null
        }
    }

    private fun assetsReady(): Boolean = listOf(
        "$ASSET_DIR/model.int8.onnx",
        "$ASSET_DIR/voices.bin",
        "$ASSET_DIR/tokens.txt",
        "$ASSET_DIR/lexicon-us-en.txt",
    ).all { path -> runCatching { context.assets.open(path).close() }.isSuccess }

    override fun close() {
        closed = true
        runCatching { track?.stop() }
        if (speakLock.tryLock()) {
            try {
                runCatching { tts?.release() }
                tts = null
            } finally {
                speakLock.unlock()
            }
        } else {
            Log.w(TAG, "Kokoro still speaking at close; leaving it to the process")
        }
    }

    private companion object {
        const val TAG = "KokoroTts"
        const val ASSET_DIR = "kokoro-multi-lang-v1_0"
        const val SAMPLE_RATE = 24_000

        const val SPEED = 1.0f
    }
}
