package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Turns a spoken command into the app's fixed intent JSON using a local Qwen
 * 1.5B GGUF model (llama.cpp via the llama.android AAR). Nothing is sent to a
 * server.
 *
 * The model lives in the app's internal storage, never in the APK:
 *
 *   /data/data/uk.co.jwgardenservices.crm/files/models/<name>.gguf
 *
 * Drop a quantised Qwen GGUF there (for example
 * `qwen2.5-1.5b-instruct-q4_k_m.gguf`) via `adb run-as` or a first-run import.
 * Any `.gguf` is accepted; the preferred names are tried first.
 *
 * Output contract (also in `assets/intent.gbnf`):
 *
 *   { "intent": "log_hours" | "check_schedule" | "next_job" | "mark_job_done",
 *     "who_owes_me" | "weekly_earnings" | "quote" | "other",
 *     "client_or_topic": "string",
 *     "requires_cloud": true | false }
 *
 * The AAR at version 0.1.1 does not expose llama.cpp's grammar sampler, so the
 * grammar is embedded in the prompt and every reply is validated against the
 * schema here. Invalid or missing fields fall back to a deterministic rule
 * classifier, which is also what runs when the model cannot be loaded.
 */
class QwenIntentEngine(private val context: Context) : AutoCloseable {

    enum class Kind(val wire: String) {
        LogHours("log_hours"),
        CheckSchedule("check_schedule"),
        NextJob("next_job"),
        MarkJobDone("mark_job_done"),
        WhoOwesMe("who_owes_me"),
        WeeklyEarnings("weekly_earnings"),
        Quote("quote"),
        Other("other"),
    }

