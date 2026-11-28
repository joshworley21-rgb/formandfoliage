package uk.co.jwgardenservices.crm.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import uk.co.jwgardenservices.crm.ui.common.JwAlertDialog
import uk.co.jwgardenservices.crm.data.reminders.ReminderWorker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.business.BusinessRunningScreen
import uk.co.jwgardenservices.crm.ui.clients.ClientsScreen
import uk.co.jwgardenservices.crm.ui.common.ActionRow
import uk.co.jwgardenservices.crm.ui.common.SystemBarAppearance
import uk.co.jwgardenservices.crm.ui.dashboard.DashboardScreen
import uk.co.jwgardenservices.crm.ui.enquiries.EnquiriesScreen
import uk.co.jwgardenservices.crm.ui.invoices.InvoicesScreen
import uk.co.jwgardenservices.crm.ui.navigation.AppDestination
import uk.co.jwgardenservices.crm.ui.quotes.QuotesScreen
import uk.co.jwgardenservices.crm.ui.schedule.ScheduleScreen
import uk.co.jwgardenservices.crm.ui.settings.SettingsScreen
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.ui.visits.VisitsScreen
import uk.co.jwgardenservices.crm.ui.voice.AssistantScreen
import uk.co.jwgardenservices.crm.ui.voice.AssistantSettingsScreen
import uk.co.jwgardenservices.crm.ui.common.AppBottomBar
import uk.co.jwgardenservices.crm.ui.common.BottomBarEntry
import uk.co.jwgardenservices.crm.ui.common.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(onSignOut: () -> Unit, isStaff: Boolean) {
    val navController = rememberNavController()
    val crmViewModel: CrmViewModel = viewModel { CrmViewModel() }
    val crmState by crmViewModel.state.collectAsStateWithLifecycle()
    val failedWrites by crmViewModel.failedWrites.collectAsStateWithLifecycle()
    var failedOpen by remember { mutableStateOf(false) }
    var signOutWarning by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        if (!ReminderWorker.canNotify(appContext)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val unsynced = crmState.pendingWrites + failedWrites.size
    val requestSignOut: () -> Unit = {
        if (unsynced > 0) signOutWarning = true else onSignOut()
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var moreOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val barItems = if (isStaff) {
        listOf(AppDestination.Schedule, AppDestination.Clients)
    } else {
        AppDestination.primaryItems
    }
    val moreItems = if (isStaff) emptyList() else AppDestination.moreItems

    SystemBarAppearance(darkStatusBar = true)

    fun navigate(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The forest header draws behind the status bar; the content itself
        // still clears the navigation bar and the bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // No global app bar: each screen leads with its own hero or title, like
        // the reference. Settings and sign-out live in More.

        bottomBar = {
            val entries = barItems.map { destination ->
                BottomBarEntry(
                    label = destination.label,
                    icon = destination.icon,
                    selected = currentRoute == destination.route,
                    onClick = { navigate(destination) },
                )
            } + if (moreItems.isNotEmpty()) {
                listOf(
                    BottomBarEntry(
                        label = "More",
                        icon = Icons.Outlined.MoreHoriz,
                        selected = moreItems.any { it.route == currentRoute } ||
                            currentRoute == "assistant-settings",
                        onClick = { moreOpen = true },
                    ),
                )
            } else {
                emptyList()
            }
            AppBottomBar(entries)
        },
    ) { padding ->
        // The one status-bar layer in the app: a forest strip that every header
        // continues from. Screens and headers add no inset of their own.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(JwTheme.colors.forest)
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (failedWrites.isNotEmpty()) {
                FailedWritesBanner(
                    count = failedWrites.size,
                    onClick = { failedOpen = true },
                )
            }
            if (crmState.pendingWrites > 0) {
                PendingWritesBanner(
                    count = crmState.pendingWrites,
                    offline = crmState.offline,
                )
            }
            NavHost(
                navController = navController,
                startDestination = if (isStaff) {
                    AppDestination.Schedule.route
                } else {
                    AppDestination.Dashboard.route
                },
                modifier = Modifier.weight(1f),
            ) {
                composable(AppDestination.Schedule.route) {
                    ScheduleScreen(viewModel = crmViewModel, isStaff = isStaff)
                }
                composable(AppDestination.Clients.route) {
                    ClientsScreen(viewModel = crmViewModel, isStaff = isStaff)
                }

                if (!isStaff) {
                    composable(AppDestination.Dashboard.route) {
                        DashboardScreen(
                            viewModel = crmViewModel,
                            onOpenSchedule = { navigate(AppDestination.Schedule) },
                            onOpenInvoices = { navigate(AppDestination.Invoices) },
                            onOpenSecretary = { navigate(AppDestination.Secretary) },
                        )
                    }
                    composable(AppDestination.Business.route) {
                        BusinessRunningScreen(
                            viewModel = crmViewModel,
                            onOpenInvoices = { navigate(AppDestination.Invoices) },
                            onOpenSchedule = { navigate(AppDestination.Schedule) },
                            onOpenClients = { navigate(AppDestination.Clients) },
                        )
                    }
                    composable(AppDestination.Invoices.route) {
                        InvoicesScreen(viewModel = crmViewModel)
                    }
                    composable(AppDestination.Visits.route) {
                        VisitsScreen(viewModel = crmViewModel)
                    }
                    composable(AppDestination.Enquiries.route) {
                        EnquiriesScreen(
                            viewModel = crmViewModel,
                            onStartQuote = { enquiry ->
                                crmViewModel.startQuoteFromEnquiry(enquiry)
                                navigate(AppDestination.Quotes)
                            },
                        )
                    }
                    composable(AppDestination.Quotes.route) {
                        QuotesScreen(viewModel = crmViewModel)
                    }
                    composable(AppDestination.Secretary.route) {
                        AssistantScreen(
                            onOpenSettings = { navController.navigate("assistant-settings") },
                        )
                    }
                    composable(AppDestination.Settings.route) {
                        SettingsScreen(
                            viewModel = crmViewModel,
                            onOpenAssistantSettings = { navController.navigate("assistant-settings") },
                        )
                    }
                    composable("assistant-settings") {
                        AssistantSettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    if (moreOpen) {
        ModalBottomSheet(
            onDismissRequest = { moreOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = JwTokens.sheetRadius, topEnd = JwTokens.sheetRadius),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = JwTokens.screenPadding)
                    .padding(bottom = 24.dp),
            ) {
                SectionLabel("More")
                Spacer(Modifier.height(4.dp))
                Text("Everything else", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                moreItems.forEach { destination ->
                    ActionRow(
                        title = destination.label,
                        icon = destination.icon,
                        active = destination.route == currentRoute,
                        onClick = {
                            moreOpen = false
                            navigate(destination)
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ActionRow(
                    title = "Sign out",
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    onClick = {
                        moreOpen = false
                        requestSignOut()
                    },
                )
            }
        }
    }

    if (signOutWarning) {
        JwAlertDialog(
            onDismissRequest = { signOutWarning = false },
            title = { Text("Sign out with unsynced changes?") },
            text = {
                Text(
                    "$unsynced ${if (unsynced == 1) "change has" else "changes have"} not reached the server yet. " +
                        "Signing out now will lose them. Go back and wait for them to sync, or sign out anyway.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signOutWarning = false
                        onSignOut()
                    },
                ) { Text("Sign out anyway", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { signOutWarning = false }) { Text("Stay signed in") }
            },
        )
    }

    if (failedOpen) {
        if (failedWrites.isEmpty()) failedOpen = false
        JwAlertDialog(
            onDismissRequest = { failedOpen = false },
            title = { Text("Changes that could not sync") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    failedWrites.forEach { entry ->
                        Column {
                            Text(entry.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                entry.failedReason ?: "Could not be saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row {
                                TextButton(onClick = { crmViewModel.retryFailedWrite(entry.id) }) {
                                    Text("Retry")
                                }
                                TextButton(onClick = { crmViewModel.discardFailedWrite(entry.id) }) {
                                    Text("Discard", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { crmViewModel.retryFailedWrite() }) { Text("Retry all") }
            },
            dismissButton = {
                TextButton(onClick = { failedOpen = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun FailedWritesBanner(count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = "$count ${if (count == 1) "change" else "changes"} could not sync — tap to review",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = JwTokens.screenPadding, vertical = 8.dp),
        )
    }
}

@Composable
private fun PendingWritesBanner(count: Int, offline: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JwTheme.colors.forestRecessed,
        contentColor = JwTheme.colors.onForestMuted,
    ) {
        Text(
            text = if (offline) {
                "$count ${if (count == 1) "change" else "changes"} saved — will sync when you are back online"
            } else {
                "$count ${if (count == 1) "change" else "changes"} waiting to sync"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = JwTokens.screenPadding, vertical = 8.dp),
        )
    }
}
