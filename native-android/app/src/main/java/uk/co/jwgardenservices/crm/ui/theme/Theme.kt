package uk.co.jwgardenservices.crm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = LightPrimaryForeground,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightForeground,
    secondary = LightTrack,
    onSecondary = LightForeground,
    secondaryContainer = LightTrack,
    onSecondaryContainer = LightForeground,
    tertiary = Ochre,
    onTertiary = OnOchre,
    tertiaryContainer = LightOchreContainer,
    onTertiaryContainer = LightForeground,
    background = LightBackground,
    onBackground = LightForeground,
    surface = LightCard,
    onSurface = LightForeground,
    surfaceVariant = LightTrack,
    onSurfaceVariant = LightMutedForeground,
    surfaceTint = LightCard,
    surfaceContainerLowest = LightCard,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightCard,
    surfaceContainerHigh = LightCard,
    surfaceContainerHighest = LightTrack,
    outline = LightFieldBorder,
    outlineVariant = LightCardBorder,
    error = LightDestructive,
    onError = LightDestructiveForeground,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkPrimaryForeground,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkTrack,
    onSecondary = DarkForeground,
    secondaryContainer = DarkTrackStrong,
    onSecondaryContainer = DarkForeground,
    tertiary = DarkOchre,
    onTertiary = OnOchre,
    tertiaryContainer = DarkOchreContainer,
    onTertiaryContainer = DarkForeground,
    background = DarkBackground,
    onBackground = DarkForeground,
    surface = DarkCard,
    onSurface = DarkForeground,
    surfaceVariant = DarkTrack,
    onSurfaceVariant = DarkMutedForeground,
    surfaceTint = DarkCard,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkCard,
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkFieldBorder,
    outlineVariant = DarkCardBorder,
    error = DarkDestructive,
    onError = DarkDestructiveForeground,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

// One radius per job: fields, cards, sheets. Material components (menus, text
// fields, date pickers, dialogs) inherit these, so they match the hand-built
// surfaces without each call site passing a shape.
internal val JwShapes = Shapes(
    extraSmall = RoundedCornerShape(JwTokens.controlRadius),
    small = RoundedCornerShape(JwTokens.controlRadius),
    medium = RoundedCornerShape(JwTokens.cardRadius),
    large = RoundedCornerShape(JwTokens.cardRadius),
    extraLarge = RoundedCornerShape(JwTokens.sheetRadius),
)

@Composable
fun JwCrmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalJwPalette provides jwPaletteFor(darkTheme)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = JwTypography,
            shapes = JwShapes,
            content = content,
        )
    }
}
