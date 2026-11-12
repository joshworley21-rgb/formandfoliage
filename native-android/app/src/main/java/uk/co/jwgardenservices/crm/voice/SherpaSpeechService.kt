package uk.co.jwgardenservices.crm.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException

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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Each finished utterance, for callers that react to what was said. */
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<String> = _transcripts.asSharedFlow()

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var listening = false

    /** Set while the confirmation is spoken, so the phone ignores itself. */
    @Volatile
    private var paused = false

    @Volatile
    private var released = false

    /** Last finalised line, so stopping does not repeat a confirmation. */
    @Volatile
    private var lastResult = ""

    /**
     * Open the mic, loading the model from assets the first time. Returns
     * immediately if already listening; never leaves a half-started loop.
     */
    suspend fun startListening() {
        if (released || listening) return

        if (recognizer == null) {
            _state.update { it.copy(phase = Phase.Loading, message = "Loading offline speech model…") }
            try {
                val created = withContext(Dispatchers.IO) { createRecognizer() }
                if (released) {
                    created.release()
                    return
                }
                recognizer = created
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Could not load the sherpa-onnx model", error)
                fail("The offline speech model could not be loaded. Check assets/$MODEL_DIR.")
                return
            }
        }

        if (!hasMicPermission()) {
            fail("Microphone permission is needed to listen.")
            return
        }

        val record = try {
            withContext(Dispatchers.IO) { createAudioRecord() }
        } catch (error: Exception) {
            Log.e(TAG, "Could not open the microphone", error)
            fail("The microphone could not be opened.")
            return
        }
        val engine = recognizer ?: return

        audioRecord = record
        listening = true
        paused = false
        _state.update { it.copy(phase = Phase.Listening, message = null, partial = "") }

        thread = Thread({ process(engine, record) }, "sherpa-asr").apply { start() }
    }

    /** Close the mic; the last words are flushed before the thread ends. */
    fun stopListening() {
        if (!listening) return
        listening = false
        // read() is blocking, so stop() is what wakes the thread.
        runCatching { audioRecord?.stop() }
        _state.update { it.copy(phase = Phase.Ready) }
    }

    fun isListening(): Boolean = listening

    /** Discard microphone frames while the confirmation is spoken. */
    fun pauseForSpeech() {
        paused = true
    }

    fun resumeAfterSpeech() {
        paused = false
    }

    fun clear() {
        _state.update { it.copy(partial = "", finalText = "", message = null) }
    }

    override fun close() {
        released = true
        listening = false
        runCatching { audioRecord?.stop() }
        val worker = thread
        thread = null
        if (worker != null) runCatching { worker.join(2_000) }
        runCatching { recognizer?.release() }
        recognizer = null
        _state.value = UiState()
    }

    // --- audio loop -------------------------------------------------------

    @SuppressLint("MissingPermission") // checked by startListening before this point
    private fun process(engine: OnlineRecognizer, record: AudioRecord) {
        val stream = engine.createStream()
        try {
            record.startRecording()
            val buffer = ShortArray(FRAME_SAMPLES)
            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (paused) continue
                feed(engine, stream, buffer, read)
            }
            finish(engine, stream)
        } catch (error: Exception) {
            Log.e(TAG, "Recognition loop failed", error)
            if (!released) fail(error.message ?: "Speech recognition failed.")
        } finally {
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

    private fun feed(engine: OnlineRecognizer, stream: OnlineStream, buffer: ShortArray, length: Int) {
        val samples = FloatArray(length) { buffer[it] / 32768.0f }
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (engine.isReady(stream)) engine.decode(stream)

        val text = engine.getResult(stream).text.trim()
        if (text.isNotEmpty()) {
            _state.update { it.copy(partial = text) }
        }
        if (engine.isEndpoint(stream)) {
            engine.reset(stream)
            if (text.isNotEmpty()) publish(text)
        }
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
        if (text == lastResult) return
        lastResult = text
        _state.update { it.copy(finalText = text, partial = "") }
        _transcripts.tryEmit(text)
    }

    // --- setup ------------------------------------------------------------

    private fun createRecognizer(): OnlineRecognizer {
        requireModelFiles()
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = ENCODER,
                    decoder = DECODER,
                    joiner = JOINER,
                ),
                tokens = TOKENS,
                numThreads = 2,
                provider = "cpu",
            ),
            // Endpoint on roughly 1.4 s of trailing silence, which is what
            // turns "the speaker stopped" into a finished line on screen.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
                rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
                rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(assetManager = context.assets, config = config)
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
    private fun createAudioRecord(): AudioRecord {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Four frames of slack keeps a slow decode from overrunning the buffer.
        val bytes = maxOf(minBytes, FRAME_SAMPLES * 2 * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord could not be initialised.")
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

        /**
         * Streaming Zipformer (English, 20M parameters, int8) from
         * https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
         * The decoder stays float32, as the model's own int8 instructions say.
         */
        const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
        const val ENCODER = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
        const val JOINER = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS = "$MODEL_DIR/tokens.txt"
    }
}
