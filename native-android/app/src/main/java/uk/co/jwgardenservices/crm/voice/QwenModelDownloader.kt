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
 * The on-device conversation model: Qwen 2.5 0.5B Instruct, q4_k_m.
 *
 * The phone ships no GGUF, and without one the assistant falls back to
 * keyword rules. This downloads a small one (about 469 MB) over Wi-Fi with
 * the same resumable, byte-counted downloader as the Kokoro voice, so the
 * assistant can actually understand a sentence like "put four hours on the
 * Smiths job yesterday".
 *
 * 0.5B is deliberate: the model's only job is turning a sentence into the
 * intent JSON. Open conversation is left to the cloud fallback, and a bigger
 * model here would cost seconds per command on a phone CPU.
 */
object QwenModelDownloader {
    private const val TAG = "QwenDownload"

    const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val MODEL_BYTES = 491_400_032L
    private const val MIN_BYTES = 450L * 1024 * 1024
    private const val FREE_SPACE_MARGIN = 80L * 1024 * 1024
    private const val STALL_TIMEOUT_MS = 60_000L

    private const val URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/$FILE_NAME"

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

    fun modelFile(context: Context): File = File(context.filesDir, "models/$FILE_NAME")

    private fun partialFile(context: Context): File =
        File(context.filesDir, "models/$FILE_NAME.part")

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_BYTES
    }

    fun savedBytes(context: Context): Long = partialFile(context).length().coerceAtLeast(0L)

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                download(appContext)
                _state.value = State.Done
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isActive) return@launch
                CrashLog.note(appContext, TAG, "model download failed: ${error.message}")
                Log.w(TAG, "Qwen model download failed", error)
                _state.value = State.Failed(error.message ?: "The download failed. Try again to resume.")
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
        modelFile(context).delete()
        partialFile(context).delete()
        _state.value = State.Idle
    }

    private suspend fun download(context: Context) {
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val partial = partialFile(context)
        val have = partial.length()

        if (have >= MODEL_BYTES) {
            install(partial, target)
            return
        }

        val usable = context.filesDir.usableSpace
        val needed = MODEL_BYTES - have + FREE_SPACE_MARGIN
        if (usable in 1 until needed) {
            throw IOException(
                "Not enough free space: about ${needed / 1048576} MB needed, " +
                    "${usable / 1048576} MB available.",
            )
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(URL).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        _state.value = State.Working(have, MODEL_BYTES)
        val call = client.newCall(request)
        activeCall = call
        var lastByteAt = System.currentTimeMillis()
        var lastReportAt = 0L
        val jobContext = currentCoroutineContext()

        val watchdog = scope.launch {
            while (isActive) {
                delay(5_000)
                if (System.currentTimeMillis() - lastByteAt > STALL_TIMEOUT_MS) {
                    CrashLog.note(context, TAG, "no data for 60s; restarting the call")
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
                            if (now - lastReportAt > 200 || written >= MODEL_BYTES) {
                                lastReportAt = now
                                _state.value = State.Working(
                                    written.coerceAtMost(MODEL_BYTES),
                                    MODEL_BYTES,
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

        val size = partial.length()
        if (size < MODEL_BYTES) {
            throw IOException("The download stopped at ${size / 1048576} MB. Try again to resume.")
        }
        install(partial, target)
    }

    private fun install(partial: File, target: File) {
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        Log.i(TAG, "Qwen model installed at $target")
    }
}
