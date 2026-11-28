package uk.co.jwgardenservices.crm.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Secretary is the front door: the first tab and the page the app opens on.
 * The bar then keeps the owner's working day: the round, the calendar, clients
 * and billing. Business (running costs, profit, the coach) sits behind More
 * with quotes, enquiries and visits. Staff keep their shorter bar (Schedule +
 * Clients).
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val primary: Boolean,
) {
    Secretary("assistant", "Secretary", Icons.Outlined.AutoAwesome, true),
    Dashboard("dashboard", "Home", Icons.Outlined.Dashboard, true),
    Schedule("schedule", "Rota", Icons.Outlined.CalendarMonth, true),
    Clients("clients", "Clients", Icons.Outlined.People, true),
    Invoices("invoices", "Invoice", Icons.AutoMirrored.Outlined.ReceiptLong, true),
    Business("business", "Business", Icons.Outlined.Insights, false),
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
