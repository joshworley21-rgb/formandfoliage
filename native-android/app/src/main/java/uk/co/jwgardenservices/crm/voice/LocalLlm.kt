package uk.co.jwgardenservices.crm.voice

import android.app.ActivityManager
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
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
 * A small instruct model is enough for short answers and for labelling a
 * sentence without a cloud key. The owner can also install a larger Qwen3 and
 * switch to it; the small one stays the fallback when memory is short. Nothing
 * is loaded at startup: the first question that needs a model pays a one-off
 * load, and it is closed again after a few idle minutes so the speech models
 * keep the RAM.
 */
object LocalLlm {
    private const val TAG = "LocalLlm"
    private const val DIR = "local-llm"
    private const val IDLE_CLOSE_MS = 300_000L

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
    private val states = LlmModel.entries.associateWith { MutableStateFlow<State>(State.Idle) }
    private val jobs = HashMap<LlmModel, Job>()

    /** Download state of the small model. */
    val state: StateFlow<State> = stateFor(LlmModel.Small)

    fun stateFor(model: LlmModel): StateFlow<State> = states.getValue(model).asStateFlow()

    /** An engine, the model it holds, and which job it was loaded for. */
    private class Loaded(val engine: Engine, val model: LlmModel, val smart: Boolean)

    private var idleJob: Job? = null

    /** The model that answered last, for the phone check (the clever one can fall back to the fast one). */
    @Volatile
    var lastUsed: LlmModel? = null
        private set
    private var loaded: Loaded? = null
    private val loadLock = Any()

    fun file(context: Context, model: LlmModel = LlmModel.Small): File =
        File(File(context.filesDir, DIR), model.fileName)

    private fun partFile(context: Context, model: LlmModel): File =
        File(File(context.filesDir, DIR), "${model.fileName}.part")

    fun isInstalled(context: Context, model: LlmModel): Boolean = file(context, model).let {
        it.isFile && it.length() == model.bytes
    }

    /** True when any local model is installed. */
    fun isInstalled(context: Context): Boolean =
        LlmModel.entries.any { isInstalled(context, it) }

    fun savedBytes(context: Context, model: LlmModel = LlmModel.Small): Long =
        maxOf(file(context, model).length(), partFile(context, model).length())
            .coerceIn(0L, model.bytes)

    fun start(context: Context, model: LlmModel = LlmModel.Small) {
        if (jobs[model]?.isActive == true) return
        val app = context.applicationContext
        val flow = states.getValue(model)
        jobs[model] = scope.launch {
            try {
                download(app, model, flow)
                flow.value = State.Done
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.note(app, TAG, "${model.id} download failed: ${error.message}")
                flow.value = State.Failed(error.message ?: "Download failed.")
            }
        }
    }

    fun cancel(model: LlmModel = LlmModel.Small) {
        jobs[model]?.cancel()
        states.getValue(model).value = State.Idle
    }

    fun clearResult(model: LlmModel = LlmModel.Small) {
        if (jobs[model]?.isActive != true) states.getValue(model).value = State.Idle
    }

    fun remove(context: Context, model: LlmModel = LlmModel.Small) {
        cancel(model)
        release()
        file(context, model).delete()
        partFile(context, model).delete()
        states.getValue(model).value = State.Idle
    }

    suspend fun generate(
        context: Context,
        system: String,
        question: String,
        maxTokens: Int = 80,
        temperature: Double = 0.4,
        smart: Boolean = false,
    ): String? = generate(context, { system }, question, maxTokens, temperature, smart)

    /** [systemFor] runs once the model is chosen, so slower models can be given a shorter prompt. */
    suspend fun generate(
        context: Context,
        systemFor: (LlmModel) -> String,
        question: String,
        maxTokens: Int = 80,
        temperature: Double = 0.4,
        smart: Boolean = false,
    ): String? = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        // A new question cancels the pending idle close; otherwise the timer
        // could shut the engine down mid-generation.
        idleJob?.cancel()
        idleJob = null
        val ready = load(app, smart) ?: return@withContext null
        lastUsed = ready.model

