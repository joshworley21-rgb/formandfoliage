package uk.co.jwgardenservices.crm.ui.schedule

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.ui.common.MessageDialog
import java.time.Instant

/** One job on a day's route, in start-time order. */
data class RouteStop(
    val time: Instant?,
    val client: String,
    val address: String?,
    val phone: String?,
)

/**
 * The day's stops in time order, with one Google Maps link and a prefilled
 * "on my way" message per client. Addresses are passed as waypoints; Maps does
 * the driving, so no geocoding key or server call is involved.
 */
@Composable
fun RouteDialog(
    title: String,
    stops: List<RouteStop>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var messaging by remember { mutableStateOf<RouteStop?>(null) }
    val routable = stops.filter { !it.address.isNullOrBlank() }
    val used = routable.take(MAX_STOPS)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        routable.isEmpty() -> "No client on this day has an address."
                        routable.size > used.size ->
                            "${used.size} stops in time order; Google Maps takes $MAX_STOPS."
                        else -> "${used.size} stops in time order."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    stops.forEach { stop ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stop.time?.formatTime() ?: "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stop.client,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stop.address?.takeIf { it.isNotBlank() }
                                        ?: "No address — skipped",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!stop.phone.isNullOrBlank()) {
                                IconButton(onClick = { messaging = stop }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Sms,
                                        contentDescription = "Message ${stop.client}",
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Button(
                        enabled = routable.isNotEmpty(),
                        onClick = {
                            val url = mapsUrl(used) ?: return@Button
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                onDismiss()
                            } catch (error: ActivityNotFoundException) {
                                Toast.makeText(context, "No maps app found", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open route")
                    }
                }
            }
        }
    }

    messaging?.let { stop ->
        val phone = stop.phone
        if (phone != null) {
            MessageDialog(
                phone = phone,
                title = "Message ${stop.client}",
                initialMessage = "Hi ${stop.client}, I'm on my way and should be with you shortly.",
                onDismiss = { messaging = null },
            )
        }
    }
}

private const val MAX_STOPS = 9

private fun mapsUrl(stops: List<RouteStop>): String? {
    val addresses = stops.mapNotNull { stop ->
        stop.address?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (addresses.isEmpty()) return null
    val builder = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
        .appendQueryParameter("api", "1")
        .appendQueryParameter("travelmode", "driving")
        .appendQueryParameter("destination", addresses.last())
    val waypoints = addresses.dropLast(1)
    if (waypoints.isNotEmpty()) {
        builder.appendQueryParameter("waypoints", waypoints.joinToString("|"))
    }
    return builder.build().toString()
}
