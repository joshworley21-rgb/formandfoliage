package uk.co.jwgardenservices.crm.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.voice.AssistantSpeaker
import uk.co.jwgardenservices.crm.voice.QwenIntentEngine
import uk.co.jwgardenservices.crm.voice.SherpaSpeechService

/**
 * The Assistant tab: hold a conversation with the phone, not a server.
 *
 * Tapping the mic loads the sherpa-onnx streaming Zipformer model bundled in
 * assets and then listens entirely offline. Partial words appear greyed while
 * it is still deciding, and the finished sentence is left in the panel.
 */
@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { SherpaSpeechService(context) }
    val speaker = remember { AssistantSpeaker(context) }
    val intents = remember { QwenIntentEngine(context) }
    val state by service.state.collectAsStateWithLifecycle()

    fun micGranted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun listen() = scope.launch { service.startListening() }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) listen()
    }

    DisposableEffect(service, intents) {
        onDispose {
            speaker.close()
            intents.close()
            service.close()
        }
    }

    // sherpa-onnx has finished a sentence: say so out loud, then ask the
    // local Qwen model what it means. The mic is paused while the
    // confirmation plays, otherwise the phone would transcribe itself and
    // answer forever. The intent JSON is logged (tag QwenIntent) ready for
    // the next phase to route.
    LaunchedEffect(service, speaker, intents) {
        service.transcripts.collect { text ->
            val paused = service.isListening()
            if (paused) service.pauseForSpeech()
            speaker.speak("Command received") {
                if (paused) service.resumeAfterSpeech()
            }
            intents.classify(text)
        }
    }

    val listening = state.phase == SherpaSpeechService.Phase.Listening
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
            text = "Offline speech to text · sherpa-onnx",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        StatusPill(
            text = when (state.phase) {
                SherpaSpeechService.Phase.Idle -> "Tap the mic to start"
                SherpaSpeechService.Phase.Loading -> "Loading model"
                SherpaSpeechService.Phase.Ready -> "Ready"
                SherpaSpeechService.Phase.Listening -> "Listening…"
                SherpaSpeechService.Phase.Error -> "Unavailable"
            },
            container = when (state.phase) {
                SherpaSpeechService.Phase.Listening -> MaterialTheme.colorScheme.primaryContainer
                SherpaSpeechService.Phase.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            content = when (state.phase) {
                SherpaSpeechService.Phase.Listening -> MaterialTheme.colorScheme.onPrimaryContainer
                SherpaSpeechService.Phase.Error -> MaterialTheme.colorScheme.onErrorContainer
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

        Spacer(Modifier.height(20.dp))

        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
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
                            "Speak normally. The words appear here."
                        } else {
                            "Tap the mic below and speak. Everything stays on this phone."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        if (state.finalText.isNotBlank()) {
            TextButton(onClick = { service.clear() }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("Clear")
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }

        IconButton(
            onClick = {
                when {
                    listening -> service.stopListening()
                    busy -> Unit
                    !micGranted() -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    else -> listen()
                }
            },
            enabled = !busy,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(
                    if (listening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
        ) {
            Icon(
                imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (listening) "Stop listening" else "Start listening",
                tint = if (listening) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = if (listening) "Tap to stop" else "Tap to listen",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
