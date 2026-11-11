package uk.co.jwgardenservices.crm.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.repository.AuthRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

enum class AuthMode { SIGN_IN, SIGN_UP, RESET }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class AuthViewModel(
    private val auth: AuthRepository = ServiceLocator.authRepository,
) : ViewModel() {
    var state by mutableStateOf(AuthUiState())
        private set

    fun setMode(mode: AuthMode) {
        state = state.copy(mode = mode, error = null, notice = null)
    }

    fun setEmail(value: String) {
        state = state.copy(email = value, error = null)
    }

    fun setPassword(value: String) {
        state = state.copy(password = value, error = null)
    }

    fun submit() {
        if (state.busy) return
        val email = state.email.trim()
        if (email.isBlank() || !email.contains('@')) {
            state = state.copy(error = "Enter a valid email address")
            return
        }
        if (state.mode != AuthMode.RESET && state.password.length < 6) {
            state = state.copy(error = "Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            state = state.copy(busy = true, error = null, notice = null)
            try {
                when (state.mode) {
                    AuthMode.SIGN_IN -> auth.signIn(email, state.password)
                    AuthMode.SIGN_UP -> {
                        auth.signUp(email, state.password)
                        state = state.copy(
                            busy = false,
                            notice = "Account created. Check your email if confirmation is required.",
                            mode = AuthMode.SIGN_IN,
                        )
                        return@launch
                    }
                    AuthMode.RESET -> {
                        auth.resetPassword(email)
                        state = state.copy(
                            busy = false,
                            notice = "If that address has an account, a reset link is on its way.",
                            mode = AuthMode.SIGN_IN,
                        )
                        return@launch
                    }
                }
                state = state.copy(busy = false)
            } catch (error: Exception) {
                state = state.copy(
                    busy = false,
                    error = error.message ?: "Could not complete that",
                )
            }
        }
    }
}
