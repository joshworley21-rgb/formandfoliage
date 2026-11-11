package uk.co.jwgardenservices.crm.ui.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp

private val monthLabelFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK)

private data class DragState(val job: ScheduledJob, val position: Offset)

/**
 * The month at a glance, with long-press drag-to-move for the owner.
 *
 * The agenda below remains the place to read the round; this grid is for the
 * one gesture that was still missing: picking a job up and dropping it on
 * another day. Staff can see the month but cannot drag.
 */
@Composable
fun MonthCalendar(
    jobs: Map<LocalDate, List<ScheduledJob>>,
    month: YearMonth,
    selectedDay: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    canDrag: Boolean,
    clientName: (String) -> String,
    onMove: (ScheduledJob, LocalDate) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var monthOrigin by remember { mutableStateOf(Offset.Zero) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val dayBounds = remember { mutableStateMapOf<LocalDate, Rect>() }
    var drag by remember { mutableStateOf<DragState?>(null) }

    // Holding a dragged job against the top or bottom of the grid pages the
    // month backwards or forwards, so a job can be moved without first
    // navigating elsewhere. A short cooldown stops it running away while the
    // finger stays parked on the edge.
    val edgeZone = with(density) { 28.dp.toPx() }
    val edgeCooldownMs = 700L
    var lastAutoPageAt by remember { mutableStateOf(0L) }
    fun autoPage(position: Offset) {
        val height = gridSize.height
        if (height <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastAutoPageAt < edgeCooldownMs) return
        when {
            position.y < monthOrigin.y + edgeZone -> {
                lastAutoPageAt = now
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMonthChange(month.minusMonths(1))
            }
            position.y > monthOrigin.y + height - edgeZone -> {
                lastAutoPageAt = now
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMonthChange(month.plusMonths(1))
            }
        }
    }

    val days = remember(month) {
        val start = month.atDay(1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = month.atEndOfMonth()
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .toList()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                monthOrigin = coordinates.boundsInRoot().topLeft
                gridSize = coordinates.size
            },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = month.format(monthLabelFormat),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                days.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            val dayJobs = jobs[day].orEmpty().sortedBy { it.startsAt }
                            val inMonth = YearMonth.from(day) == month
                            val isToday = day == LocalDate.now()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 54.dp)
                                    .onGloballyPositioned { coordinates ->
                                        dayBounds[day] = coordinates.boundsInRoot()
                                    }
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(
                                        if (day == selectedDay) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .clickable { onSelectDay(day) }
                                    .padding(2.dp),
                            ) {
                                Text(
                                    text = day.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isToday -> MaterialTheme.colorScheme.primary
                                        inMonth -> MaterialTheme.colorScheme.onSurface
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    },
                                )
                                dayJobs.take(3).forEach { job ->
                                    JobChip(
                                        job = job,
                                        canDrag = canDrag && job.status == "scheduled",
                                        haptics = haptics,
                                        onDragStart = { start, chipOrigin ->
                                            drag = DragState(job, chipOrigin + start)
                                        },
                                        onDrag = { amount ->
                                            drag = drag?.let {
                                                val moved = it.copy(position = it.position + amount)
                                                autoPage(moved.position)
                                                moved
                                            }
                                        },
                                        onDragEnd = {
                                            val current = drag
                                            drag = null
                                            val target = current?.position?.let { position ->
                                                dayBounds.entries
                                                    .firstOrNull { it.value.contains(position) }
                                                    ?.key
                                            }
                                            if (current != null && target != null) {
                                                val currentDay = parseTimestamp(current.job.startsAt)
                                                    ?.localDate()
                                                if (target != currentDay) onMove(current.job, target)
                                            }
                                        },
                                        onDragCancel = { drag = null },
                                    )
                                }
                                if (dayJobs.size > 2) {
                                    Text(
                                        text = "+${dayJobs.size - 2}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        repeat(7 - week.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Text(
                    text = if (canDrag) {
                        "Tap a day to see its jobs. Long-press a job to move it."
                    } else {
                        "Tap a day to see its jobs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            drag?.let { state ->
                Surface(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (state.position.x - monthOrigin.x).roundToInt(),
                                (state.position.y - monthOrigin.y).roundToInt(),
                            )
                        }
                        .zIndex(2f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = "${parseTimestamp(state.job.startsAt)?.formatTime() ?: ""} " +
                            clientName(state.job.clientId),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun JobChip(
    job: ScheduledJob,
    canDrag: Boolean,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onDragStart: (Offset, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var chipOrigin by remember(job.id) { mutableStateOf(Offset.Zero) }
    val dragModifier = if (canDrag) {
        Modifier
            .onGloballyPositioned { chipOrigin = it.boundsInRoot().topLeft }
            .pointerInput(job.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDragStart(start, chipOrigin)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
    } else {
        Modifier
    }

    Surface(
        color = if (job.status == "done") {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        },
        shape = RoundedCornerShape(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .then(dragModifier),
    ) {
        Text(
            text = parseTimestamp(job.startsAt)?.formatTime() ?: "",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
            color = if (job.status == "done") {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}
