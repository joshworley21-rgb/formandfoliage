package uk.co.jwgardenservices.crm.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The phone bar keeps the owner's driving workflow: the round, the calendar,
 * clients, the business's running costs and billing. Quotes, enquiries,
 * visits and Secretary live behind More. Secretary is also a tab inside
 * Business; its route is kept for the Dashboard microphone. Staff keep their
 * shorter bar (Schedule + Clients).
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val primary: Boolean,
) {
    Dashboard("dashboard", "Home", Icons.Outlined.Dashboard, true),
    Schedule("schedule", "Rota", Icons.Outlined.CalendarMonth, true),
    Business("business", "Business", Icons.Outlined.Insights, true),
    Invoices("invoices", "Invoice", Icons.AutoMirrored.Outlined.ReceiptLong, true),
    Clients("clients", "Clients", Icons.Outlined.People, true),
    Enquiries("enquiries", "Enquiries", Icons.Outlined.Inbox, false),
    Quotes("quotes", "Quotes", Icons.Outlined.RequestQuote, false),
    Visits("visits", "Visits", Icons.Outlined.Checklist, false),
    Secretary("assistant", "Secretary", Icons.Outlined.Mic, false),
    Settings("settings", "Settings", Icons.Outlined.Settings, false),
    ;

    companion object {
        val primaryItems: List<AppDestination> = entries.filter { it.primary }
        val moreItems: List<AppDestination> = entries.filter { !it.primary }
    }
}
