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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import uk.co.jwgardenservices.crm.ui.components.BrandMark
import uk.co.jwgardenservices.crm.ui.common.ActionRow
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
    // Settings lives in the top bar gear, so it is not repeated here.
    val moreItems = if (isStaff) {
        emptyList()
    } else {
        AppDestination.moreItems.filter { it != AppDestination.Secretary }
    }

    fun navigate(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // No global app bar: each screen leads with its own hero or title, like
        // the reference. Settings and sign-out live in More.

        bottomBar = {
            val moreSelected = moreItems.any { it.route == currentRoute }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    barItems.forEach { destination ->
                        BottomBarItem(
                            label = destination.label,
                            selected = currentRoute == destination.route,
                            icon = destination.icon,
                            modifier = Modifier.weight(1f),
                            onClick = { navigate(destination) },
                        )
                    }
                    if (moreItems.isNotEmpty()) {
                        BottomBarItem(
                            label = "More",
                            selected = moreSelected,
                            icon = Icons.Outlined.MoreHoriz,
                            modifier = Modifier.weight(1f),
                            onClick = { moreOpen = true },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                        AssistantScreen()
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
            shape = RoundedCornerShape(JwTokens.bandRadius),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = JwTokens.screenPadding)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "MORE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("Everything else", style = MaterialTheme.typography.titleLarge)
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
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
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
private fun BottomBarItem(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = JwTheme.colors
    val colour = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(JwTokens.controlRadius),
            color = if (selected) palette.forest else Color.Transparent,
            contentColor = if (selected) palette.onForestMuted else colour,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) palette.brass else colour,
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        letterSpacing = 0.sp,
                    ),
                    color = if (selected) palette.onForestMuted else colour,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
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
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PendingWritesBanner(count: Int, offline: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    ) {
        Text(
            text = if (offline) {
                "$count ${if (count == 1) "change" else "changes"} saved — will sync when you are back online"
            } else {
                "$count ${if (count == 1) "change" else "changes"} waiting to sync"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PlaceholderScreen(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This screen is next on the rewrite list. The web app remains the working version until it lands.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
