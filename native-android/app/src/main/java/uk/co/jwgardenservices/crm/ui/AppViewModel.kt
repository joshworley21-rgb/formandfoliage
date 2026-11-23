package uk.co.jwgardenservices.crm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.core.OfflineSession
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
    companion object {
        /**
         * Last known signed-in state and workspace, kept process-wide.
         *
         * Returning to the app can recreate the activity (low memory) while
         * the process survives; without this the UI would fall back to the
         * "Opening your workspace…" splash while it revalidates. With the
         * cache the workspace renders immediately and the session is checked
         * underneath.
         */
        @Volatile
        private var cachedAuth: AuthState.SignedIn? = null

        @Volatile
        private var cachedWorkspace: WorkspaceState? = null
    }

    val authState: StateFlow<AuthState> = auth.authState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = cachedAuth ?: AuthState.Loading,
    )

    private val savedRole = runCatching {
        if (ServiceLocator.isInitialised()) {
            OfflineSession.role(ServiceLocator.appContext)
        } else {
            null
        }
    }.getOrNull()

    private val _workspace = MutableStateFlow(
        cachedWorkspace ?: when (savedRole) {
            "owner" -> WorkspaceState.Owner
            "staff" -> WorkspaceState.Staff
            else -> WorkspaceState.Loading
        },
    )
    val workspace: StateFlow<WorkspaceState> = _workspace.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { auth.awaitInitialization() }
            authState.collect { state ->
                when (state) {
                    is AuthState.SignedIn -> {
                        cachedAuth = state
                        loadWorkspace()
                    }
                    else -> {
                        cachedAuth = null
                        cachedWorkspace = null
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
        // Do not blank an established workspace on a refresh: that would flash
        // the splash every time the activity comes back.
        if (_workspace.value !is WorkspaceState.Owner && _workspace.value !is WorkspaceState.Staff) {
            _workspace.value = WorkspaceState.Loading
        }
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
            cachedWorkspace = _workspace.value
            runCatching {
                OfflineSession.save(
                    ServiceLocator.appContext,
                    authState.value.let { (it as? AuthState.SignedIn)?.userId.orEmpty() },
                    (authState.value as? AuthState.SignedIn)?.email,
                    role,
                )
            }
            CrashLog.note(ServiceLocator.appContext, "Workspace", "loaded role=$role")
        } catch (error: Exception) {
            // The workspace RPC needs the network; the CRM cache does not.
            // Open the last known role rather than trapping the owner on an
            // error screen with no signal.
            val offlineRole = runCatching {
                OfflineSession.role(ServiceLocator.appContext)
            }.getOrNull()
            if (offlineRole != null) {
                ServiceLocator.workspaceRole = offlineRole
                _workspace.value = if (offlineRole == "staff") {
                    WorkspaceState.Staff
                } else {
                    WorkspaceState.Owner
                }
                cachedWorkspace = _workspace.value
                CrashLog.note(
                    ServiceLocator.appContext,
                    "Workspace",
                    "offline: using cached role=$offlineRole (${error.message})",
                )
            } else {
                _workspace.value = WorkspaceState.Error(
                    error.message ?: "Could not load your workspace",
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { auth.signOut() }
            runCatching { ServiceLocator.crmRepository.clearLocalCache() }
            ServiceLocator.workspaceRole = "owner"
            cachedAuth = null
            cachedWorkspace = null
            runCatching { OfflineSession.clear(ServiceLocator.appContext) }
            _workspace.value = WorkspaceState.Loading
        }
    }
}
