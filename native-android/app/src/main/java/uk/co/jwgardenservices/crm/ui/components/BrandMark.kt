package uk.co.jwgardenservices.crm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.co.jwgardenservices.crm.R

/**
 * The Form & Foliage lockup: the seedling-in-a-clip mark above the stacked
 * wordmark. Tinted from the theme, so it works on cream, on the primary green
 * banner and on the dark theme without a second asset.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    reversed: Boolean = false,
) {
    val ink = if (reversed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val textColour = if (reversed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = "Form & Foliage",
            colorFilter = ColorFilter.tint(ink),
            modifier = Modifier.size(
                width = if (compact) 24.dp else 54.dp,
                height = if (compact) 32.dp else 72.dp,
            ),
        )
        if (!compact) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Form &\nFoliage",
                style = MaterialTheme.typography.headlineSmall,
                color = textColour,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.headlineSmall.fontSize * 1.05f,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "CRM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
