package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.util.Log
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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional Whisper small.en (MIT), used as the offline second look at each
 * sentence.
 *
 * Whisper was trained on a great deal of noisy speech, which is why it is the
 * candidate for a van. It is slower: it always processes a 30 second window, so
 * expect a second or more per sentence on a phone, and about 375 MB on disk.
 * The assistant log records how long each sentence took.
 *
 * Files come from the sherpa-onnx export on Hugging Face (int8). The download
 * is resumable and is deliberately not bundled in the APK.
 */
object WhisperDownloader {
    private const val TAG = "WhisperDownload"

    const val MODEL_DIR = "whisper-small-en"
    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main/"

    /** Exact sizes let a half file be detected and resumed without a HEAD request. */
    private val FILES: Map<String, Long> = linkedMapOf(
        "small.en-encoder.int8.onnx" to 112_442_483L,
        "small.en-decoder.int8.onnx" to 262_223_042L,
        "small.en-tokens.txt" to 835_554L,
    )

    const val TOTAL_BYTES = 375_501_079L

    private const val STALL_TIMEOUT_MS = 60_000L
    private const val FREE_SPACE_MARGIN = 80L * 1024 * 1024

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
            File(dir, name).length().coerceAtLeast(0) + File(dir, "$name.part").length().coerceAtLeast(0)
        }.coerceAtMost(TOTAL_BYTES)
    }

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                download(appContext)
                if (!isInstalled(appContext)) throw IOException("The Whisper files are incomplete.")
                _state.value = State.Done
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isActive) return@launch
                CrashLog.note(appContext, TAG, "download failed: ${error.message}")
                Log.w(TAG, "Whisper download failed", error)
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
        val stillNeeded = (TOTAL_BYTES - completed - have).coerceAtLeast(0) + FREE_SPACE_MARGIN
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
                if (!response.isSuccessful) {
                    throw IOException("Download failed: HTTP ${response.code}")
                }
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
            throw IOException("$name stopped at ${partial.length() / 1048576} MB. Try again to resume.")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return size
    }
}
