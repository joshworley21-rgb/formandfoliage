package uk.co.jwgardenservices.crm.ui.business

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HomeWork
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import androidx.compose.ui.draw.clip
import uk.co.jwgardenservices.crm.ui.common.CompactScreenHeader
import uk.co.jwgardenservices.crm.ui.common.FilterPill
import uk.co.jwgardenservices.crm.ui.common.ForestCard
import uk.co.jwgardenservices.crm.ui.common.ForestFigure
import uk.co.jwgardenservices.crm.ui.common.HeaderIconAction
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.PillButton
import uk.co.jwgardenservices.crm.ui.common.PillStyle
import uk.co.jwgardenservices.crm.ui.common.SectionLabel
import uk.co.jwgardenservices.crm.ui.common.SegmentedPillTabs

private enum class BusinessTab(val label: String, val subtitle: String) {
    Overview("Overview", "Costs, profit and the next useful move."),
    Costs("Costs", "Vehicles, fuel, insurance, tools."),
    Coach("Coach", "What needs attention, what is an opportunity."),
}

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
            CompactScreenHeader(title = "Business", subtitle = "Costs, profit and the next useful move.")
            LoadingState(
                "Adding up this month…",
                modifier = Modifier.padding(horizontal = JwTokens.screenPadding),
            )
        }
        return
    }

    // One header and one tab strip for all three views, so switching tabs
    // changes the content and nothing else.
    Column(modifier = Modifier.fillMaxSize()) {
        CompactScreenHeader(
            title = "Business",
            subtitle = tab.subtitle,
            actions = {
                when (tab) {
                    BusinessTab.Overview -> {
                        HeaderIconAction(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            onClick = viewModel::refresh,
                        )
                    }
                    BusinessTab.Costs -> PillButton(
                        label = "+ Add",
                        onClick = {
                            editing = null
                            editorOpen = true
                        },
                    )
                    BusinessTab.Coach -> HeaderIconAction(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Refresh",
                        onClick = viewModel::refresh,
                    )
                }
            },
        )
        SegmentedPillTabs(
            options = BusinessTab.entries,
            selected = tab,
            onSelect = { tab = it },
            label = { it.label },
            modifier = Modifier.padding(
                start = JwTokens.screenPadding,
                end = JwTokens.screenPadding,
                top = JwTokens.sectionGap,
            ),
        )
        val listPadding = PaddingValues(
            start = JwTokens.screenPadding,
            end = JwTokens.screenPadding,
            top = JwTokens.sectionGap,
            bottom = 32.dp,
        )
        when (tab) {
            BusinessTab.Overview -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(JwTokens.sectionGap),
            ) {
                item { NetBand(snapshot) }
                item {
                    HealthCard(
                        score = score,
                        hasData = snapshot.hasData,
                        onClick = { tab = BusinessTab.Coach },
                    )
                }
                item {
                    CostBreakdownCard(
                        breakdown = breakdown.take(5),
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
            BusinessTab.Costs -> RunningCostsTab(
                monthly = monthly,
                daily = daily,
                annual = annual,
                breakdown = breakdown,
                costs = costs,
                contentPadding = listPadding,
                onAdd = {
                    editing = null
                    editorOpen = true
                },
                onEdit = { cost ->
                    editing = cost
                    editorOpen = true
                },
                onDelete = { cost -> pendingDelete = cost },
                modifier = Modifier.weight(1f),
            )
            BusinessTab.Coach -> BusinessCoachTab(
                score = score,
                scoreParts = scoreParts,
                hasData = snapshot.hasData,
                insights = insights,
                contentPadding = listPadding,
                onAction = ::handleAction,
                modifier = Modifier.weight(1f),
            )
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
    contentPadding: PaddingValues,
    onAction: (CoachAction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var breakdownOpen by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(JwTokens.sectionGap),
    ) {
        item {
            CoachScoreCard(
                score = score,
                scoreParts = scoreParts,
                hasData = hasData,
                breakdownOpen = breakdownOpen,
                onToggleBreakdown = { breakdownOpen = !breakdownOpen },
            )
        }
        if (insights.isEmpty()) {
            item {
                JwCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Nothing needs attention", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Log a few visits and running costs and the coach will start " +
                            "pointing at the next useful move.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            CoachTone.entries.forEach { tone ->
                val rows = insights.filter { it.tone == tone }
                if (rows.isNotEmpty()) {
                    item { CoachSectionHeader(tone = tone, count = rows.size) }
                    rows.forEach { insight ->
                        item {
                            JwCard(
                                modifier = Modifier.fillMaxWidth(),
                                padding = PaddingValues(0.dp),
                            ) {
                                InsightBody(insight = insight, onAction = onAction, compact = false)
                            }
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
            )
        }
    }
}

/** Coach's hero: the same score as Overview, with the breakdown behind it. */
@Composable
private fun CoachScoreCard(
    score: Int,
    scoreParts: List<CoachScorePart>,
    hasData: Boolean,
    breakdownOpen: Boolean,
    onToggleBreakdown: () -> Unit,
) {
    val ink = JwTheme.colors.onForestMuted
    ForestCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = score, onForest = true)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Monthly health score", onForest = true)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (!hasData) "Not enough data" else scoreLabel(score),
                    style = MaterialTheme.typography.titleLarge,
                    color = JwTheme.colors.brass,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = scoreSummary(score, hasData),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.72f),
                )
            }
        }
        if (scoreParts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = ink.copy(alpha = 0.14f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(JwTokens.smallRadius))
                    .clickable(onClick = onToggleBreakdown)
                    .padding(top = 12.dp, bottom = if (breakdownOpen) 8.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (breakdownOpen) "Hide breakdown" else "View breakdown",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (breakdownOpen) "▴" else "▾",
                    style = MaterialTheme.typography.labelLarge,
                    color = JwTheme.colors.brass,
                )
            }
            if (breakdownOpen) {
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
                            color = ink.copy(alpha = 0.72f),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${part.points}/${part.max}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private fun scoreSummary(score: Int, hasData: Boolean): String = when {
    !hasData -> "Add costs and log some visits to start."
    score >= 80 -> "Cash, margin and costs are all pulling in the same direction."
    score >= 60 -> "Solid. The biggest win is usually the overdue invoice."
    score >= 40 -> "Some pressure. Check cash flow and running costs."
    else -> "Several things need a look. Start with money already earned."
}

@Composable
private fun toneTint(tone: CoachTone): Color = when (tone) {
    CoachTone.Attention -> JwTheme.colors.attention
    CoachTone.Opportunity -> JwTheme.colors.brassDeep
    CoachTone.Good -> JwTheme.colors.positive
}

private fun toneIcon(tone: CoachTone): ImageVector = when (tone) {
    CoachTone.Attention -> Icons.Outlined.WarningAmber
    CoachTone.Opportunity -> Icons.Outlined.Lightbulb
    CoachTone.Good -> Icons.Outlined.CheckCircle
}

@Composable
private fun CoachSectionHeader(tone: CoachTone, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = toneIcon(tone),
            contentDescription = null,
            tint = toneTint(tone),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        SectionLabel(
            text = when (tone) {
                CoachTone.Attention -> "Needs attention"
                CoachTone.Opportunity -> "Opportunities"
                CoachTone.Good -> "What's working"
            },
            modifier = Modifier.weight(1f),
        )
        SectionLabel(count.toString())
    }
}

@Composable
private fun RunningCostsTab(
    monthly: Double,
    daily: Double,
    annual: Double,
    breakdown: List<CostBreakdown>,
    costs: List<RunningCost>,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onEdit: (RunningCost) -> Unit,
    onDelete: (RunningCost) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(JwTokens.sectionGap),
    ) {
        item {
            ForestCard(modifier = Modifier.fillMaxWidth()) {
                SectionLabel("Running costs", onForest = true)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ForestFigure("Monthly", moneyCompact(monthly), Modifier.weight(1f))
                    ForestFigure("Daily", moneyCompact(daily), Modifier.weight(1f))
                    ForestFigure("Annual", moneyCompact(annual), Modifier.weight(1f))
                }
            }
        }
        item {
            CostBreakdownCard(
                breakdown = breakdown,
                monthly = monthly,
                onAdd = onAdd,
                onOpenCosts = null,
            )
        }
        if (costs.isEmpty()) {
            item {
                JwCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        icon = Icons.Outlined.Savings,
                        title = "No running costs yet",
                        body = "Add the van, fuel, insurance, tools or software. Each one is " +
                            "worked out monthly so it sits beside income.",
                    )
                }
            }
        } else {
            val groups = costs
                .groupBy { CostCategory.from(it.category) }
                .mapValues { (_, rows) -> rows to rows.sumOf { it.monthlyAmount() } }
                .toList()
                .sortedByDescending { it.second.second }
            groups.forEach { (category, pair) ->
                item(key = "group-${category.key}") {
                    CostGroupCard(
                        category = category,
                        rows = pair.first,
                        total = pair.second,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
        item {
            Text(
                text = "Monthly totals convert weekly, quarterly and annual costs. " +
                    "Tap an item to edit it; everything syncs to your other devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: CostCategory, size: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(categoryColor(category).copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = costCategoryIcon(category),
            contentDescription = null,
            tint = categoryColor(category),
            modifier = Modifier.size(size / 2 + 1.dp),
        )
    }
}

@Composable
private fun CostGroupCard(
    category: CostCategory,
    rows: List<RunningCost>,
    total: Double,
    onEdit: (RunningCost) -> Unit,
    onDelete: (RunningCost) -> Unit,
) {
    JwCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = JwTokens.cardPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryBadge(category)
            Spacer(Modifier.width(10.dp))
            Text(
                text = category.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${moneyCompact(total)}/mo",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider()
        rows.forEachIndexed { index, cost ->
            if (index > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = JwTokens.cardPadding))
            }
            CostItemRow(
                cost = cost,
                onEdit = { onEdit(cost) },
                onDelete = { onDelete(cost) },
            )
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
            .padding(start = JwTokens.cardPadding, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = cost.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = frequency.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${money(cost.amount)}${frequency.suffix()}",
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = "Delete ${cost.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NetBand(snapshot: BusinessSnapshot) {
    ForestCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("This month · net position", onForest = true)
        Spacer(Modifier.height(6.dp))
        Text(
            text = money(snapshot.netProfit),
            style = MaterialTheme.typography.displaySmall,
            color = if (snapshot.netProfit < 0) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                JwTheme.colors.brass
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = JwTheme.colors.onForestMuted
            ForestFigure("Work", moneyCompact(snapshot.monthIncome), Modifier.weight(1f), ink)
            ForestFigure("Labour", moneyCompact(snapshot.monthLabour), Modifier.weight(1f), ink)
            ForestFigure("Materials", moneyCompact(snapshot.monthMaterials), Modifier.weight(1f), ink)
            ForestFigure("Running", moneyCompact(snapshot.monthlyCosts), Modifier.weight(1f), ink)
        }
        if (snapshot.monthInvoiced > 0.0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${money(snapshot.monthInvoiced)} invoiced · " +
                    "${money(snapshot.monthPaid)} collected",
                style = MaterialTheme.typography.bodySmall,
                color = JwTheme.colors.onForestMuted.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun HealthCard(
    score: Int,
    hasData: Boolean,
    onClick: () -> Unit,
) {
    JwCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(score = score, onForest = false)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel("Monthly health score")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (!hasData) "Not enough data" else scoreLabel(score),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = scoreSummary(score, hasData),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** The one score ring, on a white card (Overview) or the forest hero (Coach). */
@Composable
private fun ScoreRing(score: Int, onForest: Boolean) {
    val palette = JwTheme.colors
    val track = if (onForest) palette.onForestMuted.copy(alpha = 0.14f) else palette.track
    val ring = when {
        onForest -> palette.brass
        score >= 60 -> palette.forest
        else -> MaterialTheme.colorScheme.error
    }
    val ink = if (onForest) palette.onForestMuted else MaterialTheme.colorScheme.onSurface
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
                color = ink,
            )
            Text(
                text = "/100",
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = 0.62f),
            )
        }
    }
}

/**
 * Where the money goes: donut on the left of its own row, the category list
 * beneath, and the ochre `+ Add` pill in the title row. Overview shows the top
 * five with a link to the rest; Costs shows them all.
 */
@Composable
private fun CostBreakdownCard(
    breakdown: List<CostBreakdown>,
    monthly: Double,
    onAdd: () -> Unit,
    onOpenCosts: (() -> Unit)?,
) {
    JwCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cost breakdown", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Monthly recurring costs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillButton(
                label = if (breakdown.isEmpty()) "+ Add a cost" else "+ Add",
                onClick = onAdd,
            )
        }
        if (breakdown.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add the first cost and this becomes a picture of where the money goes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@JwCard
        }
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DonutChart(
                breakdown = breakdown,
                centreTop = moneyCompact(monthly),
                centreBottom = "per month",
            )
        }
        Spacer(Modifier.height(12.dp))
        breakdown.forEachIndexed { index, slice ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryBadge(slice.category)
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
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = "${money(slice.monthly)}/mo",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        if (onOpenCosts != null) {
            Spacer(Modifier.height(8.dp))
            PillButton(
                label = "All costs",
                onClick = onOpenCosts,
                style = PillStyle.Quiet,
                fill = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DonutChart(
    breakdown: List<CostBreakdown>,
    centreTop: String,
    centreBottom: String,
    size: Dp = 148.dp,
) {
    val colours = breakdown.map { categoryColor(it.category) }
    val trackColour = JwTheme.colors.track
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
                        JwTheme.colors.forestRecessed
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
                    .padding(JwTokens.cardPadding),
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
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = JwTokens.cardPadding))
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
    val tint = toneTint(insight.tone)
    Column(modifier = Modifier.padding(JwTokens.cardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(tint.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    toneIcon(insight.tone),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = insight.area.uppercase(Locale.UK),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = insight.title, style = MaterialTheme.typography.titleMedium)
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = insight.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (insight.action != null && insight.actionLabel != null) {
            Spacer(Modifier.height(10.dp))
            PillButton(
                label = insight.actionLabel,
                onClick = { onAction(insight.action) },
                style = if (compact) PillStyle.Quiet else PillStyle.Gold,
            )
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
                        FilterPill(
                            label = option.label,
                            selected = category == option,
                            onClick = { category = option },
                        )
                    }
                }
                Text(
                    text = "How often is it paid?",
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CostFrequency.entries) { option ->
                        FilterPill(
                            label = option.label,
                            selected = frequency == option,
                            onClick = { frequency = option },
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
