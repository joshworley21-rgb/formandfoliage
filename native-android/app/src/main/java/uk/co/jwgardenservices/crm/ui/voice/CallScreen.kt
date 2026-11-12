package uk.co.jwgardenservices.crm.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.domain.parseVoiceCommand
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.voice.VoiceExecutor
import uk.co.jwgardenservices.crm.voice.VoiceSession

/** One line in the call transcript. */
private data class Turn(val mine: Boolean, val text: String)

private enum class Phase { Listening, Thinking, Speaking, Ended }

/**
 * The "phone call to your own office" screen: continuous listening, a short
 * spoken reply, and a transcript. Actions run against the offline cache;
 * money actions always read back a confirmation first.
 */
@Composable
fun CallScreen(
    viewModel: CrmViewModel,
    onEnd: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val executor = remember { VoiceExecutor(viewModel) }
    val turns = remember {
        mutableStateListOf(Turn(mine = false, text = "This is your assistant. What do you need?"))
    }
    var phase by remember { mutableStateOf(Phase.Listening) }
    var muted by remember { mutableStateOf(false) }
    var engineError by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<VoiceSession?>(null) }
    var silenceStreak by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    fun keepListening() {
        if (!muted && phase != Phase.Ended) session?.listen()
    }

    fun finishCall() {
        phase = Phase.Ended
        onEnd()
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            engineError = null
            session?.start()
        } else {
            engineError = "Microphone permission is needed for the call."
        }
    }

    DisposableEffect(Unit) {
        val voiceSession = VoiceSession(context, object : VoiceSession.Listener {
            override fun onHeard(text: String) {
                silenceStreak = 0
                turns.add(Turn(mine = true, text))
                phase = Phase.Thinking
                scope.launch {
                    try {
                        val reply = executor.handle(parseVoiceCommand(text))
                        turns.add(Turn(mine = false, reply.text))
                        if (reply.endSession) {
                            finishCall()
                        } else {
                            phase = Phase.Speaking
                            session?.speak(reply.text)
                        }
                    } catch (error: Exception) {
                        val message = error.message ?: "Something went wrong"
                        turns.add(Turn(mine = false, message))
                        phase = Phase.Speaking
                        session?.speak(message)
                    }
                }
            }

            override fun onSilence() {
                silenceStreak += 1
                // Sit quietly through the first few, then gently prompt.
                if (silenceStreak == 3) {
                    turns.add(Turn(mine = false, "Still here. Say 'help' for what I can do."))
                    phase = Phase.Speaking
                    session?.speak("Still here. Say help for what I can do.")
                } else {
                    keepListening()
                }
            }

            override fun onSpoken() {
                phase = Phase.Listening
                keepListening()
            }

            override fun onEngineError(message: String) {
                engineError = message
            }
        })
        session = voiceSession
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted && SpeechRecognizer.isRecognitionAvailable(context)) {
            voiceSession.start()
        } else if (!granted) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            engineError = "This phone has no speech recognition."
        }
        onDispose { voiceSession.stop() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Assistant call",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (phase) {
                    Phase.Listening -> "Listening…"
                    Phase.Thinking -> "Thinking…"
                    Phase.Speaking -> "Speaking…"
                    Phase.Ended -> "Call ended"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(turns) { turn ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (turn.mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = if (turn.mine) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.widthIn(max = 320.dp),
                        ) {
                            Text(
                                text = turn.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            engineError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                IconButton(
                    onClick = {
                        muted = !muted
                        if (!muted) keepListening()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Outlined.MicOff else Icons.Filled.Mic,
                        contentDescription = if (muted) "Unmute" else "Mute",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = { finishCall() },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = "End call",
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
                IconButton(
                    onClick = { },
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Reply comes through the speaker",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
