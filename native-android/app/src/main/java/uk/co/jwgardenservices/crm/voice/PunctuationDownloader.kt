package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * The optional English punctuation model: sherpa-onnx's streaming
 * punctuation CNN-BiLSTM, int8 (Apache 2.0).
 *
 * The recogniser emits unpunctuated lowercase words. This model adds commas,
 * full stops and capital letters to the final sentence, which is what makes a
 * dictated client note read like English, and what lets the multi-command
 * splitter see real sentence boundaries.
 *
 * About 7.6 MB on disk and a few milliseconds per sentence. The size and
 * licence come from the sherpa-onnx export; the files are the unmodified
 * two-file copy published on Hugging Face.
 */
object PunctuationDownloader {
    private const val TAG = "PunctuationDl"

    const val MODEL_DIR = "punct-en-2024-08-06"
    private const val BASE =
        "https://huggingface.co/eng-james-miller/sherpa-onnx-online-punct-en-2024-08-06/resolve/main/"

    /** Exact sizes let a half file be detected and resumed without a HEAD request. */
    private val FILES: Map<String, Long> = linkedMapOf(
        "model.int8.onnx" to 7_490_500L,
        "bpe.vocab" to 149_430L,
    )

    const val TOTAL_BYTES = 7_639_930L

    private const val STALL_TIMEOUT_MS = 60_000L
    private const val FREE_SPACE_MARGIN = 40L * 1024 * 1024

    sealed interface State {
        data object Idle : State
        data class Working(val done: Long, val total: Long) : State {
            val progress: Float
                get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
        data class Failed(val message: String) : State
        data object Done : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var activeCall: okhttp3.Call? = null

    fun dir(context: Context): File = File(context.filesDir, MODEL_DIR)

    fun isInstalled(context: Context): Boolean {
        val dir = dir(context)
        return FILES.all { (name, size) ->
            File(dir, name).let { it.isFile && it.length() == size }
        }
    }

    fun savedBytes(context: Context): Long {
        val dir = dir(context)
        return FILES.keys.sumOf { name ->
            File(dir, name).length().coerceAtLeast(0) +
                File(dir, "$name.part").length().coerceAtLeast(0)
        }.coerceAtMost(TOTAL_BYTES)
    }

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                download(appContext)
                if (!isInstalled(appContext)) throw IOException("The punctuation files are incomplete.")
                _state.value = State.Done
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isActive) return@launch
                CrashLog.note(appContext, TAG, "download failed: ${error.message}")
                Log.w(TAG, "Punctuation download failed", error)
                _state.value = State.Failed(error.message ?: "The download failed.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        activeCall?.cancel()
        _state.value = State.Idle
    }

    fun clearResult() {
        if (job?.isActive != true) {
            job = null
            _state.value = State.Idle
        }
    }

    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        job?.cancelAndJoin()
        job = null
        dir(context).deleteRecursively()
        Punctuator.release()
        _state.value = State.Idle
    }

    private suspend fun download(context: Context) {
        val dir = dir(context)
        dir.mkdirs()

        var completed = 0L
        for ((name, size) in FILES) {
            completed += downloadFile(context, dir, name, size, completed)
        }
        if (completed < TOTAL_BYTES) {
            throw IOException("The download stopped early. Try again to resume.")
        }
    }

    private suspend fun downloadFile(
        context: Context,
        dir: File,
        name: String,
        size: Long,
        completed: Long,
    ): Long {
        val target = File(dir, name)
        if (target.isFile && target.length() == size) return size

        val partial = File(dir, "$name.part")
        val have = partial.length().coerceAtMost(size)
        val stillNeeded = ((TOTAL_BYTES - completed - have).coerceAtLeast(0)) + FREE_SPACE_MARGIN
        if (context.filesDir.usableSpace in 1 until stillNeeded) {
            throw IOException("Not enough free space: about ${stillNeeded / 1048576} MB needed.")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(BASE + name).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        _state.value = State.Working(completed + have, TOTAL_BYTES)
        val call = client.newCall(request)
        activeCall = call
        var lastByteAt = System.currentTimeMillis()
        var lastReportAt = 0L
        val jobContext = currentCoroutineContext()

        val watchdog = scope.launch {
            while (isActive) {
                delay(5_000)
                if (System.currentTimeMillis() - lastByteAt > STALL_TIMEOUT_MS) {
                    CrashLog.note(context, TAG, "no data for 60s; restarting $name")
                    call.cancel()
                    return@launch
                }
            }
        }

        try {
            call.execute().use { response ->
                val appending = response.code == 206 && have > 0
                if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IOException("The download returned no body")
                var written = if (appending) have else 0L
                if (!appending) partial.delete()

                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val read = super.read(buffer, offset, length)
                        if (read > 0) {
                            written += read
                            lastByteAt = System.currentTimeMillis()
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt > 200 || written >= size) {
                                lastReportAt = now
                                _state.value = State.Working(
                                    (completed + written).coerceAtMost(TOTAL_BYTES),
                                    TOTAL_BYTES,
                                )
                            }
                        }
                        return read
                    }
                }

                java.io.FileOutputStream(partial, appending).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        jobContext.ensureActive()
                        val read = counting.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            watchdog.cancel()
            if (activeCall === call) activeCall = null
        }

        if (partial.length() < size) {
            throw IOException("$name stopped early. Try again to resume.")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return size
    }
}

/**
 * Adds punctuation and truecase to a finished sentence. The engine is loaded
 * once, only when the model is installed, and every call degrades to the raw
 * transcript if anything goes wrong — punctuation is a polish, never a
 * prerequisite for acting on a command.
 */
object Punctuator {
    @Volatile
    private var engine: com.k2fsa.sherpa.onnx.OnlinePunctuation? = null

    private val lock = Any()

    fun isReady(context: Context): Boolean = PunctuationDownloader.isInstalled(context)

    /** Load in the background when a call starts, so the first sentence is fast. */
    suspend fun warm(context: Context) {
        if (!PunctuationDownloader.isInstalled(context)) return
        withContext(Dispatchers.IO) { engine ?: build(context) }
    }

    fun punctuate(context: Context, text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        if (!PunctuationDownloader.isInstalled(context)) return text
        val model = engine ?: build(context) ?: return text
        return runCatching { model.addPunctuation(trimmed) }
            .getOrElse { text }
    }

    fun release() {
        synchronized(lock) {
            runCatching { engine?.release() }
            engine = null
        }
    }

    private fun build(context: Context): com.k2fsa.sherpa.onnx.OnlinePunctuation? =
        synchronized(lock) {
            engine?.let { return it }
            runCatching {
                val dir = PunctuationDownloader.dir(context)
                com.k2fsa.sherpa.onnx.OnlinePunctuation(
                    config = com.k2fsa.sherpa.onnx.OnlinePunctuationConfig(
                        model = com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig(
                            cnnBilstm = File(dir, "model.int8.onnx").absolutePath,
                            bpeVocab = File(dir, "bpe.vocab").absolutePath,
                            numThreads = 1,
                            provider = "cpu",
                        ),
                    ),
                )
            }.onFailure {
                CrashLog.note(context, "Punctuator", "could not load: ${it.message}")
            }.getOrNull()
                ?.also { engine = it }
        }
}
