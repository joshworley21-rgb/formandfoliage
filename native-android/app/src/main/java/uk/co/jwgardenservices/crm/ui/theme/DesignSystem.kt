package uk.co.jwgardenservices.crm.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The single source of truth for the app's measurements. Every screen, card,
 * pill and dialog reaches for these rather than inventing a nearby value, so
 * Home, Rota, Business, Invoices and Settings line up down the edge.
 */
object JwTokens {
    /** Left and right gutter of every screen, and of the header. */
    val screenPadding = 16.dp
    /** Vertical gap between cards in a list. */
    val sectionGap = 12.dp
    /** Padding inside a card. */
    val cardPadding = 16.dp
    /** White cards, forest hero cards and recessed KPI cards. */
    val cardRadius = 20.dp
    /** Dialogs and bottom sheets. */
    val sheetRadius = 24.dp
    /** Text fields, dropdowns and square-ish controls. */
    val controlRadius = 12.dp
    /** Tiles and rows nested inside a card. */
    val smallRadius = 10.dp
    val tinyRadius = 6.dp
    val microRadius = 4.dp
    /** Pills: tabs, chips, buttons. */
    val chipRadius = 50.dp
    val hairline = 1.dp
    val fabSize = 56.dp
    val fabMargin = 20.dp
    val bottomBarHeight = 64.dp
}

/**
 * Colours that Material's scheme does not name, but the product does: ochre,
 * the forest pair behind every hero, attention, and the cost-donut tones.
 * Provided once by [JwCrmTheme] and read through [JwTheme.colors].
 */
@Immutable
data class JwPalette(
    val brass: Color,
    val brassDeep: Color,
    val onBrass: Color,
    val positive: Color,
    val attention: Color,
    val info: Color,
    /** Pill tracks, quiet tiles and the selected row behind a list item. */
    val track: Color,
    val trackStrong: Color,
    /** Header and hero-card surface. */
    val forest: Color,
    /** Figures set into a forest surface: the Home KPI cards, status tiles. */
    val forestRecessed: Color,
    val forestRecessedBorder: Color,
    val forestSoft: Color,
    val onForestMuted: Color,
    /** The hairline around a white card. */
    val cardBorder: Color,
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
)

private val LightPalette = JwPalette(
    brass = Ochre,
    brassDeep = OchreDeep,
    onBrass = OnOchre,
    positive = LightPositive,
    attention = LightAttention,
    info = LightInfo,
    track = LightTrack,
    trackStrong = LightTrackStrong,
    forest = Forest,
    forestRecessed = ForestRecessed,
    forestRecessedBorder = ForestRecessedBorder,
    forestSoft = ForestSoft,
    onForestMuted = OnForest,
    cardBorder = LightCardBorder,
    dashed = LightDashed,
    costVehicle = LightCostTones[0],
    costInsurance = LightCostTones[1],
    costFuel = LightCostTones[2],
    costTools = LightCostTones[3],
    costSoftware = LightCostTones[4],
    costPremises = LightCostTones[5],
    costOther = LightCostTones[6],
    callGreen = CallGreenLight,
    callBar = CallBar,
    callEnd = CallEnd,
    callToggle = CallToggleLight,
    onCall = OnCall,
)

private val DarkPalette = JwPalette(
    brass = DarkOchre,
    brassDeep = Ochre,
    onBrass = OnOchre,
    positive = DarkPositive,
    attention = DarkAttention,
    info = DarkInfo,
    track = DarkTrack,
    trackStrong = DarkTrackStrong,
    forest = Forest,
    forestRecessed = ForestRecessed,
    forestRecessedBorder = ForestRecessedBorder,
    forestSoft = ForestSoft,
    onForestMuted = DarkForeground,
    cardBorder = DarkCardBorder,
    dashed = DarkDashed,
    costVehicle = DarkCostTones[0],
    costInsurance = DarkCostTones[1],
    costFuel = DarkCostTones[2],
    costTools = DarkCostTones[3],
    costSoftware = DarkCostTones[4],
    costPremises = DarkCostTones[5],
    costOther = DarkCostTones[6],
    callGreen = CallGreenDark,
    callBar = CallBar,
    callEnd = CallEnd,
    callToggle = CallToggleDark,
    onCall = OnCall,
)

val LocalJwPalette = staticCompositionLocalOf { LightPalette }

/** `JwTheme.colors.brass` — the shared handle every screen uses. */
object JwTheme {
    val colors: JwPalette
        @Composable get() = LocalJwPalette.current
}

fun jwPaletteFor(dark: Boolean): JwPalette = if (dark) DarkPalette else LightPalette
