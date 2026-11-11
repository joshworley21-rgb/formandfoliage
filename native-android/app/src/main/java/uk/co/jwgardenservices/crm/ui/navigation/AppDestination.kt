package uk.co.jwgardenservices.crm.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The same information architecture as the web app's AppShell: four tabs on the
 * phone bar and everything else behind More.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val primary: Boolean,
) {
    Dashboard("dashboard", "Dashboard", Icons.Outlined.Dashboard, true),
    Schedule("schedule", "Schedule", Icons.Outlined.CalendarMonth, true),
    Clients("clients", "Clients", Icons.Outlined.People, true),
    Invoices("invoices", "Invoices", Icons.AutoMirrored.Outlined.ReceiptLong, true),
    Enquiries("enquiries", "Enquiries", Icons.Outlined.Inbox, false),
    Quotes("quotes", "Quotes", Icons.Outlined.RequestQuote, false),
    Visits("visits", "Visits", Icons.Outlined.Checklist, false),
    Settings("settings", "Settings", Icons.Outlined.Settings, false),
    ;

    companion object {
        val primaryItems: List<AppDestination> = entries.filter { it.primary }
        val moreItems: List<AppDestination> = entries.filter { !it.primary }
    }
}
