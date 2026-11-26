package uk.co.jwgardenservices.crm.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for the app's measurements. Every screen, card,
 * pill and dialog reaches for these rather than inventing a nearby value, so
 * Home, Rota, Quotes, Costs, Coach and Settings line up down the edge.
 */
object JwTokens {
    val screenPadding = 20.dp
    val sectionGap = 12.dp
    val cardRadius = 20.dp
    val bandRadius = 28.dp
    val controlRadius = 14.dp
    val smallRadius = 12.dp
    val tinyRadius = 8.dp
    val microRadius = 4.dp
    val fieldRadius = 14.dp
    val chipRadius = 50.dp
    val hairline = 1.dp
    val fabSize = 56.dp
    val fabMargin = 20.dp
    val headerTop = 14.dp
    val headerBottom = 18.dp
}

/**
 * Colours that Material's scheme does not name, but the product does: brass,
 * attention, the cost-donut tones, and the muted-on-forest pair used across
 * every hero. Provided once by [JwCrmTheme] and read through [JwTheme.colors].
 */
@Immutable
data class JwPalette(
    val brass: Color,
    val brassDeep: Color,
    val onBrass: Color,
    val positive: Color,
    val attention: Color,
    val info: Color,
    val sage: Color,
    val sageStrong: Color,
    val forest: Color,
    val forestDeep: Color,
    val forestSoft: Color,
    val onForestMuted: Color,
    val hairline: Color,
    val dashed: Color,
    val costVehicle: Color,
    val costInsurance: Color,
    val costFuel: Color,
    val costTools: Color,
    val costSoftware: Color,
    val costPremises: Color,
    val costOther: Color,
    val callGreen: Color,
    val callBar: Color,
    val callEnd: Color,
    val callToggle: Color,
    val onCall: Color,
    val kpiCard: Color,
    val kpiCardAlt: Color,
    val kpiBorder: Color,
)

private val LightPalette = JwPalette(
    brass = Color(0xFFC9A96A),
    brassDeep = Color(0xFF9C7A35),
    onBrass = Color(0xFF2A2213),
    positive = Color(0xFF3F7A4E),
    attention = Color(0xFFB4553F),
    info = Color(0xFF4C6A92),
    sage = Color(0xFFE6EADF),
    sageStrong = Color(0xFFD8E2D1),
    forest = Color(0xFF233828),
    forestDeep = Color(0xFF1B2A1C),
    forestSoft = Color(0xFF2E4A34),
    onForestMuted = Color(0xFFFFFCF2),
    hairline = Color(0xFFD6DCCF),
    dashed = Color(0xFF9CA88F),
    costVehicle = Color(0xFF2E4A34),
    costInsurance = Color(0xFF394553),
    costFuel = Color(0xFF6E8B6E),
    costTools = Color(0xFF8CA07E),
    costSoftware = Color(0xFF586A5A),
    costPremises = Color(0xFFA9B79A),
    costOther = Color(0xFF7E9A79),
    callGreen = Color(0xFF1B8F3A),
    callBar = Color(0xFFB6E388),
    callEnd = Color(0xFFC33B3B),
    callToggle = Color(0xFF2C4A2E),
    onCall = Color(0xFFFFFFFF),
    kpiCard = Color(0xFF142217),
    kpiCardAlt = Color(0xFF142217),
    kpiBorder = Color(0x668FA392),
)

private val DarkPalette = JwPalette(
    brass = Color(0xFFE2C588),
    brassDeep = Color(0xFFC9A96A),
    onBrass = Color(0xFF2B2110),
    positive = Color(0xFF7FD78E),
    attention = Color(0xFFFF9A84),
    info = Color(0xFF9FB6D8),
    sage = Color(0xFF29372A),
    sageStrong = Color(0xFF354535),
    forest = Color(0xFF233828),
    forestDeep = Color(0xFF18251A),
    forestSoft = Color(0xFF2E4A34),
    onForestMuted = Color(0xFFF1EFE4),
    hairline = Color(0xFF41503F),
    dashed = Color(0xFF64745F),
    costVehicle = Color(0xFF6E8B6E),
    costInsurance = Color(0xFF8A9BB0),
    costFuel = Color(0xFF94B18F),
    costTools = Color(0xFFB0C4A1),
    costSoftware = Color(0xFF7C8F7C),
    costPremises = Color(0xFFC3D0B5),
    costOther = Color(0xFF9DB697),
    callGreen = Color(0xFF63C176),
    callBar = Color(0xFFB6E388),
    callEnd = Color(0xFFC33B3B),
    callToggle = Color(0xFF36583A),
    onCall = Color(0xFFFFFFFF),
    kpiCard = Color(0xFF142217),
    kpiCardAlt = Color(0xFF142217),
    kpiBorder = Color(0x668FA392),
)

val LocalJwPalette = staticCompositionLocalOf { LightPalette }

/** `JwTheme.colors.brass` — the shared handles every screen should use. */
object JwTheme {
    val colors: JwPalette
        @Composable get() = LocalJwPalette.current

    val spacing: JwSpacing
        get() = JwSpacing
}

object JwSpacing {
    val screen = JwTokens.screenPadding
    val section = JwTokens.sectionGap
    val card = JwTokens.cardRadius
    val band = JwTokens.bandRadius
    val control = JwTokens.controlRadius
    val chip = JwTokens.chipRadius
}

fun jwPaletteFor(dark: Boolean): JwPalette = if (dark) DarkPalette else LightPalette
