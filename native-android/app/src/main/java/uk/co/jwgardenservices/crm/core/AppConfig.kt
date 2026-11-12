package uk.co.jwgardenservices.crm.core

import uk.co.jwgardenservices.crm.BuildConfig

/** Public client configuration, injected at build time by Gradle. */
object AppConfig {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val authScheme: String = BuildConfig.AUTH_SCHEME
    val authHost: String = BuildConfig.AUTH_HOST
    val authRedirect: String = BuildConfig.AUTH_REDIRECT

    /**
     * The VPS voice pipeline (Pipecat WebSocket transport: Whisper STT,
     * Ollama brain, Kokoro TTS). Unencrypted ws:// on purpose for now —
     * move to wss:// behind a domain before using it for anything sensitive.
     */
    const val ASSISTANT_WS_URL: String = "ws://45.13.232.205:8765/ws"

    /** Self-hosted LiveKit on the VPS: the assistant's audio room. */
    val livekitUrl: String = BuildConfig.LIVEKIT_URL
    val livekitRoom: String = BuildConfig.LIVEKIT_ROOM
    val livekitToken: String = BuildConfig.LIVEKIT_TOKEN
}
