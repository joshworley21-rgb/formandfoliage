package uk.co.jwgardenservices.crm.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The only file in the app that spells a colour as hex. The Material scheme in
 * Theme.kt and the product palette in DesignSystem.kt both read from here, so
 * a screen can never drift onto a nearby shade.
 *
 * Paper and forest: a warm off-white ground, crisp white cards with a hairline
 * edge, deep forest for the header and the hero cards, a darker recessed green
 * for figures set into a hero, sage for labels and pill tracks, and ochre for
 * the one action that matters. Text pairs are at least 4.5:1.
 */

// Brand: identical in light and dark, because the forest blocks never invert.
val Forest = Color(0xFF233828)
val ForestRecessed = Color(0xFF17261B)
val ForestRecessedBorder = Color(0x598FA392) // #8FA392 at 35%
val ForestSoft = Color(0xFF2E4A34)
val OnForest = Color(0xFFFFFCF2)
val Ochre = Color(0xFFC5A358)
val OchreDeep = Color(0xFF96782F)
val OnOchre = Color(0xFF2A2213)

// Light
val LightBackground = Color(0xFFF4F3EE)
val LightForeground = Color(0xFF1B2A1C)
val LightCard = Color(0xFFFFFFFF)
val LightCardBorder = Color(0xFFE5E3DA)
val LightTrack = Color(0xFFEAE8DF)
val LightTrackStrong = Color(0xFFDFDCD0)
val LightPrimaryForeground = Color(0xFFF6F2E7)
val LightPrimaryContainer = Color(0xFFDFE6D8)
val LightMutedForeground = Color(0xFF68746A)
val LightFieldBorder = Color(0xFFC9C7BA)
val LightDashed = Color(0xFFA9AE9C)
val LightDestructive = Color(0xFFA63D2B)
val LightDestructiveForeground = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFF4DCD5)
val LightOnErrorContainer = Color(0xFF4A1208)
val LightOchreContainer = Color(0xFFEDE2C4)
val LightSurfaceLow = Color(0xFFF7F6F2)
val LightPositive = Color(0xFF3F7A4E)
val LightAttention = Color(0xFFB4553F)
val LightInfo = Color(0xFF4C6A92)

// Dark (opt-in from Settings → Appearance)
val DarkBackground = Color(0xFF141D15)
val DarkForeground = Color(0xFFF1EFE4)
val DarkCard = Color(0xFF1E2A1F)
val DarkCardBorder = Color(0xFF33402F)
val DarkTrack = Color(0xFF29372A)
val DarkTrackStrong = Color(0xFF354535)
val DarkPrimary = Color(0xFFD7E3CC)
val DarkPrimaryForeground = Color(0xFF1B2A1C)
val DarkPrimaryContainer = Color(0xFF2E4030)
val DarkOnPrimaryContainer = Color(0xFFE4EFDC)
val DarkMutedForeground = Color(0xFFB3BFB0)
val DarkFieldBorder = Color(0xFF55664F)
val DarkDashed = Color(0xFF64745F)
val DarkDestructive = Color(0xFFFF8A74)
val DarkDestructiveForeground = Color(0xFF3A0A03)
val DarkErrorContainer = Color(0xFF742A1B)
val DarkOnErrorContainer = Color(0xFFFFDAD3)
val DarkOchre = Color(0xFFE2C588)
val DarkOchreContainer = Color(0xFF4D4225)
val DarkSurfaceLow = Color(0xFF1B271C)
val DarkSurfaceHigh = Color(0xFF2C3A2C)
val DarkPositive = Color(0xFF7FD78E)
val DarkAttention = Color(0xFFFF9A84)
val DarkInfo = Color(0xFF9FB6D8)

// Cost-category tones for the Business donut: one sage family, light to deep.
val LightCostTones = listOf(
    Color(0xFF2E4A34), Color(0xFF394553), Color(0xFF6E8B6E), Color(0xFF8CA07E),
    Color(0xFF586A5A), Color(0xFFA9B79A), Color(0xFF7E9A79),
)
val DarkCostTones = listOf(
    Color(0xFF6E8B6E), Color(0xFF8A9BB0), Color(0xFF94B18F), Color(0xFFB0C4A1),
    Color(0xFF7C8F7C), Color(0xFFC3D0B5), Color(0xFF9DB697),
)

// The in-call screen keeps telephone conventions: green to talk, red to end.
val CallGreenLight = Color(0xFF1B8F3A)
val CallGreenDark = Color(0xFF63C176)
val CallBar = Color(0xFFB6E388)
val CallEnd = Color(0xFFC33B3B)
val CallToggleLight = Color(0xFF2C4A2E)
val CallToggleDark = Color(0xFF36583A)
val OnCall = Color(0xFFFFFFFF)
