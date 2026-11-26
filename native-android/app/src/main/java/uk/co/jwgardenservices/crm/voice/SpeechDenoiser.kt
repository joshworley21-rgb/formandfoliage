package uk.co.jwgardenservices.crm.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * Optional noise suppression in front of the recogniser: sherpa-onnx's
 * streaming GTCRN denoiser (about 0.5 MB, runs in a few milliseconds per
 * 100 ms of audio). It is aimed at a van: engine, road and wind noise.
 *
 * It is off by default because a denoiser can also blur speech; the switch is
 * there so it can be compared on a real drive.
 */
object SpeechDenoiser {
    private const val TAG = "SpeechDenoiser"
    private const val DIR = "denoise"
    private const val FILE = "gtcrn_simple.onnx"
    private const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx"

    /** A half-sized or empty download must not count as installed. */
    private const val MIN_BYTES = 200_000L
    private const val SAMPLE_RATE = 16_000

    sealed interface State {
        data object Idle : State
        data object Working : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun modelFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    fun isInstalled(context: Context): Boolean =
        modelFile(context).let { it.isFile && it.length() >= MIN_BYTES }

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = State.Working
        job = scope.launch {
            try {
                download(app)
                _state.value = State.Done
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isActive) return@launch
                CrashLog.note(app, TAG, "download failed: ${error.message}")
                _state.value = State.Failed(error.message ?: "The download failed.")
            }
        }
    }

    fun clearResult() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    fun remove(context: Context) {
        modelFile(context).delete()
        _state.value = State.Idle
    }

    private fun download(context: Context) {
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$FILE.part")
        part.delete()
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            val expected = body.contentLength()
            body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            if (part.length() < MIN_BYTES || (expected > 0 && part.length() != expected)) {
                part.delete()
                throw IOException("The download stopped early.")
            }
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** A denoiser for one listening session, or null when it cannot be used. */
    fun open(context: Context): Session? {
        if (!isInstalled(context)) return null
        return try {
            val config = OnlineSpeechDenoiserConfig(
                OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
                        modelFile(context).absolutePath,
                    ),
                    numThreads = 1,
                ),
            )
            val denoiser = OnlineSpeechDenoiser(null, config)
            if (denoiser.sampleRate != SAMPLE_RATE) {
                denoiser.release()
                CrashLog.note(context, TAG, "unexpected sample rate ${denoiser.sampleRate}")
                null
            } else {
                Session(denoiser)
            }
        } catch (error: Exception) {
            CrashLog.note(context, TAG, "could not start: ${error.message}")
            null
        } catch (error: LinkageError) {
            CrashLog.note(context, TAG, "native library missing: ${error.message}")
            null
        }
    }

    /**
     * Cleans one frame at a time and always returns exactly as many samples
     * as it was given, so nothing downstream has to know it is there. Any
     * failure hands the audio back untouched for the rest of the call.
     */
    class Session internal constructor(private val denoiser: OnlineSpeechDenoiser) {
        private val lock = Any()
        private val fifo = SampleFifo(prime = PRIME_SAMPLES, maxBuffered = MAX_BUFFERED)
        private var open = true

        fun process(samples: FloatArray): FloatArray = synchronized(lock) {
            if (!open || samples.isEmpty()) return samples
            try {
                fifo.exchange(denoiser.run(samples, SAMPLE_RATE).samples, samples.size)
            } catch (error: Exception) {
                open = false
                samples
            }
        }

        fun release() = synchronized(lock) {
            open = false
            runCatching { denoiser.release() }
            Unit
        }

        private companion object {
            /** About 64 ms: covers the denoiser's own frame delay. */
            const val PRIME_SAMPLES = 1_024
            const val MAX_BUFFERED = 8_192
        }
    }
}

/**
 * Hands back exactly the number of samples asked for, however many a
 * frame-based processor produced this time. It starts primed with silence so a
 * short call never runs dry, and drops the oldest audio if it ever backs up.
 */
internal class SampleFifo(private val prime: Int, private val maxBuffered: Int) {
    private val queue = ArrayDeque<Float>()

    init {
        reset()
    }

    fun reset() {
        queue.clear()
        repeat(prime) { queue.addLast(0f) }
    }

    fun exchange(produced: FloatArray, want: Int): FloatArray {
        for (sample in produced) queue.addLast(sample)
        while (queue.size > maxBuffered) queue.removeFirst()
        val out = FloatArray(want)
        val have = min(want, queue.size)
        val pad = want - have
        for (index in 0 until have) out[pad + index] = queue.removeFirst()
        return out
    }
}
