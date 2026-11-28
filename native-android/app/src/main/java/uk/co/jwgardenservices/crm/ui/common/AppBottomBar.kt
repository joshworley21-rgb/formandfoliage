package uk.co.jwgardenservices.crm.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens

data class BottomBarEntry(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * The bottom bar: a floating frosted pill. The active tab sits inside a
 * rounded forest pill with an ochre icon; the rest are muted sage.
 */
@Composable
fun AppBottomBar(entries: List<BottomBarEntry>, modifier: Modifier = Modifier) {
    val palette = JwTheme.colors
    val barShape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 10.dp, shape = barShape, clip = false)
                .glass(barShape, strength = 0.80f)
                .height(JwTokens.bottomBarHeight)
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            entries.forEach { entry ->
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    onClick = entry.onClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics {
                            role = Role.Tab
                            selected = entry.selected
                        },
                    shape = RoundedCornerShape(22.dp),
                    color = if (entry.selected) palette.forest else Color.Transparent,
                    contentColor = if (entry.selected) palette.onForestMuted else muted,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(
                            imageVector = entry.icon,
                            contentDescription = null,
                            tint = if (entry.selected) palette.brass else muted,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                            ),
                            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
