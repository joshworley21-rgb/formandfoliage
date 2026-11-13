package uk.co.jwgardenservices.crm.voice

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hybrid fallback for the few questions an offline assistant cannot answer:
 * live material prices, supplier data and dynamic quote calculations.
 *
 * Qwen flags those turns with `requires_cloud: true`; the Assistant service
 * then speaks "Checking online rates…" and calls [route], which posts the
 * transcript to OpenAI with tool definitions. Tool calls are executed
 * on-device (currently against the local rate-card stubs below) and the
 * model's final sentence is returned for Kokoro to read out.
 *
 * The API key is never in the repo. It comes from `-POPENAI_API_KEY=...` or
 * the `OPENAI_API_KEY` environment variable at build time, and is only sent
 * in the Authorization header. With no key the router refuses and the
 * service speaks a friendly failure instead.
 */
class OpenAiCloudRouter(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = ENDPOINT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Ask the cloud model to handle [query]. Returns a short spoken answer.
     * Throws [IOException] on network/API failure so the caller can apologise
     * out loud.
     */
    suspend fun route(query: String, clientOrTopic: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IOException("OPENAI_API_KEY is not configured for this build")
        }

        val messages = mutableListOf(
            ChatMessage(role = "system", content = SYSTEM_PROMPT),
            ChatMessage(
                role = "user",
                content = buildString {
                    append("Customer or topic: ")
                    append(clientOrTopic.ifBlank { "not stated" })
                    append("\nSpoken request: ")
                    append(query)
                },
            ),
        )

        val first = chat(messages)
        val calls = first.toolCalls.orEmpty()
        if (calls.isEmpty()) {
            return@withContext first.content?.trim().orEmpty().ifBlank {
                "I found a result online, but no usable answer came back."
            }
        }

        messages += first
        calls.forEach { call ->
            val result = executeTool(call.function)
            Log.i(TAG, "Tool ${call.function.name} -> ${result.take(240)}")
            messages += ChatMessage(role = "tool", content = result, toolCallId = call.id)
        }

        chat(messages).content?.trim().orEmpty().ifBlank {
            "The online rates came back, but no spoken answer was produced."
        }
    }

    private fun chat(messages: List<ChatMessage>): ChatMessage {
        val body = json.encodeToString(
            ChatRequest(model = model, messages = messages, tools = TOOLS),
        ).toRequestBody(JSON)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(300).orEmpty()
                throw IOException("OpenAI HTTP ${response.code}: $detail")
            }
            val text = response.body?.string().orEmpty()
            val parsed = json.decodeFromString<ChatResponse>(text)
            return parsed.choices.firstOrNull()?.message
                ?: throw IOException("OpenAI returned no choices")
        }
    }

    // --- tools ------------------------------------------------------------

    private fun executeTool(call: FunctionCall): String = when (call.name) {
        "fetch_live_pricing" -> fetchLivePricing(call.arguments)
        "calculate_commercial_quote" -> calculateQuote(call.arguments)
        else -> buildJsonObject { put("error", "Unknown tool ${call.name}") }.toString()
    }

    private fun fetchLivePricing(arguments: String): String {
        val material = argument(arguments, "material_type").lowercase()
        val match = RATE_CARD.entries.firstOrNull { material.contains(it.key) }
        val (price, unit) = match?.value ?: (12.0 to "unit")
        return buildJsonObject {
            put("material_type", material.ifBlank { "unspecified" })
            put("canonical_material", match?.key ?: "unlisted")
            put("unit_price_gbp", price)
            put("unit", unit)
            put("currency", "GBP")
            put("source", "local rate card")
            put("is_estimate", true)
            put("note", "Illustrative rate; confirm with the supplier before quoting.")
        }.toString()
    }

    private fun calculateQuote(arguments: String): String {
        val client = argument(arguments, "client")
        val scope = argument(arguments, "scope").lowercase()
        val hours = when {
            "mow" in scope -> 1.5
            "hedge" in scope -> 3.0
            "design" in scope || "landscap" in scope -> 16.0
            "clean" in scope -> 2.0
            else -> 4.0
        }
        val materials = when {
            "turf" in scope -> 320.0
            "bark" in scope || "mulch" in scope -> 140.0
            "paving" in scope -> 480.0
            else -> 90.0
        }
        val labour = hours * LABOUR_RATE
        val subtotal = labour + materials
        val vat = (subtotal * VAT_RATE * 100).toInt() / 100.0

        return buildJsonObject {
            put("client", client.ifBlank { "unspecified" })
            put("scope", argument(arguments, "scope"))
            put("currency", "GBP")
            put("labour_hours", hours)
            put("labour_rate_gbp", LABOUR_RATE)
            put("labour_total_gbp", round(labour))
            put("materials_estimate_gbp", materials)
            put("subtotal_gbp", round(subtotal))
            put("vat_rate_percent", 20)
            put("vat_gbp", round(vat))
            put("total_gbp", round(subtotal + vat))
            put("is_estimate", true)
            put("note", "Illustrative quote from the local rate card; confirm before sending.")
        }.toString()
    }

    private fun argument(arguments: String, key: String): String = runCatching {
        val obj: JsonObject = json.parseToJsonElement(arguments).jsonObject
        obj[key]?.jsonPrimitive?.contentOrNull ?: ""
    }.getOrDefault("")

    private fun round(value: Double): Double = (value * 100).toInt() / 100.0

    // --- wire format ------------------------------------------------------

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
        @SerialName("tool_call_id") val toolCallId: String? = null,
    )

    @Serializable
    private data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: FunctionCall,
    )

    @Serializable
    private data class FunctionCall(val name: String, val arguments: String)

    @Serializable
    private data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    @Serializable
    private data class Tool(val type: String = "function", val function: FunctionDef)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val tools: List<Tool>,
        @SerialName("tool_choice") val toolChoice: String = "auto",
        val temperature: Double = 0.2,
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ChatMessage)

    private companion object {
        const val TAG = "CloudRouter"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val LABOUR_RATE = 35.0
        const val VAT_RATE = 0.2
        val JSON = "application/json; charset=utf-8".toMediaType()

        val SYSTEM_PROMPT = """
            You are the phone assistant for JW Garden Services, a UK gardening
            business. Answer spoken questions in at most 45 words, in GBP, with
            no lists, markdown or emoji: your reply is read aloud by a speech
            engine.

            Use fetch_live_pricing whenever a material price is needed and
            calculate_commercial_quote whenever a quote, estimate or job total
            is needed. Never invent a price that a tool did not return. If a
            tool marks its answer as an estimate, say so plainly ("that's an
            estimate"). Ask one short clarifying question if the request lacks
            the material or the scope.
        """.trimIndent()

        /** Illustrative rate card; swap for a live supplier API later. */
        val RATE_CARD: Map<String, Pair<Double, String>> = mapOf(
            "turf" to (7.5 to "m2"),
            "grass" to (7.5 to "m2"),
            "bark" to (6.0 to "bag"),
            "mulch" to (6.0 to "bag"),
            "paving" to (45.0 to "m2"),
            "sleeper" to (28.0 to "each"),
            "soil" to (5.0 to "bag"),
            "labour" to (LABOUR_RATE to "hour"),
        )

        val TOOLS: List<Tool> = listOf(
            Tool(
                function = FunctionDef(
                    name = "fetch_live_pricing",
                    description = "Look up the current price of a garden material.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("material_type") {
                                put("type", "string")
                                put("description", "Material such as turf, bark, paving, sleepers or soil")
                            }
                        }
                        putJsonArray("required") { add("material_type") }
                    },
                ),
            ),
            Tool(
                function = FunctionDef(
                    name = "calculate_commercial_quote",
                    description = "Calculate a commercial quote for a client's scope of work.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("client") {
                                put("type", "string")
                                put("description", "Client name or site")
                            }
                            putJsonObject("scope") {
                                put("type", "string")
                                put("description", "Work requested, e.g. turf 40 square metres or hedge trimming")
                            }
                        }
                        putJsonArray("required") {
                            add("client")
                            add("scope")
                        }
                    },
                ),
            ),
        )
    }
}
