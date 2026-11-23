package uk.co.jwgardenservices.crm.core

import android.content.Context

/**
 * Last known signed-in workspace, on disk.
 *
 * Supabase can restore the session offline once a refresh token exists, but
 * the workspace RPC still needs the network. Without this the app sat on
 * "Opening your workspace…" or showed an error even though Room had all the
 * data cached. This is only a role hint for opening the cache; real
 * authorisation still comes from the server-backed session and RLS.
 */
object OfflineSession {
    private const val PREFS = "offline_session"
    private const val ROLE = "role"
    private const val USER = "user_id"
    private const val EMAIL = "email"

    fun save(context: Context, userId: String, email: String?, role: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ROLE, role)
            .putString(USER, userId)
            .putString(EMAIL, email)
            .apply()
    }

    fun role(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ROLE, null)
            ?.takeIf { it == "owner" || it == "staff" }

    fun hasSession(context: Context): Boolean = role(context) != null

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
