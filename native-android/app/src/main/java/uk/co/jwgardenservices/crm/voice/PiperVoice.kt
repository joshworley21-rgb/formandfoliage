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
import uk.co.jwgardenservices.crm.core.CrashLog
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Optional Piper (VITS) voices for on-device speech.
 *
 * Kokoro is expressive but heavy: the fp32 model takes seconds to load and to
 * synthesise on a phone. Piper's VITS models are much smaller and quicker, and
 * several British voices are available. They are a free way to audition a
 * different, often less synthetic, sound before paying for anything.
 *
 * Each archive is 20–160 MB and is downloaded once over Wi-Fi, resumably,
 * then unpacked into filesDir/piper/<id>.
 */
/** Which sherpa-onnx TTS config a downloaded pack needs. */
enum class LocalVoiceEngine { Vits, Supertonic, Pocket, Kitten }

enum class PiperVoice(
    val id: String,
    val label: String,
    val bytes: Long,
    val engine: LocalVoiceEngine = LocalVoiceEngine.Vits,
) {
    /** Kyutai Pocket TTS: 100M parameters, CPU, ~200 ms to first audio. */
    Pocket(
        "sherpa-onnx-pocket-tts-int8-2026-01-26",
        "pocket tts — Kyutai, cloned voice (fast)",
        98_336_520,
        LocalVoiceEngine.Pocket,
    ),

    /** The same model at full precision: the best quality, 160 MB. */
    PocketHq(
        "sherpa-onnx-pocket-tts-2026-01-26",
        "pocket tts — Kyutai, cloned voice (best)",
        168_148_625,
        LocalVoiceEngine.Pocket,
    ),

    /** Kitten nano: 15M parameters, the fastest lifelike voice here. */
    KittenNano(
        "kitten-nano-en-v0_8-int8",
        "kitten nano — 8 voices, fastest (int8)",
        31_220_690,
        LocalVoiceEngine.Kitten,
    ),

    Supertonic3(
        "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
        "supertonic 3 — English (10 voices)",
        128_774_318,
        LocalVoiceEngine.Supertonic,
    ),

    /** British Piper voices. Cori high is the best-quality Piper voice. */
    Cori(
        "vits-piper-en_GB-cori-high",
        "cori — British female (highest quality)",
        115_574_061,
        LocalVoiceEngine.Vits,
    ),
    Alan(
        "vits-piper-en_GB-alan-medium-int8",
        "alan — British male (medium)",
        21_103_831,
        LocalVoiceEngine.Vits,
    ),
    Jenny(
        "vits-piper-en_GB-jenny_dioco-medium-int8",
        "jenny — British female (medium)",
        20_950_036,
        LocalVoiceEngine.Vits,
    ),
    ;

    val url: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"
}

object PiperVoiceDownloader {
    private const val TAG = "PiperDownload"

    private val SUPERTONIC_FILES = setOf(
        "text_encoder.int8.onnx",
        "duration_predictor.int8.onnx",
        "vector_estimator.int8.onnx",
        "vocoder.int8.onnx",
        "tts.json",
        "unicode_indexer.bin",
        "voice.bin",
    )
    private const val STALL_TIMEOUT_MS = 60_000L
    private const val FREE_SPACE_MARGIN = 60L * 1024 * 1024

    enum class Phase { Downloading, Extracting }

