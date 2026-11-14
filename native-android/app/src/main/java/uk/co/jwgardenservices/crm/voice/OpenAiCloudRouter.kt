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
 * Qwen flags those turns with `requires_cloud: true`; the Assistant service
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
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Ask the cloud model to handle [query]. Returns a short spoken answer.
     *
     * [context] is the same business snapshot the local model sees, so the
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
        val token = accessToken() ?: throw IOException("Sign in again to use online answers")

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
                Log.w(TAG, "voice-route HTTP ${response.code}: ${text.take(300)}")
                throw IOException("Online answers are unavailable right now")
            }
            val parsed = json.decodeFromString<SpokenResponse>(text)
            if (parsed.error != null) throw IOException(parsed.error)
            return@withContext parsed.answer.trim().ifBlank {
                "I found a result online, but no usable answer came back."
            }
        }
    }

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
