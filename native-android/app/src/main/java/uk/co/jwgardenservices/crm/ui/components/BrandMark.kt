package uk.co.jwgardenservices.crm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The wordmark from the web app's Logo component, rebuilt in Compose. */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    reversed: Boolean = false,
) {
    val foreground = if (reversed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 34.dp else 52.dp)
                .clip(RoundedCornerShape(if (compact) 10.dp else 16.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "JW",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 13.sp else 19.sp,
                letterSpacing = (-0.5).sp,
            )
        }
        if (!compact) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Garden Services",
                style = MaterialTheme.typography.titleLarge,
                color = foreground,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "CRM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
