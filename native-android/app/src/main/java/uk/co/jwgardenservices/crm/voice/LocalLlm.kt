package uk.co.jwgardenservices.crm.voice

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * Free, fully local "thinking".
 *
 * A 0.5B instruct model is enough for short factual/conversational answers
 * without a cloud key. It is deliberately not loaded at startup: the first
 * question that needs it pays a one-off load, and it is closed again after a
 * minute idle so Nemotron/Moonshine keep the RAM.
 */
object LocalLlm {
    private const val TAG = "LocalLlm"
    private const val DIR = "local-llm"
    private const val FILE = "qwen3-0.6b-int4.litertlm"
    private const val URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/" +
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    const val BYTES = 347_251_840L
    private const val IDLE_CLOSE_MS = 300_000L
    private const val MIN_FREE_MB = 900L

    sealed interface State {
        data object Idle : State
        data class Working(val done: Long, val total: Long) : State {
            val progress: Float
                get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var idleJob: Job? = null
    private var engine: Engine? = null
    private val loadLock = Any()

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)
    fun isInstalled(context: Context): Boolean = file(context).let {
        it.isFile && it.length() == BYTES
    }
    fun savedBytes(context: Context): Long =
        file(context).length().coerceIn(0L, BYTES)

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            try {
                download(app)
                _state.value = State.Done
            } catch (error: Exception) {
                CrashLog.note(app, TAG, "download failed: ${error.message}")
                _state.value = State.Failed(error.message ?: "Download failed.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun clearResult() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    fun remove(context: Context) {
        cancel()
        release()
        file(context).delete()
        _state.value = State.Idle
    }

    suspend fun generate(
        context: Context,
        system: String,
        question: String,
        maxTokens: Int = 80,
        temperature: Double = 0.4,
    ): String? = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        // A new question cancels the pending idle close; otherwise the timer
        // could shut the engine down mid-generation.
        idleJob?.cancel()
        idleJob = null
        if (!isInstalled(app)) return@withContext null
        if (!hasHeadroom(app)) {
            CrashLog.note(app, TAG, "not enough memory for local model")
            return@withContext null
        }
        val llm = load(app) ?: return@withContext null

        val answer: String? = runCatching {
            val conversation: com.google.ai.edge.litertlm.Conversation =
                llm.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = temperature,
                        ),
                        maxOutputToken = maxTokens,
                    ),
                )
            try {
                val message = conversation.sendMessage(question)
                message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(" ") { it.text }
            } finally {
                runCatching { conversation.close() }
            }
        }.onFailure {
            CrashLog.note(app, TAG, "generate failed: ${it.message}")
        }.getOrNull()?.trim()
        scheduleIdleClose()
        answer?.takeIf { it.isNotBlank() }
    }

    /**
     * The reply as it is generated, so the first sentence can be spoken while
     * the rest is still being written. Cancelling the collector stops the
     * model. Emits nothing when the model is missing or memory is short.
     */
    fun stream(
        context: Context,
        system: String,
        question: String,
        maxTokens: Int = 100,
    ): Flow<String> = flow {
        val app = context.applicationContext
        idleJob?.cancel()
        idleJob = null
        if (!isInstalled(app)) return@flow
        if (!hasHeadroom(app)) {
            CrashLog.note(app, TAG, "not enough memory for local model")
            return@flow
        }
        val llm = load(app) ?: return@flow
        val conversation: com.google.ai.edge.litertlm.Conversation = llm.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4),
                maxOutputToken = maxTokens,
            ),
        )
        var finished = false
        try {
            conversation.sendMessageAsync(question).collect { message ->
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) emit(text)
            }
            finished = true
        } finally {
            if (!finished) runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
            scheduleIdleClose()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Load in the background while the call is already open, so the first
     * question does not wait ~12 s for the engine. Still call-scoped: it is
     * never loaded at app start, and it closes on the usual idle timer.
     */
    suspend fun warm(context: Context) = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        if (!isInstalled(app) || !hasHeadroom(app)) return@withContext
        idleJob?.cancel()
        idleJob = null
        runCatching { load(app) }
    }

    private fun load(app: Context): Engine? = synchronized(loadLock) {
        engine ?: runCatching {
            val started = System.currentTimeMillis()
            Engine(
                EngineConfig(
                    modelPath = file(app).absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = app.cacheDir.path,
                ),
            ).also {
                it.initialize()
                CrashLog.note(
                    app,
                    TAG,
                    "loaded in ${System.currentTimeMillis() - started} ms",
                )
            }
        }.onFailure {
            CrashLog.note(app, TAG, "load failed: ${it.message}")
        }.getOrNull()?.also { engine = it }
    }

    fun release() {
        idleJob?.cancel()
        idleJob = null
        synchronized(loadLock) {
            runCatching { engine?.close() }
            engine = null
        }
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            release()
        }
    }

    private fun hasHeadroom(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L) >= MIN_FREE_MB
    }

    private suspend fun download(context: Context) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "$FILE.part")
        var have = part.length().coerceAtMost(BYTES)
        _state.value = State.Working(have, BYTES)
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(URL).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val append = response.code == 206 && have > 0
            if (!append) {
                have = 0
                part.delete()
            }
            val body = response.body ?: throw IOException("Empty download")
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = have
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        _state.value = State.Working(done.coerceAtMost(BYTES), BYTES)
                    }
                }
            }
        }
        if (part.length() != BYTES) {
            throw IOException("Download stopped at ${part.length()} of $BYTES bytes.")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        CrashLog.note(context, TAG, "model downloaded")
    }
}
