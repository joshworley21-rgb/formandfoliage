package uk.co.jwgardenservices.crm.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import uk.co.jwgardenservices.crm.core.AppLock
import uk.co.jwgardenservices.crm.data.repository.AuthState
import uk.co.jwgardenservices.crm.ui.auth.AuthScreen
import uk.co.jwgardenservices.crm.ui.components.BrandMark
import uk.co.jwgardenservices.crm.ui.main.MainScaffold

@Composable
fun AppRoot(
    appViewModel: AppViewModel = viewModel { AppViewModel() },
) {
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    val workspace by appViewModel.workspace.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val lockEnabled by AppLock.enabled.collectAsStateWithLifecycle()

    when (authState) {
        AuthState.Loading -> SplashScreen()
        AuthState.SignedOut -> AuthScreen()
        is AuthState.SignedIn ->
            if (locked && lockEnabled) {
                LockScreen()
            } else when (val role = workspace) {
            WorkspaceState.Loading -> SplashScreen()
            WorkspaceState.Owner -> MainScaffold(
                onSignOut = appViewModel::signOut,
                isStaff = false,
            )
            WorkspaceState.Staff -> MainScaffold(
                onSignOut = appViewModel::signOut,
                isStaff = true,
            )
            is WorkspaceState.Error -> WorkspaceErrorScreen(
                message = role.message,
                onRetry = appViewModel::retryWorkspace,
            )
        }
    }
}

@Composable
private fun WorkspaceErrorScreen(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Opening your workspace…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LockScreen() {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    fun unlock() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            // Should not happen — MainActivity is a FragmentActivity.
            AppLock.unlock()
            return
        }
        val manager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No fingerprint or PIN on this phone: fail open rather than
            // locking the owner out of their own jobs.
            AppLock.unlock()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AppLock.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        error = null
                    } else {
                        error = errString.toString()
                    }
                }
            },
        )
        runCatching {
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock JW CRM")
                .setAllowedAuthenticators(authenticators)
                .build()
            prompt.authenticate(info)
        }.onFailure {
            // Never trap the owner behind a prompt the device cannot show.
            AppLock.unlock()
        }
    }

    LaunchedEffect(Unit) { unlock() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(compact = false)
            Spacer(Modifier.height(20.dp))
            val message = error
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = { error = null; unlock() }) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Unlock")
            }
        }
    }
}