    sealed interface State {
        data object Idle : State
        data class Working(
            val voice: PiperVoice,
            val phase: Phase,
            val done: Long,
            val total: Long,
        ) : State {
            val progress: Float
                get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
        data class Failed(val voice: PiperVoice, val message: String) : State
        data class Done(val voice: PiperVoice) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var activeCall: okhttp3.Call? = null

    fun dir(context: Context, voice: PiperVoice): File =
        File(context.filesDir, "piper/${voice.id}")

    private fun archive(context: Context, voice: PiperVoice): File =
        File(context.cacheDir, "piper/${voice.id}.tar.bz2.part")

    private val KITTEN_FILES = setOf(
        "model.int8.onnx",
        "voices.bin",
        "tokens.txt",
    )

    fun isInstalled(context: Context, voice: PiperVoice): Boolean =
        hasFiles(dir(context, voice), voice)

    /**
     * True when this voice was packaged into the APK (assets/voices/<id>).
     * Installing it is then a local copy, not a download.
     */
    fun isBundled(context: Context, voice: PiperVoice): Boolean = runCatching {
        context.assets.list("voices/${voice.id}")?.isNotEmpty() == true
    }.getOrDefault(false)

    /** Copy a bundled voice out of the APK into filesDir/piper/<id>. */
    suspend fun installBundled(context: Context, voice: PiperVoice): Boolean =
        withContext(Dispatchers.IO) {
            if (!isBundled(context, voice)) return@withContext false
            val target = dir(context, voice)
            val temp = File(target.parentFile, "${voice.id}.bundled")
            temp.deleteRecursively()
            temp.mkdirs()
            var copied = 0L
            _state.value = State.Working(voice, Phase.Extracting, 0, voice.bytes)
            try {
                copyAssetTree(context, "voices/${voice.id}", temp) { bytes ->
                    copied += bytes
                    _state.value = State.Working(
                        voice,
                        Phase.Extracting,
                        copied.coerceAtMost(voice.bytes),
                        voice.bytes,
                    )
                }
                if (!hasFiles(temp, voice)) {
                    throw IOException("The bundled voice was missing its model files.")
                }
                target.deleteRecursively()
                if (!temp.renameTo(target)) {
                    temp.copyRecursively(target, overwrite = true)
                    temp.deleteRecursively()
                }
                CrashLog.note(context, TAG, "bundled ${voice.id} installed")
                _state.value = State.Working(voice, Phase.Extracting, voice.bytes, voice.bytes)
                true
            } catch (error: Exception) {
                temp.deleteRecursively()
                CrashLog.note(context, TAG, "bundled install failed (${voice.id}): ${error.message}")
                false
            }
        }

    private fun copyAssetTree(
        context: Context,
        assetPath: String,
        destination: File,
        onBytes: (Long) -> Unit,
    ) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                destination.parentFile?.mkdirs()
                destination.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
        } else {
            destination.mkdirs()
            children.forEach { child ->
                copyAssetTree(context, "$assetPath/$child", File(destination, child), onBytes)
            }
        }
    }

    private fun anyOf(dir: File, vararg names: String): Boolean =
        names.any { File(dir, it).isFile }

    /** Whether [dir] already holds every file the engine needs. */
    private fun hasFiles(dir: File, voice: PiperVoice): Boolean = when (voice.engine) {
        LocalVoiceEngine.Supertonic -> SUPERTONIC_FILES.all { File(dir, it).isFile }
        LocalVoiceEngine.Pocket -> {
            // int8 and fp32 packs differ only in the .int8 suffix.
            File(dir, "encoder.onnx").isFile &&
                File(dir, "text_conditioner.onnx").isFile &&
                File(dir, "vocab.json").isFile &&
                File(dir, "token_scores.json").isFile &&
                anyOf(dir, "lm_flow.int8.onnx", "lm_flow.onnx") &&
                anyOf(dir, "lm_main.int8.onnx", "lm_main.onnx") &&
                anyOf(dir, "decoder.int8.onnx", "decoder.onnx")
        }
        LocalVoiceEngine.Kitten -> KITTEN_FILES.all { File(dir, it).isFile } &&
            File(dir, "espeak-ng-data/phontab").isFile
        LocalVoiceEngine.Vits -> {
            // Piper archives name the graph after the voice (en_GB-...onnx);
            // the downloader renames it, bundled assets keep the original.
            File(dir, "tokens.txt").isFile &&
                File(dir, "espeak-ng-data/phontab").isFile &&
                dir.listFiles()?.any { it.isFile && it.extension == "onnx" } == true
        }
    }

