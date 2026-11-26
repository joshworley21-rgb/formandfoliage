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
    primary = LightPrimary,
    onPrimary = LightPrimaryForeground,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightForeground,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightLime,
    onTertiary = LightLimeForeground,
    tertiaryContainer = LightLimeContainer,
    onTertiaryContainer = LightForeground,
    background = LightBackground,
    onBackground = LightForeground,
    surface = LightCard,
    onSurface = LightForeground,
    surfaceVariant = LightSecondary,
    onSurfaceVariant = LightMutedForeground,
    surfaceTint = LightPrimary,
    surfaceContainerLowest = LightCard,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    outline = LightBorder,
    outlineVariant = LightOutlineVariant,
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
    secondary = DarkSecondary,
    onSecondary = DarkForeground,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkLime,
    onTertiary = DarkLimeForeground,
    tertiaryContainer = DarkLimeContainer,
    onTertiaryContainer = DarkForeground,
    background = DarkBackground,
    onBackground = DarkForeground,
    surface = DarkCard,
    onSurface = DarkForeground,
    surfaceVariant = DarkSecondary,
    onSurfaceVariant = DarkMutedForeground,
    surfaceTint = DarkPrimary,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    outline = DarkBorder,
    outlineVariant = DarkOutlineVariant,
    error = DarkDestructive,
    onError = DarkDestructiveForeground,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

// Softer, rounder corners read as friendlier on a phone. Every screen uses
// these five steps; ad-hoc radii belong in the shared tokens, not in a screen.
internal val JwShapes = Shapes(
    extraSmall = RoundedCornerShape(JwTokens.smallRadius),
    small = RoundedCornerShape(JwTokens.fieldRadius),
    medium = RoundedCornerShape(JwTokens.controlRadius),
    large = RoundedCornerShape(JwTokens.cardRadius),
    extraLarge = RoundedCornerShape(JwTokens.bandRadius),
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
