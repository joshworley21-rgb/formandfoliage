package uk.co.jwgardenservices.crm.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private fun launch(context: Context, intent: Intent, missing: String) {
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
    }
}

/** Call, text/WhatsApp and navigate shortcuts. Buttons only appear for details that exist. */
@Composable
fun ContactButtons(
    phone: String?,
    address: String?,
    clientName: String? = null,
    defaultMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val number = phone?.trim()?.takeIf { it.isNotEmpty() }
    val place = address?.trim()?.takeIf { it.isNotEmpty() }
    var messaging by remember { mutableStateOf(false) }
    if (number == null && place == null) return

    Row(modifier = modifier) {
        if (number != null) {
            IconButton(
                onClick = { launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "No phone app found") },
            ) {
                Icon(Icons.Outlined.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { messaging = true }) {
                Icon(Icons.Outlined.Sms, contentDescription = "Text or WhatsApp", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
        if (place != null) {
            IconButton(
                onClick = {
                    val uri = Uri.parse("geo:0,0?q=${Uri.encode(place)}")
                    launch(context, Intent(Intent.ACTION_VIEW, uri), "No maps app found")
                },
            ) {
                Icon(Icons.Outlined.Navigation, contentDescription = "Navigate", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (messaging && number != null) {
        MessageDialog(
            phone = number,
            title = if (clientName.isNullOrBlank()) "Message" else "Message $clientName",
            initialMessage = defaultMessage ?: "Hi ${clientName ?: "there"}, ",
            onDismiss = { messaging = false },
        )
    }
}
