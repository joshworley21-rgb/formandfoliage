package uk.co.jwgardenservices.crm.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens

// ---------------------------------------------------------------------------
// System bars
// ---------------------------------------------------------------------------

/**
 * Status-bar icon colour. The signed-in app sits under a forest header, so
 * [darkStatusBar] is true there and the icons are white
 * (`isAppearanceLightStatusBars = false`). Light screens (auth, splash, lock)
 * pass false. The white navigation bar always keeps dark icons.
 */
@Composable
fun SystemBarAppearance(darkStatusBar: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkStatusBar
        controller.isAppearanceLightNavigationBars = true
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * The one page header: a forest band with the title and a short subtitle on
 * the left and the screen's actions on the same row on the right, so a header
 * never costs more than one row of height. [footer] holds content that belongs
 * to the band itself (Home's KPI cards, Quotes' status tiles).
 *
 * The status-bar inset is applied once, by MainScaffold, above every screen;
 * this composable deliberately adds none of its own.
 */
@Composable
fun CompactScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val palette = JwTheme.colors
    val ink = palette.onForestMuted
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.forest,
        contentColor = ink,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = JwTokens.screenPadding, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        HeaderIconAction(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = backLabel?.let { "Back to $it" } ?: "Back",
                            onClick = onBack,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = ink.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
            if (footer != null) {
                Column(
                    modifier = Modifier.padding(
                        start = JwTokens.screenPadding,
                        end = JwTokens.screenPadding,
                        top = 4.dp,
                        bottom = 14.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = footer,
                )
            }
        }
    }
}

/** Round icon button for the header: notifications, mic, refresh, back. */
@Composable
fun HeaderIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val ink = JwTheme.colors.onForestMuted
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = ink.copy(alpha = 0.12f),
        contentColor = ink,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The `< Today >` control: one outlined pill, three targets. */
