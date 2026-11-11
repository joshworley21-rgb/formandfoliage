package uk.co.jwgardenservices.crm.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
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
import uk.co.jwgardenservices.crm.ui.clients.ClientsScreen
import uk.co.jwgardenservices.crm.ui.components.BrandMark
import uk.co.jwgardenservices.crm.ui.dashboard.DashboardScreen
import uk.co.jwgardenservices.crm.ui.enquiries.EnquiriesScreen
import uk.co.jwgardenservices.crm.ui.invoices.InvoicesScreen
import uk.co.jwgardenservices.crm.ui.navigation.AppDestination
import uk.co.jwgardenservices.crm.ui.quotes.QuotesScreen
import uk.co.jwgardenservices.crm.ui.schedule.ScheduleScreen
import uk.co.jwgardenservices.crm.ui.settings.SettingsScreen
import uk.co.jwgardenservices.crm.ui.visits.VisitsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(onSignOut: () -> Unit, isStaff: Boolean) {
    val navController = rememberNavController()
    val crmViewModel: CrmViewModel = viewModel { CrmViewModel() }
    val crmState by crmViewModel.state.collectAsStateWithLifecycle()
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

    fun navigate(destination: AppDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(compact = true)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Garden Services",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "CRM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    if (!isStaff) {
                        IconButton(onClick = { navigate(AppDestination.Settings) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            val moreSelected = moreItems.any { it.route == currentRoute }
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                barItems.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navigate(destination) },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
                if (moreItems.isNotEmpty()) {
                    NavigationBarItem(
                        selected = moreSelected,
                        onClick = { moreOpen = true },
                        icon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = "More") },
                        label = { Text("More") },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
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
                    composable(AppDestination.Settings.route) {
                        SettingsScreen(viewModel = crmViewModel)
                    }
                }
            }
        }
    }

    if (moreOpen) {
        ModalBottomSheet(
            onDismissRequest = { moreOpen = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("Everything else", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                moreItems.forEach { destination ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                moreOpen = false
                                navigate(destination)
                            }
                            .padding(horizontal = 10.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(destination.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            moreOpen = false
                            onSignOut()
                        }
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text("Sign out", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
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
