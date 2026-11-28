package uk.co.jwgardenservices.crm.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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
import kotlin.math.log10
import kotlin.math.pow
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
/**
 * Frame-wise automatic gain control for a quiet phone microphone.
 *
 * A fixed ladder cannot follow a voice: it sits at the boost chosen for the
 * quietest recent frame, so the first word after a pause starts too low. This
 * tracks RMS against a target and lifts quietly, caps the lift at 6x, never
 * attenuates, and freezes during true silence so the room noise floor is not
 * pumped up between sentences. Loud speech therefore passes untouched.
 */
private class InputAgc {
    private var gain = 1f

    fun reset() {
        gain = 1f
    }

    fun process(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) return raw
        var energy = 0.0
        var peak = 0f
        for (sample in raw) {
            energy += sample * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        val rms = sqrt(energy / raw.size)
        val rmsDb = (20 * log10(rms.coerceAtLeast(1e-5))).toFloat()
        if (rmsDb < -55f) {
            // Silence: ease back rather than amplifying the noise floor.
            gain = 1f + (gain - 1f) * 0.99f
            return FloatArray(raw.size) { (raw[it] * gain).coerceIn(-0.98f, 0.98f) }
        }
        // Target the RMS *and* keep the peak below clipping. A Bluetooth car
        // mic arrives at rms 0.14 but peaks 0.5; a boost-only AGC drove that
        // into the limiter, and the distorted audio decoded to nothing.
        val rmsTarget = 10f.pow((-24f - rmsDb) / 20f)
        val peakTarget = 0.9f / peak.coerceAtLeast(0.001f)
        val desired = minOf(rmsTarget, peakTarget).coerceIn(0.2f, 6f)
        val coefficient = if (desired < gain) 0.6f else 0.25f
        gain += (desired - gain) * coefficient
        // Hard guard: never let a transient clip the feature extractor.
        val ceiling = 0.95f / peak.coerceAtLeast(0.001f)
        if (gain > ceiling) gain = ceiling
        return FloatArray(raw.size) { (raw[it] * gain).coerceIn(-0.98f, 0.98f) }
    }