@Composable
fun HeaderStepper(
    label: String,
    onPrevious: () -> Unit,
    onLabel: () -> Unit,
    onNext: () -> Unit,
) {
    val ink = JwTheme.colors.onForestMuted
    Surface(
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = ink.copy(alpha = 0.08f),
        contentColor = ink,
        border = BorderStroke(JwTokens.hairline, ink.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperTarget(onPrevious) {
                Icon(
                    Icons.Outlined.ChevronLeft,
                    contentDescription = "Previous",
                    modifier = Modifier.size(18.dp),
                )
            }
            StepperTarget(onLabel) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            StepperTarget(onNext) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "Next",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun StepperTarget(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = Color.Transparent,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Pills: buttons, tabs, filters
// ---------------------------------------------------------------------------

enum class PillStyle {
    /** Ochre. The one primary action on a screen: New invoice, + Add, Review. */
    Gold,
    /** Forest. A committed action inside a white card: Bill all. */
    Forest,
    /** Sage track. A quiet secondary action: Open rota, Open invoices. */
    Quiet,
    /** Outline on the forest header: Missed. */
    OnForest,
}

/** Every pill-shaped button in the app. */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Gold,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fill: Boolean = false,
) {
    val palette = JwTheme.colors
    val container = when (style) {
        PillStyle.Gold -> palette.brass
        PillStyle.Forest -> palette.forest
        PillStyle.Quiet -> palette.track
        PillStyle.OnForest -> Color.Transparent
    }
    val content = when (style) {
        PillStyle.Gold -> palette.onBrass
        PillStyle.Forest -> palette.onForestMuted
        PillStyle.Quiet -> MaterialTheme.colorScheme.onSurface
        PillStyle.OnForest -> palette.onForestMuted
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = if (enabled) container else container.copy(alpha = 0.45f),
        contentColor = if (enabled) content else content.copy(alpha = 0.6f),
        border = if (style == PillStyle.OnForest) {
            BorderStroke(JwTokens.hairline, palette.onForestMuted.copy(alpha = 0.35f))
        } else {
            null
        },
    ) {
        Row(
            modifier = (if (fill) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = 36.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/**
 * The uniform segmented selector: `This week | Today | Cash flow`,
 * `Week | Month | List`, `Overview | Costs | Coach | Secretary`.
 */
@Composable
fun <T> SegmentedPillTabs(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val palette = JwTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = palette.track,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(JwTokens.chipRadius),
                    color = if (active) palette.forest else Color.Transparent,
                    contentColor = if (active) {
                        palette.onForestMuted
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One filter pill: forest when on, white with a hairline when off. */
@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = JwTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = if (selected) palette.forest else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) {
            palette.onForestMuted
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) null else BorderStroke(JwTokens.hairline, palette.cardBorder),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp))
        }
    }
}

/** The floating ochre `+`. */
@Composable
fun GoldFab(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: ImageVector,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = JwTheme.colors.brass,
        contentColor = JwTheme.colors.onBrass,
        shadowElevation = 6.dp,
        modifier = modifier.size(JwTokens.fabSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** The white card: 16dp radius, 1dp hairline, no shadow. */
@Composable
fun JwCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(JwTokens.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    CardSurface(
        modifier = modifier,
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(JwTokens.hairline, JwTheme.colors.cardBorder),
        padding = padding,
        content = content,
    )
}

/** The forest hero card: WEEK TOTALS, NET POSITION, OWED TO YOU. */
@Composable
fun ForestCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(JwTokens.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    CardSurface(
        modifier = modifier,
        onClick = onClick,
        color = JwTheme.colors.forest,
        contentColor = JwTheme.colors.onForestMuted,
        border = null,
        padding = padding,
        content = content,
    )
}

/**
 * The darker card set into a forest surface: Home's KPI pair, status tiles.
 * The sage hairline is what keeps it readable against the header.
 */
@Composable
fun RecessedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    active: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(JwTokens.cardRadius),
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = JwTheme.colors
    CardSurface(
        modifier = modifier,
        onClick = onClick,
        shape = shape,
        color = palette.forestRecessed,
        contentColor = palette.onForestMuted,
        border = BorderStroke(
            JwTokens.hairline,
            if (active) palette.brass else palette.forestRecessedBorder,
        ),
        padding = padding,
        content = content,
    )
}

@Composable
private fun CardSurface(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    color: Color,
    contentColor: Color,
    border: BorderStroke?,
    padding: PaddingValues,
    shape: RoundedCornerShape = RoundedCornerShape(JwTokens.cardRadius),
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(padding), content = content)
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = body,
        )
    }
}

/** The uppercase eyebrow above a figure or a section: THIS WEEK, PROFIT & LOSS. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    onForest: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (onForest) {
            JwTheme.colors.onForestMuted.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A figure with its caption, for the row of numbers inside a forest card. */
@Composable
fun ForestFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = JwTheme.colors.brass,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = JwTheme.colors.onForestMuted.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A count tile set into a forest surface; doubles as a filter. */
@Composable
fun StatusTile(
    label: String,
    count: Int,
    icon: ImageVector,
    tint: Color,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = JwTheme.colors.onForestMuted
    RecessedCard(
        modifier = modifier,
        onClick = onClick,
        active = active,
        shape = RoundedCornerShape(JwTokens.controlRadius),
        padding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
                color = ink.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

/** White metric tile: a label, a figure and an optional caption. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
) {
    JwCard(modifier = modifier, padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A hairline strip of context above a list. */
@Composable
fun SummaryStrip(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    JwCard(
        modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Surface(
                    shape = RoundedCornerShape(JwTokens.chipRadius),
                    color = JwTheme.colors.track,
                ) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The status edge used by quote and invoice cards: state is visible before a
 * word is read.
 */
@Composable
fun StatusStripe(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/** Tappable row for the More sheet and other menus. */
@Composable
fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    active: Boolean = false,
) {
    val palette = JwTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.controlRadius),
        color = if (active) palette.track else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (active) palette.forest else palette.track),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (active) palette.brass else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/** The form dialog shell: the white card surface at sheet radius. */
@Composable
fun JwDialog(
    onDismissRequest: () -> Unit,
    maxHeight: Dp = 780.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(JwTokens.sheetRadius),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(JwTokens.hairline, JwTheme.colors.cardBorder),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/**
 * Confirm/cancel decisions. Same slots as Material's AlertDialog, on the same
 * white surface, radius and title style as [JwDialog].
 */
@Composable
fun JwAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(JwTokens.sheetRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        title = {
            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
        },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

@Composable
fun DialogTitle(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Short helper for the muted-on-forest copy repeated in every hero. */
@Composable
fun onForestMuted(alpha: Float = 1f): Color =
    JwTheme.colors.onForestMuted.copy(alpha = alpha)
