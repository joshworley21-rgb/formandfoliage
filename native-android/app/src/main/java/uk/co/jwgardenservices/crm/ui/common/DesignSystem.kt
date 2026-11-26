package uk.co.jwgardenservices.crm.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens

/** True when a banner above the screen has already consumed the status inset. */
val LocalStatusBarInsetConsumed = staticCompositionLocalOf { false }

/**
 * Keep the system status bar transparent over the forest band with light
 * icons, while the white navigation bar keeps dark icons. Light screens
 * (auth, splash, lock) call this with false.
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

/**
 * The one page header. A full-bleed forest band with the screen's name, an
 * optional back affordance, one or two top actions, and a footer for status
 * tiles or controls. Home, Rota, Quotes, Costs, Coach and Settings all lead
 * with this, so changing tabs never feels like changing apps.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    eyebrow: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    topActions: @Composable RowScope.() -> Unit = {},
    titleAction: (@Composable () -> Unit)? = null,
    footerPadding: Dp = 14.dp,
    footerVisible: Boolean = false,
    largeTitle: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val palette = JwTheme.colors
    val ink = palette.onForestMuted
    val consumedStatusInset = LocalStatusBarInsetConsumed.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.forest,
        contentColor = ink,
    ) {
        Column(
            modifier = if (consumedStatusInset) Modifier else Modifier.statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = JwTokens.screenPadding,
                        end = JwTokens.screenPadding,
                        top = 8.dp,
                        bottom = if (footerVisible) 10.dp else footerPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    HeaderIconAction(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = backLabel?.let { "Back to $it" } ?: "Back",
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (eyebrow != null) {
                        Text(
                            text = eyebrow.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = title,
                        style = if (largeTitle) {
                            MaterialTheme.typography.headlineMedium
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = ink.copy(alpha = 0.68f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topActions()
                    if (titleAction != null) titleAction()
                }
            }
            if (footerVisible) {
                Column(
                    modifier = Modifier.padding(
                        start = JwTokens.screenPadding,
                        end = JwTokens.screenPadding,
                        bottom = 14.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    footer()
                }
            }
        }
    }
}

@Composable
fun HeaderIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val ink = JwTheme.colors.onForestMuted
    Surface(
        shape = CircleShape,
        color = ink.copy(alpha = 0.12f),
        contentColor = ink,
        modifier = Modifier.size(34.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun HeaderGhostAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val ink = JwTheme.colors.onForestMuted
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = ink.copy(alpha = 0.8f),
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
fun HeaderPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = JwTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(JwTokens.controlRadius),
        color = palette.brass,
        contentColor = palette.onBrass,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun HeaderOutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = JwTheme.colors.onForestMuted
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.controlRadius),
        color = Color.Transparent,
        contentColor = ink,
        border = BorderStroke(1.dp, ink.copy(alpha = 0.35f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
        )
    }
}

/** A two-to-four option segmented control. Used for tabs, not for filters. */
@Composable
fun <T> SegmentedPills(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = MaterialTheme.colorScheme.secondary,
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(JwTokens.chipRadius),
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

data class PillOption(val key: String, val label: String, val count: Int? = null)

/** Dark header status tile: used by Quotes and reusable by any dashboard band. */
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
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.smallRadius),
        color = ink.copy(alpha = if (active) 0.18f else 0.08f),
        contentColor = ink,
        border = if (active) {
            BorderStroke(1.dp, ink.copy(alpha = 0.45f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.sp),
                color = ink.copy(alpha = 0.65f),
                maxLines = 1,
            )
        }
    }
}

/** Scrolling chip filters for long lists (quotes, future invoice filters). */
@Composable
fun FilterPillRow(
    options: List<PillOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            val active = option.key == selected
            Surface(
                onClick = { onSelect(option.key) },
                shape = RoundedCornerShape(JwTokens.chipRadius),
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = if (active) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
            ) {
                Text(
                    text = if (option.count != null) {
                        "${option.label} (${option.count})"
                    } else {
                        option.label
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.smallRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Surface(
                    shape = RoundedCornerShape(JwTokens.smallRadius),
                    color = MaterialTheme.colorScheme.secondary,
                ) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** The floating create action shared by Rota and Quotes. */
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
        shadowElevation = 8.dp,
        modifier = modifier.size(JwTokens.fabSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

/** The modal shell: one radius, one padding rhythm, one surface. */
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
            shape = RoundedCornerShape(JwTokens.bandRadius),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/**
 * Material's AlertDialog keeps the system's default footprint; this is the
 * same confirm/cancel decision with the app's forest-paper surface and title
 * rhythm. Slots match AlertDialog exactly, so migrations stay mechanical.
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
        shape = RoundedCornerShape(JwTokens.bandRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = AlertDialogDefaults.titleContentColor,
        textContentColor = AlertDialogDefaults.textContentColor,
        title = {
            ProvideTextStyle(
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            ) {
                title()
            }
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
            .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
            .background(color),
    )
}

@Composable
fun AccentPill(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    container: Color = color.copy(alpha = 0.12f),
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = container,
        contentColor = color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

/** Quiet tappable row for More, settings groups and secondary actions. */
@Composable
fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.controlRadius),
        color = if (active) MaterialTheme.colorScheme.secondary else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(JwTokens.smallRadius),
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
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

/** Compact action used inside empty and summary surfaces. */
@Composable
fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.smallRadius),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(JwTokens.hairline)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** Short helper for the muted-on-forest copy repeated in every hero. */
@Composable
fun onForestMuted(alpha: Float = 1f): Color =
    JwTheme.colors.onForestMuted.copy(alpha = alpha)

/** White metric tile used for costs, visits and quote summaries. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    dark: Boolean = false,
) {
    val palette = JwTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.cardRadius),
        color = if (dark) palette.forest else MaterialTheme.colorScheme.surface,
        contentColor = if (dark) palette.onForestMuted else MaterialTheme.colorScheme.onSurface,
        border = if (dark) {
            null
        } else {
            BorderStroke(JwTokens.hairline, MaterialTheme.colorScheme.outline)
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (dark) {
                    palette.onForestMuted.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (dark) palette.brass else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dark) {
                        palette.onForestMuted.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun Modifier.screenPadding(): Modifier =
    this.padding(horizontal = JwTokens.screenPadding)
