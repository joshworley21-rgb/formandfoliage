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
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import uk.co.jwgardenservices.crm.core.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
/** Cached hello, used only when the call briefing cannot be built. */
internal const val PICKUP_TEXT = "Hello, how can I help?"

class KokoroTts(private val context: Context) : AutoCloseable {

    private val loadLock = Mutex()
    private val speakLock = Mutex()

    private var tts: OfflineTts? = null
    private var track: AudioTrack? = null

    /** Pre-synthesised "thinker" clips, rotated so a slow turn is not silent. */
    private val fillers = java.util.concurrent.ConcurrentHashMap<Int, GeneratedAudio>()
    private var fillerIndex = 0

    /** Cached "Hello?" for the start of a call, so pickup is instant. */
    private var pickup: GeneratedAudio? = null

    /** Phonon 31m, when downloaded and selected; otherwise this class's own engines. */
    private var phonon: PhononSpeech? = null

    private enum class EngineKind { Kokoro, Piper, Supertonic }

    @Volatile
    private var engineKind = EngineKind.Kokoro

    @Volatile
    private var closed = false

    /** Set by [stopPlayback] so playback does not hold the mic for the rest of the clip. */
    @Volatile
    private var stopRequested = false

    /** Synthesise [text] with [speakerId] at [speed] and play it, suspending until the audio has finished. */
    suspend fun speak(
        text: String,
        speakerId: Int = KokoroVoice.BmFable.speakerId,
        speed: Float = AssistantPreferences.DEFAULT_SPEED,
    ) {
        val (pace, stripped) = TtsProsody.extract(text)
        val clean = TtsProsody.smooth(stripped)
        if (closed || clean.isEmpty()) return
        if (phononActive()) {
            val spoken = runCatching { phononSpeech().speak(clean) }.getOrDefault(false)
            if (spoken) return
            CrashLog.note(context, TAG, "Phonon failed; falling back to the local voice")
        }
        CrashLog.note(context, TAG, "speak: ${clean.take(80)} (sid=$speakerId, speed=$speed)")
        speakLock.withLock {
            if (closed) return@withLock
            stopRequested = false
            val engine = ensureEngine() ?: return@withLock
            // Piper voices are single-speaker; Kokoro has 53. Sending Kokoro's
            // speaker id to a VITS model can index past its table.
            val sid = speakerFor(engine, speakerId)
            val audio = withContext(Dispatchers.Default) {
                engine.generate(
                    clean,
                    sid,
                    (speed * pace).coerceIn(AssistantPreferences.MIN_SPEED, AssistantPreferences.MAX_SPEED),
                )
            }
            if (!closed) play(audio)
        }
    }

    /** True when the voice engine is already loaded, so a call can resume fast. */
    fun isWarm(): Boolean = tts != null || phonon?.isReady == true

    /**
     * Unload the voice model while the app is backgrounded with no call.
     * Unlike [close] this can be reopened by the next utterance.
     */
    suspend fun releaseIdle() {
        if (closed) return
        runCatching { phonon?.close() }
        phonon = null
        speakLock.withLock {
            loadLock.withLock {
                runCatching { tts?.release() }
                tts = null
                fillers.clear()
                pickup = null
            }
        }
    }

    /** Load the model in the background so the first reply is not delayed by it. */
    suspend fun warmUp() {
        if (closed) return
        if (phononActive()) {
            runCatching { phononSpeech().warmUp() }
            return
        }
        ensureEngine()
    }

    private suspend fun phononActive(): Boolean =
        AssistantPreferences.phononFlow(context).first() && PhononDownloader.isInstalled(context)

    private fun phononSpeech(): PhononSpeech =
        phonon ?: PhononSpeech(context).also { phonon = it }

    /**
     * Play "Let me check." while a cloud turn is in flight. The clip is
     * generated once and cached for the current voice; this is the difference
     * between a call that feels alive and a dead line.
     */
    suspend fun speakFiller(
        speakerId: Int = KokoroVoice.BmFable.speakerId,
        speed: Float = AssistantPreferences.DEFAULT_SPEED,
    ) {
        if (closed) return
        if (phononActive()) {
            runCatching { phononSpeech().speak(FILLER_TEXTS[0]) }
            return
        }
        speakLock.withLock {
            if (closed) return@withLock
            val engine = ensureEngine() ?: return@withLock
            val sid = speakerFor(engine, speakerId)
            val index = fillerIndex % FILLER_TEXTS.size
            fillerIndex++
            val audio = fillers[index] ?: withContext(Dispatchers.Default) {
                engine.generate(FILLER_TEXTS[index], sid, speed)
            }.also { fillers[index] = it }
            stopRequested = false
            play(audio)
        }
    }

