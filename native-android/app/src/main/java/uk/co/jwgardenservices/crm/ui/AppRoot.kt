package uk.co.jwgardenservices.crm.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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

    when (authState) {
        AuthState.Loading -> SplashScreen()
        AuthState.SignedOut -> AuthScreen()
        is AuthState.SignedIn -> when (val role = workspace) {
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
