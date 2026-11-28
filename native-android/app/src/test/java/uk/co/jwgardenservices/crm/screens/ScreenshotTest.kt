package uk.co.jwgardenservices.crm.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.ui.common.AppBottomBar
import uk.co.jwgardenservices.crm.ui.common.BottomBarEntry
import uk.co.jwgardenservices.crm.ui.common.CompactScreenHeader
import uk.co.jwgardenservices.crm.ui.common.FilterPill
import uk.co.jwgardenservices.crm.ui.common.ForestCard
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.PillButton
import uk.co.jwgardenservices.crm.ui.common.PillStyle
import uk.co.jwgardenservices.crm.ui.common.SegmentedPillTabs
import uk.co.jwgardenservices.crm.ui.common.glassBackdrop
import uk.co.jwgardenservices.crm.ui.invoices.BillPreviewDialog
import uk.co.jwgardenservices.crm.ui.invoices.InvoiceCard
import uk.co.jwgardenservices.crm.ui.invoices.ReadyToBillCard
import uk.co.jwgardenservices.crm.ui.theme.JwCrmTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTheme

/**
 * Draws the app's building blocks and a few real screens to PNGs in
 * app/build/screenshots, light and dark, at a Galaxy S22+'s width.
 * Run with: ./gradlew :app:testDebugUnitTest -Pscreenshots=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-xxhdpi")
class ScreenshotTest {
    private val sunny = Client(id = "c1", userId = "u", name = "Sunny", hourlyRate = 30.0)
    private val hazel = Client(id = "c2", userId = "u", name = "Hazel (Badgers oak)", hourlyRate = 35.0)
    private val visits = listOf(
        Visit(id = "v1", userId = "u", clientId = "c2", visitDate = "2026-11-02", hours = 4.5, amount = 157.5, description = "Hedge cut"),
        Visit(id = "v2", userId = "u", clientId = "c2", visitDate = "2026-11-09", hours = 3.0, amount = 105.0),
        Visit(id = "v3", userId = "u", clientId = "c1", visitDate = "2026-11-20", hours = 3.5, amount = 105.0, description = "Leaves"),
    )
    private val invoice = Invoice(
        id = "i1", userId = "u", clientId = "c1", number = "JW-0008",
        issueDate = "2026-11-28", subtotal = 1080.0, total = 1080.0, status = "draft",
    )

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) =
        captureRoboImage("build/screenshots/${name}_${if (dark) "dark" else "light"}.png") {
            JwCrmTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().glassBackdrop()) { content() }
            }
        }

    @Composable
    private fun Gallery() {
        Column {
            CompactScreenHeader(title = "Invoices", subtitle = "Owed, sent and paid", actions = {
                PillButton(label = "New invoice", onClick = {}, style = PillStyle.Gold)
            })
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SegmentedPillTabs(listOf("Week", "Today", "Cash"), "Week", {}, { it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("All", true, {})
                    FilterPill("Drafts", false, {})
                    FilterPill("Overdue", false, {})
                }
                ForestCard(Modifier.fillMaxWidth()) { Text("OWED TO YOU  £1,080.00", color = JwTheme.colors.onForestMuted) }
                JwCard(Modifier.fillMaxWidth()) { Text("A glass card with some text in it.") }
                InvoiceCard(
                    invoice = invoice, clientName = "Louisa and George",
                    warning = JwTheme.colors.attention, taxRatePercent = 0.0, busy = false,
                    lastEvent = null, events = emptyList(), showHistory = false,
                    onHistory = {}, onPdf = {}, onWhatsApp = {}, onSend = {}, onPaid = {}, onEdit = {}, onDelete = {},
                )
                ReadyToBillCard(
                    clients = listOf(sunny, hazel), visits = visits,
                    clientNames = mapOf("c1" to "Sunny", "c2" to "Hazel (Badgers oak)"),
                    busy = false, onCreate = { _, _ -> },
                )
                AppBottomBar(
                    listOf(
                        BottomBarEntry("Home", Icons.Outlined.Dashboard, true, {}),
                        BottomBarEntry("Secretary", Icons.Outlined.AutoAwesome, false, {}),
                        BottomBarEntry("Rota", Icons.Outlined.CalendarMonth, false, {}, badge = 3),
                        BottomBarEntry("Clients", Icons.Outlined.People, false, {}),
                        BottomBarEntry("Invoice", Icons.AutoMirrored.Outlined.ReceiptLong, false, {}),
                        BottomBarEntry("More", Icons.Outlined.MoreHoriz, false, {}),
                    ),
                )
            }
        }
    }

    @Test fun galleryLight() = shoot("gallery", dark = false) { Gallery() }

    @Test fun galleryDark() = shoot("gallery", dark = true) { Gallery() }

    @Test fun billPreview() = shoot("bill_preview", dark = false) {
        BillPreviewDialog(client = hazel, visits = visits.filter { it.clientId == "c2" }, vatRate = 0.0, onDismiss = {}, onConfirm = {})
    }
}
