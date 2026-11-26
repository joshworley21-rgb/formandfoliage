package uk.co.jwgardenservices.crm.ui.business

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.ui.common.JwAlertDialog
import uk.co.jwgardenservices.crm.data.model.RunningCost
import uk.co.jwgardenservices.crm.data.model.RunningCostDraft
import uk.co.jwgardenservices.crm.domain.BusinessSnapshot
import uk.co.jwgardenservices.crm.domain.CoachAction
import uk.co.jwgardenservices.crm.domain.CoachInsight
import uk.co.jwgardenservices.crm.domain.CoachScorePart
import uk.co.jwgardenservices.crm.domain.CoachTone
import uk.co.jwgardenservices.crm.domain.CostBreakdown
import uk.co.jwgardenservices.crm.domain.CostCategory
import uk.co.jwgardenservices.crm.domain.CostFrequency
import uk.co.jwgardenservices.crm.domain.annualRunningCosts
import uk.co.jwgardenservices.crm.domain.buildBusinessSnapshot
import uk.co.jwgardenservices.crm.domain.coachInsights
import uk.co.jwgardenservices.crm.domain.coachScore
import uk.co.jwgardenservices.crm.domain.coachScoreParts
import uk.co.jwgardenservices.crm.domain.costBreakdown
import uk.co.jwgardenservices.crm.domain.dailyRunningCosts
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.moneyCompact
import uk.co.jwgardenservices.crm.domain.monthlyAmount
import uk.co.jwgardenservices.crm.domain.monthlyRunningCosts
import uk.co.jwgardenservices.crm.domain.scoreLabel
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.HeaderGhostAction
import uk.co.jwgardenservices.crm.ui.common.HeaderPillButton
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.ScreenHeader
import uk.co.jwgardenservices.crm.ui.common.SystemBarAppearance
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.SegmentedPills
import uk.co.jwgardenservices.crm.ui.common.StatCard
import uk.co.jwgardenservices.crm.ui.common.StatTile
import uk.co.jwgardenservices.crm.ui.theme.JwPalette
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.ui.voice.AssistantScreen

private enum class BusinessTab(val label: String) {
    Overview("Overview"),
    Costs("Costs"),
    Coach("Coach"),
    Secretary("Secretary"),
}

@Composable
private fun brass(): Color = JwTheme.colors.brass

/** Forest, slate and sage: the three tones in the reference donut. */
@Composable
private fun categoryColor(category: CostCategory): Color {
    val palette = JwTheme.colors
    return when (category) {
        CostCategory.Vehicle -> palette.costVehicle
        CostCategory.Insurance -> palette.costInsurance
        CostCategory.Fuel -> palette.costFuel
        CostCategory.Tools -> palette.costTools
        CostCategory.Software -> palette.costSoftware
        CostCategory.Premises -> palette.costPremises
        CostCategory.Other -> palette.costOther
    }
}

private fun costCategoryIcon(category: CostCategory): ImageVector = when (category) {
    CostCategory.Vehicle -> Icons.Outlined.DirectionsCar
    CostCategory.Fuel -> Icons.Outlined.LocalGasStation
    CostCategory.Insurance -> Icons.Outlined.Shield
    CostCategory.Tools -> Icons.Outlined.Build
    CostCategory.Software -> Icons.Outlined.Computer
    CostCategory.Premises -> Icons.Outlined.HomeWork
    CostCategory.Other -> Icons.Outlined.Category
}

private fun CostFrequency.suffix(): String = when (this) {
    CostFrequency.Weekly -> "/wk"
    CostFrequency.Fortnightly -> "/2wk"
    CostFrequency.Monthly -> "/mo"
    CostFrequency.Quarterly -> "/qtr"
    CostFrequency.Annual -> "/yr"
}

/**
 * The old Assistant tab, reworked into the business's money page.
 *
 * Four views: what the month looks like (Overview), what it costs to run
 * (Costs), what deserves attention next (Coach), and the voice assistant
 * itself, now called Secretary.
 */
