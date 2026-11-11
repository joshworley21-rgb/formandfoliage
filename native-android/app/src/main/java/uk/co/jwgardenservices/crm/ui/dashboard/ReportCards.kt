package uk.co.jwgardenservices.crm.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.TextStyle
import java.util.Locale
import uk.co.jwgardenservices.crm.domain.ClientTotal
import uk.co.jwgardenservices.crm.domain.MonthTotal
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.ui.common.SectionCard

@Composable
fun RevenueCard(months: List<MonthTotal>) {
    SectionCard(title = "Last 6 months") {
        val peak = months.maxOfOrNull { maxOf(it.invoiced, it.paid) }?.takeIf { it > 0 } ?: 1.0
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                months.forEach { month ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.height(96.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Bar((month.invoiced / peak).toFloat(), MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                            Bar((month.paid / peak).toFloat(), MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            text = month.month.month.getDisplayName(TextStyle.SHORT, Locale.UK),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            val current = months.lastOrNull()
            if (current != null) {
                Text(
                    text = "This month: ${money(current.invoiced)} invoiced · ${money(current.paid)} paid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), "Invoiced")
                Legend(MaterialTheme.colorScheme.primary, "Paid")
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .width(14.dp)
            .height((96 * fraction.coerceIn(0.02f, 1f)).dp)
            .background(color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
    )
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun TopClientsCard(clients: List<ClientTotal>, clientName: (String) -> String) {
    SectionCard(title = "Top clients (12 months)") {
        if (clients.isEmpty()) {
            Text(
                text = "Invoices will rank your best clients here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@SectionCard
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            clients.forEachIndexed { index, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        text = clientName(row.clientId),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = money(row.total),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
