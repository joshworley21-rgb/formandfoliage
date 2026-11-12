package uk.co.jwgardenservices.crm.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The phone bar keeps the owner's driving workflow: the round, the calendar,
 * the assistant call and billing, with Clients and everything else behind
 * More. Staff keep their own shorter bar (Schedule + Clients).
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val primary: Boolean,
) {
    Dashboard("dashboard", "Dashboard", Icons.Outlined.Dashboard, true),
    Schedule("schedule", "Schedule", Icons.Outlined.CalendarMonth, true),
    Assistant("assistant", "Assistant", Icons.Outlined.Mic, true),
    Invoices("invoices", "Invoices", Icons.AutoMirrored.Outlined.ReceiptLong, true),
    Clients("clients", "Clients", Icons.Outlined.People, false),
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
