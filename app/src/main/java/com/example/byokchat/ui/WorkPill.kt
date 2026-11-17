package com.example.byokchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.container

/** Status of one work step, derived exactly like the old per-tool cards did. */
private enum class StepStatus { RUNNING, WAITING, DONE, FAILED, INTERRUPTED }

/**
 * One pill for one stretch of work by one agent (17/11/2026, mockup
 * "Pill mockup v2"): a single-line pill at the top of its turn — the
 * agent's name and the latest step in short Vietnamese ("Đang đọc file
 * abc.md…", "Đã xem thời tiết Hà Nội hôm nay"), never file contents or
 * tool results. Tap it for the table of steps: a bottom sheet with two
 * detents (50% on open, 100% on drag up, still leaving the status bar),
 * and tap one step to slide right into its detail page. Reasoning
 * appears only when the provider really returned readable text — never
 * invented. The pill only renders when the turn has tool calls or
 * readable reasoning (a plain answer has no pill); the grouping lives in
 * WorkSegments and is unchanged.
 */
@Composable
internal fun WorkPill(
    segment: WorkSegment,
    generating: Boolean,
) {
    val container = LocalContext.current.container
    val pending by container.confirmations.pending.collectAsState()
    val progressMap by container.toolProgress.progress.collectAsState()
    val agentName = if (segment.sender == null) "Main" else subAgentMember(segment.sender).name
    // Display name for a sub-agent id (the id itself when the agent is gone).
    val agentNameFor: (String) -> String = { id -> subAgentMember(id).name }

    fun displayName(call: AiToolCall): String =
        call.uid?.let { container.registry.nameForUid(it) } ?: call.name

    fun statusOf(step: WorkStep.Tool): StepStatus {
        val res = step.result
        val waiting = res == null && generating && pending.any { it.uid == step.call.uid }
        val running = res == null && generating && !waiting
        return when {
            waiting -> StepStatus.WAITING
            running -> StepStatus.RUNNING
            res == null -> StepStatus.DONE
            res.text == ModelFacingText.INTERRUPTED -> StepStatus.INTERRUPTED
            res.toolIsError -> StepStatus.FAILED
            else -> StepStatus.DONE
        }
    }

    val toolStatuses = segment.toolSteps.map { statusOf(it) }
    // A reasoning-only segment has no tool steps: it is running while the
    // chat is still generating, done once the turn is over.
    val segmentStatus = when {
        segment.toolSteps.isEmpty() -> if (generating) StepStatus.RUNNING else StepStatus.DONE
        toolStatuses.contains(StepStatus.WAITING) -> StepStatus.WAITING
        toolStatuses.contains(StepStatus.RUNNING) -> StepStatus.RUNNING
        toolStatuses.contains(StepStatus.FAILED) -> StepStatus.FAILED
        toolStatuses.contains(StepStatus.INTERRUPTED) -> StepStatus.INTERRUPTED
        else -> StepStatus.DONE
    }
    // The step the pill streams while the agent works: the latest one with
    // no result yet. A running download reports live progress in the
    // sheet; the pill itself stays one short sentence.
    val runningStep = segment.toolSteps.lastOrNull { statusOf(it) == StepStatus.RUNNING }
    val latestStep = segment.steps.lastOrNull()
    val label = when (segmentStatus) {
        StepStatus.WAITING -> "Chờ bạn duyệt…"
        StepStatus.RUNNING -> when {
            runningStep != null -> pillLabel(runningStep, running = true, done = false, agentNameFor)
            latestStep is WorkStep.Reasoning -> "Đang suy nghĩ…"
            else -> "Đang chạy…"
        }
        StepStatus.FAILED -> latestStep?.let {
            "Lỗi khi " + stepPhrase(it, agentNameFor).replaceFirstChar { c -> c.lowercaseChar() }
        } ?: "Lỗi"
        StepStatus.INTERRUPTED -> "Đã dừng"
        StepStatus.DONE -> latestStep?.let { pillLabel(it, running = false, done = true, agentNameFor) } ?: "Xong"
    }

    var sheetOpen by remember(segment.anchorId) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(50),
        modifier = Modifier.clickable { sheetOpen = true },
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (segmentStatus) {
                StepStatus.RUNNING, StepStatus.WAITING ->
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                StepStatus.FAILED ->
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                StepStatus.INTERRUPTED ->
                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                StepStatus.DONE ->
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Text(
                agentName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Xem các bước làm việc của $agentName",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (sheetOpen) {
        WorkSheet(
            segment = segment,
            agentName = agentName,
            agentNameFor = agentNameFor,
            displayName = ::displayName,
            statusOf = ::statusOf,
            progressLabel = { step -> progressMap[step.call.id]?.label() },
            onDismiss = { sheetOpen = false },
        )
    }
}

/**
 * Second level: the table of steps as a bottom sheet with two detents —
 * 50% on open, 100% on drag up (ModalBottomSheet's expanded state still
 * leaves the status bar visible); drag down returns to 50%, further
 * down dismisses; reopening starts at 50% again. Tap a step to slide
 * right into its detail page; back goes one level at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkSheet(
    segment: WorkSegment,
    agentName: String,
    agentNameFor: (String) -> String,
    displayName: (AiToolCall) -> String,
    statusOf: (WorkStep.Tool) -> StepStatus,
    progressLabel: (WorkStep.Tool) -> String?,
    onDismiss: () -> Unit,
) {
    // skipPartiallyExpanded = false: the sheet opens at the 50% detent
    // and can be dragged up to full height.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var detailStep by remember(segment.anchorId) { mutableStateOf<WorkStep?>(null) }
    BackHandler(enabled = detailStep != null) { detailStep = null }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        AnimatedContent(
            targetState = detailStep,
            transitionSpec = {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it / 5 } + fadeOut()
            },
            label = "work-step-detail",
        ) { step ->
            if (step == null) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("$agentName — các bước đã làm", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Theo thứ tự thời gian · chạm vào một việc để xem chi tiết",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                        itemsIndexed(segment.steps, key = { index, s -> "$index:${stepKey(s)}" }) { _, s ->
                            when (s) {
                                is WorkStep.Reasoning -> StepRow(
                                    icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                                    title = stepPhrase(s, agentNameFor),
                                    meta = "Xong",
                                    metaColor = MaterialTheme.colorScheme.primary,
                                    onClick = { detailStep = s },
                                )
                                is WorkStep.Tool -> {
                                    val status = statusOf(s)
                                    val progress = progressLabel(s)
                                    val (meta, metaColor) = when (status) {
                                        StepStatus.WAITING -> "Chờ duyệt…" to MaterialTheme.colorScheme.tertiary
                                        StepStatus.RUNNING -> (progress ?: "Đang chạy…") to MaterialTheme.colorScheme.onSurfaceVariant
                                        StepStatus.FAILED -> "Lỗi" to MaterialTheme.colorScheme.error
                                        StepStatus.INTERRUPTED -> "Đã dừng" to MaterialTheme.colorScheme.onSurfaceVariant
                                        StepStatus.DONE -> "Xong" to MaterialTheme.colorScheme.primary
                                    }
                                    StepRow(
                                        icon = {
                                            when (status) {
                                                StepStatus.RUNNING, StepStatus.WAITING ->
                                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                                StepStatus.FAILED ->
                                                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                                StepStatus.INTERRUPTED ->
                                                    Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                                StepStatus.DONE ->
                                                    if (s.result == null) {
                                                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    } else {
                                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    }
                                            }
                                        },
                                        title = stepPhrase(s, agentNameFor),
                                        meta = meta,
                                        metaColor = metaColor,
                                        onClick = { detailStep = s },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                WorkStepDetail(
                    step = step,
                    agentNameFor = agentNameFor,
                    displayName = displayName,
                    statusOf = statusOf,
                    onBack = { detailStep = null },
                )
            }
        }
    }
}

private fun stepKey(step: WorkStep): String = when (step) {
    is WorkStep.Reasoning -> "reasoning:${step.text.hashCode()}"
    is WorkStep.Tool -> "tool:${step.call.id}"
}

@Composable
private fun StepRow(
    icon: @Composable () -> Unit,
    title: String,
    meta: String,
    metaColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        Text(meta, style = MaterialTheme.typography.labelSmall, color = metaColor)
    }
}

/**
 * Third level: one step's detail, slid in from the right over the list
 * — what tool did it, what it aimed at, its status, and the result
 * (shown here only, never streamed on the pill).
 */
@Composable
private fun WorkStepDetail(
    step: WorkStep,
    agentNameFor: (String) -> String,
    displayName: (AiToolCall) -> String,
    statusOf: (WorkStep.Tool) -> StepStatus,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tất cả công việc")
            }
            Text("Tất cả công việc", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Text("Chi tiết", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                is WorkStep.Reasoning -> {
                    Text("Suy nghĩ", style = MaterialTheme.typography.titleMedium)
                    Text("Xong", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(step.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is WorkStep.Tool -> {
                    val name = displayName(step.call)
                    val status = statusOf(step)
                    val res = step.result
                    val (statusLabel, statusColor) = when (status) {
                        StepStatus.WAITING -> "Chờ duyệt…" to MaterialTheme.colorScheme.tertiary
                        StepStatus.RUNNING -> "Đang chạy…" to MaterialTheme.colorScheme.onSurfaceVariant
                        StepStatus.FAILED -> "Lỗi" to MaterialTheme.colorScheme.error
                        StepStatus.INTERRUPTED -> "Đã dừng" to MaterialTheme.colorScheme.onSurfaceVariant
                        StepStatus.DONE -> "Xong" to MaterialTheme.colorScheme.primary
                    }
                    Text(stepPhrase(step, agentNameFor), style = MaterialTheme.typography.titleMedium)
                    Text(statusLabel, style = MaterialTheme.typography.labelLarge, color = statusColor)
                    DetailRow("Làm bằng", name)
                    stepTarget(step, agentNameFor)?.let { DetailRow("Nhắm vào", it) }
                    DetailRow("Trạng thái", statusLabel)
                    Text("Tham số", style = MaterialTheme.typography.labelSmall)
                    Text(
                        prettyArgs(step.call.argsJson).take(3000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("Kết quả", style = MaterialTheme.typography.labelSmall)
                    Text(
                        res?.text?.take(4000) ?: "Chưa có kết quả",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