    val gainDb: Float
        get() = (20.0 * log10(gain.coerceAtLeast(0.001f).toDouble())).toFloat()
}

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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Each finished utterance, for callers that react to what was said. */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    /** Parakeet TDT 0.6B, an optional offline recogniser; loaded only when chosen and installed. */
    private var parakeet: OfflineRecognizer? = null

    /** The optional Whisper second pass; loaded only when chosen and installed. */
    private var whisper: OfflineRecognizer? = null
    private val whisperLock = Mutex()

    /** whisper.cpp, the other optional second pass: shorter encoder window, built-in VAD. */
    private var whisperCpp: WhisperCpp? = null

    /** Which offline recogniser takes the second look; read once when listening starts. */
    @Volatile
    private var finalPass = FinalPass.Off
    private var nemotron: OnlineRecognizer? = null
    private val nemotronLock = Mutex()
    private val offlineDecodeLock = Any()
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var listening = false

    /** True when the current call is using the offline recogniser alone, with no live partials. */
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

    /** A possible interruption: dip the voice before confirming it. */
    @Volatile
    var onBargeCandidate: (() -> Unit)? = null

    /** Speaker bleed rather than a person: bring the voice back up. */
    @Volatile
    var onBargeAbort: (() -> Unit)? = null

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
     * Set while the assistant has asked which client was meant. The name
     * corrector then widens its phonetic/Levenshtein window, because a
     * narrowband headset answer is often one mangled word.
     */
    @Volatile
    var expectClientName = false

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
    /** The engine this listening session actually runs, for quiet-mic fallback. */
    @Volatile
    private var activeEngine = AsrEngine.Offline

    /** A raw-mic fallback chosen after a silent source; clears with the service. */
    @Volatile
    private var silentFallbackSource: MicSource? = null

    /** True when the owner prefers the phone mic over a Bluetooth HFP mic. */
    @Volatile
    private var preferPhoneMic = false

    /** The noise-suppression switch, read once when listening starts. */
    private var denoiseWanted = false

    /** The audio-capture switch, read once when listening starts. */
    private var captureWanted = false

    /** Client names alone (not the wider correction list), for choosing between transcripts. */
    private var clientNames: List<String> = emptyList()

    /** True when this session explicitly pinned the built-in microphone. */
    @Volatile
    private var forcedBuiltInMic = false

    /** True when a Bluetooth headset is connected for this listening session. */
    @Volatile
    private var bluetoothRouteAvailable = false

    /** Quiet "Mm-hmm" while the owner is talking; wired headsets only. */
    @Volatile
    var onBackchannel: (() -> Unit)? = null

    /** False once the call pauses or ends, so a fallback restart cannot reopen the mic. */
    @Volatile
    private var listenWanted = false

    private var gainController: AutomaticGainControl? = null

    /** Wind filter + light AGC for Bluetooth HFP/SCO capture. */
    private val headsetAudio = HeadsetAudio()

    @Volatile
    private var pausedAt = 0L

    /** Barge-in is allowed on every source; the guard changes with the route. */
    @Volatile
    private var bargeEnabled = true

    /** True when AEC or a headset echo path protects the microphone. */
    @Volatile
    private var bargeHasEchoCancel = true

    @Volatile
    private var bargeRmsThreshold = BARGE_RMS

    @Volatile
    private var bargeFramesNeeded = BARGE_FRAMES

    /** VAD can help a headset; on a loudspeaker it mostly hears the TTS. */
    @Volatile
    private var bargeUseVad = true

    /** Longer on a loudspeaker so the first words are not mistaken for a barge. */
    @Volatile
    private var bargeGraceMs = BARGE_GRACE_MS

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
        loadFinalPass()
        val engine = engineOverride ?: runCatching {
            AssistantPreferences.asrEngineFlow(context).first()
        }.getOrDefault(AsrEngine.Nemotron)
        val offlineHere = offlineRecogniserReady()
        val nemotronHere = NemotronDownloader.isInstalled(context)
        CrashLog.note(
            context,
            TAG,
            "prepare: engine=${engine.name} installed(offline=$offlineHere via ${finalPass.id}, " +
                "nemotron=$nemotronHere) forCall=$forCall override=${engineOverride?.name}",
        )
        when {
            engine == AsrEngine.Offline && offlineHere -> {
                if (forCall) warmFinalPass()
            }
            engine == AsrEngine.Nemotron && nemotronHere -> {
                if (forCall) ensureNemotron()
            }
            else -> CrashLog.note(context, TAG, "prepare: no usable engine installed")
        }
    }

    private suspend fun loadFinalPass() {
        finalPass = runCatching { AssistantPreferences.finalPassFlow(context).first() }
            .getOrDefault(FinalPass.Off)
    }

    /** True when the chosen offline recogniser (Parakeet or a Whisper) has its files. */
    private fun offlineRecogniserReady(): Boolean = when (finalPass) {
        FinalPass.Parakeet -> ParakeetDownloader.isInstalled(context)
        FinalPass.Whisper -> WhisperDownloader.isInstalled(context)
        FinalPass.WhisperCpp -> WhisperCppDownloader.isInstalled(context) && WhisperCpp.available
        FinalPass.Off -> false
    }

    /**
     * Load Parakeet TDT 0.6B (int8, about 660 MB on disk and more in memory), so
     * it is skipped when the phone is short of free RAM.
     */
    private suspend fun ensureParakeet(): OfflineRecognizer? {
        parakeet?.let { return it }
        return whisperLock.withLock {
            parakeet?.let { return@withLock it }
            if (!ParakeetDownloader.isInstalled(context)) return@withLock null
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            val availableMb = info.availMem / (1024L * 1024L)
            if (manager != null && availableMb < PARAKEET_MIN_FREE_MB) {
                CrashLog.note(context, TAG, "only $availableMb MB free for Parakeet; not loading it")
                return@withLock null
            }
            val dir = ParakeetDownloader.dir(context)
            CrashLog.note(context, TAG, "loading Parakeet (native construct starts)")
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
                                transducer = OfflineTransducerModelConfig(
                                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                                    joiner = File(dir, "joiner.int8.onnx").absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                modelType = "nemo_transducer",
                                numThreads = 4,
                                provider = "cpu",
                            ),
                            decodingMethod = "greedy_search",
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not load Parakeet", error)
                CrashLog.note(context, TAG, "Parakeet could not be loaded (${error.message})")
                return@withLock null
            }
            if (released) {
                created.release()
                null
            } else {
                parakeet = created
                CrashLog.note(context, TAG, "Parakeet ready in ${System.currentTimeMillis() - started}ms")
                created
            }
        }
    }

    /**
     * Load Whisper small.en, about 375 MB on disk and noticeably more in memory,
     * so it is skipped when the phone is short of free RAM.
     */
    private suspend fun ensureWhisper(): OfflineRecognizer? {
        whisper?.let { return it }
        return whisperLock.withLock {
            whisper?.let { return@withLock it }
            if (!WhisperDownloader.isInstalled(context)) return@withLock null
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            val availableMb = info.availMem / (1024L * 1024L)
            if (manager != null && availableMb < WHISPER_MIN_FREE_MB) {
                CrashLog.note(context, TAG, "only $availableMb MB free for Whisper; skipping the second look")
                return@withLock null
            }
            val dir = WhisperDownloader.dir(context)
            CrashLog.note(context, TAG, "loading Whisper (native construct starts)")
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
                                whisper = OfflineWhisperModelConfig(
                                    encoder = File(dir, "small.en-encoder.int8.onnx").absolutePath,
                                    decoder = File(dir, "small.en-decoder.int8.onnx").absolutePath,
                                    language = "en",
                                    task = "transcribe",
                                ),
                                tokens = File(dir, "small.en-tokens.txt").absolutePath,
                                numThreads = 3,
                                provider = "cpu",
                            ),
                            decodingMethod = "greedy_search",
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not load Whisper", error)
                CrashLog.note(context, TAG, "Whisper could not be loaded (${error.message}); skipping the second look")
                return@withLock null
            }
            if (released) {
                created.release()
                null
            } else {
                whisper = created
                CrashLog.note(context, TAG, "Whisper ready in ${System.currentTimeMillis() - started}ms")
                created
            }
        }
    }

    /** Load whisper.cpp (about 190 MB on disk) when it is chosen and installed. */
    private suspend fun ensureWhisperCpp(): WhisperCpp? {
        whisperCpp?.let { return it }
        return whisperLock.withLock {
            whisperCpp?.let { return@withLock it }
            if (!WhisperCppDownloader.isInstalled(context)) return@withLock null
            if (!WhisperCpp.available) {
                CrashLog.note(context, TAG, "whisper.cpp library missing from this build; skipping the second look")
                return@withLock null
            }
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            val availableMb = info.availMem / (1024L * 1024L)
            if (manager != null && availableMb < WHISPER_CPP_MIN_FREE_MB) {
                CrashLog.note(context, TAG, "only $availableMb MB free for whisper.cpp; skipping the second look")
                return@withLock null
            }
            val dir = WhisperCppDownloader.dir(context)
            CrashLog.note(context, TAG, "loading whisper.cpp (native construct starts)")
            val started = System.currentTimeMillis()
            val created = withContext(Dispatchers.IO) {
                WhisperCpp.open(File(dir, WhisperCppDownloader.MODEL_FILE), File(dir, WhisperCppDownloader.VAD_FILE))
            }
            if (created == null) {
                CrashLog.note(context, TAG, "whisper.cpp could not be loaded; skipping the second look")
                return@withLock null
            }
            if (released) {
                created.close()
                null
            } else {
                whisperCpp = created
                CrashLog.note(context, TAG, "whisper.cpp ready in ${System.currentTimeMillis() - started}ms")
                created
            }
        }
    }

    fun releaseWhisper() {
        if (listening) return
        whisper?.release()
        whisper = null
        parakeet?.release()
        parakeet = null
        whisperCpp?.close()
        whisperCpp = null
    }

    /** Load the chosen second-pass recogniser at the start of a call, not mid-sentence. */
    suspend fun warmFinalPass() {
        val pass = runCatching { AssistantPreferences.finalPassFlow(context).first() }            .getOrDefault(FinalPass.Off)
        if (pass == FinalPass.Parakeet) ensureParakeet()
        if (pass == FinalPass.Whisper) ensureWhisper()
        if (pass == FinalPass.WhisperCpp) ensureWhisperCpp()
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
                fail("Nemotron kept failing on this phone. Use an offline recogniser instead.")
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
                                // Nemotron's encoder is trained on 128 mel bins.
                                featureDim = 128,
                            ),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                                    joiner = File(dir, "joiner.int8.onnx").absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 4,
                                provider = "cpu",
                                // The NeMo streaming implementation, rather than
                                // relying on node-count auto-detection.
                                modelType = "nemotron",
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
                            // Silero/energy decides the turn; NeMo's internal
                            // endpoint resets mid-sentence on quiet audio.
                            enableEndpoint = false,
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
        releaseWhisper()
        releaseNemotron()
        // A few megabytes that only matter while a call is up; it reloads
        // from disk on the next one.
        Punctuator.release()
        TtsGain.reset()
    }

    /** Warm checks so a returning caller can skip the dial tone. */
    fun offlineWarm(): Boolean = parakeet != null || whisper != null || whisperCpp != null
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
        listenWanted = true
        headsetAudio.reset()

        loadFinalPass()
        var selected = engineOverride
            ?: AssistantPreferences.asrEngineFlow(context).first()
        CrashLog.note(context, TAG, "startListening: engine=${selected.name}")
        val offlineInstalled = offlineRecogniserReady()
        val nemotronInstalled = NemotronDownloader.isInstalled(context)
        if (selected == AsrEngine.Nemotron && !nemotronInstalled) {
            if (!offlineInstalled) {
                fail("Nemotron is not downloaded. Open Assistant settings to install it.")
                return
            }
            CrashLog.note(context, TAG, "Nemotron is not installed; using the offline recogniser")
            selected = AsrEngine.Offline
        }
        offlineMode = selected == AsrEngine.Offline && offlineInstalled
        if (selected == AsrEngine.Offline && !offlineMode) {
            fail("No offline recogniser is downloaded. Open Assistant settings to install Parakeet or Whisper.")
            return
        }
        var onlineEngine = when {
            offlineMode -> null
            selected == AsrEngine.Nemotron -> ensureNemotron()
            else -> null
        }
        if (selected == AsrEngine.Nemotron && onlineEngine == null) {
            if (!offlineInstalled) {
                fail("Nemotron could not be loaded, and no offline recogniser is installed.")
                return
            }
            // A load failure (including a retired Nemotron after native
            // deaths) must not leave the call dead. Continue this session on
            // the offline recogniser and clear the error the failed load posted.
            CrashLog.note(context, TAG, "Nemotron unavailable; using the offline recogniser")
            selected = AsrEngine.Offline
            offlineMode = true
            onlineEngine = null
            _state.update { it.copy(phase = Phase.Ready, message = null, partial = "") }
        }
        if (offlineMode) {
            _state.update { it.copy(phase = Phase.Loading, message = "Loading the offline recogniser…") }
            warmFinalPass()
            if (!offlineWarm()) {
                fail("The offline recogniser could not be loaded. Check the downloaded files and free memory.")
                return
            }
            _state.update { state ->
                if (state.phase == Phase.Loading) state.copy(phase = Phase.Ready, message = null) else state
            }
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

        val savedSource = AssistantPreferences.micSourceFlow(context).first()
        // The preference only applies to Bluetooth calls; without a headset
        // the owner's chosen mic source is left alone.
        val preferSetting = AssistantPreferences.preferPhoneMicFlow(context).first()
        val bluetoothConnected = context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.any { it.type in BLUETOOTH_OUTPUT_TYPES } == true
        preferPhoneMic = preferSetting && bluetoothConnected
        bluetoothRouteAvailable = bluetoothConnected
        denoiseWanted = AssistantPreferences.denoiseFlow(context).first()
        captureWanted = AssistantPreferences.saveAudioFlow(context).first()
        finalPass = AssistantPreferences.finalPassFlow(context).first()
        clientNames = runCatching { AsrHotwords.names() }.getOrDefault(emptyList())
        // Prefer-phone-mic captures on the built-in VOICE_RECOGNITION source
        // at 16 kHz while Bluetooth carries the reply as normal media audio.
        val micSource = silentFallbackSource
            ?: if (preferPhoneMic) MicSource.Phone else savedSource
        bargeEnabled = true
        CrashLog.note(
            context,
            TAG,
            "mic=${micSource.name}" +
                when {
                    preferPhoneMic -> " (prefer phone mic)"
                    silentFallbackSource != null -> " (silent-source fallback from ${savedSource.name})"
                    else -> ""
                },
        )
        val record = try {
            withContext(Dispatchers.IO) { createAudioRecord(micSource.androidSource) }
        } catch (error: Exception) {
            Log.e(TAG, "Could not open the microphone", error)
            fail("The microphone could not be opened.")
            return
        }
        audioRecord = record
        // With prefer-phone-mic on, always pin the built-in microphone. With it
        // off, a connected headset is the cleanest mic on a call and is kept;
        // the built-in mic is forced only when there is no headset.
        forcedBuiltInMic = false
        if (micSource == MicSource.Phone || preferPhoneMic) {
            val manager = context.getSystemService(AudioManager::class.java)
            val inputs = manager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
            val headset = inputs.firstOrNull {
                it.type in setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                )
            }
            if (preferPhoneMic || headset == null) {
                val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                if (builtIn != null) {
                    runCatching { record.setPreferredDevice(builtIn) }
                    forcedBuiltInMic = true
                    CrashLog.note(
                        context,
                        TAG,
                        if (preferPhoneMic) {
                            "phone mic forced (prefer phone mic, ${builtIn.productName})"
                        } else {
                            "phone mic forced (preferred ${builtIn.productName})"
                        },
                    )
                } else {
                    CrashLog.note(context, TAG, "no built-in mic found; using the route default")
                }
            } else {
                CrashLog.note(
                    context,
                    TAG,
                    "headset mic kept (${headset.productName}); phone mic not forced",
                )
            }
        }
        // Classify the guard from the route the record actually got, not the
        // saved preference. A forced built-in mic must not inherit the
        // sensitive headset threshold from a headset that is merely paired.
        val onHeadset = !forcedBuiltInMic && record.routedDevice?.type in setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        bargeHasEchoCancel = echoCanceller != null || onHeadset
        bargeUseVad = onHeadset
        bargeGraceMs = if (onHeadset) BARGE_GRACE_MS else BARGE_GRACE_SPEAKER_MS
        bargeRmsThreshold = when {
            onHeadset -> BARGE_RMS
            echoCanceller != null -> BARGE_RMS_AEC_SPEAKER
            else -> BARGE_RMS_NO_AEC
        }
        bargeFramesNeeded = if (onHeadset) {
            BARGE_FRAMES
        } else {
            BARGE_FRAMES_SPEAKER
        }
        CrashLog.note(
            context,
            TAG,
            "barge guard=${if (onHeadset) "headset" else if (echoCanceller != null) "aec-speaker" else "energy"} " +
                "threshold=${"%.3f".format(bargeRmsThreshold)} " +
                "frames=$bargeFramesNeeded grace=${bargeGraceMs}ms",
        )
        listening = true
        paused = false
        _state.update { it.copy(phase = Phase.Listening, message = null, partial = "") }

        activeEngine = if (offlineMode) AsrEngine.Offline else AsrEngine.Nemotron
        thread = if (offlineMode) {
            Thread({ processOffline(record) }, "offline-asr")
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
        listenWanted = false
        if (!listening) return
        listening = false
        resumeAfterPause = false
        // read() is blocking, so stop() is what wakes the thread.
        runCatching { audioRecord?.stop() }
        _state.update { it.copy(phase = Phase.Ready) }
    }

    fun isListening(): Boolean = listening

    /** True when the current route has AEC or a headset echo path. */
    val bargeAvailable: Boolean
        get() = bargeEnabled && bargeHasEchoCancel

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
        runCatching { parakeet?.release() }
        parakeet = null
        runCatching { whisper?.release() }
        whisper = null
        runCatching { whisperCpp?.close() }
        whisperCpp = null
        runCatching { nemotron?.release() }
        nemotron = null
        _state.value = UiState()
    }

    // --- audio loop -------------------------------------------------------

    /**
     * A source can open cleanly yet deliver a flat-zero signal: Samsung does
     * this for VOICE_COMMUNICATION on the loudspeaker, and Bluetooth SCO can
     * do it when the headset does not hand its mic to the app. Fall back to
     * the raw microphone without changing the owner's saved setting.
     */
    private fun healSilentInput(record: AudioRecord) {
        val current = record.audioSource
        if (silentFallbackSource == MicSource.Phone) {
            CrashLog.note(context, TAG, "built-in phone mic is silent too; leaving source as-is")
            return
        }
        // Phone is VOICE_RECOGNITION pinned to the built-in mic: it bypasses
        // both Samsung's silent communication path and a Bluetooth HFP mic
        // that never handed audio to the app.
        silentFallbackSource = MicSource.Phone
        CrashLog.note(
            context,
            TAG,
            "mic source $current delivered no signal; retrying with built-in phone mic",
        )
        Thread({
            listening = false
            runCatching { record.stop() }
            Thread.sleep(500)
            if (!listenWanted) return@Thread
            runCatching { kotlinx.coroutines.runBlocking { startListening() } }
        }, "mic-heal").start()
    }

    @SuppressLint("MissingPermission") // checked by startListening before this point
    private fun process(engine: OnlineRecognizer, record: AudioRecord) {
        val stream = engine.createStream()
        // A Bluetooth HFP/BLE mic is usually quiet and noisy; the software
        // gain lifts it and the VAD threshold stays low enough to hear speech.
        val onBluetooth = record.routedDevice?.type in BLUETOOTH_INPUT_TYPES
        val headsetRoute = !forcedBuiltInMic &&
            (record.routedDevice?.type in HEADSET_ROUTE_TYPES || bluetoothRouteAvailable)
        val fallbackGain = if (gainController == null) SOFTWARE_MIC_GAIN else 1f
        val denoise = openDenoiser()
        val vad = createVad(
            if (headsetRoute) VAD_THRESHOLD_HEADSET else VAD_THRESHOLD_SPEAKER,
        )
        CrashLog.note(
            context,
            TAG,
            if (headsetRoute) {
                "mic HPF+AGC (100Hz, target=0.12, 1.4-2.6x) " +
                    "vad=${"%.2f".format(VAD_THRESHOLD_HEADSET)}"
            } else {
                "mic gain=${"%.1f".format(fallbackGain)}x " +
                    "vad=${"%.2f".format(VAD_THRESHOLD_SPEAKER)}"
            },
        )
        var hasSpeech = false
        var producedText = false
        var quietSignalBeats = 0
        var lastVoiceAt = 0L
        var pausedClean = false
        CrashLog.note(context, TAG, "recognition loop starting (source=${record.audioSource})")
        Log.i(TAG, "Recognition loop starting (source=${record.audioSource})")
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        // Prime the cache-aware encoder with 0.5 s of silence, as sherpa's
        // Nemotron examples do; a cold first chunk decodes poorly.
        runCatching {
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            while (engine.isReady(stream)) engine.decode(stream)
        }
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
            var inputPeak = 0f
            var rawBeatPeak = 0f
            var beatRms = 0f
            var noiseFloor = 0.008f
            var beatAudioMs = 0L
            var beatDecodeMs = 0L
            var speechStartedAt = 0L
            var lastBackchannelAt = 0L
            val checkSilentInput = true
            var silentFrames = 0
            var silentPeak = 0f
            var sawSignal = false
            val wired = record.routedDevice?.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            )
            // AEC or a headset keeps the low backchannel out of the ASR.
            val canBackchannel = wired || bargeHasEchoCancel
            val agc = InputAgc()
            // Amplified audio for the whole utterance: Nemotron streams the
            // partials, the offline recogniser re-decodes this buffer for the final text.
            val utterance = ArrayList<Float>(SAMPLE_RATE * 8)
            val preRoll = java.util.ArrayDeque<Float>()
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
                        agc.reset()
                        utterance.clear()
                        preRoll.clear()
                        pausedClean = true
                        pausePreRoll.clear()
                    }
                    // Keep the last couple of seconds rolling so a barge-in
                    // can replay the start of the interjection.
                    for (index in 0 until read) {
                        pausePreRoll.addLast(buffer[index])
                    }
                    while (pausePreRoll.size > PAUSE_PRE_ROLL_SAMPLES) pausePreRoll.removeFirst()
                    val pausedRaw = FloatArray(read) { buffer[it] / 32768.0f }
                    val pausedSamples = if (headsetRoute) {
                        headsetAudio.process(pausedRaw)
                    } else {
                        boosted(pausedRaw, fallbackGain)
                    }
                    vad?.acceptWaveform(pausedSamples)
                    val bargeVoiced = vad?.isSpeechDetected() == true
                    if (maybeBargeIn(buffer, read, bargeVoiced)) {
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
                val raw = FloatArray(read) { buffer[it] / 32768.0f }
                // A phone mic is denoised as it arrives. A Bluetooth headset arrives
                // too quiet for the denoiser to tell speech from noise, so it is
                // levelled first and denoised afterwards (below).
                val heard = if (headsetRoute) raw else (denoise?.process(raw) ?: raw)
                var framePeak = 0f
                var frameEnergy = 0.0
                for (sample in raw) {
                    framePeak = maxOf(framePeak, kotlin.math.abs(sample))
                    frameEnergy += sample * sample
                }
                rawBeatPeak = maxOf(rawBeatPeak, framePeak)
                inputPeak = maxOf(inputPeak * 0.985f, framePeak)
                if (framePeak > 0.0025f) sawSignal = true
                if (checkSilentInput && !sawSignal && !hasSpeech) {
                    silentFrames++
                    silentPeak = maxOf(silentPeak, framePeak)
                    // 2.5 s of a digitally flat input is not a quiet room; it
                    // is a route that never delivered the mic.
                    if (silentFrames >= 25 && silentPeak < 0.0025f) {
                        healSilentInput(record)
                        return
                    }
                } else {
                    silentFrames = 0
                    silentPeak = 0f
                }
                val frameRms = if (denoise != null) rmsOf(heard) else sqrt(frameEnergy / raw.size).toFloat()
                beatRms = maxOf(beatRms, frameRms)
                // AGC, not a step ladder: it follows the voice instead of the
                // quietest recent frame, and never attenuates loud speech.
                val samples = if (headsetRoute) {
                    val leveled = headsetAudio.process(raw)
                    denoise?.process(leveled) ?: leveled
                } else {
                    agc.process(boosted(heard, fallbackGain))
                }
                // On a headset the energy gate must see the levelled audio: a quiet
                // headset's raw level sits under the speech threshold.
                val gateRms = if (headsetRoute) rmsOf(samples) else frameRms
                vad?.acceptWaveform(samples)
                val vadVoiced = vad?.isSpeechDetected() == true
                // Track the noise floor: fast down, and quick up only while the
                // VAD hears no voice outside a turn, so a steady engine or road
                // rumble settles into the floor instead of reading as speech.
                noiseFloor = NoiseFloorTracker.next(noiseFloor, gateRms, vadVoiced, hasSpeech)
                // Silero misses quiet speech; raw RMS does not. Either is
                // enough to open a turn, so a quiet sentence still endpoints.
                val speechFloor = maxOf(RMS_SPEECH_THRESHOLD, noiseFloor * 2.2f)
                val voiced = vadVoiced || gateRms >= speechFloor
                if (voiced) {
                    val now = SystemClock.elapsedRealtime()
                    if (speechStartedAt == 0L) speechStartedAt = now
                    // A quiet noise of agreement only on an echo-controlled
                    // route, after a longer multi-clause turn has formed.
                    val partialWords = _state.value.partial
                        .split(Regex("\\s+"))
                        .count { it.isNotBlank() }
                    if (canBackchannel && partialWords >= 6 &&
                        now - speechStartedAt >= 3_500 &&
                        now - lastBackchannelAt >= 4_500
                    ) {
                        lastBackchannelAt = now
                        onBackchannel?.invoke()
                    }
                    if (!hasSpeech) {
                        // Keep the syllable before the detector fired.
                        while (preRoll.isNotEmpty()) utterance.add(preRoll.removeFirst())
                    }
                    hasSpeech = true
                    lastVoiceAt = now
                } else {
                    speechStartedAt = 0L
                }
                val feedStarted = SystemClock.elapsedRealtimeNanos()
                val text = feed(engine, stream, samples)
                beatDecodeMs += (SystemClock.elapsedRealtimeNanos() - feedStarted) / 1_000_000
                beatAudioMs += read.toLong() * 1000 / SAMPLE_RATE
                if (text.isNotEmpty()) producedText = true

                if (hasSpeech) {
                    for (sample in samples) utterance.add(sample)
                } else {
                    for (sample in samples) {
                        preRoll.addLast(sample)
                        while (preRoll.size > PRE_ROLL_SAMPLES) preRoll.removeFirst()
                    }
                }

                // No recogniser endpoint: NeMo's own rule resets mid-sentence
                // on quiet audio. The VAD/energy silence is authoritative,
                // but the wait adapts to the words in the live partial: a
                // crisp question gets a quick turn, a trailing "and…" gets
                // room to finish while driving.
                val quietFor = SystemClock.elapsedRealtime() - lastVoiceAt
                val waitMs = SmartEndpointing.waitMs(text, onBluetooth, noiseFloor)
                val endpoint = hasSpeech && !voiced && quietFor >= waitMs
                val overflow = utterance.size >= MAX_UTTERANCE_SAMPLES
                if (endpoint) {
                    CrashLog.note(
                        context,
                        TAG,
                        "smart endpoint quiet=${quietFor}ms wait=${waitMs}ms " +
                            "text=${text.take(80)}",
                    )
                }
                if (endpoint || overflow) {
                    // Nemotron gives the live partials; the offline recogniser, decoding the
                    // same buffered audio, gives the final sentence. That is the
                    // hybrid: streaming feel without the NeMo low-energy misses.
                    val heard = TranscriptCleanup.clean(text)
                    val refined = TranscriptCleanup.clean(decodeSecondLook(utterance, heard))
                    // Neither recogniser is always right, so the one that reads as
                    // something the owner could have said wins (a command the rules
                    // know, a client, a day, a time, a length); a tie keeps the
                    // streaming one, and a blank one always yields.
                    val finalText = TranscriptChooser.choose(heard, refined, clientNames)
                    CrashLog.note(
                        context,
                        TAG,
                        "endpoint: nemotron=${text.take(120)} offline=${refined.take(120)} " +
                            "chosen=${if (finalText == heard.trim()) "streaming" else "offline"}",
                    )
                    if (captureWanted) {
                        CallAudio.save(
                            context,
                            utterance.toFloatArray(),
                            "${routeNote(record)}\nstreaming=$text\noffline=$refined\nchosen=$finalText\n",
                        )
                    }
                    engine.reset(stream)
                    if (finalText.isNotEmpty()) {
                        producedText = true
                        publish(finalText)
                        // Decoding held the reader; drop the audio it queued so
                        // the next turn cannot start with stale words.
                        runCatching {
                            record.stop()
                            record.startRecording()
                        }
                    }
                    utterance.clear()
                    preRoll.clear()
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
                            "src=${record.audioSource} rec=${record.recordingState} " +
                            "agc=${"%.1f".format(agc.gainDb)}dB " +
                            "rms=${"%.3f".format(beatRms)} " +
                            "floor=${"%.3f".format(noiseFloor)} " +
                            "rtf=${"%.2f".format(if (beatAudioMs > 0) beatDecodeMs.toFloat() / beatAudioMs else 0f)} " +
                            "rawPeak=${"%.2f".format(rawBeatPeak)} $route",
                    )
                    if (activeEngine == AsrEngine.Nemotron && !producedText) {
                        // Count signal-bearing beats, not consecutive ones: the
                        // room dips between words, and resetting on a quiet beat
                        // meant the fallback never fired.
                        if (rawBeatPeak > 0.02f) quietSignalBeats++
                        if (quietSignalBeats >= 3 && offlineRecogniserReady()) {
                            quietSignalBeats = 0
                            CrashLog.note(
                                context,
                                TAG,
                                "Nemotron saw signal but produced no words; restarting on the offline recogniser",
                            )
                            Thread({
                                kotlinx.coroutines.runBlocking {
                                    AssistantPreferences.setAsrEngine(context, AsrEngine.Offline)
                                }
                                // Wake the blocking read, then reopen on the
                                // other engine. The loop's finally releases its
                                // own AudioRecord; give it a moment first.
                                listening = false
                                runCatching { audioRecord?.stop() }
                                Thread.sleep(700)
                                if (!listenWanted) return@Thread
                                runCatching { kotlinx.coroutines.runBlocking { startListening() } }
                                    .onFailure { Log.w(TAG, "Offline restart failed", it) }
                            }, "asr-fallback").start()
                        }
                    }
                    peakLevel = 0f
                    peakPartial = 0
                    rawBeatPeak = 0f
                    beatRms = 0f
                    beatAudioMs = 0L
                    beatDecodeMs = 0L
                }
            }
            finish(engine, stream)
        } catch (error: Exception) {
            Log.e(TAG, "Recognition loop failed", error)
            if (!released) fail(error.message ?: "Speech recognition failed.")
        } finally {
            denoise?.release()
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
     * Offline loop: collect one utterance with Silero VAD, then decode it
     * offline. There are no live partials; the final sentence appears after the
     * endpoint. A 0.4 s pre-roll keeps the first syllable.
     */
    private fun processOffline(record: AudioRecord) {
        val onBluetooth = record.routedDevice?.type in BLUETOOTH_INPUT_TYPES
        val headsetRoute = !forcedBuiltInMic &&
            (record.routedDevice?.type in HEADSET_ROUTE_TYPES || bluetoothRouteAvailable)
        val fallbackGain = if (gainController == null) SOFTWARE_MIC_GAIN else 1f
        val denoise = openDenoiser()
        val vad = createVad(
            if (headsetRoute) VAD_THRESHOLD_HEADSET else VAD_THRESHOLD_SPEAKER,
        )
        CrashLog.note(
            context,
            TAG,
            if (headsetRoute) {
                "offline mic HPF+AGC (100Hz, target=0.12, 1.4-2.6x) " +
                    "vad=${"%.2f".format(VAD_THRESHOLD_HEADSET)}"
            } else {
                "offline mic gain=${"%.1f".format(fallbackGain)}x " +
                    "vad=${"%.2f".format(VAD_THRESHOLD_SPEAKER)}"
            },
        )
        var hasSpeech = false
        var lastVoiceAt = 0L
        var pausedClean = false
        var frames = 0
        val utterance = ArrayList<Float>(SAMPLE_RATE * 4)
        val preRoll = ArrayDeque<Float>()
        val agc = InputAgc()
        var noiseFloor = 0.008f
        var speechStartedAt = 0L
        var lastBackchannelAt = 0L
        val checkSilentInput = true
        var silentFrames = 0
        var silentPeak = 0f
        var sawSignal = false
        val wired = record.routedDevice?.type in setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        CrashLog.note(context, TAG, "offline loop starting (source=${record.audioSource})")
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
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
                        agc.reset()
                        utterance.clear()
                        preRoll.clear()
                        pausedClean = true
                        pausePreRoll.clear()
                    }
                    for (index in 0 until read) {
                        pausePreRoll.addLast(buffer[index])
                    }
                    while (pausePreRoll.size > PAUSE_PRE_ROLL_SAMPLES) pausePreRoll.removeFirst()
                    val pausedRaw = FloatArray(read) { buffer[it] / 32768.0f }
                    val pausedSamples = if (headsetRoute) {
                        headsetAudio.process(pausedRaw)
                    } else {
                        boosted(pausedRaw, fallbackGain)
                    }
                    vad?.acceptWaveform(pausedSamples)
                    val bargeVoiced = vad?.isSpeechDetected() == true
                    if (maybeBargeIn(buffer, read, bargeVoiced)) {
                        // The offline path buffers a whole utterance anyway:
                        // seed it with the pre-roll (oldest first) so "Yep"
                        // decodes whole.
                        for (sample in pausePreRoll) utterance.add(sample / 32768.0f)
                        for (sample in pausedSamples) utterance.add(sample)
                        pausePreRoll.clear()
                        hasSpeech = true
                        lastVoiceAt = SystemClock.elapsedRealtime()
                    }
                    continue
                }
                pausedClean = false
                val raw = FloatArray(read) { buffer[it] / 32768.0f }
                // A phone mic is denoised as it arrives. A Bluetooth headset arrives
                // too quiet for the denoiser to tell speech from noise, so it is
                // levelled first and denoised afterwards (below).
                val heard = if (headsetRoute) raw else (denoise?.process(raw) ?: raw)
                var energy = 0.0
                for (sample in raw) energy += sample * sample
                val frameRms = if (denoise != null) rmsOf(heard) else sqrt(energy / raw.size).toFloat()
                val framePeak = kotlin.math.abs(raw.maxOrNull() ?: 0f)
                if (framePeak > 0.0025f) sawSignal = true
                if (checkSilentInput && !sawSignal && !hasSpeech) {
                    silentFrames++
                    silentPeak = maxOf(silentPeak, framePeak)
                    if (silentFrames >= 25 && silentPeak < 0.0025f) {
                        healSilentInput(record)
                        return
                    }
                } else {
                    silentFrames = 0
                    silentPeak = 0f
                }
                val samples = if (headsetRoute) {
                    val leveled = headsetAudio.process(raw)
                    denoise?.process(leveled) ?: leveled
                } else {
                    agc.process(boosted(heard, fallbackGain))
                }
                // On a headset the energy gate must see the levelled audio: a quiet
                // headset's raw level sits under the speech threshold.
                val gateRms = if (headsetRoute) rmsOf(samples) else frameRms
                vad?.acceptWaveform(samples)
                val vadVoiced = vad?.isSpeechDetected() == true
                noiseFloor = NoiseFloorTracker.next(noiseFloor, gateRms, vadVoiced, hasSpeech)
                // Energy fallback: Silero misses quiet phone speech, but the
                // floor keeps a car's road noise from opening a turn.
                val speechFloor = maxOf(RMS_SPEECH_THRESHOLD, noiseFloor * 2.2f)
                val voiced = vadVoiced || gateRms >= speechFloor
                val now = SystemClock.elapsedRealtime()
                if (voiced) {
                    if (speechStartedAt == 0L) speechStartedAt = now
                    if (wired && now - speechStartedAt >= 3_000 &&
                        now - lastBackchannelAt >= 4_500
                    ) {
                        lastBackchannelAt = now
                        onBackchannel?.invoke()
                    }
                } else {
                    speechStartedAt = 0L
                }

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
                    // The offline recogniser has no live partials. End quickly after the
                    // VAD hears silence instead of buffering seconds of dead
                    // air and decoding an empty window.
                    val waitMs = OFFLINE_ENDPOINT_MS +
                        (if (onBluetooth) SmartEndpointing.BLUETOOTH_EXTRA_MS else 0L) +
                        (if (noiseFloor >= SmartEndpointing.NOISY_FLOOR) {
                            SmartEndpointing.NOISY_EXTRA_MS
                        } else {
                            0L
                        })
                    val endpoint = !voiced && now - lastVoiceAt >= waitMs
                    if (endpoint || utterance.size >= MAX_UTTERANCE_SAMPLES) {
                        var utterEnergy = 0.0
                        for (sample in utterance) utterEnergy += sample * sample
                        val utterRms = sqrt(
                            utterEnergy / utterance.size.coerceAtLeast(1),
                        ).toFloat()
                        val speech = trimSpeech(
                            utterance,
                            maxOf(RMS_SPEECH_THRESHOLD, noiseFloor * 1.6f),
                        )
                        if (utterRms < POST_GAIN_MIN_RMS || speech.speechMs < MIN_SPEECH_MS) {
                            CrashLog.note(
                                context,
                                TAG,
                                "offline skipped blank turn " +
                                    "rms=${"%.4f".format(utterRms)} " +
                                    "speech=${speech.speechMs}ms",
                            )
                            utterance.clear()
                            hasSpeech = false
                            vad?.reset()
                            // A click used to mark sawSignal forever, which
                            // disabled the no-mic-sound detector for the rest
                            // of the call. Restart that watch on a blank turn.
                            sawSignal = false
                            silentFrames = 0
                            silentPeak = 0f
                        } else {
                            val text = decodeSecondLook(speech.samples)
                            if (captureWanted) {
                                CallAudio.save(context, speech.samples.toFloatArray(), "${routeNote(record)}\nheard=$text\n")
                            }
                            utterance.clear()
                            hasSpeech = false
                            vad?.reset()
                            if (text.isNotEmpty()) {
                                pauseForSpeech()
                                // Decoding blocked the loop, so the record
                                // buffer holds pre-decode audio. Flush it
                                // before the next listening turn so it cannot
                                // become a phantom line.
                                runCatching {
                                    record.stop()
                                    record.startRecording()
                                }
                                publish(text)
                            }
                        }
                    }
                }

                frames += 1
                if (frames % 100 == 0) {
                    Log.d(TAG, "offline frames=$frames buffered=${utterance.size}")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Offline loop failed", error)
            if (!released) fail(error.message ?: "Speech recognition failed.")
        } finally {
            denoise?.release()
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

    /**
     * The second look at a sentence, in whichever offline recogniser was chosen
     * (Parakeet, Whisper or whisper.cpp). Returns "" when none is chosen or
     * ready, or the clip is too short to be a turn. [streaming] is what the
     * live recogniser heard; null means no live recogniser is running.
     */
    private fun decodeSecondLook(samples: List<Float>, streaming: String? = null): String {
        if (samples.size < SAMPLE_RATE / 3) return ""
        return when (finalPass) {
            FinalPass.WhisperCpp -> {
                if (whisperCpp == null) runCatching { kotlinx.coroutines.runBlocking { ensureWhisperCpp() } }
                whisperCpp?.let { decodeWhisperCpp(it, samples) }.orEmpty()
            }
            FinalPass.Parakeet -> {
                if (parakeet == null) runCatching { kotlinx.coroutines.runBlocking { ensureParakeet() } }
                decodeOnnx(parakeet, "parakeet", samples)
            }
            FinalPass.Whisper -> {
                // The ONNX Whisper takes about three seconds a sentence, so next to
                // a live recogniser it only looks again when that heard almost nothing.
                val missed = streaming == null ||
                    streaming.split(' ').count { it.isNotBlank() } < WHISPER_MIN_WORDS
                if (!missed) return ""
                if (whisper == null) runCatching { kotlinx.coroutines.runBlocking { ensureWhisper() } }
                decodeOnnx(whisper, "whisper", samples)
            }
            FinalPass.Off -> ""
        }
    }

    /** Free RAM, logged beside each decode: a slow one is usually memory pressure, not the model. */
    private fun freeMemoryMb(): Long {
        val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return -1
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun decodeWhisperCpp(engine: WhisperCpp, samples: List<Float>): String {
        val started = SystemClock.elapsedRealtime()
        return synchronized(offlineDecodeLock) {
            val text = try {
                engine.transcribe(samples.toFloatArray())
            } catch (error: Exception) {
                CrashLog.note(context, TAG, "whisper.cpp decode failed: ${error.message}")
                ""
            }
            CrashLog.note(
                context,
                TAG,
                "whisper.cpp ${SystemClock.elapsedRealtime() - started}ms " +
                    "(${samples.size / SAMPLE_RATE}s, ctx=${WhisperCpp.audioContextFor(samples.size / SAMPLE_RATE.toFloat())}): " +
                    text.take(120),
            )
            text
        }
    }

    private fun decodeOnnx(engine: OfflineRecognizer?, label: String, samples: List<Float>): String {
        if (engine == null) return ""
        if (samples.isEmpty()) return ""
        val started = SystemClock.elapsedRealtime()
        return synchronized(offlineDecodeLock) {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples.toFloatArray(), SAMPLE_RATE)
                engine.decode(stream)
                val text = engine.getResult(stream).text.trim()
                CrashLog.note(
                    context,
                    TAG,
                    "$label ${SystemClock.elapsedRealtime() - started}ms " +
                        "(${samples.size / SAMPLE_RATE}s, ${freeMemoryMb()}MB free): ${text.take(120)}",
                )
                text
            } catch (error: Exception) {
                CrashLog.note(context, TAG, "$label decode failed: ${error.message}")
                ""
            } finally {
                runCatching { stream.release() }
            }
        }
    }

    /**
     * While the assistant speaks, the recognizer is not fed, but the mic is
     * still read: if the owner talks over it for ~300 ms, playback is cut and
     * the normal loop resumes. Echo cancellation comes from VOICE_COMMUNICATION
     * and AcousticEchoCanceler, so the assistant does not trigger itself.
     */
    private fun maybeBargeIn(
        buffer: ShortArray,
        length: Int,
        vadVoiced: Boolean = false,
    ): Boolean {
        if (!bargeEnabled || length <= 0) return false
        if (SystemClock.elapsedRealtime() - pausedAt < bargeGraceMs) return false
        var energy = 0.0
        for (index in 0 until length) {
            val sample = buffer[index] / 32768.0
            energy += sample * sample
        }
        val rms = sqrt(energy / length)
        // With AEC/headset echo control, Silero can confirm a quieter
        // interruption. Without it, only a clearly louder-than-speaker
        // energy floor counts, so the phone cannot interrupt itself.
        val candidate = if (bargeUseVad) {
            vadVoiced && rms >= bargeRmsThreshold * 0.75f || rms >= bargeRmsThreshold
        } else {
            // Loudspeaker: VAD hears the assistant itself, so require a
            // clearly louder, sustained interruption.
            rms >= bargeRmsThreshold
        }
        if (candidate) {
            if (bargeFrames == 0) onBargeCandidate?.invoke()
            if (++bargeFrames >= bargeFramesNeeded) {
                bargeFrames = 0
                paused = false
                resumeAfterPause = false
                if (captureInterjections) interjectionArmed = true
                CrashLog.note(
                    context,
                    TAG,
                    "barge-in (rms=${"%.3f".format(rms)} vad=$vadVoiced " +
                        "threshold=${"%.3f".format(bargeRmsThreshold)} frames=$bargeFramesNeeded)",
                )
                onBargeIn?.invoke()
                return true
            }
        } else {
            if (bargeFrames > 0) onBargeAbort?.invoke()
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
     * Bluetooth SCO/BLE mic audio arrives quiet because the platform's AGC is
     * often unavailable. Lift it before VAD and the recogniser see it, with a
     * hard clamp so a loud transient cannot overflow the float pipeline.
     */
    private fun boosted(samples: FloatArray, gain: Float): FloatArray {
        if (gain == 1f) return samples
        return FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) }
    }

    private data class TrimmedSpeech(val samples: List<Float>, val speechMs: Long)

    /**
     * Trim leading and trailing near-silence in 20 ms frames. The returned
     * speechMs counts only frames above [threshold], so a buffer of clicks or
     * room noise is rejected before the offline recogniser is asked to decode it.
     */
    private fun trimSpeech(samples: List<Float>, threshold: Float): TrimmedSpeech {
        if (samples.isEmpty()) return TrimmedSpeech(emptyList(), 0L)
        val frame = SAMPLE_RATE / 50
        var first = -1
        var last = -1
        var voicedFrames = 0
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + frame, samples.size)
            var energy = 0.0
            for (index in start until end) {
                val sample = samples[index]
                energy += sample * sample
            }
            val rms = sqrt(energy / (end - start)).toFloat()
            if (rms >= threshold) {
                if (first < 0) first = start
                last = end
                voicedFrames++
            }
            start = end
        }
        if (first < 0) return TrimmedSpeech(emptyList(), 0L)
        val pad = SAMPLE_RATE / 10
        val clip = samples.subList(
            maxOf(0, first - pad),
            minOf(samples.size, last + pad),
        )
        val speechMs = voicedFrames.toLong() * frame * 1000L / SAMPLE_RATE
        return TrimmedSpeech(clip, speechMs)
    }

    /**
     * Silero VAD decides when the owner has stopped, instead of relying only
     * on the recognizer's trailing-silence heuristic. Loaded from assets; if
     * it cannot be created, the recognizer's endpoint still ends the turn.
     */
    private fun routeNote(record: AudioRecord): String =
        record.routedDevice?.let { "route=type ${it.type} ${it.productName}" } ?: "route=unknown"

    /** A denoiser for this listening session when the switch is on and the model is installed. */
    private fun openDenoiser(): SpeechDenoiser.Session? {
        if (!denoiseWanted) return null
        val session = SpeechDenoiser.open(context)
        CrashLog.note(context, TAG, "noise suppression ${if (session != null) "on" else "unavailable"}")
        return session
    }

    private fun rmsOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var energy = 0.0
        for (sample in samples) energy += sample * sample
        return sqrt(energy / samples.size).toFloat()
    }

    private fun createVad(threshold: Float = VAD_THRESHOLD_SPEAKER): Vad? {
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
                    threshold = threshold,
                    minSilenceDuration = VAD_MIN_SILENCE_SECONDS,
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
            ?.also {
                CrashLog.note(context, TAG, "silero VAD ready (threshold=${"%.2f".format(threshold)})")
            }
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
        // Sherpa recommends 0.8 s of tail padding for Nemotron: the last
        // chunk is otherwise incomplete and the final words vanish.
        stream.acceptWaveform(FloatArray(SAMPLE_RATE * 4 / 5), SAMPLE_RATE)
        while (engine.isReady(stream)) engine.decode(stream)
        val text = engine.getResult(stream).text.trim()
        if (text.isNotEmpty()) {
            publish(text)
        } else {
            _state.update { it.copy(partial = "") }
        }
    }

    private fun publish(spoken: String) {
        val text = TranscriptCleanup.clean(spoken)
        if (text.isEmpty()) return
        val corrected = AsrHotwords.correct(text, hotwordNames, aggressive = expectClientName)
        if (expectClientName) {
            CrashLog.note(context, TAG, "client-name correction aggressive: $corrected")
        }
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
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceller = runCatching {
                val aec = AcousticEchoCanceler.create(record.audioSessionId) ?: return@runCatching null
                aec.enabled = true
                if (aec.enabled) {
                    aec
                } else {
                    aec.release()
                    null
                }
            }.getOrNull()
            CrashLog.note(
                context,
                TAG,
                "echo canceller=${echoCanceller != null} (source=$source)",
            )
        }
        // AGC helps on the plain microphone (quiet gardens, phones on a desk)
        // and fights the platform suppressor on the communication source, so
        // it follows the source and stays off whenever AEC is active.
        gainController = runCatching {
            AutomaticGainControl.create(record.audioSessionId)
        }.getOrNull()
        val gainWanted = echoCanceller == null &&
            source != MediaRecorder.AudioSource.VOICE_COMMUNICATION
        runCatching { gainController?.enabled = gainWanted }
        CrashLog.note(
            context,
            TAG,
            "agc=${gainWanted && gainController != null} available=${gainController != null}",
        )
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
        const val BARGE_RMS = 0.045
        const val BARGE_FRAMES = 3

        /** A loudspeaker needs a clearly louder, longer interruption. */
        const val BARGE_RMS_AEC_SPEAKER = 0.09
        const val BARGE_RMS_NO_AEC = 0.11
        const val BARGE_FRAMES_SPEAKER = 6
        const val BARGE_GRACE_SPEAKER_MS = 900L

        /**
         * Raw RMS that counts as speech even when Silero says no. Quiet phone
         * audio arrives around -40 dBFS; room noise stays far below this.
         */
        const val RMS_SPEECH_THRESHOLD = 0.008f

        /** Free memory needed before Whisper is loaded; below this the second look is skipped. */
        const val WHISPER_MIN_FREE_MB = 900L
        const val WHISPER_CPP_MIN_FREE_MB = 500L
        const val PARAKEET_MIN_FREE_MB = 1200L
        private const val WHISPER_MIN_WORDS = 3

        /** 250 ms kept before VAD reports speech, so the first word is not clipped. */
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 25 / 100

        /** Fixed lift for a quiet Bluetooth headset or a missing platform AGC. */
        const val SOFTWARE_MIC_GAIN = 2.2f

        /** Silero speech thresholds: headsets are quiet, the speaker is noisy. */
        const val VAD_THRESHOLD_HEADSET = 0.28f
        const val VAD_THRESHOLD_SPEAKER = 0.25f

        /** End a turn 400 ms after the VAD hears silence. */
        const val VAD_MIN_SILENCE_SECONDS = 0.4f
        const val OFFLINE_ENDPOINT_MS = 400L

        /** Reject post-gain buffers that are effectively flat or too short. */
        const val POST_GAIN_MIN_RMS = 0.015f
        const val MIN_SPEECH_MS = 250L

        val BLUETOOTH_INPUT_TYPES = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        val BLUETOOTH_OUTPUT_TYPES = setOf(
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )

        val HEADSET_ROUTE_TYPES = BLUETOOTH_INPUT_TYPES + setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        )

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
