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
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.voice.AssistantService
import uk.co.jwgardenservices.crm.voice.AssistantTurn
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
        launch { bound.muted.collect { muted = it } }
        launch { bound.speakerOn.collect { speakerOn = it } }
        launch { bound.connectedAt.collect { connectedAt = it } }
        launch { bound.turns.collect { turns = it } }
    }

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
            .background(CallBg),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Assistant",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = CallDim,
            )
            Spacer(Modifier.weight(1f))
            if (turns.isNotEmpty()) {
                IconButton(onClick = { service?.clearTranscript() }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Clear conversation",
                        tint = CallDim,
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
            text = "J W Assistant",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (callActive) CallOn else CallDim,
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
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1C1C1C),
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = if (state.phase == SherpaSpeechService.Phase.Error) {
                    Color(0xFFFF8A80)
                } else {
                    CallDim
                },
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        if (!granted) {
            Text(
                text = "The assistant needs the microphone to hear you. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = CallDim,
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

        if (callActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundControl(
                    label = if (muted) "Unmute" else "Mute",
                    container = if (muted) Color(0xFF5C1F1F) else ControlBg,
                    content = if (muted) Color(0xFFFF8A80) else CallOn,
                    onClick = { service?.setMuted(!muted) },
                ) {
                    Icon(
                        imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (muted) "Unmute" else "Mute",
                        modifier = Modifier.size(26.dp),
                    )
                }
                RoundControl(
                    label = "Speaker",
                    container = if (speakerOn) Color(0xFF1F3A5C) else ControlBg,
                    content = if (speakerOn) Color(0xFF8AC6FF) else CallOn,
                    onClick = { service?.setSpeaker(!speakerOn) },
                ) {
                    Icon(
                        imageVector = if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = "Toggle speaker",
                        modifier = Modifier.size(26.dp),
                    )
                }
                RoundControl(
                    label = "End",
                    container = Color(0xFFC33B3B),
                    content = Color.White,
                    onClick = { service?.endCall() },
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = "End call",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        } else {
            CallButton(
                label = if (granted) "Call" else "Allow microphone",
                container = CallGreen,
                content = Color.White,
                onClick = {
                    if (granted) startCall(context) else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
            ) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = "Call the assistant",
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
    }
    }
}

/** Phone-call colours, independent of the app theme. */
private val CallBg = Color(0xFF060606)
private val CallOn = Color(0xFFF2F2F2)
private val CallDim = Color(0xFF8F978D)
private val ControlBg = Color(0xFF232323)
private val BarGreen = Color(0xFFB6E388)

/** The familiar phone-call green, independent of the app's theme. */
private val CallGreen = Color(0xFF1B8F3A)

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
                .background(if (active) BarGreen else Color(0xFF3A3F3A))
                .alpha(if (active) glow * 0.35f else 0.06f),
        )
        Box(
            modifier = Modifier
                .size(size * 0.7f * pulse)
                .clip(CircleShape)
                .background(
                    when {
                        !active -> Color(0xFF2A2D2A)
                        speaking -> Color(0xFF37531F)
                        else -> Color(0xFF1B8F3A)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                Text(
                    text = "✆",
                    color = CallOn,
                    style = MaterialTheme.typography.displaySmall,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = if (active) Color(0xFFE9F7DC) else CallDim,
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
        color = CallDim,
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
                    .background(if (active) BarGreen else Color(0xFF3A3F3A)),
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
        color = Color(0xFF111111),
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
                    color = CallDim,
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
    val container = if (fromUser) Color(0xFF233023) else Color(0xFF1D1D1D)
    val content = if (fromUser) Color(0xFFD9E8CF) else CallOn
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
            color = CallDim,
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
            color = CallDim,
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
