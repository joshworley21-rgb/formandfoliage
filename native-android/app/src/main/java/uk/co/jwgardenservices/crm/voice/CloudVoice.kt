package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.util.Log
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uk.co.jwgardenservices.crm.core.AppConfig
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The optional natural voice.
 *
 * Reply text is posted to the `voice-speak` Edge Function, which holds the
 * OpenAI key and returns an MP3 for OpenAI's `gpt-4o-mini-tts`. Nothing about
 * the owner's speech is uploaded — only the sentence the assistant is about
 * to say, and only when this is switched on.
 *
 * Every failure returns false so the caller can fall back to Kokoro; a voice
 * that sometimes cannot be reached must never leave the assistant mute.
 */
class CloudVoice(private val context: Context) : AutoCloseable {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var player: MediaPlayer? = null

    @Volatile
    private var stopped = false

    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext false
        if (!online()) {
            Log.i(TAG, "no network; using the on-device voice")
            return@withContext false
        }
        stopped = false
        val token = SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken
            ?: return@withContext false

        val body = json.encodeToString(SpokenRequest(text.take(600))).toRequestBody(JSON)
        val request = Request.Builder()
            .url("${AppConfig.supabaseUrl}/functions/v1/voice-speak")
            .header("Authorization", "Bearer $token")
            .header("apikey", AppConfig.supabaseKey)
            .post(body)
            .build()

        val bytes = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "voice-speak HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                    return@withContext false
                }
                response.body?.bytes()
            }
        }.getOrNull() ?: return@withContext false
        if (bytes.isEmpty()) return@withContext false

        val file = File(context.cacheDir, "assistant-reply.mp3")
        runCatching { file.writeBytes(bytes) }.getOrElse {
            Log.w(TAG, "could not cache the reply audio", it)
            return@withContext false
        }
        play(file)
    }

    private suspend fun play(file: File): Boolean = suspendCancellableCoroutine { continuation ->
        val media = MediaPlayer()
        player = media

        fun finish(result: Boolean) {
            runCatching { media.release() }
            if (player === media) player = null
            if (continuation.isActive) continuation.resume(result)
        }

        media.setOnCompletionListener { finish(true) }
        media.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error $what/$extra")
            finish(false)
            true
        }
        continuation.invokeOnCancellation {
            runCatching { media.stop() }
            runCatching { media.release() }
            if (player === media) player = null
        }

        try {
            media.setDataSource(file.absolutePath)
            media.prepare()
            if (stopped) {
                finish(false)
            } else {
                media.start()
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not play the online voice", error)
            finish(false)
        }
    }

    private fun online(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Cut the clip short when a call arrives or the user ends the conversation. */
    fun stop() {
        stopped = true
        runCatching { player?.stop() }
    }

    override fun close() {
        stopped = true
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { File(context.cacheDir, "assistant-reply.mp3").delete() }
    }

    @Serializable
    private data class SpokenRequest(val text: String)

    private companion object {
        const val TAG = "CloudVoice"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
