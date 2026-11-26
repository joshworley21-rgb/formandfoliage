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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.voice.AssistantService
import uk.co.jwgardenservices.crm.voice.AssistantTurn
import uk.co.jwgardenservices.crm.voice.CallLedger
import uk.co.jwgardenservices.crm.voice.SherpaSpeechService

/**
 * The Assistant tab, styled as a phone call.
 *
 * Opening the tab binds to [AssistantService] but does not dial. The green
 * button starts the call: a ringback plays while the recogniser and voice
 * warm up, then the assistant answers and the timer starts. With warm models
 * a repeat call resumes instantly without the dial tone. While connected the
 * screen looks like a call sheet: glowing orb, name, timer, speaking bars,
 * status pill and the rolling transcript above the Mute / Speaker / End
 * controls. The service keeps the loaded models when the screen leaves, so
 * the next call is quick.
 */
@Composable
fun AssistantScreen() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<AssistantService?>(null) }
    var state by remember { mutableStateOf(SherpaSpeechService.UiState()) }
    var speaking by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var callActive by remember { mutableStateOf(false) }
    var dialling by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(false) }
    var connectedAt by remember { mutableLongStateOf(0L) }
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
                // A connected call keeps the microphone and CPU alive with the
                // screen off. Only leaving the Secretary screen unbinds/pauses.
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
        launch { bound.muted.collect { muted = it } }
        launch { bound.speakerOn.collect { speakerOn = it } }
        launch { bound.connectedAt.collect { connectedAt = it } }
        launch { bound.turns.collect { turns = it } }
    }
    // The ledger is process-wide, so the card outlives the service.
    val ledger by CallLedger.actions.collectAsStateWithLifecycle()

    val connected = callActive && !dialling &&
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
    val busy = speaking || thinking

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Short screens (small phones, split screen) get a smaller orb and
        // tighter spacing so the controls and transcript always fit.
        val compact = maxHeight < 640.dp
        val orbSize = if (compact) 132.dp else 168.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (compact) 16.dp else 24.dp,
                vertical = if (compact) 8.dp else 14.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (callActive) {
            // In a call the whole sheet is the dashboard: status, one big
            // visualiser, the half-heard words, and the end button.
            CallScreen(
                dialling = dialling,
                connectedAt = connectedAt,
                speaking = busy,
                level = if (connected) state.level else 0f,
                partial = state.partial,
                muted = muted,
                speakerOn = speakerOn,
                onToggleMute = { service?.setMuted(!muted) },
                onToggleSpeaker = { service?.setSpeaker(!speakerOn) },
                onEnd = { service?.endCall() },
                compact = compact,
            )
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Form & Foliage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
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

        Spacer(Modifier.weight(if (compact) 0.45f else 1.1f))

        CallOrb(
            active = callActive,
            speaking = busy,
            loading = dialling,
            size = orbSize,
        )

        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))

        Text(
            text = "Secretary",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // The familiar ticking call timer; runs while the call is connected.
        CallTimer(startedAt = connectedAt, running = callActive && connectedAt > 0L)

        Spacer(Modifier.weight(if (compact) 0.3f else 0.7f))

        SpeakingBars(
            active = callActive,
            speaking = busy,
            level = if (connected) state.level else 0f,
        )

        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(JwTokens.chipRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = if (state.phase == SherpaSpeechService.Phase.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!granted) {
            Text(
                text = "Secretary needs the microphone to hear you. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }

        Transcript(
            turns = turns,
            partial = state.partial,
            listening = connected,
            modifier = Modifier
                .fillMaxWidth()
                .weight(2.4f),
        )

        Spacer(Modifier.height(14.dp))

        CallButton(
            label = if (granted) "Call" else "Allow microphone",
            container = JwTheme.colors.callGreen,
            content = JwTheme.colors.onCall,
            onClick = {
                if (granted) startCall(context) else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = "Call Secretary",
                modifier = Modifier.size(28.dp),
            )
        }

        if (!callActive && ledger.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PostCallSummary(
                entries = ledger,
                onUndo = { entry ->
                    scope.launch {
                        val reply = CallLedger.undo(entry.id)
                        if (reply != null) {
                            Toast.makeText(context, reply, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDismiss = { CallLedger.clear() },
            )
        }

        Spacer(Modifier.height(6.dp))
    }
    }
}

/** Glowing orb at the centre of the call sheet. */
@Composable
private fun CallOrb(
    active: Boolean,
    speaking: Boolean,
    loading: Boolean,
    size: Dp,
) {
    val halo = rememberInfiniteTransition(label = "halo")
    val glow by halo.animateFloat(
        initialValue = 0.25f,
        targetValue = if (active) 0.65f else 0.25f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "glow",
    )
    val pulse by halo.animateFloat(
        initialValue = 0.94f,
        targetValue = if (speaking) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Soft halo behind the orb, breathing only while the call is live.
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (active) JwTheme.colors.callBar else MaterialTheme.colorScheme.surfaceVariant)
                .alpha(if (active) glow * 0.35f else 0.06f),
        )
        Box(
            modifier = Modifier
                .size(size * 0.7f * pulse)
                .clip(CircleShape)
                .background(
                    when {
                        !active -> MaterialTheme.colorScheme.surface
                        speaking -> MaterialTheme.colorScheme.primaryContainer
                        else -> JwTheme.colors.callGreen
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                Text(
                    text = "✆",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.displaySmall,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = if (active) JwTheme.colors.onCall else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.28f),
                )
            }
        }
    }
}

/** Ticking call timer, shown once the line is connected. */
@Composable
private fun CallTimer(startedAt: Long, running: Boolean) {
    var now by remember(startedAt) { mutableLongStateOf(startedAt) }
    LaunchedEffect(startedAt, running) {
        if (!running) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = if (running && startedAt > 0) ((now - startedAt) / 1_000).coerceAtLeast(0) else 0
    val minutes = elapsed / 60
    val seconds = elapsed % 60
    Text(
        text = "%02d:%02d".format(minutes, seconds),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The row of light bars under the name, alive while the call is. */
@Composable
private fun SpeakingBars(active: Boolean, speaking: Boolean, level: Float) {
    val transition = rememberInfiniteTransition(label = "bars")
    val wave by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "wave",
    )
    val heights = List(9) { index ->
        val stagger = ((index % 3) + 1) / 3f
        when {
            !active -> 6.dp
            speaking || level > 0.05f -> (12 + 26 * wave * stagger + 22 * level).dp
            else -> 8.dp
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        heights.forEach { height ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(height)
                    .clip(CircleShape)
                    .background(if (active) JwTheme.colors.callBar else MaterialTheme.colorScheme.surfaceVariant),
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
        shape = RoundedCornerShape(JwTokens.cardRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
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

/**
 * Everything the last call changed, one row per action, each with its own
 * undo — the two-second check on the drive before trusting the paperwork.
 */
@Composable
private fun PostCallSummary(
    entries: List<CallLedger.Entry>,
    onUndo: (CallLedger.Entry) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(JwTokens.controlRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "This call",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Undo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(14.dp)
                            .alpha(if (entry.undo != null) 1f else 0.25f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.undo != null) {
                        TextButton(
                            onClick = { onUndo(entry) },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("Undo") }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, pending: Boolean = false) {
    val container = if (fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    // A streaming partial is a rumour, not a fact: dimmed grey and italic
    // until the turn finalises, so live Nemotron feedback reads as provisional.
    val content = when {
        fromUser && pending -> MaterialTheme.colorScheme.onSurfaceVariant
        fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(JwTokens.cardRadius),
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

/** A round in-call control: Mute, Speaker or End. */
@Composable
private fun RoundControl(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(66.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onClick) { icon() }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Surface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            modifier = Modifier.size(68.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onClick) { icon() }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
