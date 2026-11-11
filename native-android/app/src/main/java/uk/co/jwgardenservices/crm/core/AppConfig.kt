package uk.co.jwgardenservices.crm.core

import uk.co.jwgardenservices.crm.BuildConfig

/** Public Supabase client configuration, injected at build time by Gradle. */
object AppConfig {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val authScheme: String = BuildConfig.AUTH_SCHEME
    val authHost: String = BuildConfig.AUTH_HOST
    val authRedirect: String = BuildConfig.AUTH_REDIRECT
}
