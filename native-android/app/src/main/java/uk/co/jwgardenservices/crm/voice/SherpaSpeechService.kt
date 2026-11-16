package uk.co.jwgardenservices.crm.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import uk.co.jwgardenservices.crm.core.CrashLog
import kotlin.math.sqrt

/**
 * Offline speech recognition for the Assistant tab, backed by sherpa-onnx and
 * a streaming Zipformer transducer bundled in `assets/`.
 *
 * The microphone is read in 100 ms frames on a dedicated thread, each frame is
 * fed straight into the recognizer, and the in-progress text is published as
 * it changes. When the recognizer's endpoint detector hears the speaker stop,
 * the sentence is finalised and also emitted on [transcripts].
 *
 * No model is downloaded and nothing leaves the phone, so this works in a
 * dead-signal lane.
 */
class SherpaSpeechService(private val context: Context) : AutoCloseable {

    enum class Phase {
        /** Model not loaded yet; the mic has not been tapped. */
        Idle,

        /** Reading the model out of assets. */
        Loading,

        /** Ready, waiting for the mic. */
        Ready,

        /** Microphone open, listening for speech. */
        Listening,

        /** Something failed; [UiState.message] says what. */
        Error,
    }

    data class UiState(
        val phase: Phase = Phase.Idle,
        /** Progress or failure text. */
        val message: String? = null,
        /** Words not yet finalised, shown while speaking. */
        val partial: String = "",
        /** The last finished utterance. */
        val finalText: String = "",
        /** Current input loudness, 0..1, for the on-screen meter. */
        val level: Float = 0f,
    )

    /** Serialises starts: three ensureListening calls must not open three mics. */
    private val startLock = Mutex()

    /** Serialises model loading, so a warm-up and a call cannot both build it. */
    private val loadLock = Mutex()

    /** Moonshine loading is separate: it is 135 MB and only loaded on demand. */
    private val moonshineLock = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Each finished utterance, for callers that react to what was said. */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    private var recognizer: OnlineRecognizer? = null
    private var moonshine: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var listening = false

    /** True when the current call is using the offline Moonshine recogniser. */
    @Volatile
    private var offlineMode = false

    /** Client names used to correct the final transcript for this call. */
    @Volatile
    private var hotwordNames: List<String> = emptyList()

    /** Set while the confirmation is spoken, so the phone ignores itself. */
    @Volatile
    private var paused = false

    /** True when playback should reopen the mic; cleared by a user stop. */
    @Volatile
    private var resumeAfterPause = false

    @Volatile
    private var released = false

    /** Last finalised line, so stopping does not repeat a confirmation. */
    @Volatile
    private var lastResult = ""

    /** Called when the owner talks over the assistant, so playback can stop. */
    @Volatile
    var onBargeIn: (() -> Unit)? = null

    private var echoCanceller: AcousticEchoCanceler? = null

    @Volatile
    private var pausedAt = 0L

    /** False with the accuracy-first microphone source: no echo canceller. */
    @Volatile
    private var bargeEnabled = true

    @Volatile
    private var bargeFrames = 0

    /**
     * Load the recogniser without opening the microphone.
     *
     * Called from the app process when the assistant has been used before, so
     * the 190 MB model is already in memory by the time the tab opens. It is
     * safe to call while a call is starting: [loadLock] makes the two share
     * one load instead of racing.
     */
    suspend fun prepare(forCall: Boolean = false) {
        if (released) return
        // Moonshine is 135 MB: never pull that into memory at app launch. The
        // ringback covers its load when a call actually starts.
        val engine = runCatching { AssistantPreferences.asrEngineFlow(context).first() }
            .getOrDefault(AsrEngine.Zipformer)
        if (engine == AsrEngine.Moonshine && MoonshineDownloader.isInstalled(context)) {
            if (forCall) ensureMoonshine()
            return
        }
        if (recognizer != null) return
        ensureRecognizer()
    }

