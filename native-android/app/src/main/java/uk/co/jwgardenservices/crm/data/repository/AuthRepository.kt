package uk.co.jwgardenservices.crm.data.repository

import android.content.Intent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uk.co.jwgardenservices.crm.core.AppConfig
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String, val email: String?) : AuthState
}

class AuthRepository(
    private val client: SupabaseClient = SupabaseProvider.client,
) {
    /**
     * The session, as the SDK sees it. Unlike the web app there is no offline
     * sign-in path: a native app opened with no signal can show cached data once
     * a refresh token is on disk, but the first sign-in still needs the network.
     */
    val authState: Flow<AuthState> = client.auth.sessionStatus.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> AuthState.SignedIn(
                userId = status.session.user?.id.orEmpty(),
                email = status.session.user?.email,
            )
            is SessionStatus.NotAuthenticated -> AuthState.SignedOut
            is SessionStatus.Initializing -> AuthState.Loading
            else -> AuthState.Loading
        }
    }

    suspend fun awaitInitialization() = client.auth.awaitInitialization()

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun resetPassword(email: String) {
        client.auth.resetPasswordForEmail(email, AppConfig.authRedirect)
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    /** Finish a password reset or email-confirmation link handed back to the app. */
    suspend fun handleDeepLink(intent: Intent) {
        client.handleDeeplinks(intent)
    }
}
