package uk.co.jwgardenservices.crm.screens

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uk.co.jwgardenservices.crm.data.local.CrmDatabase
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.offline.SyncEngine
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.clients.ClientsScreen
import uk.co.jwgardenservices.crm.ui.common.glassBackdrop
import uk.co.jwgardenservices.crm.ui.dashboard.DashboardScreen
import uk.co.jwgardenservices.crm.ui.schedule.ScheduleScreen
import uk.co.jwgardenservices.crm.ui.theme.JwCrmTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Draws the real Home, Rota and Clients pages from an in-memory copy of the
 * app's database filled with made-up work, so they can be looked at before a
 * release. Run with: ./gradlew :app:testDebugUnitTest -Pscreenshots=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-xxhdpi")
class PageScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: CrmDatabase
    private lateinit var viewModel: CrmViewModel

    private val today = LocalDate.now()
    private val zone = ZoneId.systemDefault()

    private fun at(day: LocalDate, hour: Int) =
        day.atTime(LocalTime.of(hour, 0)).atZone(zone).toOffsetDateTime().toString()

    @Before
    fun seed() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CrmDatabase::class.java,
        ).allowMainThreadQueries().build()
        // Never contacted: the view model is told not to sync.
        val offline = createSupabaseClient("http://127.0.0.1:9", "test-key") { install(Postgrest) }
        val clients = listOf(
            Client(id = "c1", userId = "u", name = "Simon Hart", hourlyRate = 30.0, phone = "07700 900123", address = "4 Mill Lane, Bierton"),
            Client(id = "c2", userId = "u", name = "Hazel (Badgers oak)", hourlyRate = 35.0, address = "Badgers Oak, Wendover"),
            Client(id = "c3", userId = "u", name = "Rose Cottage", billingMode = "fixed", fixedPrice = 120.0, address = "Rose Cottage, Weedon"),
            Client(id = "c4", userId = "u", name = "Louisa and George", hourlyRate = 30.0),
        )
        val jobs = listOf(
            ScheduledJob(id = "j1", userId = "u", clientId = "c1", startsAt = at(today, 9), durationMinutes = 180),
            ScheduledJob(id = "j2", userId = "u", clientId = "c3", startsAt = at(today, 14), durationMinutes = 120),
            ScheduledJob(id = "j3", userId = "u", clientId = "c2", startsAt = at(today.plusDays(1), 10), durationMinutes = 240),
            ScheduledJob(id = "j4", userId = "u", clientId = "c4", startsAt = at(today.plusDays(2), 9), durationMinutes = 120),
            // Two past jobs nobody marked: they should show under Needs marking.
            ScheduledJob(id = "j5", userId = "u", clientId = "c2", startsAt = at(today.minusDays(3), 9), durationMinutes = 180),
            ScheduledJob(id = "j6", userId = "u", clientId = "c4", startsAt = at(today.minusDays(2), 13), durationMinutes = 120),
        )
        val visits = listOf(
            Visit(id = "v1", userId = "u", clientId = "c1", visitDate = today.minusDays(1).toString(), hours = 4.0, amount = 120.0, description = "Hedge cut"),
            Visit(id = "v2", userId = "u", clientId = "c3", visitDate = today.minusDays(4).toString(), hours = 2.0, amount = 120.0, billed = true),
            Visit(id = "v3", userId = "u", clientId = "c2", visitDate = today.minusDays(8).toString(), hours = 5.0, amount = 175.0),
        )
        val invoices = listOf(
            Invoice(id = "i1", userId = "u", clientId = "c3", number = "JW-0007", issueDate = today.minusDays(40).toString(),
                dueDate = today.minusDays(10).toString(), total = 240.0, status = "sent"),
            Invoice(id = "i2", userId = "u", clientId = "c4", number = "JW-0008", issueDate = today.toString(), total = 1080.0, status = "draft"),
        )
        runBlocking {
            database.clientDao().upsertAll(clients)
            database.scheduledJobDao().upsertAll(jobs)
            database.visitDao().upsertAll(visits)
            database.invoiceDao().upsertAll(invoices)
        }
        viewModel = CrmViewModel(
            repository = CrmRepository(database, client = offline),
            syncEngine = SyncEngine(database, client = offline),
            autoSync = false,
        )
    }

    @After
    fun close() = database.close()

    /**
     * Room answers on its own thread, which the drawing rule does not wait for.
     * Subscribe first and run the main thread until the data is in, then draw.
     */
    private fun loadData() {
        CoroutineScope(Dispatchers.Main).launch { viewModel.state.collect { } }
        repeat(100) {
            if (viewModel.state.value.clients.isNotEmpty() && viewModel.state.value.jobs.isNotEmpty()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(30)
        }
    }

    private fun shoot(name: String, dark: Boolean, page: @androidx.compose.runtime.Composable () -> Unit) {
        loadData()
        compose.setContent {
            JwCrmTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().glassBackdrop()) { page() }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/page_${name}_${if (dark) "dark" else "light"}.png")
    }

    @Test fun homeLight() = shoot("home", dark = false) { DashboardScreen(viewModel, onOpenSchedule = {}, onOpenInvoices = {}) }

    @Test fun homeDark() = shoot("home", dark = true) { DashboardScreen(viewModel, onOpenSchedule = {}, onOpenInvoices = {}) }

    @Test fun rotaLight() = shoot("rota", dark = false) { ScheduleScreen(viewModel, isStaff = false) }

    @Test fun clientsLight() = shoot("clients", dark = false) { ClientsScreen(viewModel, isStaff = false) }
}
