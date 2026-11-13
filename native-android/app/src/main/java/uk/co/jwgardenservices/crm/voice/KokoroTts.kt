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
import uk.co.jwgardenservices.crm.core.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

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
        CrashLog.note(context, TAG, "speak: ${clean.take(80)} (sid=$speakerId)")
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
                val dir = runCatching { modelFiles() }.getOrElse { error ->
                    CrashLog.note(context, TAG, "asset copy failed: ${error.message}")
                    Log.w(TAG, "Kokoro assets are missing under $ASSET_DIR", error)
                    return@withContext null
                }
                // Real file paths, not the AssetManager: reading the 109 MB
                // model through AAsset_getBuffer aborts the process on some
                // devices. Copying once with ordinary streams is reliable.
                val highQuality = AssistantPreferences.highQualityFlow(context).first() &&
                    KokoroHqDownloader.isInstalled(context)
                val modelPath = if (highQuality) {
                    KokoroHqDownloader.modelFile(context).absolutePath
                } else {
                    File(dir, "model.int8.onnx").absolutePath
                }
                CrashLog.note(context, TAG, "model=${if (highQuality) "fp32" else "int8"}")
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = modelPath,
                            voices = File(dir, "voices.bin").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                            // The GB lexicon plus en-gb made this build
                            // produce empty audio, so keep the proven US
                            // lexicon and let the voice bank decide the accent.
                            lexicon = File(dir, "lexicon-us-en.txt").absolutePath,
                            lengthScale = 1.0f,
                        ),
                        numThreads = 4,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                )
                CrashLog.note(context, TAG, "creating OfflineTts from ${dir.absolutePath}")
                val created = try {
                    OfflineTts(assetManager = null, config = config)
                } catch (error: Exception) {
                    CrashLog.note(context, TAG, "OfflineTts failed: ${error.message}")
                    Log.e(TAG, "OfflineTts creation failed", error)
                    return@withContext null
                }
                CrashLog.note(context, TAG, "OfflineTts ready: ${created.sampleRate()} Hz")
                Log.i(TAG, "Kokoro ready: ${created.sampleRate()} Hz, ${created.numSpeakers()} voices")
                if (created.sampleRate() != SAMPLE_RATE) {
                    Log.w(TAG, "Expected $SAMPLE_RATE Hz but the model reports ${created.sampleRate()} Hz")
                }
                tts = created
                created
            }
        }
    }

    /**
     * Copy the Kokoro assets into internal storage once and return that
     * directory. A marker file means a previous copy finished.
     */
    private fun modelFiles(): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val marker = File(dir, ".complete")
        val required = listOf(
            "model.int8.onnx",
            "voices.bin",
            "tokens.txt",
            "lexicon-gb-en.txt",
            "espeak-ng-data/phontab",
        )
        if (marker.isFile && marker.readText().trim() == COPY_VERSION &&
            required.all { File(dir, it).isFile }
        ) {
            return dir
        }

        dir.deleteRecursively()
        dir.mkdirs()
        val started = System.currentTimeMillis()
        copyAssetTree(ASSET_DIR, dir)
        File(dir, ".complete").writeText(COPY_VERSION)
        CrashLog.note(
            context,
            TAG,
            "copied model files in ${System.currentTimeMillis() - started} ms",
        )
        return dir
    }

    /**
     * Kokoro pads each clip with silence; trimming it removes the dead air
     * between the filler and the reply.
     */
    private fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.isEmpty()) return samples
        val threshold = 0.006f
        var start = 0
        while (start < samples.size && abs(samples[start]) < threshold) start++
        if (start == samples.size) return FloatArray(0)
        var end = samples.size - 1
        while (end > start && abs(samples[end]) < threshold) end--
        val pad = (sampleRate * 0.04f).toInt()
        val from = (start - pad).coerceAtLeast(0)
        val to = (end + pad).coerceAtMost(samples.size - 1)
        return samples.copyOfRange(from, to + 1)
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } else {
            target.mkdirs()
            children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
        }
    }

    private suspend fun play(audio: GeneratedAudio) {
        val samples = trimSilence(audio.samples, audio.sampleRate)
        if (samples.isEmpty()) {
            CrashLog.note(context, TAG, "empty audio returned by Kokoro")
            return
        }
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
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
        CrashLog.note(context, TAG, "playing ${pcm.size} samples @ ${audio.sampleRate} Hz")
        try {
            if (player.write(pcm, 0, pcm.size) <= 0) return
            player.play()
            // Static playback has no blocking write; wait out the clip and a
            // small tail so the last syllable is not cut off.
            delay(pcm.size * 1000L / audio.sampleRate + 60)
        } finally {
            runCatching { player.stop() }
            player.release()
            if (track === player) track = null
            CrashLog.note(context, TAG, "playback finished")
        }
    }

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

        /** Bump whenever the set of copied files changes. */
        const val COPY_VERSION = "2"

        const val SPEED = 1.0f
    }
}
