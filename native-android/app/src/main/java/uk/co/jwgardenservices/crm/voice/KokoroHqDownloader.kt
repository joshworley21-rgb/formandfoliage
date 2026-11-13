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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import uk.co.jwgardenservices.crm.core.AppConfig
import uk.co.jwgardenservices.crm.core.CrashLog
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional high-quality Kokoro voice.
 *
 * The bundled model is the int8 quantised one (109 MB). The full fp32 model
 * sounds noticeably more natural but is 310 MB, so it is fetched on demand.
 *
 * Two sources are tried, in order:
 *  1. `model.onnx` straight from the project's public Supabase Storage bucket.
 *     One resumable file, no decompression, real byte progress.
 *  2. The official sherpa-onnx `.tar.bz2`, which is downloaded to the cache
 *     and then unpacked. Used until the bucket is populated.
 *
 * The split download/extract matters on a phone:
 *  - the download has real byte progress, Range resume and a stall watchdog,
 *    instead of a percentage of bytes a bzip2 decoder happened to consume;
 *  - bzip2 of a 350 MB archive is CPU-bound (minutes), so the UI can say
 *    "Extracting" rather than looking frozen at 0%;
 *  - leaving the settings screen does not cancel it: the work runs in an
 *    application-scoped coroutine and the UI only observes [state].
 */
object KokoroHqDownloader {
    private const val TAG = "KokoroHq"

    /** Populated by `scripts/upload-kokoro-fp32.sh`. */
    private val DIRECT_URL: String =
        "${AppConfig.supabaseUrl}/storage/v1/object/public/voice-models/kokoro-multi-lang-v1_0/model.onnx"

    private const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

    /** Compressed archive size; the CDN omits Content-Length often enough not to trust it. */
    const val ARCHIVE_BYTES = 349_886_476L

    /** `model.onnx` itself. Anything else is a broken install. */
    const val MODEL_BYTES = 325_560_556L
    const val MIN_BYTES = 300L * 1024 * 1024

    /** No payload for this long means the connection is dead, not merely slow. */
    private const val STALL_TIMEOUT_MS = 60_000L

    /** Never start a download that cannot also hold the extracted model. */
    private const val FREE_SPACE_MARGIN = 80L * 1024 * 1024

    enum class Phase {
        Downloading,
        Extracting,
    }

    sealed interface State {
        data object Idle : State

        /** [done] and [total] are bytes for the current phase. */
        data class Working(val phase: Phase, val done: Long, val total: Long) : State {
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

    /** The in-flight HTTP call, so [cancel] can unblock a socket read. */
    @Volatile
    private var activeCall: okhttp3.Call? = null

    fun modelFile(context: Context): File =
        File(context.filesDir, "kokoro-hq/model.onnx")

    /** Half-written model, kept between attempts so a dropped connection resumes. */
    private fun modelPartial(context: Context): File =
        File(context.filesDir, "kokoro-hq/model.onnx.part")

    /** Half-downloaded archive, used only when the direct file is unavailable. */
    fun archiveFile(context: Context): File =
        File(context.cacheDir, "kokoro-hq/kokoro-multi-lang-v1_0.tar.bz2.part")

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() >= MIN_BYTES
    }

    /** Bytes already saved from an interrupted attempt, for the "Resume" label. */
    fun savedBytes(context: Context): Long =
        maxOf(archiveFile(context).length(), modelPartial(context).length()).coerceAtLeast(0L)

    fun isRunning(): Boolean = job?.isActive == true

