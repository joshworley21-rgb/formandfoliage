package uk.co.jwgardenservices.crm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.repository.AuthRepository
import uk.co.jwgardenservices.crm.data.repository.AuthState
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

sealed interface WorkspaceState {
    data object Loading : WorkspaceState
    data object Owner : WorkspaceState
    data object Staff : WorkspaceState
    data class Error(val message: String) : WorkspaceState
}

class AppViewModel(
    private val auth: AuthRepository = ServiceLocator.authRepository,
) : ViewModel() {
    val authState: StateFlow<AuthState> = auth.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AuthState.Loading,
    )

    private val _workspace = MutableStateFlow<WorkspaceState>(WorkspaceState.Loading)
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { auth.awaitInitialization() }
            authState.collect { state ->
                when (state) {
                    is AuthState.SignedIn -> loadWorkspace()
                    else -> {
                        ServiceLocator.workspaceRole = "owner"
                        _workspace.value = WorkspaceState.Loading
                    }
                }
            }
        }
    }

    fun retryWorkspace() {
        viewModelScope.launch { loadWorkspace() }
    }

    private suspend fun loadWorkspace() {
        _workspace.value = WorkspaceState.Loading
        try {
            val repository = ServiceLocator.workspaceRepository
            // Links a pending invite if this email was invited as staff.
            repository.claimStaffInvite()
            val role = if (repository.myWorkspace()?.role == "staff") "staff" else "owner"
            ServiceLocator.workspaceRole = role
            _workspace.value = if (role == "staff") {
                WorkspaceState.Staff
            } else {
                WorkspaceState.Owner
            }
        } catch (error: Exception) {
            _workspace.value = WorkspaceState.Error(
                error.message ?: "Could not load your workspace",
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { auth.signOut() }
            runCatching { ServiceLocator.crmRepository.clearLocalCache() }
            ServiceLocator.workspaceRole = "owner"
            _workspace.value = WorkspaceState.Loading
        }
    }
}
