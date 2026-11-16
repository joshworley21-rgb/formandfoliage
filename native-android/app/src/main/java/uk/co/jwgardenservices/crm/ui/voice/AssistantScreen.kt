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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.voice.AssistantService
import uk.co.jwgardenservices.crm.voice.AssistantTurn
import uk.co.jwgardenservices.crm.voice.SherpaSpeechService

/**
 * The Assistant tab, laid out like a phone call.
 *
 * Opening the tab binds to [AssistantService] but does not dial. The green
 * button starts the call: a ringback plays while the recogniser and voice
 * warm up, then the assistant answers "Hello?" and the microphone opens. The
 * service keeps the loaded models when the screen leaves, so the next call is
 * quick, and the red button hangs up.
 *
 * Every recognised sentence and every spoken reply is kept in the service's
 * rolling transcript, so the other person's words do not vanish when the
 * assistant starts talking.
 */
@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<AssistantService?>(null) }
    var state by remember { mutableStateOf(SherpaSpeechService.UiState()) }
    var speaking by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var callActive by remember { mutableStateOf(false) }
    var dialling by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf<List<AssistantTurn>>(emptyList()) }
    var granted by remember { mutableStateOf(micGranted(context)) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        if (result) startCall(context)
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, AssistantService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = (binder as? AssistantService.LocalBinder)?.service() ?: return
                service = bound
                if (bound.callActive.value) bound.ensureListening()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                callActive = false
            }
        }
        // Binding keeps the screen in touch with the service without opening
        // the microphone. The call only begins when the green button is
        // pressed, so opening the tab is silent and does not dial out.
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            // Unbind, but leave the service running: the model stays loaded
            // for the next visit. The microphone closes while we are away.
            service?.pauseListening()
            service = null
            runCatching { context.unbindService(connection) }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, service) {
        val bound = service
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> bound?.pauseListening()
                Lifecycle.Event.ON_START -> if (bound?.callActive?.value == true) bound.ensureListening()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(service) {
        val bound = service ?: return@LaunchedEffect
        launch { bound.speechState.collect { state = it } }
        launch { bound.speaking.collect { speaking = it } }
        launch { bound.thinking.collect { thinking = it } }
        launch { bound.callActive.collect { callActive = it } }
        launch { bound.dialling.collect { dialling = it } }
        launch { bound.turns.collect { turns = it } }
    }

    val listening = callActive && !dialling &&
        state.phase == SherpaSpeechService.Phase.Listening && !speaking
    val status = when {
        !granted -> "Microphone permission needed"
        !callActive -> "Ready to call"
        dialling -> "Dialling…"
        thinking -> "Thinking…"
        speaking -> "Speaking…"
        state.phase == SherpaSpeechService.Phase.Listening -> "Connected"
        state.phase == SherpaSpeechService.Phase.Error -> "Unavailable"
        state.phase == SherpaSpeechService.Phase.Ready -> "Reconnecting…"
        else -> "Dialling…"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (callActive) "Hands-free call · on this phone" else "Offline · on this phone",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (turns.isNotEmpty()) {
                IconButton(onClick = { service?.clearTranscript() }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Clear conversation",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        CallOrb(
            active = callActive,
            speaking = speaking || thinking,
            loading = dialling,
            level = if (listening) state.level else 0f,
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = status,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (state.phase == SherpaSpeechService.Phase.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (state.phase == SherpaSpeechService.Phase.Error && state.message != null) {
            Text(
                text = state.message!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        Transcript(
            turns = turns,
            partial = state.partial,
            listening = listening,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Spacer(Modifier.height(14.dp))

        if (!granted) {
            Text(
                text = "The assistant needs the microphone to hear you. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (callActive) {
            CallButton(
                label = "End call",
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError,
                onClick = { service?.endCall() },
            ) { Icon(Icons.Filled.CallEnd, contentDescription = "End call", modifier = Modifier.size(30.dp)) }
        } else {
            CallButton(
                label = "Call",
                container = CallGreen,
                content = Color.White,
                onClick = {
                    if (granted) startCall(context) else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
            ) { Icon(Icons.Filled.Call, contentDescription = "Call the assistant", modifier = Modifier.size(28.dp)) }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CallOrb(active: Boolean, speaking: Boolean, loading: Boolean, level: Float) {
    val container = when {
        !active -> MaterialTheme.colorScheme.surfaceContainerHigh
        speaking -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant
        speaking -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = content,
                    strokeWidth = 3.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        if (active) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier
                    .width(96.dp)
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun Transcript(
    turns: List<AssistantTurn>,
    partial: String,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val listState = rememberLazyListState()
        val itemCount = turns.size + if (partial.isNotBlank()) 1 else 0

        LaunchedEffect(itemCount) {
            if (itemCount > 0) listState.scrollToItem(itemCount - 1)
        }

        if (itemCount == 0) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (listening) {
                        "Say “what’s next”, “who owes me” or “log two hours for Smiths”."
                    } else {
                        "Everything stays on this phone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(turns) { turn ->
                    Bubble(text = turn.text, fromUser = turn.fromUser)
                }
                if (partial.isNotBlank()) {
                    item {
                        Bubble(text = partial, fromUser = true, pending = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, pending: Boolean = false) {
    val container = if (fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = container,
            contentColor = content,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CallButton(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = container,
                contentColor = content,
            ),
        ) { icon() }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The familiar phone-call green, independent of the app's theme. */
private val CallGreen = Color(0xFF1B8F3A)

/** Dial out: start the call on the green button, not when the tab opens. */
private fun startCall(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, AssistantService::class.java),
    )
}

private fun micGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.RECORD_AUDIO,
) == PackageManager.PERMISSION_GRANTED
