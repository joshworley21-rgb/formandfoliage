package uk.co.jwgardenservices.crm.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens

/**
 * The frosted-glass look: a translucent pane with a light catching its top
 * edge. It is drawn, not blurred, so it costs nothing on an older phone; it
 * reads as glass when it sits over [glassBackdrop] or any tinted ground.
 */
@Composable
fun Modifier.glass(shape: Shape, strength: Float = 0.62f): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val fill = Brush.verticalGradient(
        listOf(
            surface.copy(alpha = (strength + 0.14f).coerceAtMost(1f)),
            surface.copy(alpha = strength),
        ),
    )
    val edge = Brush.verticalGradient(
        if (dark) {
            listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.04f))
        } else {
            listOf(Color.White.copy(alpha = 0.95f), JwTheme.colors.cardBorder.copy(alpha = 0.55f))
        },
    )
    return this
        .clip(shape)
        .background(fill)
        .border(JwTokens.hairline, edge, shape)
}

/** A soft wash of the brand colours for glass panes to sit on. */
@Composable
fun Modifier.glassBackdrop(): Modifier {
    val palette = JwTheme.colors
    val ground = MaterialTheme.colorScheme.background
    val dark = ground.luminance() < 0.5f
    return this
        .background(ground)
        .background(
            Brush.verticalGradient(
                0f to palette.forestSoft.copy(alpha = if (dark) 0.55f else 0.20f),
                0.45f to Color.Transparent,
                1f to palette.brass.copy(alpha = if (dark) 0.10f else 0.14f),
            ),
        )
}