    data class Intent(
        val intent: Kind,
        val clientOrTopic: String,
        val requiresCloud: Boolean,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("intent", intent.wire)
            put("client_or_topic", clientOrTopic)
            put("requires_cloud", requiresCloud)
        }.toString()
    }

    private val loadLock = Mutex()
    private val inferLock = Mutex()
    private var model: LlamaModel? = null

    @Volatile
    private var closed = false

    /**
     * Classify [transcript], log the resulting JSON line to Logcat under the
     * [TAG] tag, and return the structured intent.
     *
     * [context] is a compact snapshot of the business (clients, bookings,
     * money owed, remembered facts). It is what lets a small model resolve
     * "the Smiths job" or "yesterday" without being trained on this business.
     */
    suspend fun classify(transcript: String, context: String = ""): Intent {
        val text = transcript.trim()
        if (text.isEmpty() || closed) {
            return Intent(Kind.Other, "", false).also { Log.i(TAG, it.toJson()) }
        }

        val reply = runCatching {
            val loaded = loadModel()
            inferLock.withLock {
                Llama.complete(
                    model = loaded,
                    prompt = "Spoken command: \"${text.replace("\"", "'")}\"",
                    systemPrompt = systemPrompt(context),
                    maxTokens = 96,
                ).text
            }
        }.onFailure { error ->
            Log.w(TAG, "Local model unavailable (${error.message}); using rules")
        }.getOrNull()

        val intent = reply?.let { coerce(it, text) } ?: fallback(text)
        Log.i(TAG, intent.toJson())
        return intent
    }

    /**
     * Load the model in the background when a call starts, so the first
     * command does not have to wait for it. Missing models are not an error:
     * the rule classifier handles those turns.
     */
    suspend fun warmUp() {
        if (closed) return
        runCatching { loadModel() }
            .onFailure { Log.i(TAG, "No local intent model yet (${it.message})") }
    }

    private suspend fun loadModel(): LlamaModel {
        model?.let { return it }
        return loadLock.withLock {
            model ?: withContext(Dispatchers.IO) {
                val file = findModel()
                    ?: error("No .gguf model in ${modelsDir().absolutePath}")
                Log.i(TAG, "Loading ${file.name} (${file.length() / (1024 * 1024)} MB)")
                Llama.loadModel(
                    modelPath = file.absolutePath,
                    config = LlamaConfig(
                        contextSize = 2048,
                        threads = 4,
                        gpuLayers = 0,
                        // Greedy sampling: the intent JSON has one right answer.
                        temperature = 0f,
                        topP = 1f,
                        topK = 1,
                        seed = 42,
                    ),
                ).also { model = it }
            }
        }
    }

    private fun findModel(): File? {
        val dir = modelsDir()
        if (!dir.isDirectory) return null
        PREFERRED_NAMES.map { File(dir, it) }.firstOrNull { it.isFile }?.let { return it }
        return dir.listFiles { file -> file.isFile && file.extension.equals("gguf", true) }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun modelsDir(): File = File(context.filesDir, "models")

    private fun systemPrompt(businessContext: String): String {
        val grammar = runCatching {
            context.assets.open(GRAMMAR_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return buildString {
            appendLine("You convert a gardener's spoken command into one JSON object and nothing else.")
            appendLine("Intents: log_hours (time worked), check_schedule (today, diary),")
            appendLine("next_job (next appointment or who is next), mark_job_done (finish a job),")
            appendLine("who_owes_me (unpaid or outstanding invoices),")
            appendLine("weekly_earnings (what this week has earned),")
            appendLine("quote (price or quote), other (anything else).")
            appendLine("requires_cloud is true when the answer needs live or external data:")
            appendLine("current material prices, supplier rates, a dynamic quote or estimate,")
            appendLine("weather, traffic, anything conversational, or anything not stored on this")
            appendLine("phone. Otherwise false.")
            appendLine("Local actions such as logging hours, the diary, money owed or earnings are false.")
            appendLine("client_or_topic is the client or subject mentioned, or an empty string.")
            appendLine("Match names to the client list below; copy the client's name as written.")
            if (businessContext.isNotBlank()) {
                appendLine()
                appendLine("Business context:")
                appendLine(businessContext.take(1600))
            }
            appendLine()
            appendLine("Examples:")
            appendLine("Spoken command: \"logged three hours at the Smiths yesterday\"")
            appendLine("{\"intent\":\"log_hours\",\"client_or_topic\":\"Smiths\",\"requires_cloud\":false}")
            appendLine("Spoken command: \"what's next\"")
            appendLine("{\"intent\":\"next_job\",\"client_or_topic\":\"\",\"requires_cloud\":false}")
            appendLine("Spoken command: \"who still owes me\"")
            appendLine("{\"intent\":\"who_owes_me\",\"client_or_topic\":\"\",\"requires_cloud\":false}")
            appendLine("Spoken command: \"finished the Jones job\"")
            appendLine("{\"intent\":\"mark_job_done\",\"client_or_topic\":\"Jones\",\"requires_cloud\":false}")
            appendLine("Spoken command: \"how much for forty square metres of turf\"")
            appendLine("{\"intent\":\"quote\",\"client_or_topic\":\"turf 40 square metres\",\"requires_cloud\":true}")
            appendLine("Spoken command: \"what's the weather doing tomorrow\"")
            appendLine("{\"intent\":\"other\",\"client_or_topic\":\"weather tomorrow\",\"requires_cloud\":true}")
            appendLine()
            appendLine("Reply with JSON only, matching this grammar exactly:")
            append(grammar)
        }
    }

    /** Parse the model's reply and enforce the schema. */
    private fun coerce(reply: String, transcript: String): Intent? {
        val jsonText = extractJsonObject(reply) ?: return null
        val parsed = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null

        val kind = Kind.entries.firstOrNull { it.wire == parsed.optString("intent") } ?: return null
        if (!parsed.has("client_or_topic") || !parsed.has("requires_cloud")) return null
        val topic = parsed.optString("client_or_topic").trim().take(MAX_TOPIC)
        return Intent(kind, topic, parsed.optBoolean("requires_cloud"))
    }

    private fun extractJsonObject(reply: String): String? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return reply.substring(start, end + 1)
    }

    /** Deterministic stand-in whenever the model is missing or malformed. */
    private fun fallback(transcript: String): Intent {
        val text = transcript.lowercase()
        val kind = when {
            text.containsAny("owe me", "owes", "owed", "unpaid", "outstanding", "invoice") ->
                Kind.WhoOwesMe
            text.containsAny("earn", "earned", "earnings", "takings", "made this week", "income") ->
                Kind.WeeklyEarnings
            text.containsAny("next job", "next appointment", "next visit", "who's next", "what's next", "whats next") ->
                Kind.NextJob
            text.containsAny("mark", "finished", "complete", "completed", "done with") &&
                !text.containsAny("how many", "hours", "hrs") -> Kind.MarkJobDone
            text.containsAny("hour", "hours", "log", "worked", "work for") -> Kind.LogHours
            text.containsAny("schedule", "diary", "today", "calendar", "what's on", "whats on") ->
                Kind.CheckSchedule
            text.containsAny("quote", "price", "cost", "charge", "how much") -> Kind.Quote
            else -> Kind.Other
        }
        // Only a live quote/pricing question leaves the phone. Everything the
        // CRM already knows is answered locally, even when the same words
        // ("how much do they owe") could sound like a price question.
        val needsCloud = kind == Kind.Quote || text.containsAny(
            "weather",
            "forecast",
            "traffic",
            "supplier",
            "live price",
            "current price",
            "price list",
            "website",
            "online",
        )
        return Intent(kind, transcript.take(MAX_TOPIC), needsCloud)
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    override fun close() {
        closed = true
        val handle = model ?: return
        model = null
        // Never free the handle under a running native inference: the crash
        // would kill the app. Leave it for process teardown in that rare case.
        if (inferLock.tryLock()) {
            try {
                runCatching { Llama.releaseModel(handle) }
            } finally {
                inferLock.unlock()
            }
        } else {
            Log.w(TAG, "Model still in use at close; leaving it to the process")
        }
    }

    private companion object {
        const val TAG = "QwenIntent"
        const val GRAMMAR_ASSET = "intent.gbnf"
        const val MAX_TOPIC = 120

        /** Tried in order before any other `.gguf` in the models folder. */
        val PREFERRED_NAMES = listOf(
            QwenModelDownloader.FILE_NAME,
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen1.5-1.8b-chat-q4_k_m.gguf",
            "qwen-1.5b-q4_k_m.gguf",
        )
    }
}
