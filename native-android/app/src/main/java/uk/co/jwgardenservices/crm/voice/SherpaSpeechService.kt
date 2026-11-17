package uk.co.jwgardenservices.crm.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
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

    /** Moonshine loading is separate: it is 135 MB and only loaded on demand. */
    private val moonshineLock = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Each finished utterance, for callers that react to what was said. */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    private var moonshine: OfflineRecognizer? = null
    private var nemotron: OnlineRecognizer? = null
    private val nemotronLock = Mutex()
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

    /**
     * Armed while a spoken question is waiting for an answer (a wrap-up
     * prompt, a "send it?"). A barge-in then replays the frames heard before
     * the interruption into the recogniser, so a one-word answer like "Yep"
     * decodes whole instead of losing its first syllable to the dropped
     * frames.
     */
    @Volatile
    var captureInterjections = false

    /** True while the last prompt was a question with an answer pending. */
    @Volatile
    var fastPath = false

    /**
     * Called with the finalised interjection instead of the transcript queue,
     * so the answer to a question the assistant was still asking skips the
     * processing pause entirely. Fired from the recognition thread.
     */
    @Volatile
    var onInterjection: ((String) -> Unit)? = null

    /** Frames kept from just before a barge-in while paused (about 2 s). */
    private val pausePreRoll = ArrayDeque<Short>()

    @Volatile
    private var interjectionArmed = false

    private var echoCanceller: AcousticEchoCanceler? = null
    private var gainController: AutomaticGainControl? = null

    @Volatile
    private var pausedAt = 0L

    /** False with the accuracy-first microphone source: no echo canceller. */
    @Volatile
    private var bargeEnabled = true

    @Volatile
    private var bargeFrames = 0

    /** Rolling ambient microphone level, for the adaptive TTS gain. */
    @Volatile
    private var ambientLevel = 0f

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
        // Neither model is bundled: never pull one into memory at app launch.
        // The ringback covers its load when a call actually starts.
        val engine = engineOverride ?: runCatching {
            AssistantPreferences.asrEngineFlow(context).first()
        }.getOrDefault(AsrEngine.Moonshine)
        val moonshineHere = MoonshineDownloader.isInstalled(context)
        val nemotronHere = NemotronDownloader.isInstalled(context)
        CrashLog.note(
            context,
            TAG,
            "prepare: engine=${engine.name} installed(moonshine=$moonshineHere, " +
                "nemotron=$nemotronHere) forCall=$forCall override=${engineOverride?.name}",
        )
        when {
            engine == AsrEngine.Moonshine && moonshineHere -> {
                if (forCall) ensureMoonshine()
            }
            engine == AsrEngine.Nemotron && nemotronHere -> {
                if (forCall) ensureNemotron()
            }
            else -> CrashLog.note(context, TAG, "prepare: no usable engine installed")
        }
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
            CrashLog.note(context, TAG, "loading Moonshine (native construct starts)")
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

    /**
     * Load the optional Nemotron 3.5 streaming recogniser: a true online
     * transducer, so the shared streaming loop gives live partial words. The
     * model is English (en) only and nothing auto-detects language.
     */
    private suspend fun ensureNemotron(): OnlineRecognizer? {
        nemotron?.let { return it }
        return nemotronLock.withLock {
            nemotron?.let { return@withLock it }
            val dir = NemotronDownloader.dir(context)
            if (!NemotronDownloader.isInstalled(context)) {
                fail("Nemotron is not downloaded. Open Assistant settings to install it.")
                return@withLock null
            }
            val failures = NemotronDownloader.loadFailures(context)
            if (failures >= 2) {
                // Two native deaths loading this model on this phone. Stop
                // trying: the call keeps working on the smaller engine.
                fail("Nemotron kept failing on this phone. Moonshine is the safer engine.")
                CrashLog.note(context, TAG, "nemotron retired after $failures native failures")
                return@withLock null
            }
            _state.update { it.copy(phase = Phase.Loading, message = "Loading Nemotron…") }
            CrashLog.note(
                context,
                TAG,
                "loading Nemotron (native construct starts, previousFailures=$failures)",
            )
            val started = System.currentTimeMillis()
            if (failures > 0) {
                CrashLog.note(context, TAG, "retrying Nemotron after a native crash")
            }
            // This model is a NeMo transducer: sherpa-onnx's implementation
            // accepts greedy_search only. modified_beam_search, active paths
            // and hotword scores are rejected at load time by the native
            // library (sherpa-onnx issue #3572), which aborts the process.
            // Client-name accuracy comes from the transcript corrector
            // instead of decoder biasing on this engine.
            NemotronDownloader.markAttempt(context)
            val created = try {
                withContext(Dispatchers.IO) {
                    OnlineRecognizer(
                        assetManager = null,
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(
                                sampleRate = SAMPLE_RATE,
                                featureDim = FEATURE_DIM,
                            ),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                                    joiner = File(dir, "joiner.int8.onnx").absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 2,
                                provider = "cpu",
                            ),
                            endpointConfig = EndpointConfig(
                                rule1 = EndpointRule(
                                    mustContainNonSilence = false,
                                    minTrailingSilence = 0.65f,
                                    minUtteranceLength = 0f,
                                ),
                                rule2 = EndpointRule(
                                    mustContainNonSilence = true,
                                    minTrailingSilence = 0.4f,
                                    minUtteranceLength = 0f,
                                ),
                                rule3 = EndpointRule(
                                    mustContainNonSilence = false,
                                    minTrailingSilence = 0f,
                                    minUtteranceLength = 20f,
                                ),
                            ),
                            enableEndpoint = true,
                            // NeMo transducer: greedy is the only supported
                            // decoder in sherpa-onnx.
                            decodingMethod = "greedy_search",
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Cancelled by the user, not a native death.
                NemotronDownloader.clearAttempt(context)
                throw cancelled
            } catch (error: Exception) {
                NemotronDownloader.clearAttempt(context)
                Log.e(TAG, "Could not load Nemotron", error)
                CrashLog.note(context, TAG, "Nemotron load threw: ${error.message}")
                fail("Nemotron could not be loaded. Check the downloaded files.")
                return@withLock null
            }
            // The construct returned, so the marker has done its job.
            NemotronDownloader.clearAttempt(context)
            if (released) {
                created.release()
                null
            } else {
                nemotron = created
                // NeMo transducer has no decoder biasing here; the transcript
                // corrector in AsrHotwords still fixes client names.
                CrashLog.note(
                    context,
                    TAG,
                    "Nemotron ready in ${System.currentTimeMillis() - started}ms " +
                        "(decoding=greedy_search, biasing=off)",
                )
                _state.update { state ->
                    if (state.phase == Phase.Loading) state.copy(phase = Phase.Ready, message = null) else state
                }
                created
            }
        }
    }

    /** Free the ~650 MB Nemotron model when the owner switches back or removes it. */
    fun releaseNemotron() {
        if (listening) return
        nemotron?.release()
        nemotron = null
    }

    /**
     * Free the recognisers while the app is in the background and no call is
     * active. A 190 MB (or 660 MB) resident process is the first thing the
     * system kills, which cold-starts the app and shows the splash again on
     * return; dropping the models keeps the process small. The next call
     * reloads them behind the ringback.
     */
    fun releaseIdle() {
        if (listening) return
        releaseMoonshine()
        releaseNemotron()
        // A few megabytes that only matter while a call is up; it reloads
        // from disk on the next one.
        Punctuator.release()
        TtsGain.reset()
    }

    /** Warm checks so a returning caller can skip the dial tone. */
    fun moonshineWarm(): Boolean = moonshine != null
    fun nemotronWarm(): Boolean = nemotron != null

    /**
     * Per-call engine override, set by the service's memory guard. A phone
     * short on RAM is spared the 660 MB Nemotron load, which is a native
     * allocation that aborts the process when it fails — uncatchable.
     * Null means "whatever the owner chose".
     */
    @Volatile
    var engineOverride: AsrEngine? = null

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

        val selected = engineOverride
            ?: AssistantPreferences.asrEngineFlow(context).first()
        CrashLog.note(context, TAG, "startListening: engine=${selected.name}")
        offlineMode = selected == AsrEngine.Moonshine && MoonshineDownloader.isInstalled(context)
        if (selected == AsrEngine.Moonshine && !offlineMode) {
            fail("Moonshine is not downloaded. Open Assistant settings to install it.")
            return
        }
        val onlineEngine = when {
            offlineMode -> null
            selected == AsrEngine.Nemotron && NemotronDownloader.isInstalled(context) ->
                ensureNemotron()
            selected == AsrEngine.Nemotron -> {
                fail("Nemotron is not downloaded. Open Assistant settings to install it.")
                return
            }
            else -> null
        }
        if (!offlineMode && onlineEngine == null) return
        // Load the tiny punctuation model off the call path; the first
        // sentence is then punctuated without a stall.
        runCatching { Punctuator.warm(context) }
        // Client names, aliases and the patch vocabulary, used by the
        // post-ASR fuzzy corrector. Decoder biasing is not available on
        // either engine here (Nemotron's NeMo transducer accepts
        // greedy_search only), so this list never reaches the recogniser.
        hotwordNames = AsrHotwords.correctionTerms(context)
        CrashLog.note(context, TAG, "correction names=${hotwordNames.size}")

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
            Thread({ process(engine, record) }, "streaming-asr")
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
        releaseMicEffects()
        listening = false
        resumeAfterPause = false
        runCatching { audioRecord?.stop() }
        val worker = thread
        thread = null
        if (worker != null) runCatching { worker.join(2_000) }
        runCatching { moonshine?.release() }
        moonshine = null
        runCatching { nemotron?.release() }
        nemotron = null
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
            var readFailures = 0
            var peakLevel = 0f
            var peakPartial = 0
            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.e(TAG, "AudioRecord.read failed with $read")
                    if (++readFailures == 1) {
                        CrashLog.note(
                            context,
                            TAG,
                            "AudioRecord.read failed: $read (src=${record.audioSource})",
                        )
                    }
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
                        pausePreRoll.clear()
                    }
                    // Keep the last couple of seconds rolling so a barge-in
                    // can replay the start of the interjection.
                    for (index in 0 until read) {
                        pausePreRoll.addLast(buffer[index])
                    }
                    while (pausePreRoll.size > PAUSE_PRE_ROLL_SAMPLES) pausePreRoll.removeFirst()
                    if (maybeBargeIn(buffer, read)) {
                        // The interjection began before the RMS trigger: feed
                        // what was buffered, then the live loop takes over.
                        val replay = pausePreRoll.toShortArray()
                        pausePreRoll.clear()
                        if (replay.isNotEmpty()) {
                            feed(
                                engine,
                                stream,
                                FloatArray(replay.size) { replay[it] / 32768.0f },
                            )
                        }
                    }
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
                val engineEndpoint = engine.isEndpoint(stream)
                // The recogniser's own idle endpoint (rule 1) can fire while a
                // sentence is still being spoken. Resetting then throws the
                // words away — the log showed only the tail arriving
                // ("…you today"). While the VAD says speech is in progress,
                // an empty engine endpoint is ignored and the stream survives.
                if (engineEndpoint && text.isEmpty() && hasSpeech && !vadEndpoint) {
                    if (frames % 100 == 0) {
                        CrashLog.note(context, TAG, "engine endpoint ignored: speech in progress")
                    }
                } else if (vadEndpoint || engineEndpoint) {
                    engine.reset(stream)
                    if (vadEndpoint || text.isNotEmpty()) {
                        CrashLog.note(
                            context,
                            TAG,
                            "endpoint: vad=$vadEndpoint engine=$engineEndpoint text=${text.take(80)}",
                        )
                    }
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
                // Listening heartbeat, every ~5 s of audio: is anything
                // arriving at all, does the VAD see a voice, is text forming?
                // Peak catches a sentence spoken between beats; the routed
                // device names the physical microphone in use.
                peakLevel = maxOf(peakLevel, _state.value.level)
                peakPartial = maxOf(peakPartial, _state.value.partial.length)
                if (frames % 50 == 0) {
                    val route = record.routedDevice
                        ?.let { "type=${it.type} ${it.productName}" }
                        ?: "route=?"
                    CrashLog.note(
                        context,
                        TAG,
                        "listen: level=${"%.2f".format(_state.value.level)} " +
                            "peak=${"%.2f".format(peakLevel)} " +
                            "voiced=$voiced hasSpeech=$hasSpeech " +
                            "partial=${_state.value.partial.length} maxPartial=$peakPartial " +
                            "src=${record.audioSource} rec=${record.recordingState} $route",
                    )
                    peakLevel = 0f
                    peakPartial = 0
                }
            }
            finish(engine, stream)
        } catch (error: Exception) {
            Log.e(TAG, "Recognition loop failed", error)
            if (!released) fail(error.message ?: "Speech recognition failed.")
        } finally {
            runCatching { vad?.release() }
            releaseMicEffects()
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
                        pausePreRoll.clear()
                    }
                    for (index in 0 until read) {
                        pausePreRoll.addLast(buffer[index])
                    }
                    while (pausePreRoll.size > PAUSE_PRE_ROLL_SAMPLES) pausePreRoll.removeFirst()
                    if (maybeBargeIn(buffer, read)) {
                        // The offline path buffers a whole utterance anyway:
                        // seed it with the pre-roll (oldest first) so "Yep"
                        // decodes whole.
                        for (sample in pausePreRoll) utterance.add(sample / 32768.0f)
                        for (index in 0 until read) {
                            utterance.add(buffer[index] / 32768.0f)
                        }
                        pausePreRoll.clear()
                        hasSpeech = true
                        lastVoiceAt = SystemClock.elapsedRealtime()
                    }
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
            releaseMicEffects()
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
    private fun maybeBargeIn(buffer: ShortArray, length: Int): Boolean {
        if (!bargeEnabled || length <= 0) return false
        if (SystemClock.elapsedRealtime() - pausedAt < BARGE_GRACE_MS) return false
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
                if (captureInterjections) interjectionArmed = true
                CrashLog.note(context, TAG, "barge-in (rms=$rms)")
                onBargeIn?.invoke()
                return true
            }
        } else {
            bargeFrames = 0
        }
        return false
    }

    private fun feed(engine: OnlineRecognizer, stream: OnlineStream, samples: FloatArray): String {
        var energy = 0.0
        for (sample in samples) energy += sample * sample
        val level = (sqrt(energy / samples.size) * 8).toFloat().coerceIn(0f, 1f)

        // Slow EMA of the ambient level (~5 s at 10 frames/s) drives the TTS
        // gain: louder over road noise, normal in a quiet garden.
        ambientLevel = ambientLevel * 0.98f + level * 0.02f
        TtsGain.updateFromAmbient(ambientLevel)

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
                    // A van cabin and a phone in a cradle are quieter and
                    // duller than a desk mic; 0.5 missed ordinary speech.
                    threshold = 0.3f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.15f,
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
            ?.also { CrashLog.note(context, TAG, "silero VAD ready (threshold=0.3)") }
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
        // Punctuation and truecase on the finished sentence only: it makes a
        // dictated note read like English and gives the multi-command
        // splitter real sentence boundaries. Off unless the model is
        // installed, and failure falls back to the raw transcript.
        val punctuated = Punctuator.punctuate(context, corrected)
        if (interjectionArmed) {
            // The answer to a question the assistant was still asking: it
            // takes the fast path in the service instead of the transcript
            // queue, and the flag is spent so nothing leaks onward.
            interjectionArmed = false
            captureInterjections = false
            lastResult = punctuated
            _state.update { it.copy(finalText = punctuated, partial = "") }
            CrashLog.note(context, TAG, "interjection: ${punctuated.take(60)}")
            onInterjection?.invoke(punctuated)
            return
        }
        if (punctuated == lastResult) return
        lastResult = punctuated
        _state.update { it.copy(finalText = punctuated, partial = "") }
        CrashLog.note(context, TAG, "final: ${punctuated.take(80)}")
        _transcripts.tryEmit(punctuated)
    }

    // --- setup ------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked by startListening before this point
    private fun createAudioRecord(source: Int): AudioRecord {
        releaseMicEffects()
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
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            // Keep hardware AEC for barge-in, but turn Android's automatic gain
            // control off: on the communication source it ducks and clips the
            // speech harmonics, which the recogniser reads as worse audio.
            gainController = runCatching {
                AutomaticGainControl.create(record.audioSessionId)
            }.getOrNull()
            runCatching { gainController?.setEnabled(false) }
            CrashLog.note(context, TAG, "agc off=${gainController != null}")
        }
        return record
    }

    /** Release the platform echo/gain effects; safe to call repeatedly. */
    private fun releaseMicEffects() {
        runCatching { echoCanceller?.release() }
        echoCanceller = null
        runCatching { gainController?.release() }
        gainController = null
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String) {
        // Engine and microphone failures used to be UI-only, which left the
        // assistant log silent about them. They belong in the breadcrumb trail.
        CrashLog.note(context, TAG, "fail: $message")
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
        /**
         * A natural sentence has pauses of half a second or more. Cutting at
         * 450 ms sliced "what have I got on today" into "…you today"; 800 ms
         * lets the thought finish before the stream is closed.
         */
        const val ENDPOINT_SILENCE_MS = 800L

        /** Audio kept before VAD reports speech, so the first word is not clipped. */
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 4 / 10

        /** How much pre-barge audio is replayed into the recogniser. */
        const val PAUSE_PRE_ROLL_SAMPLES = SAMPLE_RATE * 2

        /** Decode a very long turn rather than buffering forever. */
        const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 20

        const val VAD_MODEL = "silero_vad.onnx"

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