    /** Delete packs from voices that are no longer offered. */
    suspend fun removeUnused(context: Context) = withContext(Dispatchers.IO) {
        val keep = PiperVoice.entries.map { it.id }.toSet()
        File(context.filesDir, "piper").listFiles()?.forEach { dir ->
            if (dir.name !in keep) dir.deleteRecursively()
        }
        File(context.cacheDir, "piper").listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".tar.bz2.part").removeSuffix(".tar.bz2")
            if (id !in keep) file.delete()
        }
        // The removed local LLM left a ~500 MB GGUF behind here.
        File(context.filesDir, "models").deleteRecursively()
        // The removed Parakeet experiment left a ~630 MB model folder.
        File(context.filesDir, "parakeet-tdt-0.6b-v2-en").deleteRecursively()
        // The bundled Zipformer was removed; free any extracted copy.
        File(context.filesDir, "sherpa-onnx-streaming-zipformer-en-2023-06-21").deleteRecursively()
        // Phonon voices are left alone: imported custom voices live in the same
        // folder and cannot be told apart from retired catalogue entries.
    }

    /** How much of an interrupted download is already on disk. */
    fun savedBytes(context: Context, voice: PiperVoice): Long =
        archive(context, voice).length().coerceAtLeast(0L)

    fun start(context: Context, voice: PiperVoice) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                if (isBundled(appContext, voice)) {
                    if (!installBundled(appContext, voice)) {
                        throw IOException("The bundled voice was incomplete.")
                    }
                } else {
                    downloadAndExtract(appContext, voice)
                }
                _state.value = State.Done(voice)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isActive) return@launch
                CrashLog.note(appContext, TAG, "download failed (${voice.id}): ${error.message}")
                Log.w(TAG, "Piper download failed", error)
                _state.value = State.Failed(voice, error.message ?: "The download failed.")
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

    suspend fun remove(context: Context, voice: PiperVoice) = withContext(Dispatchers.IO) {
        job?.cancelAndJoin()
        job = null
        dir(context, voice).deleteRecursively()
        archive(context, voice).delete()
        _state.value = State.Idle
    }

    // --- work -------------------------------------------------------------

    private suspend fun downloadAndExtract(context: Context, voice: PiperVoice) {
        val archive = archive(context, voice)
        archive.parentFile?.mkdirs()

        val have = archive.length()
        val needed = (voice.bytes - have).coerceAtLeast(0) + voice.bytes / 2 + FREE_SPACE_MARGIN
        val usable = context.filesDir.usableSpace
        if (usable in 1 until needed) {
            throw IOException(
                "Not enough free space: about ${needed / 1048576} MB needed, " +
                    "${usable / 1048576} MB available.",
            )
        }

        if (have < voice.bytes) {
            download(context, voice, archive, have)
        }
        if (archive.length() < voice.bytes) {
            throw IOException("The download stopped early. Try again to resume.")
        }

        _state.value = State.Working(voice, Phase.Extracting, 0, archive.length())
        extract(context, voice, archive)
        archive.delete()
        CrashLog.note(context, TAG, "${voice.id} installed")
    }

    private suspend fun download(
        context: Context,
        voice: PiperVoice,
        target: File,
        have: Long,
    ) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(voice.url).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()

        _state.value = State.Working(voice, Phase.Downloading, have, voice.bytes)
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
                if (!response.isSuccessful) {
                    throw IOException("Download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("The download returned no body")
                var written = if (appending) have else 0L
                if (!appending) target.delete()

                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val read = super.read(buffer, offset, length)
                        if (read > 0) {
                            written += read
                            lastByteAt = System.currentTimeMillis()
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt > 200 || written >= voice.bytes) {
                                lastReportAt = now
                                _state.value = State.Working(
                                    voice,
                                    Phase.Downloading,
                                    written.coerceAtMost(voice.bytes),
                                    voice.bytes,
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
    }

    /** Unpack model.onnx, tokens.txt and espeak-ng-data into a temp folder. */
    private suspend fun extract(context: Context, voice: PiperVoice, archive: File) =
        withContext(Dispatchers.IO) {
            when (voice.engine) {
                LocalVoiceEngine.Supertonic -> {
                    extractSupertonic(context, voice, archive)
                    return@withContext
                }
                // Pocket is a five-model pipeline and Kitten ships voices.bin
                // beside its model, so both need their own filenames.
                LocalVoiceEngine.Pocket, LocalVoiceEngine.Kitten -> {
                    extractTree(context, voice, archive)
                    return@withContext
                }
                LocalVoiceEngine.Vits -> Unit
            }
            val target = dir(context, voice)
            val temp = File(target.parentFile, "${voice.id}.tmp")
            temp.deleteRecursively()
            temp.mkdirs()
            val total = archive.length()
            val jobContext = currentCoroutineContext()
            var lastReportAt = 0L

            try {
                archive.inputStream().use { raw ->
                    val counting = CountingStream(raw, total) { read ->
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt > 500) {
                            lastReportAt = now
                            _state.value = State.Working(voice, Phase.Extracting, read, total)
                        }
                    }
                    BZip2CompressorInputStream(counting, true).use { bzip ->
                        TarArchiveInputStream(bzip).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                jobContext.ensureActive()
                                val name = entry.name
                                when {
                                    entry.isDirectory -> Unit

                                    name.endsWith(".onnx") -> {
                                        writeEntry(tar, File(temp, "model.onnx"))
                                    }

                                    name.endsWith("/tokens.txt") -> {
                                        writeEntry(tar, File(temp, "tokens.txt"))
                                    }

                                    name.contains("/espeak-ng-data/") -> {
                                        val relative = name.substringAfter("/espeak-ng-data/")
                                        if (relative.isNotBlank()) {
                                            val output = File(temp, "espeak-ng-data/$relative")
                                            output.parentFile?.mkdirs()
                                            writeEntry(tar, output)
                                        }
                                    }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }

                if (!File(temp, "model.onnx").isFile ||
                    !File(temp, "tokens.txt").isFile ||
                    !File(temp, "espeak-ng-data/phontab").isFile
                ) {
                    throw IOException("The voice archive was missing its model files.")
                }

                target.deleteRecursively()
                if (!temp.renameTo(target)) {
                    temp.copyRecursively(target, overwrite = true)
                    temp.deleteRecursively()
                }
                _state.value = State.Working(voice, Phase.Extracting, total, total)
            } catch (error: Exception) {
                temp.deleteRecursively()
                throw error
            }
        }

    /**
     * Multi-file packs must keep the layout the engine expects. Unlike the
     * VITS path — which renames its single model to `model.onnx` — Pocket's
     * five ONNX graphs and Kitten's `voices.bin` are referenced by name.
     */
    private suspend fun extractTree(context: Context, voice: PiperVoice, archive: File) =
        withContext(Dispatchers.IO) {
            val target = dir(context, voice)
            val temp = File(target.parentFile, "${voice.id}.tmp")
            temp.deleteRecursively()
            temp.mkdirs()
            val total = archive.length()
            val jobContext = currentCoroutineContext()
            var lastReportAt = 0L

            try {
                archive.inputStream().use { raw ->
                    val counting = CountingStream(raw, total) { read ->
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt > 500) {
                            lastReportAt = now
                            _state.value = State.Working(voice, Phase.Extracting, read, total)
                        }
                    }
                    BZip2CompressorInputStream(counting, true).use { bzip ->
                        TarArchiveInputStream(bzip).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                jobContext.ensureActive()
                                val name = entry.name
                                // Strip the archive's top-level folder; sample
                                // wavs and licence files are not needed.
                                val relative = name.substringAfter('/', "")
                                val wanted = relative.isNotBlank() &&
                                    !entry.isDirectory &&
                                    !relative.startsWith("test_wavs/") &&
                                    !relative.endsWith("README.md") &&
                                    !relative.startsWith("LICENSE")
                                if (wanted) {
                                    require(!relative.contains("..")) {
                                        "The voice archive had an unsafe path."
                                    }
                                    writeEntry(tar, File(temp, relative))
                                } else {
                                    // Skip the entry's bytes without buffering it.
                                    tar.skip(entry.size)
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }

                if (!hasFiles(temp, voice)) {
                    throw IOException("The voice archive was missing its model files.")
                }

                target.deleteRecursively()
                if (!temp.renameTo(target)) {
                    temp.copyRecursively(target, overwrite = true)
                    temp.deleteRecursively()
                }
                _state.value = State.Working(voice, Phase.Extracting, total, total)
            } catch (error: Exception) {
                temp.deleteRecursively()
                throw error
            }
        }

    /**
     * Supertonic is a multi-file ONNX pipeline (text encoder, duration
     * predictor, vector estimator, vocoder, lexicons and voice styles), not a
     * single VITS model. Keep those files under their own names.
     */
    private suspend fun extractSupertonic(context: Context, voice: PiperVoice, archive: File) {
        val target = dir(context, voice)
        val temp = File(target.parentFile, "${voice.id}.tmp")
        temp.deleteRecursively()
        temp.mkdirs()
        val total = archive.length()
        val jobContext = currentCoroutineContext()
        var lastReportAt = 0L

        try {
            archive.inputStream().use { raw ->
                val counting = CountingStream(raw, total) { read ->
                    val now = System.currentTimeMillis()
                    if (now - lastReportAt > 500) {
                        lastReportAt = now
                        _state.value = State.Working(voice, Phase.Extracting, read, total)
                    }
                }
                BZip2CompressorInputStream(counting, true).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            jobContext.ensureActive()
                            val base = entry.name.substringAfterLast('/')
                            if (!entry.isDirectory && base in SUPERTONIC_FILES) {
                                writeEntry(tar, File(temp, base))
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            val missing = SUPERTONIC_FILES.filterNot { File(temp, it).isFile }
            if (missing.isNotEmpty()) {
                throw IOException("The Supertonic archive was missing: ${missing.joinToString()}")
            }
            target.deleteRecursively()
            if (!temp.renameTo(target)) {
                temp.copyRecursively(target, overwrite = true)
                temp.deleteRecursively()
            }
            _state.value = State.Working(voice, Phase.Extracting, total, total)
        } catch (error: Exception) {
            temp.deleteRecursively()
            throw error
        }
    }

    private fun writeEntry(tar: TarArchiveInputStream, output: File) {
        output.parentFile?.mkdirs()
        output.outputStream().use { stream -> tar.copyTo(stream, 128 * 1024) }
    }

    /**
     * Counts both read methods: bzip2 reads one byte at a time, so a
     * bulk-only counter reports nothing while the archive decompresses.
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