@Composable
fun BusinessRunningScreen(
    viewModel: CrmViewModel,
    onOpenInvoices: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenClients: () -> Unit,
) {
    SystemBarAppearance(darkStatusBar = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(BusinessTab.Overview) }
    var editing by remember { mutableStateOf<RunningCost?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RunningCost?>(null) }

    val today = LocalDate.now()
    val costs = state.runningCosts
    val monthly = remember(costs) { costs.monthlyRunningCosts() }
    val daily = remember(costs) { costs.dailyRunningCosts() }
    val annual = remember(costs) { costs.annualRunningCosts() }
    val breakdown = remember(costs) { costBreakdown(costs) }
    val snapshot = remember(
        state.invoices,
        state.visits,
        state.jobs,
        state.clients,
        costs,
        state.settings?.labourRate,
        today,
    ) {
        buildBusinessSnapshot(
            invoices = state.invoices,
            visits = state.visits,
            jobs = state.jobs,
            clients = state.clients,
            costs = costs,
            labourRate = state.settings?.labourRate ?: 0.0,
            today = today,
        )
    }
    val score = remember(snapshot) { coachScore(snapshot) }
    val scoreParts = remember(snapshot) { coachScoreParts(snapshot) }
    val insights = remember(snapshot, costs, state.clients) {
        coachInsights(snapshot, costs, state.clients)
    }

    fun handleAction(action: CoachAction?) {
        when (action) {
            CoachAction.Invoices -> onOpenInvoices()
            CoachAction.Schedule -> onOpenSchedule()
            CoachAction.Clients -> onOpenClients()
            CoachAction.Costs -> tab = BusinessTab.Costs
            CoachAction.AddCost -> {
                editing = null
                editorOpen = true
                tab = BusinessTab.Costs
            }
            null -> Unit
        }
    }

    if (state.loading) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = "Business", subtitle = "Costs, profit and the next useful move.")
            LoadingState(
                "Adding up this month…",
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (tab == BusinessTab.Costs) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                RunningCostsTab(
                    monthly = monthly,
                    daily = daily,
                    annual = annual,
                    breakdown = breakdown,
                    costs = costs,
                    onBack = { tab = BusinessTab.Overview },
                    onAdd = {
                        editing = null
                        editorOpen = true
                    },
                    onEdit = { cost ->
                        editing = cost
                        editorOpen = true
                    },
                    onDelete = { cost -> pendingDelete = cost },
                )
            }
        } else if (tab == BusinessTab.Coach) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                BusinessCoachTab(
                    score = score,
                    scoreParts = scoreParts,
                    hasData = snapshot.hasData,
                    insights = insights,
                    onBack = { tab = BusinessTab.Overview },
                    onRefresh = viewModel::refresh,
                    onAction = ::handleAction,
                )
            }
        } else if (tab == BusinessTab.Secretary) {
            // The call sheet owns the rest of the screen. It binds the voice
            // service only while this tab is on screen, and releases the mic
            // when the owner switches back to the figures.
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenHeader(
                    title = "Secretary",
                    subtitle = "The voice assistant for the business.",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    AssistantScreen()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenHeader(
                    title = "Business",
                    subtitle = "Costs, profit and the next useful move.",
                    topActions = {
                        HeaderGhostAction(
                            icon = Icons.Outlined.Mic,
                            contentDescription = "Call Secretary",
                            onClick = { tab = BusinessTab.Secretary },
                        )
                        HeaderGhostAction(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            onClick = viewModel::refresh,
                        )
                    },
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = JwTokens.screenPadding,
                        end = JwTokens.screenPadding,
                        top = 14.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(JwTokens.sectionGap),
                ) {
                    item {
                        BusinessTabs(selected = tab, onSelect = { tab = it })
                    }
                    item { NetBand(snapshot) }
                    item {
                        HealthCard(
                            score = score,
                            snapshot = snapshot,
                            onClick = { tab = BusinessTab.Coach },
                        )
                    }
                    item {
                        CostBreakdownCard(
                            breakdown = breakdown,
                            monthly = monthly,
                            onAdd = {
                                editing = null
                                editorOpen = true
                            },
                            onOpenCosts = { tab = BusinessTab.Costs },
                        )
                    }
                    item {
                        InsightPreview(
                            insights = insights,
                            onAction = ::handleAction,
                            onOpenCoach = { tab = BusinessTab.Coach },
                        )
                    }
                }
            }
        }
    }

    if (editorOpen) {
        RunningCostDialog(
            existing = editing,
            onDismiss = { editorOpen = false },
            onSave = { draft ->
                editorOpen = false
                scope.launch {
                    viewModel.saveRunningCost(draft, editing)
                    editing = null
                }
            },
        )
    }

    pendingDelete?.let { cost ->
        JwAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${cost.name}?") },
            text = {
                Text(
                    "This removes ${money(cost.monthlyAmount())} a month from the running " +
                        "costs. It can be added again later.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = cost.id
                        pendingDelete = null
                        scope.launch { viewModel.deleteRunningCost(id) }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun BusinessCoachTab(
    score: Int,
    scoreParts: List<CoachScorePart>,
    hasData: Boolean,
    insights: List<CoachInsight>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (CoachAction?) -> Unit,
) {
    var breakdownOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        CoachHeader(onBack = onBack, onRefresh = onRefresh)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            CoachScoreCard(
                score = score,
                scoreParts = scoreParts,
                hasData = hasData,
                breakdownOpen = breakdownOpen,
                onToggleBreakdown = { breakdownOpen = !breakdownOpen },
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        item {
            Text(
                text = "${insights.size} ${if (insights.size == 1) "insight" else "insights"} " +
                    "· based on your latest records",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        if (insights.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = JwTokens.screenPadding),
                    shape = RoundedCornerShape(JwTokens.cardRadius),
                    color = JwTheme.colors.forest,
                    contentColor = JwTheme.colors.onForestMuted,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nothing needs attention", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Log a few visits and running costs and the coach will start " +
                                "pointing at the next useful move.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JwTheme.colors.onForestMuted.copy(alpha = 0.65f),
                        )
                    }
                }
            }
        } else {
            CoachTone.entries.forEach { tone ->
                val rows = insights.filter { it.tone == tone }
                if (rows.isNotEmpty()) {
                    item {
                        CoachSectionHeader(tone = tone, count = rows.size)
                    }
                    rows.forEach { insight ->
                        item {
                            CoachInsightCard(
                                insight = insight,
                                onAction = onAction,
                                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = "The score weighs cash collected, margin, cost control, unbilled work " +
                    "and how full the week is. It is calculated on this phone from your own records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        }
    }
}

@Composable
private fun CoachHeader(onBack: () -> Unit, onRefresh: () -> Unit) {
    ScreenHeader(
        title = "Business Coach",
        subtitle = "What needs attention, what is an opportunity.",
        onBack = onBack,
        backLabel = "Business",
        topActions = {
            HeaderGhostAction(
                icon = Icons.Outlined.Refresh,
                contentDescription = "Refresh",
                onClick = onRefresh,
            )
        },
    )
}

@Composable
private fun CoachScoreCard(
    score: Int,
    scoreParts: List<CoachScorePart>,
    hasData: Boolean,
    breakdownOpen: Boolean,
    onToggleBreakdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.cardRadius),
        color = JwTheme.colors.forest,
        contentColor = JwTheme.colors.onForestMuted,
        border = BorderStroke(1.dp, JwTheme.colors.onForestMuted.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 18.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "MONTHLY HEALTH SCORE",
                style = MaterialTheme.typography.labelSmall,
                color = JwTheme.colors.onForestMuted.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(12.dp))
            CoachScoreRing(score = score)
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (!hasData) "Not enough data" else scoreLabel(score),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = when {
                    !hasData -> "Add costs and log some visits to start."
                    score >= 80 -> "Your business is performing above average for a UK gardening company."
                    score >= 60 -> "Solid. The biggest win is usually the overdue invoice."
                    score >= 40 -> "Some pressure. Check cash flow and running costs."
                    else -> "Several things need a look. Start with money already earned."
                },
                style = MaterialTheme.typography.bodySmall,
                color = JwTheme.colors.onForestMuted.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onToggleBreakdown, contentPadding = PaddingValues(vertical = 2.dp)) {
                Text(
                    text = if (breakdownOpen) "Hide breakdown ▴" else "View breakdown ▾",
                    style = MaterialTheme.typography.labelLarge,
                    color = JwTheme.colors.onForestMuted.copy(alpha = 0.8f),
                )
            }
            if (breakdownOpen && scoreParts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    scoreParts.forEach { part ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = part.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = JwTheme.colors.onForestMuted.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${part.points}/${part.max}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachScoreRing(score: Int) {
    val trackColour = JwTheme.colors.onForestMuted.copy(alpha = 0.12f)
    val ringColour = JwTheme.colors.positive
    Box(modifier = Modifier.size(142.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(142.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = trackColour,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColour,
                startAngle = -90f,
                sweepAngle = (score.coerceIn(0, 100) / 100f) * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "/100",
                style = MaterialTheme.typography.labelSmall,
                color = JwTheme.colors.onForestMuted.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun CoachSectionHeader(tone: CoachTone, count: Int) {
    val tint = when (tone) {
        CoachTone.Attention -> JwTheme.colors.attention
        CoachTone.Opportunity -> MaterialTheme.colorScheme.tertiary
        CoachTone.Good -> JwTheme.colors.positive
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (tone) {
            CoachTone.Attention -> Icons.Outlined.WarningAmber
            CoachTone.Opportunity -> Icons.Outlined.Lightbulb
            CoachTone.Good -> Icons.Outlined.CheckCircle
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = when (tone) {
                CoachTone.Attention -> "NEEDS ATTENTION"
                CoachTone.Opportunity -> "OPPORTUNITIES"
                CoachTone.Good -> "WHAT'S WORKING"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CoachInsightCard(
    insight: CoachInsight,
    onAction: (CoachAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when (insight.tone) {
        CoachTone.Attention -> JwTheme.colors.attention
        CoachTone.Opportunity -> MaterialTheme.colorScheme.tertiary
        CoachTone.Good -> JwTheme.colors.positive
    }
    val severity = when (insight.tone) {
        CoachTone.Attention -> "HIGH"
        CoachTone.Opportunity -> "MEDIUM"
        CoachTone.Good -> "POSITIVE"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.cardRadius),
        color = JwTheme.colors.forest,
        contentColor = JwTheme.colors.onForestMuted,
        border = BorderStroke(1.dp, JwTheme.colors.onForestMuted.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = tint.copy(alpha = 0.14f),
                    contentColor = tint,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = when (insight.tone) {
                            CoachTone.Attention -> Icons.Outlined.WarningAmber
                            CoachTone.Opportunity -> Icons.Outlined.Lightbulb
                            CoachTone.Good -> Icons.Outlined.CheckCircle
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "$severity · ${insight.area.uppercase(Locale.UK)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = insight.body,
                style = MaterialTheme.typography.bodySmall,
                color = JwTheme.colors.onForestMuted.copy(alpha = 0.65f),
            )
            if (insight.action != null) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onAction(insight.action) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(JwTokens.smallRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) {
                    Text(insight.actionLabel ?: "Act on this", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RunningCostsTab(
    monthly: Double,
    daily: Double,
    annual: Double,
    breakdown: List<CostBreakdown>,
    costs: List<RunningCost>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (RunningCost) -> Unit,
    onDelete: (RunningCost) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        RunningCostsHeader(onBack = onBack, onAdd = onAdd)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = JwTokens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Monthly", moneyCompact(monthly), Modifier.weight(1f))
                StatTile("Daily", moneyCompact(daily), Modifier.weight(1f))
                StatTile("Annual", moneyCompact(annual), Modifier.weight(1f))
            }
        }
        item {
            RunningCostBreakdownCard(
                breakdown = breakdown,
                monthly = monthly,
                onAdd = onAdd,
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        item {
            GroupedCostCards(
                costs = costs,
                onEdit = onEdit,
                onDelete = onDelete,
                onAdd = onAdd,
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        item {
            Text(
                text = "Monthly totals convert weekly, quarterly and annual costs. " +
                    "Tap an item to edit it; everything syncs to your other devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        }
    }
}

@Composable
private fun RunningCostsHeader(onBack: () -> Unit, onAdd: () -> Unit) {
    ScreenHeader(
        title = "Running Costs",
        subtitle = "Vehicles, fuel, insurance, tools",
        onBack = onBack,
        backLabel = "Business",
        titleAction = {
            HeaderPillButton(label = "+ Add", onClick = onAdd)
        },
    )
}

@Composable
private fun RunningCostBreakdownCard(
    breakdown: List<CostBreakdown>,
    monthly: Double,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.bandRadius),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Cost Breakdown",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Monthly recurring costs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            if (breakdown.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Add the first cost and this becomes a picture of where the money goes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onAdd) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add a cost")
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DonutChart(
                        breakdown = breakdown,
                        centreTop = moneyCompact(monthly),
                        centreBottom = "per month",
                        size = 190.dp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    breakdown.forEach { slice ->
                        BreakdownRow(slice)
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(slice: CostBreakdown) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(categoryColor(slice.category), CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = slice.category.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(JwTokens.microRadius),
                color = categoryColor(slice.category).copy(alpha = 0.12f),
                contentColor = categoryColor(slice.category),
            ) {
                Text(
                    text = "${(slice.share * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${money(slice.monthly)}/mo",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun GroupedCostCards(
    costs: List<RunningCost>,
    onEdit: (RunningCost) -> Unit,
    onDelete: (RunningCost) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (costs.isEmpty()) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(JwTokens.cardRadius),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            EmptyState(
                icon = Icons.Outlined.Savings,
                title = "No running costs yet",
                body = "Add the van, fuel, insurance, tools or software. Each one is " +
                    "worked out monthly so it sits beside income.",
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    val groups = costs
        .groupBy { CostCategory.from(it.category) }
        .mapValues { (_, rows) -> rows to rows.sumOf { it.monthlyAmount() } }
        .toList()
        .sortedByDescending { it.second.second }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { (category, pair) ->
            val (rows, total) = pair
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(JwTokens.cardRadius),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = categoryColor(category).copy(alpha = 0.14f),
                            contentColor = categoryColor(category),
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = costCategoryIcon(category),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${moneyCompact(total)}/mo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    rows.forEachIndexed { index, cost ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                        CostItemRow(
                            cost = cost,
                            onEdit = { onEdit(cost) },
                            onDelete = { onDelete(cost) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CostItemRow(cost: RunningCost, onEdit: () -> Unit, onDelete: () -> Unit) {
    val frequency = CostFrequency.from(cost.frequency)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = cost.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = money(cost.amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Surface(
                shape = RoundedCornerShape(JwTokens.microRadius),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = frequency.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete ${cost.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun BusinessTabs(selected: BusinessTab, onSelect: (BusinessTab) -> Unit) {
    SegmentedPills(
        options = BusinessTab.entries,
        selected = selected,
        onSelect = onSelect,
        label = { it.label },
    )
}

@Composable
private fun NetBand(snapshot: BusinessSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.bandRadius),
        color = JwTheme.colors.forest,
        contentColor = JwTheme.colors.onForestMuted,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "THIS MONTH · NET POSITION",
                style = MaterialTheme.typography.labelSmall,
                color = JwTheme.colors.onForestMuted.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = money(snapshot.netProfit),
                style = MaterialTheme.typography.displaySmall,
                color = if (snapshot.netProfit < 0) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    brass()
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                BandFigure(
                    label = "Work",
                    value = money(snapshot.monthIncome),
                    modifier = Modifier.weight(1f),
                )
                BandFigure(
                    label = "Labour",
                    value = money(snapshot.monthLabour),
                    modifier = Modifier.weight(1f),
                )
                BandFigure(
                    label = "Materials",
                    value = money(snapshot.monthMaterials),
                    modifier = Modifier.weight(1f),
                )
                BandFigure(
                    label = "Running",
                    value = money(snapshot.monthlyCosts),
                    modifier = Modifier.weight(1f),
                )
            }
            if (snapshot.monthInvoiced > 0.0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${money(snapshot.monthInvoiced)} invoiced · " +
                        "${money(snapshot.monthPaid)} collected",
                    style = MaterialTheme.typography.bodySmall,
                    color = JwTheme.colors.onForestMuted.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun BandFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = JwTheme.colors.onForestMuted.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun HealthCard(
    score: Int,
    snapshot: BusinessSnapshot,
    onClick: (() -> Unit)?,
) {
    val status = if (!snapshot.hasData) "Not enough data" else scoreLabel(score)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(JwTokens.bandRadius),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScoreRing(score = score)
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MONTHLY HEALTH SCORE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        !snapshot.hasData -> "Add costs and log some visits to start."
                        score >= 80 -> "Cash, margin and costs are all pulling in the same direction."
                        score >= 60 -> "Solid. The biggest win is usually the overdue invoice."
                        score >= 40 -> "Some pressure. Check cash flow and running costs."
                        else -> "Several things need a look. Start with money already earned."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onClick != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "See what to fix →",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ring = if (score >= 60) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(92.dp)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = ring,
                startAngle = -90f,
                sweepAngle = (score.coerceIn(0, 100) / 100f) * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "/100",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CostBreakdownCard(
    breakdown: List<CostBreakdown>,
    monthly: Double,
    onAdd: () -> Unit,
    onOpenCosts: (() -> Unit)?,
) {
    SectionCard(
        title = "Cost breakdown",
        actionLabel = if (onOpenCosts != null) "All costs" else null,
        onAction = onOpenCosts,
    ) {
        if (breakdown.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Monthly recurring costs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Add the first cost and this becomes a picture of where the money goes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onAdd) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add a cost")
                }
            }
            return@SectionCard
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DonutChart(
                breakdown = breakdown,
                centreTop = moneyCompact(monthly),
                centreBottom = "per month",
            )
            Spacer(Modifier.height(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                breakdown.take(5).forEach { slice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = categoryColor(slice.category).copy(alpha = 0.14f),
                            contentColor = categoryColor(slice.category),
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = costCategoryIcon(slice.category),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = slice.category.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(slice.share * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Text(
                            text = "${money(slice.monthly)}/mo",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(
    breakdown: List<CostBreakdown>,
    centreTop: String,
    centreBottom: String,
    size: Dp = 132.dp,
) {
    val colours = breakdown.map { categoryColor(it.category) }
    val trackColour = JwTheme.colors.hairline.copy(alpha = 0.6f)
    val stroke = (size.value * 0.18f).dp
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(
                color = trackColour,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx),
            )
            var start = -90f
            breakdown.forEachIndexed { index, slice ->
                val sweep = (slice.share * 360f).toFloat()
                drawArc(
                    color = colours[index],
                    startAngle = start + 1.5f,
                    sweepAngle = (sweep - 3f).coerceAtLeast(1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        var start = -90f
        breakdown.forEachIndexed { index, slice ->
            val sweep = (slice.share * 360f).toFloat()
            if (slice.share >= 0.05) {
                val midpoint = Math.toRadians((start + sweep / 2f).toDouble())
                val radius = (size / 2 - stroke / 2).value
                val x = (size.value / 2 + cos(midpoint).toFloat() * radius - 11f).dp
                val y = (size.value / 2 + sin(midpoint).toFloat() * radius - 6f).dp
                Text(
                    text = "${(slice.share * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (colours[index].luminance() > 0.55f) {
                        JwTheme.colors.forestDeep
                    } else {
                        JwTheme.colors.onForestMuted
                    },
                    modifier = Modifier.offset(x = x, y = y),
                )
            }
            start += sweep
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centreTop,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = centreBottom,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightPreview(
    insights: List<CoachInsight>,
    onAction: (CoachAction?) -> Unit,
    onOpenCoach: () -> Unit,
) {
    val preview = insights.take(2)
    SectionCard(
        title = "Coach",
        actionLabel = if (insights.isEmpty()) null else "All insights",
        onAction = if (insights.isEmpty()) null else onOpenCoach,
    ) {
        if (preview.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Nothing to flag yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Add costs and log visits and this card starts finding the next useful move.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            preview.forEachIndexed { index, insight ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                InsightBody(insight = insight, onAction = onAction, compact = true)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun InsightBody(
    insight: CoachInsight,
    onAction: (CoachAction?) -> Unit,
    compact: Boolean,
) {
    val (icon, tint) = when (insight.tone) {
        CoachTone.Attention -> Icons.Outlined.WarningAmber to MaterialTheme.colorScheme.error
        CoachTone.Opportunity -> Icons.Outlined.Lightbulb to JwTheme.colors.brass
        CoachTone.Good -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
    }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.12f),
                contentColor = tint,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = insight.area.uppercase(Locale.UK),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = insight.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = insight.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (insight.action != null && insight.actionLabel != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = { onAction(insight.action) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) {
                Text(insight.actionLabel)
            }
        }
    }
}

@Composable
private fun RunningCostDialog(
    existing: RunningCost?,
    onDismiss: () -> Unit,
    onSave: (RunningCostDraft) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var amount by remember(existing) {
        mutableStateOf(existing?.amount?.let { String.format(Locale.UK, "%.2f", it) } ?: "")
    }
    var category by remember(existing) {
        mutableStateOf(CostCategory.from(existing?.category))
    }
    var frequency by remember(existing) {
        mutableStateOf(CostFrequency.from(existing?.frequency))
    }
    var notes by remember(existing) { mutableStateOf(existing?.notes ?: "") }

    val parsed = amount.replace(",", "").toDoubleOrNull() ?: 0.0
    val valid = name.isNotBlank() && parsed > 0.0

    JwAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a running cost" else "Edit running cost") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("What is it?") },
                    placeholder = { Text("Van finance, public liability, fuel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (£)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CostCategory.entries) { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text(
                    text = "How often is it paid?",
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CostFrequency.entries) { option ->
                        FilterChip(
                            selected = frequency == option,
                            onClick = { frequency = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("Renews in March") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parsed > 0.0 && frequency != CostFrequency.Monthly) {
                    Text(
                        text = "That is ${money(parsed * frequency.perYear / 12.0)} a month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        RunningCostDraft(
                            name = name,
                            category = category.key,
                            amount = parsed,
                            frequency = frequency.key,
                            notes = notes.ifBlank { null },
                        ),
                    )
                },
                enabled = valid,
            ) {
                Text(if (existing == null) "Add" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