        val answer: String? = runCatching {
            val conversation: Conversation = ready.engine.createConversation(
                configFor(ready.model, systemFor(ready.model), maxTokens, temperature),
            )
            try {
                val started = System.nanoTime()
                val message = conversation.sendMessage(question)
                note(app, ready.model, "answered", started)
                logBenchmark(app, ready.model, conversation)
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
     * model. Emits nothing when no model is installed or memory is short.
     */
    fun stream(
        context: Context,
        system: String,
        question: String,
        maxTokens: Int = 100,
        smart: Boolean = false,
    ): Flow<String> = stream(context, { system }, question, maxTokens, smart)

    fun stream(
        context: Context,
        systemFor: (LlmModel) -> String,
        question: String,
        maxTokens: Int = 100,
        smart: Boolean = false,
    ): Flow<String> = flow {
        val app = context.applicationContext
        idleJob?.cancel()
        idleJob = null
        val ready = load(app, smart) ?: return@flow
        val conversation: Conversation = ready.engine.createConversation(
            configFor(ready.model, systemFor(ready.model), maxTokens, 0.4),
        )
        var finished = false
        var firstWord = true
        val started = System.nanoTime()
        try {
            conversation.sendMessageAsync(question).collect { message ->
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    if (firstWord) {
                        firstWord = false
                        note(app, ready.model, "first words", started)
                    }
                    emit(text)
                }
            }
            finished = true
            note(app, ready.model, "reply finished", started)
            logBenchmark(app, ready.model, conversation)
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
    suspend fun warm(context: Context, smart: Boolean = false): Unit = withContext(Dispatchers.Default) {
        val app = context.applicationContext
        idleJob?.cancel()
        idleJob = null
        runCatching { load(app, smart) }
        Unit
    }

    private fun configFor(
        model: LlmModel,
        system: String,
        maxTokens: Int,
        temperature: Double,
    ): ConversationConfig {
        val sampler = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature)
        return if (model.reasonsByDefault) {
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = sampler,
                maxOutputToken = maxTokens,
                thinkingConfig = ThinkingConfig(false),
                extraContext = mapOf("enable_thinking" to false),
            )
        } else {
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = sampler,
                maxOutputToken = maxTokens,
            )
        }
    }

    /** The runtime's own numbers: time to first token and prefill and decode speed. */
    @OptIn(ExperimentalApi::class)
    private fun logBenchmark(app: Context, model: LlmModel, conversation: Conversation) {
        runCatching {
            val info = conversation.getBenchmarkInfo()
            CrashLog.note(
                app,
                TAG,
                "${model.id}: TTFT ${"%.2f".format(info.timeToFirstTokenInSecond)} s, " +
                    "prefill ${info.lastPrefillTokenCount} tokens at " +
                    "${"%.0f".format(info.lastPrefillTokensPerSecond)} tok/s, " +
                    "decode ${info.lastDecodeTokenCount} tokens at " +
                    "${"%.1f".format(info.lastDecodeTokensPerSecond)} tok/s",
            )
        }.onFailure { CrashLog.note(app, TAG, "${model.id}: timing unavailable (${it.message})") }
    }

    /** The runtime only collects timings when asked to, before an engine is created. */
    @OptIn(ExperimentalApi::class)
    private fun enableTimings() {
        runCatching { ExperimentalFlags.enableBenchmark = true }
    }

    /** Wall-clock timings in the assistant log, so a model can be judged on the phone it runs on. */
    private fun note(app: Context, model: LlmModel, what: String, startedNanos: Long) {
        val seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        CrashLog.note(app, TAG, "${model.id}: $what after ${"%.1f".format(seconds)} s")
    }

    private fun load(app: Context, smart: Boolean = false): Loaded? = synchronized(loadLock) {
        val wanted = if (smart) LlmModel.Large else LlmModel.Small
        loaded?.let { current ->
            if (current.smart == smart) return@synchronized current
            // The other job needs the other model: only one is ever held.
            runCatching { current.engine.close() }
            loaded = null
        }
        val options = LlmModel.candidates(
            wanted,
            LlmModel.entries.filter { isInstalled(app, it) }.toSet(),
            availableMb(app),
        )
        if (options.isEmpty()) {
            CrashLog.note(app, TAG, "no installed local model fits the free memory")
            return@synchronized null
        }
        for (model in options) {
            val engine = runCatching {
                enableTimings()
                val started = System.currentTimeMillis()
                Engine(
                    EngineConfig(
                        modelPath = file(app, model).absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = app.cacheDir.path,
                    ),
                ).also {
                    it.initialize()
                    CrashLog.note(
                        app,
                        TAG,
                        "${model.id} loaded in ${System.currentTimeMillis() - started} ms",
                    )
                }
            }.onFailure {
                CrashLog.note(app, TAG, "${model.id} load failed: ${it.message}")
            }.getOrNull()
            if (engine != null) return@synchronized Loaded(engine, model, smart).also { loaded = it }
        }
        null
    }

    fun release() {
        idleJob?.cancel()
        idleJob = null
        synchronized(loadLock) {
            runCatching { loaded?.engine?.close() }
            loaded = null
        }
    }

    private fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            release()
        }
    }

    private fun availableMb(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun download(context: Context, model: LlmModel, flow: MutableStateFlow<State>) {
        val target = file(context, model)
        target.parentFile?.mkdirs()
        val part = partFile(context, model)
        var have = part.length().coerceAtMost(model.bytes)
        flow.value = State.Working(have, model.bytes)
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(model.url).apply {
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
                        flow.value = State.Working(done.coerceAtMost(model.bytes), model.bytes)
                    }
                }
            }
        }
        if (part.length() != model.bytes) {
            throw IOException("Download stopped at ${part.length()} of ${model.bytes} bytes.")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        CrashLog.note(context, TAG, "${model.id} downloaded")
    }
}