    /**
     * Generate the filler ahead of time, once the voice is warm. Called when a
     * call starts so the first slow cloud turn is covered too.
     */
    suspend fun prepareFiller(
        speakerId: Int = KokoroVoice.BmFable.speakerId,
        speed: Float = AssistantPreferences.DEFAULT_SPEED,
    ) {
        if (closed) return
        if (phononActive()) {
            runCatching { phononSpeech().warmUp() }
            return
        }
        speakLock.withLock {
            if (closed) return@withLock
            val engine = ensureEngine() ?: return@withLock
            val sid = speakerFor(engine, speakerId)
            // The first two clips are prepared eagerly; the rest are
            // generated on demand so a fast first command never waits for all
            // of them. Two gives the caller variety without much start-up cost.
            for (index in 0..1) {
                if (fillers[index] == null) {
                    fillers[index] = withContext(Dispatchers.Default) {
                        engine.generate(FILLER_TEXTS[index], sid, speed)
                    }
                }
            }
            // The pickup clip is generated in the same pass, so answering a
            // call never waits for synthesis.
            if (pickup == null) {
                pickup = withContext(Dispatchers.Default) {
                    engine.generate(PICKUP_TEXT, sid, speed)
                }
            }
        }
    }

    /** Answer the call with the greeting, using the cached clip when warm. */
    suspend fun speakPickup(
        speakerId: Int = KokoroVoice.BmFable.speakerId,
        speed: Float = AssistantPreferences.DEFAULT_SPEED,
    ): Boolean {
        if (closed) return false
        if (phononActive()) {
            val spoken = runCatching { phononSpeech().speak(PICKUP_TEXT) }.getOrDefault(false)
            if (spoken) return true
            CrashLog.note(context, TAG, "Phonon pickup failed; falling back to the local voice")
        }
        return speakLock.withLock {
            if (closed) return@withLock false
            val engine = ensureEngine() ?: return@withLock false
            val sid = speakerFor(engine, speakerId)
            val audio = pickup ?: withContext(Dispatchers.Default) {
                engine.generate(PICKUP_TEXT, sid, speed)
            }.also { pickup = it }
            stopRequested = false
            play(audio)
        }
    }

    /**
     * Prepare the rest of the filler pool after the line has connected, so a
     * slow cloud turn always has a warm clip without delaying the greeting.
     */
    suspend fun prepareRemainingFillers(
        speakerId: Int = KokoroVoice.BmFable.speakerId,
        speed: Float = AssistantPreferences.DEFAULT_SPEED,
    ) {
        if (closed) return
        if (phononActive()) return
        speakLock.withLock {
            if (closed) return@withLock
            val engine = ensureEngine() ?: return@withLock
            val sid = speakerFor(engine, speakerId)
            for (index in FILLER_TEXTS.indices) {
                if (fillers[index] != null) continue
                fillers[index] = withContext(Dispatchers.Default) {
                    engine.generate(FILLER_TEXTS[index], sid, speed)
                }
            }
        }
    }

    /** Drop the cached clips when the voice or speed changes. */
    fun clearFiller() {
        fillers.clear()
        pickup = null
    }

    /**
     * Speak a reply sentence by sentence: the first sentence starts playing as
     * soon as it is synthesised, while the rest are generated ahead. For a
     * multi-sentence answer this removes most of the wait before the first
     * word, which is the difference between "thinking" and "replying".
     */
    suspend fun speakStreaming(
        text: String,
        speakerId: Int = KokoroVoice.BmFable.speakerId,
        speed: Float = AssistantPreferences.DEFAULT_SPEED,
    ) {
        val (pace, stripped) = TtsProsody.extract(text)
        val clean = TtsProsody.smooth(stripped)
        if (closed || clean.isEmpty()) return
        if (phononActive()) {
            speak(clean, speakerId, speed)
            return
        }
        val chunks = speechChunks(clean)
        if (chunks.size <= 1) {
            speak(clean, speakerId, speed)
            return
        }
        CrashLog.note(context, TAG, "speakStreaming: ${chunks.size} sentences")
        speakLock.withLock {
            if (closed) return@withLock
            stopRequested = false
            val engine = ensureEngine() ?: return@withLock
            val sid = speakerFor(engine, speakerId)
            coroutineScope {
                val audio = Channel<GeneratedAudio>(2)
                val producer = launch(Dispatchers.Default) {
                    try {
                        for (chunk in chunks) {
                            if (stopRequested || closed) break
                            audio.send(engine.generate(chunk, sid, speed * pace))
                        }
                    } finally {
                        audio.close()
                    }
                }
                try {
                    for (generated in audio) {
                        if (stopRequested || closed) break
                        play(generated)
                    }
                } finally {
                    producer.cancel()
                    audio.close()
                }
            }
        }
    }

