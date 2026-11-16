package uk.co.jwgardenservices.crm.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The in-call screen, designed to be read at a glance in a van: status at
 * the top, one big breathing visualiser in the middle, the half-heard words
 * streaming in the lower third, and an end button the size of a palm.
 *
 * Fully dark and high contrast on purpose — sun through the windscreen,
 * gloves, no reading glasses.
 */
@Composable
fun CallScreen(
    dialling: Boolean,
    connectedAt: Long,
    speaking: Boolean,
    level: Float,
    partial: String,
    muted: Boolean,
    speakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEnd: () -> Unit,
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- status -------------------------------------------------------
        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
        if (dialling) {
            Text(
                text = "Calling…",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = CallDim,
            )
            Text(
                text = "warming up the models",
                style = MaterialTheme.typography.labelMedium,
                color = CallDim.copy(alpha = 0.7f),
            )
        } else {
            CallClock(connectedAt = connectedAt)
        }

        // --- visualiser ---------------------------------------------------
        Spacer(Modifier.weight(1f))
        CallVisualizer(
            speaking = speaking && !dialling,
            level = if (dialling) 0f else level,
            size = if (compact) 170.dp else 220.dp,
        )
        Spacer(Modifier.weight(1f))

        // --- live partials ------------------------------------------------
        val hint = when {
            dialling -> "Connecting…"
            partial.isNotBlank() -> partial
            muted -> "Microphone muted"
            else -> "Listening…"
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = if (partial.isNotBlank()) FontStyle.Italic else FontStyle.Normal,
            color = if (partial.isNotBlank()) PartialColor else CallDim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )

        // --- controls -----------------------------------------------------
        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundCallToggle(
                icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                description = if (muted) "Unmute" else "Mute",
                active = !muted,
                onClick = onToggleMute,
            )
            RoundCallToggle(
                icon = if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                description = if (speakerOn) "Speaker off" else "Speaker on",
                active = speakerOn,
                onClick = onToggleSpeaker,
            )
        }

        // --- end call -----------------------------------------------------
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        Button(
            onClick = onEnd,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = EndRed,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 16.dp else 24.dp)
                .height(if (compact) 68.dp else 78.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CallEnd,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "End Call",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
    }
}

/** MM:SS, ticking only while the line is connected. */
@Composable
private fun CallClock(connectedAt: Long) {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(connectedAt) {
        if (connectedAt <= 0L) {
            seconds = 0L
            return@LaunchedEffect
        }
        while (true) {
            seconds = (System.currentTimeMillis() - connectedAt) / 1000
            delay(250)
        }
    }
    Text(
        text = "%02d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        color = CallOn,
    )
}

/**
 * The one glanceable element: a ring that breathes while the assistant
 * speaks and swells with the owner's own voice level while he does.
 */
@Composable
private fun CallVisualizer(speaking: Boolean, level: Float, size: Dp) {
    val breathe = rememberInfiniteTransition(label = "breathe")
    val pulse by breathe.animateFloat(
        initialValue = 0.96f,
        targetValue = if (speaking) 1.07f else 1f,
        animationSpec = infiniteRepeatable(tween(if (speaking) 750 else 2200), RepeatMode.Reverse),
        label = "pulse",
    )
    val glow by breathe.animateFloat(
        initialValue = 0.35f,
        targetValue = if (speaking) 0.9f else 0.45f,
        animationSpec = infiniteRepeatable(tween(if (speaking) 750 else 2200), RepeatMode.Reverse),
        label = "glow",
    )

    Canvas(modifier = Modifier.size(size)) {
        val centre = Offset(size.toPx() / 2f, size.toPx() / 2f)
        val base = size.toPx() * 0.32f
        val live = level.coerceIn(0f, 1f)
        val scale = pulse * (1f + live * 0.35f)

        // Outer halo: loudest when the assistant has the floor.
        drawCircle(
            color = Green.copy(alpha = glow * (if (speaking) 0.5f else 0.3f) + live * 0.2f),
            radius = base * scale * 1.35f,
            center = centre,
        )
        // Reactive ring: swells with the owner's own voice.
        if (live > 0.02f) {
            drawCircle(
                color = Green.copy(alpha = 0.25f + live * 0.5f),
                radius = base * (1.15f + live * 0.5f),
                center = centre,
                style = Stroke(width = 6f, cap = StrokeCap.Round),
            )
        }
        // The core disc.
        drawCircle(color = Green.copy(alpha = 0.92f), radius = base * scale, center = centre)
        drawCircle(
            color = Color.White.copy(alpha = 0.12f),
            radius = base * scale * 0.72f,
            center = centre,
        )
    }
}

@Composable
private fun RoundCallToggle(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) ToggleActive else ToggleIdle,
        contentColor = if (active) Color.White else CallDim,
        modifier = Modifier.size(58.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(26.dp))
        }
    }
}

private val CallOn = Color(0xFFF2F2F2)
private val CallDim = Color(0xFF8F978D)
private val PartialColor = Color(0xFF9AA49A)
private val Green = Color(0xFF63C176)
private val EndRed = Color(0xFFC33B3B)
private val ToggleActive = Color(0xFF2C4A2E)
private val ToggleIdle = Color(0xFF232323)