    /**
     * Load the optional offline Moonshine recogniser.
     *
     * It has no streaming API, so it is decoded one utterance at a time after
     * the VAD endpoint. Kept process-wide like the Zipformer.
     */
    private suspend fun ensureMoonshine(): OfflineRecognizer? {
        moonshine?.let { return it }
        return moonshineLock.withLock {
            moonshine?.let { return@withLock it }
            val dir = MoonshineDownloader.dir(context)
            if (!MoonshineDownloader.isInstalled(context)) {
                fail("Moonshine is not downloaded. Open Assistant settings to install it.")
                return@withLock null
            }
            _state.update { it.copy(phase = Phase.Loading, message = "Loading Moonshine…") }
            val started = System.currentTimeMillis()
            val created = try {
                withContext(Dispatchers.IO) {
                    OfflineRecognizer(
                        assetManager = null,
                        config = OfflineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = SAMPLE_RATE,
                                featureDim = FEATURE_DIM,
                            ),
                            modelConfig = OfflineModelConfig(
                                moonshine = OfflineMoonshineModelConfig(
                                    encoder = File(dir, "encoder_model.ort").absolutePath,
                                    mergedDecoder = File(dir, "decoder_model_merged.ort").absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 2,
                                provider = "cpu",
                            ),
                            decodingMethod = "greedy_search",
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not load Moonshine", error)
                fail("Moonshine could not be loaded. Check the downloaded files.")
                return@withLock null
            }
            if (released) {
                created.release()
                null
            } else {
                moonshine = created
                CrashLog.note(
                    context,
                    TAG,
                    "Moonshine ready in ${System.currentTimeMillis() - started}ms",
                )
                _state.update { state ->
                    if (state.phase == Phase.Loading) state.copy(phase = Phase.Ready, message = null) else state
                }
                created
            }
        }
    }

    /** Free the 135 MB Moonshine model when the owner switches back or removes it. */
    fun releaseMoonshine() {
        if (listening) return
        moonshine?.release()
        moonshine = null
    }

    /** Free the 190 MB streaming model when Moonshine is selected. */
    fun releaseOnline() {
        if (listening) return
        recognizer?.release()
        recognizer = null
    }

    /** Build the recogniser once, under a lock shared by prepare and listen. */
    private suspend fun ensureRecognizer(): OnlineRecognizer? {
        recognizer?.let { return it }
        return loadLock.withLock {
            recognizer?.let { return@withLock it }
            _state.update { it.copy(phase = Phase.Loading, message = "Loading offline speech model…") }
            val started = System.currentTimeMillis()
            val created = try {
                withContext(Dispatchers.IO) { createRecognizer() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not load the sherpa-onnx model", error)
                fail("The offline speech model could not be loaded. Check assets/$MODEL_DIR.")
                return@withLock null
            }
            if (released) {
                created.release()
                null
            } else {
                recognizer = created
                CrashLog.note(
                    context,
                    TAG,
                    "recogniser ready in ${System.currentTimeMillis() - started}ms",
                )
                _state.update { state ->
                    if (state.phase == Phase.Loading) state.copy(phase = Phase.Ready, message = null) else state
                }
                created
            }
        }
    }

    /**
     * Open the mic, loading the model from assets the first time. Returns
     * immediately if already listening; never leaves a half-started loop.
     */
    suspend fun startListening() {
        // Several callers (onStartCommand, the bound screen, resume) can race.
        // Without this, each opens its own AudioRecord and they steal the mic
        // from each other; the log showed three recognition loops at once.
        if (!startLock.tryLock()) return
        try {
        if (released || listening) return

        val selected = AssistantPreferences.asrEngineFlow(context).first()
        offlineMode = selected == AsrEngine.Moonshine && MoonshineDownloader.isInstalled(context)
        if (selected == AsrEngine.Moonshine && !offlineMode) {
            CrashLog.note(context, TAG, "Moonshine selected but not installed; using Zipformer")
        }
        if (offlineMode) {
            if (ensureMoonshine() == null) {
                offlineMode = false
                CrashLog.note(context, TAG, "Moonshine unavailable; falling back to Zipformer")
            }
        }
        val onlineEngine = if (offlineMode) null else ensureRecognizer()
        if (!offlineMode && onlineEngine == null) return
        hotwordNames = AsrHotwords.names()

        if (!hasMicPermission()) {
            fail("Microphone permission is needed to listen.")
            return
        }

        val micSource = AssistantPreferences.micSourceFlow(context).first()
        bargeEnabled = micSource == MicSource.Communication
        CrashLog.note(context, TAG, "mic=${micSource.name}")
        val record = try {
            withContext(Dispatchers.IO) { createAudioRecord(micSource.androidSource) }
        } catch (error: Exception) {
            Log.e(TAG, "Could not open the microphone", error)
            fail("The microphone could not be opened.")
            return
        }
        audioRecord = record
        listening = true
        paused = false
        _state.update { it.copy(phase = Phase.Listening, message = null, partial = "") }

        thread = if (offlineMode) {
            Thread({ processOffline(record) }, "moonshine-asr")
        } else {
            val engine = onlineEngine ?: return
            Thread({ process(engine, record) }, "zipformer-asr")
        }.apply { start() }
        } finally {
            startLock.unlock()
        }
    }

    /** Close the mic; the last words are flushed before the thread ends. */
    fun stopListening() {
        if (!listening) return
        listening = false
        resumeAfterPause = false
        // read() is blocking, so stop() is what wakes the thread.
        runCatching { audioRecord?.stop() }
        _state.update { it.copy(phase = Phase.Ready) }
    }

    fun isListening(): Boolean = listening

    /**
     * Discard microphone frames while the confirmation is spoken, remembering
     * that the mic should reopen as soon as playback finishes.
     */
    fun pauseForSpeech() {
        if (!listening) return
        paused = true
        resumeAfterPause = true
        pausedAt = SystemClock.elapsedRealtime()
        bargeFrames = 0
    }

    /**
     * Reopen the microphone once Kokoro has finished. Normally the AudioRecord
     * loop is still running and this simply lets frames through again; if the
     * loop ended while the confirmation played, a fresh listening session is
     * started so the next command needs no tap.
     */
    suspend fun resumeAfterSpeech() {
        paused = false
        val reopen = resumeAfterPause
        resumeAfterPause = false
        if (reopen && !listening && !released) {
            startListening()
        }
    }

    fun clear() {
        _state.update { it.copy(partial = "", finalText = "", message = null) }
    }

    override fun close() {
        released = true
        runCatching { echoCanceller?.release() }
        echoCanceller = null
        listening = false
        resumeAfterPause = false
        runCatching { audioRecord?.stop() }
        val worker = thread
        thread = null
        if (worker != null) runCatching { worker.join(2_000) }
        runCatching { recognizer?.release() }
        recognizer = null
        runCatching { moonshine?.release() }
        moonshine = null
        _state.value = UiState()
    }

    // --- audio loop -------------------------------------------------------

    @SuppressLint("MissingPermission") // checked by startListening before this point
    private fun process(engine: OnlineRecognizer, record: AudioRecord) {
        val stream = engine.createStream()
        val vad = createVad()
        var hasSpeech = false
        var lastVoiceAt = 0L
        var pausedClean = false
        CrashLog.note(context, TAG, "recognition loop starting (source=${record.audioSource})")
        Log.i(TAG, "Recognition loop starting (source=${record.audioSource})")
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("The microphone did not start recording.")
                return
            }
            val buffer = ShortArray(FRAME_SAMPLES)
            var frames = 0
            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.e(TAG, "AudioRecord.read failed with $read")
                    continue
                }
                if (read == 0) continue
                if (paused) {
                    if (!pausedClean) {
                        // Do not let a stale speech flag end the turn the moment
                        // playback stops.
                        hasSpeech = false
                        vad?.reset()
                        pausedClean = true
                    }
                    maybeBargeIn(buffer, read)
                    continue
                }
                pausedClean = false
                frames += 1
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad?.acceptWaveform(samples)
                val voiced = vad?.isSpeechDetected() == true
                if (voiced) {
                    hasSpeech = true
                    lastVoiceAt = SystemClock.elapsedRealtime()
                }
                val text = feed(engine, stream, samples)
                val vadEndpoint = vad != null && hasSpeech && !voiced &&
                    SystemClock.elapsedRealtime() - lastVoiceAt >= ENDPOINT_SILENCE_MS
                if (vadEndpoint || engine.isEndpoint(stream)) {
                    engine.reset(stream)
                    if (text.isNotEmpty()) publish(text)
                    hasSpeech = false
                    vad?.reset()
                }
                if (frames % 25 == 0) {
                    Log.d(
                        TAG,
                        "frames=$frames read=$read rms=${_state.value.level} " +
                            "partial=${_state.value.partial.length} final=${_state.value.finalText.length}",
                    )
                }
            }
            finish(engine, stream)
        } catch (error: Exception) {
            Log.e(TAG, "Recognition loop failed", error)
            if (!released) fail(error.message ?: "Speech recognition failed.")
        } finally {
            runCatching { vad?.release() }
            runCatching { echoCanceller?.release() }
            echoCanceller = null
            runCatching { record.release() }
            if (audioRecord === record) audioRecord = null
            runCatching { stream.release() }
            listening = false
            if (!released) {
                _state.update {
                    it.copy(phase = Phase.Ready, partial = "")
                }
            }
        }
    }

    /**
     * Moonshine loop: collect one utterance with Silero VAD, then decode it
     * offline. There are no live partials; the final sentence appears after the
     * endpoint. A 0.4 s pre-roll keeps the first syllable.
     */
    private fun processOffline(record: AudioRecord) {
        val vad = createVad()
        var hasSpeech = false
        var lastVoiceAt = 0L
        var pausedClean = false
        var frames = 0
        val utterance = ArrayList<Float>(SAMPLE_RATE * 4)
        val preRoll = ArrayDeque<Float>()
        CrashLog.note(context, TAG, "moonshine loop starting (source=${record.audioSource})")
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("The microphone did not start recording.")
                return
            }
            val buffer = ShortArray(FRAME_SAMPLES)
            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.e(TAG, "AudioRecord.read failed with $read")
                    continue
                }
                if (read == 0) continue
                if (paused) {
                    if (!pausedClean) {
                        hasSpeech = false
                        vad?.reset()
                        utterance.clear()
                        preRoll.clear()
                        pausedClean = true
                    }
                    maybeBargeIn(buffer, read)
                    continue
                }
                pausedClean = false
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                vad?.acceptWaveform(samples)
                val voiced = vad?.isSpeechDetected() == true
                val now = SystemClock.elapsedRealtime()

                if (!hasSpeech) {
                    for (sample in samples) {
                        preRoll.addLast(sample)
                        while (preRoll.size > PRE_ROLL_SAMPLES) preRoll.removeFirst()
                    }
                    if (voiced) {
                        hasSpeech = true
                        lastVoiceAt = now
                        utterance.addAll(preRoll)
                        preRoll.clear()
                    }
                } else {
                    if (voiced) lastVoiceAt = now
                    for (sample in samples) utterance.add(sample)
                    val endpoint = !voiced && now - lastVoiceAt >= ENDPOINT_SILENCE_MS
                    if (endpoint || utterance.size >= MAX_UTTERANCE_SAMPLES) {
                        val text = decodeOffline(utterance)
                        utterance.clear()
                        hasSpeech = false
                        vad?.reset()
                        if (text.isNotEmpty()) {
                            pauseForSpeech()
                            // Decoding blocked the loop, so the record buffer
                            // holds pre-decode audio. Flush it before the next
                            // listening turn so it cannot become a phantom line.
                            runCatching {
                                record.stop()
                                record.startRecording()
                            }
                            publish(text)
                        }
                    }
                }

                frames += 1
                if (frames % 100 == 0) {
                    Log.d(TAG, "moonshine frames=$frames buffered=${utterance.size}")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Moonshine loop failed", error)
            if (!released) fail(error.message ?: "Speech recognition failed.")
        } finally {
            runCatching { vad?.release() }
            runCatching { echoCanceller?.release() }
            echoCanceller = null
            runCatching { record.release() }
            if (audioRecord === record) audioRecord = null
            listening = false
            if (!released) {
                _state.update { it.copy(phase = Phase.Ready, partial = "") }
            }
        }
    }

    /** Decode one buffered utterance with Moonshine and return the text. */
    private fun decodeOffline(samples: List<Float>): String {
        val engine = moonshine ?: return ""
        if (samples.isEmpty()) return ""
        val started = SystemClock.elapsedRealtime()
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples.toFloatArray(), SAMPLE_RATE)
            engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            CrashLog.note(
                context,
                TAG,
                "moonshine ${SystemClock.elapsedRealtime() - started}ms " +
                    "(${samples.size / SAMPLE_RATE}s): ${text.take(120)}",
            )
            text
        } catch (error: Exception) {
            CrashLog.note(context, TAG, "moonshine decode failed: ${error.message}")
            ""
        } finally {
            runCatching { stream.release() }
        }
    }

    /**
     * While the assistant speaks, the recognizer is not fed, but the mic is
     * still read: if the owner talks over it for ~300 ms, playback is cut and
     * the normal loop resumes. Echo cancellation comes from VOICE_COMMUNICATION
     * and AcousticEchoCanceler, so the assistant does not trigger itself.
     */
    private fun maybeBargeIn(buffer: ShortArray, length: Int) {
        if (!bargeEnabled || length <= 0) return
        if (SystemClock.elapsedRealtime() - pausedAt < BARGE_GRACE_MS) return
        var energy = 0.0
        for (index in 0 until length) {
            val sample = buffer[index] / 32768.0
            energy += sample * sample
        }
        val rms = sqrt(energy / length)
        if (rms >= BARGE_RMS) {
            if (++bargeFrames >= BARGE_FRAMES) {
                bargeFrames = 0
                paused = false
                resumeAfterPause = false
                CrashLog.note(context, TAG, "barge-in (rms=$rms)")
                onBargeIn?.invoke()
            }
        } else {
            bargeFrames = 0
        }
    }

    private fun feed(engine: OnlineRecognizer, stream: OnlineStream, samples: FloatArray): String {
        var energy = 0.0
        for (sample in samples) energy += sample * sample
        val level = (sqrt(energy / samples.size) * 8).toFloat().coerceIn(0f, 1f)

        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (engine.isReady(stream)) engine.decode(stream)

        val text = engine.getResult(stream).text.trim()
        if (text.isNotEmpty()) {
            _state.update { it.copy(partial = text, level = level) }
        } else {
            _state.update { it.copy(level = level) }
        }
        return text
    }

    /**
     * Silero VAD decides when the owner has stopped, instead of relying only
     * on the recognizer's trailing-silence heuristic. Loaded from assets; if
     * it cannot be created, the recognizer's endpoint still ends the turn.
     */
    private fun createVad(): Vad? {
        // Real file path, like the ASR model: the AssetManager buffer path is
        // unreliable and a wrong native path can abort the process.
        val modelFile = runCatching { copyVadAsset() }.getOrElse { error ->
            CrashLog.note(context, TAG, "silero VAD asset missing: ${error.message}")
            return null
        }
        return runCatching {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.1f,
                    windowSize = 512,
                    maxSpeechDuration = 10f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )
            Vad(assetManager = null, config = config)
        }
            .onFailure { Log.w(TAG, "Silero VAD unavailable", it) }
            .getOrNull()
            ?.also { CrashLog.note(context, TAG, "silero VAD ready") }
    }

    /** Copy the 640 KB VAD model out of assets once and return its path. */
    private fun copyVadAsset(): File {
        val target = File(context.filesDir, VAD_MODEL)
        if (target.isFile && target.length() > 100_000) return target
        context.assets.open(VAD_MODEL).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        CrashLog.note(context, TAG, "copied silero VAD (${target.length()} bytes)")
        return target
    }

    /** Push half a second of silence so the last words come out of the stream. */
    private fun finish(engine: OnlineRecognizer, stream: OnlineStream) {
        stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
        while (engine.isReady(stream)) engine.decode(stream)
        val text = engine.getResult(stream).text.trim()
        if (text.isNotEmpty()) {
            publish(text)
        } else {
            _state.update { it.copy(partial = "") }
        }
    }

    private fun publish(text: String) {
        val corrected = AsrHotwords.correct(text, hotwordNames)
        if (corrected == lastResult) return
        lastResult = corrected
        _state.update { it.copy(finalText = corrected, partial = "") }
        _transcripts.tryEmit(corrected)
    }

    // --- setup ------------------------------------------------------------

    private fun createRecognizer(): OnlineRecognizer {
        requireModelFiles()
        val modelDir = copyModelFiles()
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, ENCODER_FILE).absolutePath,
                    decoder = File(modelDir, DECODER_FILE).absolutePath,
                    joiner = File(modelDir, JOINER_FILE).absolutePath,
                ),
                tokens = File(modelDir, TOKENS_FILE).absolutePath,
                numThreads = 2,
                provider = "cpu",
                // Leave modelType empty: the 06-26 archive reports
                // "zipformer2" in its ONNX metadata, and forcing "zipformer"
                // makes the native loader crash while parsing the encoder.
            ),
            // Commit the line quickly after speech ends: 0.65 s of trailing
            // silence even without recognised words, 0.4 s once something was
            // heard. Shorter than this starts cutting people off mid-sentence;
            // longer is pure dead air before the reply.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.65f, minUtteranceLength = 0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.4f, minUtteranceLength = 0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
            ),
            enableEndpoint = true,
            // Beam search costs little on a phone and handles accents and
            // background noise noticeably better than greedy decoding.
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
        Log.i(TAG, "Loading streaming Zipformer: ${File(modelDir, ENCODER_FILE)}")
        return OnlineRecognizer(assetManager = null, config = config).also {
            Log.i(TAG, "Streaming recognizer ready")
        }
    }

    /**
     * Stream the model out of assets into internal storage once. The 180 MB
     * encoder is too large for the AssetManager buffer path, the same way the
     * Kokoro model was.
     */
    private fun copyModelFiles(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        val names = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE)
        if (File(dir, ".complete").isFile && names.all { File(dir, it).isFile }) return dir

        dir.deleteRecursively()
        dir.mkdirs()
        val started = System.currentTimeMillis()
        names.forEach { name ->
            context.assets.open("$MODEL_DIR/$name").use { input ->
                File(dir, name).outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
        File(dir, ".complete").writeText("1")
        CrashLog.note(
            context,
            TAG,
            "copied ASR model in ${System.currentTimeMillis() - started} ms",
        )
        return dir
    }

    /** Fail with a clear message instead of a native crash when assets are absent. */
    private fun requireModelFiles() {
        val missing = listOf(ENCODER, DECODER, JOINER, TOKENS).filterNot { path ->
            runCatching { context.assets.open(path).close() }.isSuccess
        }
        if (missing.isNotEmpty()) {
            throw IOException("Missing model files in assets: ${missing.joinToString()}")
        }
    }

    @SuppressLint("MissingPermission") // checked by startListening before this point
    private fun createAudioRecord(source: Int): AudioRecord {
        echoCanceller?.release()
        echoCanceller = null
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Four frames of slack keeps a slow decode from overrunning the buffer.
        val bytes = maxOf(minBytes, FRAME_SAMPLES * 2 * 4)
        // VOICE_COMMUNICATION enables the platform echo canceller, which is
        // what makes barge-in possible without the phone hearing itself.
        val record = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord could not be initialised.")
        }
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION &&
            AcousticEchoCanceler.isAvailable()
        ) {
            echoCanceller = runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
            CrashLog.note(context, TAG, "echo canceller=${echoCanceller != null}")
        }
        return record
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String) {
        _state.update { it.copy(phase = Phase.Error, message = message, partial = "") }
    }

    private companion object {
        const val TAG = "SherpaSpeechService"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80

        /** 100 ms of 16 kHz mono audio. */
        const val FRAME_SAMPLES = SAMPLE_RATE / 10

        /** Ignore the first moments after playback starts, while AEC settles. */
        const val BARGE_GRACE_MS = 350L

        /** Sustained speech-level energy for 3 × 100 ms means a real interruption. */
        const val BARGE_RMS = 0.04
        const val BARGE_FRAMES = 3

        /** Silence after the last voiced frame that ends the turn. */
        const val ENDPOINT_SILENCE_MS = 450L

        /** Audio kept before VAD reports speech, so the first word is not clipped. */
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 4 / 10

        /** Decode a very long turn rather than buffering forever. */
        const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 20

        const val VAD_MODEL = "silero_vad.onnx"

        /**
         * Streaming Zipformer (English, LibriSpeech + GigaSpeech, int8) from
         * https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
         * The GigaSpeech-trained model hears real phone audio much better
         * than the smaller LibriSpeech-only ones while still emitting live
         * partial words. Decoder stays float32 per the model card.
         */
        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-21"
        const val ENCODER_FILE = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER_FILE = "decoder-epoch-99-avg-1.onnx"
        const val JOINER_FILE = "joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val ENCODER = "$MODEL_DIR/$ENCODER_FILE"
        const val DECODER = "$MODEL_DIR/$DECODER_FILE"
        const val JOINER = "$MODEL_DIR/$JOINER_FILE"
        const val TOKENS = "$MODEL_DIR/$TOKENS_FILE"
    }
}

/**
 * Process-wide owner of the speech recognizer.
 *
 * The model is ~190 MB and takes seconds to build. The Assistant service can
 * be destroyed when a call ends and the screen unbinds, but the phone's
 * process usually lives on; keeping the recognizer here means "Start call"
 * reuses it instead of reloading the model. It is freed when Android kills
 * the process, which is the only time a reload is unavoidable.
 */
object SpeechEngine {
    @Volatile
    private var instance: SherpaSpeechService? = null

    fun get(context: Context): SherpaSpeechService =
        instance ?: synchronized(this) {
            instance ?: SherpaSpeechService(context.applicationContext).also { instance = it }
        }
}
