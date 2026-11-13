package uk.co.jwgardenservices.crm.ui.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import uk.co.jwgardenservices.crm.voice.AssistantService
import uk.co.jwgardenservices.crm.voice.SherpaSpeechService

/**
 * The Assistant tab: hands-free, offline conversation with the phone.
 *
 * There is no push-to-talk button. As soon as the microphone permission is
 * granted the screen starts the foreground [AssistantService], which owns the
 * recording loop, endpoint detection, the Qwen intent model and Kokoro
 * playback. The UI only observes state; the service reopens the microphone by
 * itself when a spoken confirmation ends.
 */
@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<AssistantService?>(null) }
    var state by remember { mutableStateOf(SherpaSpeechService.UiState()) }
    var speaking by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(micGranted(context)) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        if (result) service?.ensureListening()
    }

    LaunchedEffect(granted) {
        if (!granted) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }

        val intent = Intent(context, AssistantService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = (binder as? AssistantService.LocalBinder)?.service() ?: return
                service = bound
                bound.ensureListening()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            service = null
            runCatching { context.unbindService(connection) }
            context.stopService(intent)
        }
    }

    LaunchedEffect(service) {
        val bound = service ?: return@LaunchedEffect
        bound.speechState.collect { state = it }
    }
    LaunchedEffect(service) {
        val bound = service ?: return@LaunchedEffect
        bound.speaking.collect { speaking = it }
    }

    val listening = state.phase == SherpaSpeechService.Phase.Listening && !speaking
    val busy = state.phase == SherpaSpeechService.Phase.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Assistant",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Hands-free · sherpa-onnx + Kokoro",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val (pill, container) = when {
            !granted -> "Microphone permission needed" to MaterialTheme.colorScheme.errorContainer
            speaking -> "Speaking…" to MaterialTheme.colorScheme.secondaryContainer
            state.phase == SherpaSpeechService.Phase.Listening ->
                "Listening…" to MaterialTheme.colorScheme.primaryContainer
            state.phase == SherpaSpeechService.Phase.Error ->
                "Unavailable" to MaterialTheme.colorScheme.errorContainer
            state.phase == SherpaSpeechService.Phase.Loading ->
                "Loading model" to MaterialTheme.colorScheme.surfaceContainerHigh
            state.phase == SherpaSpeechService.Phase.Ready ->
                "Reopening microphone…" to MaterialTheme.colorScheme.surfaceContainerHigh
            else -> "Starting…" to MaterialTheme.colorScheme.surfaceContainerHigh
        }
        StatusPill(
            text = pill,
            container = container,
            content = when (container) {
                MaterialTheme.colorScheme.errorContainer -> MaterialTheme.colorScheme.onErrorContainer
                MaterialTheme.colorScheme.primaryContainer -> MaterialTheme.colorScheme.onPrimaryContainer
                MaterialTheme.colorScheme.secondaryContainer -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = if (state.phase == SherpaSpeechService.Phase.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (!granted) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Allow microphone")
            }
        }

        Spacer(Modifier.height(20.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                val hasText = state.finalText.isNotBlank() || state.partial.isNotBlank()
                if (hasText) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.finalText.isNotBlank()) {
                            Text(
                                text = state.finalText,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (state.partial.isNotBlank()) {
                            Text(
                                text = state.partial,
                                style = MaterialTheme.typography.titleMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (listening) {
                            "Listening. Speak naturally — the words appear here."
                        } else {
                            "Everything stays on this phone. Just start speaking."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        if (state.finalText.isNotBlank()) {
            TextButton(onClick = { service?.clearTranscript() }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("Clear")
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun micGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.RECORD_AUDIO,
) == PackageManager.PERMISSION_GRANTED

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