    /** Cut playback short, e.g. when a phone call takes the audio focus. */
    fun stopPlayback() {
        stopRequested = true
        runCatching { track?.pause() }
        phonon?.stop()
    }

    /**
     * Drop the loaded engine so the next reply picks up a changed voice or a
     * newly installed fp32 model. Waits for any sentence in flight.
     */
    suspend fun reload() {
        if (closed) return
        runCatching { phonon?.close() }
        phonon = null
        speakLock.withLock {
            loadLock.withLock {
                runCatching { tts?.release() }
                tts = null
                fillers.clear()
            }
        }
        CrashLog.note(context, TAG, "engine reloaded after a voice change")
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
                // A downloaded Piper voice, when selected, replaces Kokoro
                // entirely: it loads faster and is lighter on the phone.
                val piper = AssistantPreferences.piperVoiceFlow(context).first()
                    ?.takeIf { PiperVoiceDownloader.isInstalled(context, it) }
                val created = if (piper != null) {
                    buildLocalVoice(PiperVoiceDownloader.dir(context, piper), piper)
                        ?: buildKokoroVoice(dir)
                } else {
                    buildKokoroVoice(dir)
                }
                if (created == null) return@withContext null
                tts = created
                created
            }
        }
    }

    /** Kokoro at the selected quality, falling back to int8 on failure. */
    private suspend fun buildKokoroVoice(dir: File): OfflineTts? {
        val highQuality = AssistantPreferences.highQualityFlow(context).first() &&
            KokoroHqDownloader.isInstalled(context)
        val created = if (highQuality) {
            buildEngine(dir, KokoroHqDownloader.modelFile(context), "fp32")
                ?: run {
                    CrashLog.note(context, TAG, "fp32 model failed to load; using int8")
                    buildEngine(dir, File(dir, "model.int8.onnx"), "int8")
                }
        } else {
            buildEngine(dir, File(dir, "model.int8.onnx"), "int8")
        }
        if (created != null) engineKind = EngineKind.Kokoro
        return created
    }

    private fun buildLocalVoice(dir: File, voice: PiperVoice): OfflineTts? = when (voice.engine) {
        LocalVoiceEngine.Supertonic -> buildSupertonic(dir, voice)
        LocalVoiceEngine.Vits -> buildVits(dir, voice)
    }

    /** Supertonic 3: a fast multi-file ONNX pipeline with ten voice styles. */
    private fun buildSupertonic(dir: File, voice: PiperVoice): OfflineTts? {
        CrashLog.note(context, TAG, "model=supertonic:${voice.id}")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(dir, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(dir, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(dir, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(dir, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(dir, "tts.json").absolutePath,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(dir, "voice.bin").absolutePath,
                ),
                numThreads = 4,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return try {
            val created = OfflineTts(assetManager = null, config = config)
            engineKind = EngineKind.Supertonic
            CrashLog.note(context, TAG, "OfflineTts ready (supertonic ${voice.id}, ${created.numSpeakers()} voices)")
            created
        } catch (error: Exception) {
            CrashLog.note(context, TAG, "Supertonic engine failed: ${error.message}")
            Log.e(TAG, "Supertonic engine creation failed", error)
            null
        }
    }

    /** Kokoro has 53 speakers; the local voices use speaker 0. */
    private fun speakerFor(engine: OfflineTts, speakerId: Int): Int =
        if (engineKind == EngineKind.Kokoro) speakerId else 0

    /** A downloaded Piper (VITS) voice. */
    private fun buildVits(dir: File, voice: PiperVoice): OfflineTts? {
        CrashLog.note(context, TAG, "model=piper:${voice.id}")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, "model.onnx").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return try {
            val created = OfflineTts(assetManager = null, config = config)
            engineKind = EngineKind.Piper
            CrashLog.note(context, TAG, "OfflineTts ready (piper ${voice.id})")
            created
        } catch (error: Exception) {
            CrashLog.note(context, TAG, "Piper engine failed: ${error.message}")
            Log.e(TAG, "Piper engine creation failed", error)
            null
        }
    }

    /**
     * Create an [OfflineTts] for [model], or null when the runtime refuses it.
     * Null rather than an exception lets the caller fall back to the bundled
     * int8 model instead of leaving the assistant silent.
     */
    private fun buildEngine(dir: File, model: File, label: String): OfflineTts? {
        CrashLog.note(context, TAG, "model=$label (${model.length() / 1048576} MB)")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                    // The GB lexicon plus en-gb made this build produce empty
                    // audio, so keep the proven US lexicon and let the voice
                    // bank decide the accent.
                    lexicon = File(dir, "lexicon-us-en.txt").absolutePath,
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        val started = System.currentTimeMillis()
        return try {
            val created = OfflineTts(assetManager = null, config = config)
            CrashLog.note(
                context,
                TAG,
                "OfflineTts ready ($label) in ${System.currentTimeMillis() - started} ms",
            )
            Log.i(TAG, "Kokoro $label ready: ${created.sampleRate()} Hz, ${created.numSpeakers()} voices")
            if (created.sampleRate() != SAMPLE_RATE) {
                Log.w(TAG, "Expected $SAMPLE_RATE Hz but the model reports ${created.sampleRate()} Hz")
            }
            created
        } catch (error: Exception) {
            CrashLog.note(context, TAG, "OfflineTts failed ($label): ${error.message}")
            Log.e(TAG, "OfflineTts creation failed for $label", error)
            null
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

    /**
     * Split for speech. Clauses and conjunctions become separate clips so the
     * first words are synthesised after ~6 words rather than a whole sentence,
     * which is what makes a reply feel immediate. Intonation resets at each
     * boundary, so the minimum is kept at six words.
     */
    private fun speechChunks(text: String): List<String> {
        val parts = text.split(
            Regex(
                "(?<=[.!?])\\s+|(?<=[,;:])\\s+|\\s+(?=(?:and|or|but|then)\\b)",
                RegexOption.IGNORE_CASE,
            ),
        ).map { it.trim() }.filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        var words = 0
        for (part in parts) {
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(part)
            words += part.split(Regex("\\s+")).count { it.isNotBlank() }
            val last = part.lastOrNull()
            val sentenceEnd = last == '.' || last == '!' || last == '?'
            val clauseEnd = last == ',' || last == ';' || last == ':' || last == '—' ||
                last == '–'
            // Flush at a sentence boundary, after a clause once there are four
            // words to say, or at six words regardless. The first audio then
            // starts a clause earlier on longer replies.
            if (sentenceEnd || words >= 6 || (clauseEnd && words >= 4)) {
                chunks += buffer.toString()
                buffer.setLength(0)
                words = 0
            }
        }
        if (buffer.isNotBlank()) chunks += buffer.toString()
        return chunks.ifEmpty { listOf(text) }
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

    private suspend fun play(audio: GeneratedAudio): Boolean {
        val samples = trimSilence(audio.samples, audio.sampleRate)
        if (samples.isEmpty()) {
            CrashLog.note(context, TAG, "empty audio returned by Kokoro")
            return false
        }
        // Environmental gain: louder over engine noise, normal when quiet.
        val gain = TtsGain.current
        val pcm = ShortArray(samples.size) { index ->
            ((samples[index] * gain).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
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
            if (player.write(pcm, 0, pcm.size) <= 0) return false
            player.play()
            // Static playback has no blocking write; wait out the clip and a
            // small tail so the last syllable is not cut off. Check in slices
            // so stopPlayback (a phone call taking the mic) releases the
            // assistant immediately instead of holding it for the whole clip.
            var remaining = pcm.size * 1000L / audio.sampleRate + 60
            while (remaining > 0 && !stopRequested && !closed) {
                val slice = minOf(50L, remaining)
                delay(slice)
                remaining -= slice
            }
        } finally {
            runCatching { player.stop() }
            player.release()
            if (track === player) track = null
            CrashLog.note(context, TAG, "playback finished")
        }
        return true
    }

    override fun close() {
        if (closed) return
        phonon?.close()
        phonon = null
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

        /** Rotated while a slower answer is on its way. */
        val FILLER_TEXTS = listOf(
            "Let me check.",
            "One moment.",
            "Right, let me see.",
            "One second.",
            "Just pulling that up.",
            "Bear with me.",
            "Having a look now.",
        )
    }
}

/**
 * Process-wide owner of the Kokoro voice, for the same reason as the speech
 * and intent models: loading 110 MB before the first reply is felt, and the
 * Settings preview should not create a second copy.
 */
object KokoroEngine {
    @Volatile
    private var instance: KokoroTts? = null

    fun get(context: android.content.Context): KokoroTts =
        instance ?: synchronized(this) {
            instance ?: KokoroTts(context.applicationContext).also { instance = it }
        }
}
