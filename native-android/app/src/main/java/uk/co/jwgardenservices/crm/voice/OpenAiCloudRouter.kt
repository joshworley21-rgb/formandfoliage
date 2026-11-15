package uk.co.jwgardenservices.crm.voice

import android.util.Log
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uk.co.jwgardenservices.crm.core.AppConfig
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Hybrid fallback for the few questions an offline assistant cannot answer:
 * live material prices, supplier data and dynamic quote calculations.
 *
 * The rules or the cloud classifier flag those turns with `requires_cloud`;
 * then speaks "Checking online rates…" and calls [route], which posts the
 * transcript to the `voice-route` Edge Function. The OpenAI key lives in that
 * function's secrets, never in the APK, and the function executes the two
 * pricing tools against its rate card before returning one spoken sentence.
 *
 * The call is authenticated with the user's Supabase access token, so the
 * endpoint cannot be used by anyone who merely lifts the anon key out of the
 * build.
 */
class OpenAiCloudRouter(
    private val endpoint: String = "${AppConfig.supabaseUrl}/functions/v1/voice-route",
    private val anonKey: String = AppConfig.supabaseKey,
    private val accessToken: suspend () -> String? = {
        SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken
    },
    /** Surfaces failures in the assistant log, not just Logcat. */
    private val onEvent: (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // Defaults are real fields here: without this, "mode" and the context
        // never left the phone and the function treated every call as chat.
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Intent classification is small; never let a bad network hold a reply. */
    private val intentClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    /**
     * Ask the cloud model to handle [query]. Returns a short spoken answer.
     *
     * [context] is the same business snapshot the rules see, so the
     * cloud answer can talk about the owner's clients, diary and memories
     * rather than generic advice. Throws [IOException] on network/API failure
     * so the caller can apologise out loud.
     */
    suspend fun route(
        query: String,
        clientOrTopic: String,
        context: String = "",
        history: List<AssistantTurn> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val token = accessToken()
        if (token == null) {
            onEvent("route: no Supabase session")
            throw IOException("Sign in again to use online answers")
        }

        val body = json.encodeToString(
            SpokenRequest(
                query = query,
                clientOrTopic = clientOrTopic,
                context = context.take(2000),
                history = history.takeLast(6).map {
                    HistoryTurn(role = if (it.fromUser) "user" else "assistant", text = it.text.take(400))
                },
            ),
        ).toRequestBody(JSON)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("apikey", anonKey)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                onEvent("route: HTTP ${response.code}")
                Log.w(TAG, "voice-route HTTP ${response.code}: ${text.take(300)}")
                throw IOException("Online answers are unavailable right now")
            }
            val parsed = json.decodeFromString<SpokenResponse>(text)
            if (parsed.error != null) {
                onEvent("route: ${parsed.error}")
                throw IOException(parsed.error)
            }
            return@withContext parsed.answer.trim().ifBlank {
                "I found a result online, but no usable answer came back."
            }
        }
    }

    /**
     * Cheap JSON intent classification through the same function.
     *
     * This exists because the on-device 0.5B model can take tens of seconds on
     * a phone CPU. A cloud round trip is ~0.5-1 s and much more accurate, so
     * it is used whenever online answers are allowed;
     * as the offline fallback.
     *
     * Returns null on any failure so the caller can fall back.
     */
    data class CloudReply(
        val intent: IntentResult?,
        val answer: String,
    )

    /**
     * One call, two possible outcomes: a local intent to act on, or the spoken
     * answer already produced by the function. Either way the phone makes a
     * single request, which keeps both latency and token spend down.
     */
    suspend fun ask(
        query: String,
        context: String,
        history: List<AssistantTurn> = emptyList(),
    ): CloudReply? = withContext(Dispatchers.IO) {
        val token = accessToken()
        if (token == null) {
            onEvent("intent: no Supabase session")
            return@withContext null
        }
        val body = json.encodeToString(
            IntentRequest(
                query = query,
                context = context.take(2000),
                history = history.takeLast(6).map {
                    HistoryTurn(role = if (it.fromUser) "user" else "assistant", text = it.text.take(400))
                },
            ),
        ).toRequestBody(JSON)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("apikey", anonKey)
            .post(body)
            .build()

        intentClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                onEvent("intent: HTTP ${response.code}")
                Log.w(TAG, "intent HTTP ${response.code}: ${text.take(200)}")
                return@withContext null
            }
            val parsed = runCatching { json.decodeFromString<IntentResponse>(text) }.getOrNull()
            if (parsed == null) {
                onEvent("intent: unreadable JSON")
                return@withContext null
            }
            val kind = SpokenIntent.entries.firstOrNull { it.wire == parsed.intent }
            val intent = kind?.let {
                IntentResult(
                    intent = it,
                    clientOrTopic = parsed.clientOrTopic.take(120),
                    requiresCloud = parsed.requiresCloud,
                )
            }
            // A function without the auto mode may answer without an intent;
            // use that answer rather than calling again.
            if (intent == null && parsed.answer.isBlank()) {
                onEvent("intent: no intent or answer in the response")
                return@withContext null
            }
            CloudReply(intent = intent, answer = parsed.answer.trim())
        }
    }

    @Serializable
    private data class IntentRequest(
        val mode: String = "auto",
        val query: String,
        val context: String = "",
        val history: List<HistoryTurn> = emptyList(),
    )

    @Serializable
    private data class IntentResponse(
        val intent: String = "",
        @SerialName("client_or_topic") val clientOrTopic: String = "",
        @SerialName("requires_cloud") val requiresCloud: Boolean = false,
        val answer: String = "",
    )

    @Serializable
    private data class HistoryTurn(val role: String, val text: String)

    @Serializable
    private data class SpokenRequest(
        val query: String,
        @SerialName("client_or_topic") val clientOrTopic: String,
        val context: String = "",
        val history: List<HistoryTurn> = emptyList(),
    )

    @Serializable
    private data class SpokenResponse(
        val answer: String = "",
        val error: String? = null,
    )

    private companion object {
        const val TAG = "CloudRouter"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