    /**
     * Start (or resume) the download and extraction. Safe to call repeatedly;
     * the second call is ignored while work is in flight.
     */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                downloadAndExtract(appContext)
                _state.value = State.Done
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A user cancel surfaces as an IOException from OkHttp; do not
                // turn that into a visible failure.
                if (!isActive) return@launch
                CrashLog.note(appContext, TAG, "download failed: ${error.message}")
                Log.w(TAG, "Kokoro fp32 download failed", error)
                _state.value = State.Failed(friendly(error))
            }
        }
    }

    fun cancel() {
        // Cancelling the coroutine is not enough: a blocking socket read only
        // notices when the call itself is cancelled.
        job?.cancel()
        activeCall?.cancel()
        _state.value = State.Idle
    }

    /** Forget a finished/failed outcome so the screen falls back to its idle text. */
    fun clearResult() {
        if (job?.isActive != true) {
            job = null
            _state.value = State.Idle
        }
    }

    /** Remove the installed model and any half-downloaded files. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        job?.cancelAndJoin()
        job = null
        modelFile(context).delete()
        modelPartial(context).delete()
        archiveFile(context).delete()
        _state.value = State.Idle
    }

    // --- work -------------------------------------------------------------

    private suspend fun downloadAndExtract(context: Context) {
        val model = modelFile(context)
        model.parentFile?.mkdirs()
        val partial = modelPartial(context)

        if (downloadDirect(context, model, partial)) {
            archiveFile(context).delete()
            install(context, model)
            return
        }

        val archive = archiveFile(context)
        archive.parentFile?.mkdirs()
        requireFreeSpace(
            context,
            (ARCHIVE_BYTES - archive.length()).coerceAtLeast(0) + MODEL_BYTES + FREE_SPACE_MARGIN,
        )

        val have = archive.length()
        if (have < ARCHIVE_BYTES) {
            downloadArchive(context, archive, have)
        }
        if (archive.length() < ARCHIVE_BYTES) {
            throw IOException("The archive stopped early (${archive.length()} of $ARCHIVE_BYTES bytes).")
        }

        _state.value = State.Working(Phase.Extracting, 0, archive.length())
        extract(context, archive, model)
        archive.delete()
        partial.delete()
        install(context, model)
    }

    private fun install(context: Context, model: File) {
        CrashLog.note(context, TAG, "fp32 model installed (${model.length() / 1048576} MB)")
        Log.i(TAG, "Kokoro fp32 installed at $model")
    }

    private fun requireFreeSpace(context: Context, needed: Long) {
        val usable = context.filesDir.usableSpace
        if (usable in 1 until needed) {
            throw IOException(
                "Not enough free space: about ${needed / 1048576} MB needed, " +
                    "${usable / 1048576} MB available.",
            )
        }
    }

    /**
     * Try the Storage bucket first. Returns false only when the object is not
     * there (404/403), which is the signal to fall back to the GitHub archive.
     * A dropped connection keeps the `.part` file so the next attempt resumes.
     */
    private suspend fun downloadDirect(context: Context, model: File, partial: File): Boolean {
        val have = partial.length()
        if (have >= MODEL_BYTES) {
            finishModel(partial, model)
            return true
        }
        requireFreeSpace(context, MODEL_BYTES - have + FREE_SPACE_MARGIN)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(DIRECT_URL).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        _state.value = State.Working(Phase.Downloading, have, MODEL_BYTES)
        val call = client.newCall(request)
        activeCall = call
        var lastByteAt = System.currentTimeMillis()
        var lastReportAt = 0L
        val jobContext = currentCoroutineContext()

        val watchdog = scope.launch {
            while (isActive) {
                delay(5_000)
                if (System.currentTimeMillis() - lastByteAt > STALL_TIMEOUT_MS) {
                    CrashLog.note(context, TAG, "no data for ${STALL_TIMEOUT_MS / 1000}s; restarting the call")
                    call.cancel()
                    return@launch
                }
            }
        }

        var available = true
        try {
            call.execute().use { response ->
                if (response.code == 404 || response.code == 403 || response.code == 400) {
                    // The bucket has not been populated for this project yet.
                    CrashLog.note(context, TAG, "no direct model (HTTP ${response.code}); using the archive")
                    available = false
                    return@use
                }
                val appending = response.code == 206 && have > 0
                if (!response.isSuccessful) {
                    throw IOException("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("The download returned no body")
                var written = if (appending) have else 0L
                if (!appending) partial.delete()

                val counting = object : FilterInputStream(body.byteStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val read = super.read(buffer, offset, length)
                        if (read > 0) {
                            written += read
                            lastByteAt = System.currentTimeMillis()
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt > 200 || written >= MODEL_BYTES) {
                                lastReportAt = now
                                _state.value = State.Working(
                                    Phase.Downloading,
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
                        // Check cancellation between reads: the socket does not
                        // care about coroutine state.
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

        if (!available) {
            partial.delete()
            return false
        }
        val size = partial.length()
        if (size < MODEL_BYTES) {
            throw IOException("The download stopped at ${size / 1048576} MB. Try again to resume.")
        }
        finishModel(partial, model)
        return true
    }

    private fun finishModel(partial: File, model: File) {
        if (!partial.renameTo(model)) {
            partial.copyTo(model, overwrite = true)
            partial.delete()
        }
    }

    /**
     * Download the release archive with a Range header so an interrupted
     * attempt resumes. Emits byte progress against the known archive size,
     * not against whatever Content-Length the CDN happens to send.
     */
    private suspend fun downloadArchive(context: Context, target: File, have: Long) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(ARCHIVE_URL).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        _state.value = State.Working(Phase.Downloading, have, ARCHIVE_BYTES)
        val call = client.newCall(request)
        activeCall = call
        var lastByteAt = System.currentTimeMillis()
        var lastReportAt = 0L
        val jobContext = currentCoroutineContext()

        val watchdog = scope.launch {
            while (isActive) {
                delay(5_000)
                if (System.currentTimeMillis() - lastByteAt > STALL_TIMEOUT_MS) {
                    CrashLog.note(context, TAG, "no data for ${STALL_TIMEOUT_MS / 1000}s; restarting the call")
                    call.cancel()
                    return@launch
                }
            }
        }

        try {
            call.execute().use { response ->
                // A server that ignores Range answers 200 from the first byte.
                val appending = response.code == 206 && have > 0
                if (!response.isSuccessful) {
                    throw IOException("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("The download returned no body")
                var written = if (appending) have else 0L
                if (!appending) target.delete()

                val counting = object : FilterInputStream(body.byteStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val read = super.read(buffer, offset, length)
                        if (read > 0) {
                            written += read
                            lastByteAt = System.currentTimeMillis()
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt > 200 || written >= ARCHIVE_BYTES) {
                                lastReportAt = now
                                _state.value = State.Working(
                                    Phase.Downloading,
                                    written.coerceAtMost(ARCHIVE_BYTES),
                                    ARCHIVE_BYTES,
                                )
                            }
                        }
                        return read
                    }
                }

                java.io.FileOutputStream(target, appending).use { output ->
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

        val size = target.length()
        if (size < ARCHIVE_BYTES) {
            throw IOException("The download stopped at ${size / 1048576} MB. Try again to resume.")
        }
    }

    /** Decompress just far enough to write out `model.onnx`, reporting archive bytes read. */
    private suspend fun extract(context: Context, archive: File, model: File) = withContext(Dispatchers.IO) {
        val partial = File(model.parentFile, "model.onnx.part")
        partial.delete()
        val total = archive.length()
        val jobContext = currentCoroutineContext()

        try {
            var found = false
            archive.inputStream().use { raw ->
                val counting = CountingStream(raw, total) { read ->
                    _state.value = State.Working(Phase.Extracting, read.coerceAtMost(total), total)
                }
                BZip2CompressorInputStream(counting, true).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            jobContext.ensureActive()
                            if (!entry.isDirectory && entry.name.endsWith("/model.onnx")) {
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(256 * 1024)
                                    while (true) {
                                        jobContext.ensureActive()
                                        val count = tar.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                                found = true
                                break
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            if (!found) throw IOException("model.onnx was not in the Kokoro archive")
            _state.value = State.Working(Phase.Extracting, total, total)

            val extracted = partial.length()
            if (extracted < MIN_BYTES) {
                throw IOException("The extracted model looks incomplete (${extracted / 1048576} MB).")
            }
            if (extracted != MODEL_BYTES) {
                CrashLog.note(context, TAG, "model.onnx is $extracted bytes, expected $MODEL_BYTES")
            }
            finishModel(partial, model)
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    private fun friendly(error: Exception): String = when {
        error.message.isNullOrBlank() -> "The download failed. Try again to resume."
        else -> error.message!!
    }

    /**
     * Counts bytes pulled from [delegate] and reports at most twice a second.
     *
     * Both read methods matter. commons-compress pulls bzip2's bit stream one
     * byte at a time, so a wrapper that only overrides the bulk read reports
     * nothing at all while a 350 MB archive is decompressed — the unpacking
     * bar sits at 0% while the phone is working hard.
     */
    private class CountingStream(
        private val delegate: java.io.InputStream,
        private val total: Long,
        private val onProgress: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var seen = 0L
        private var sinceCheck = 0
        private var lastReport = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) {
                seen += 1
                if (++sinceCheck >= 4096) {
                    sinceCheck = 0
                    maybeReport()
                }
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count > 0) {
                seen += count
                maybeReport()
            }
            return count
        }

        private fun maybeReport() {
            val now = System.currentTimeMillis()
            if (now - lastReport >= 500 || seen >= total) {
                lastReport = now
                onProgress(seen)
            }
        }

        override fun available(): Int = delegate.available()

        override fun close() = delegate.close()
    }
}
