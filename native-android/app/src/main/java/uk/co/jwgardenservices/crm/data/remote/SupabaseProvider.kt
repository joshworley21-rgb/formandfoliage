package uk.co.jwgardenservices.crm.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json
import uk.co.jwgardenservices.crm.core.AppConfig

/**
 * One Supabase client for the app, configured like the web client: PKCE auth,
 * session persisted on the device, and unknown JSON columns ignored so a schema
 * addition on the server never breaks an older APK.
 */
object SupabaseProvider {
    val client: SupabaseClient by lazy {
        createSupabaseClient(AppConfig.supabaseUrl, AppConfig.supabaseKey) {
            defaultSerializer = KotlinXSerializer(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = false
                },
            )
            install(Auth) {
                scheme = AppConfig.authScheme
                host = AppConfig.authHost
            }
            install(Postgrest)
            install(Storage)
            install(Realtime)
            install(Functions)
        }
    }
}
