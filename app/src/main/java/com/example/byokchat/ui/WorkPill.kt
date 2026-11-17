package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.container

/** Status of one work step, derived exactly like the old per-tool cards did. */
private enum class StepStatus { RUNNING, WAITING, DONE, FAILED, INTERRUPTED }

private fun firstLine(text: String, max: Int = 60): String {
    val t = text.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (t.length > max) t.take(max - 1) + "…" else t
}

/**
 * One pill for one stretch of work by one agent (17/11/2026, approved
 * mockup): the organisation is Agora's three levels — a summary pill, a
 * table of steps, a bottom sheet per step — but the look is Tov's own
 * (surfaceVariant, 12dp corners, the same labels and colours the old
 * per-tool cards used). The pill streams the latest unfinished step while
 * the agent works ("Đang gọi …"), then summarises ("Đã gọi N tool • Xong").
 * Tap the pill to expand the table; tap a row to open its full content in
 * a sheet. Reasoning appears only when the provider really returned
 * readable text — never invented.
 */
@Composable
internal fun WorkPill(
    segment: WorkSegment,
    generating: Boolean,
    initiallyExpanded: Boolean = false,
) {
    val container = LocalContext.current.container
    val pending by container.confirmations.pending.collectAsState()
    val progressMap by container.toolProgress.progress.collectAsState()
    var expanded by remember(segment.anchorId) { mutableStateOf(initiallyExpanded) }
    var detailStep by remember(segment.anchorId) { mutableStateOf<WorkStep?>(null) }

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
    val segmentStatus = when {
        toolStatuses.contains(StepStatus.WAITING) -> StepStatus.WAITING
        toolStatuses.contains(StepStatus.RUNNING) -> StepStatus.RUNNING
        toolStatuses.contains(StepStatus.FAILED) -> StepStatus.FAILED
        toolStatuses.contains(StepStatus.INTERRUPTED) -> StepStatus.INTERRUPTED
        else -> StepStatus.DONE
    }
    // The step the pill streams while the agent works: the latest one with
    // no result yet. A running download reports live progress, like the
    // old cards did ("Running. Progress 42%"); no total -> downloaded size.
    val runningStep = segment.toolSteps.lastOrNull { statusOf(it) == StepStatus.RUNNING }
    // Live download progress of the running step, if any (same as the old
    // cards: "Running. Progress 42%", or downloaded size with no total).
    val runningProgress = runningStep?.let { progressMap[it.call.id]?.label() }

    val (title, label, labelColor) = when (segmentStatus) {
        StepStatus.WAITING -> "Chờ bạn duyệt…" to "Chờ duyệt…" to MaterialTheme.colorScheme.tertiary
        StepStatus.RUNNING -> {
            val summary = runningStep?.let { toolSummary(it.call.uid, displayName(it.call), it.call.argsJson) }.orEmpty()
            "Đang gọi $summary…" to (runningProgress ?: "Đang chạy…") to MaterialTheme.colorScheme.onSurfaceVariant
        }
        StepStatus.FAILED -> (
            if (segment.hasTools) "Đã gọi ${segment.toolSteps.size} tool" else "Suy nghĩ"
            ) to "Lỗi" to MaterialTheme.colorScheme.error
        StepStatus.INTERRUPTED -> (
            if (segment.hasTools) "Đã gọi ${segment.toolSteps.size} tool" else "Suy nghĩ"
            ) to "Đã dừng" to MaterialTheme.colorScheme.onSurfaceVariant
        StepStatus.DONE -> (
            if (segment.hasTools) "Đã gọi ${segment.toolSteps.size} tool" else "Suy nghĩ"
            ) to "Xong" to MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (segmentStatus) {
                    StepStatus.RUNNING, StepStatus.WAITING ->
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    StepStatus.FAILED ->
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    StepStatus.INTERRUPTED ->
                        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    StepStatus.DONE ->
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.padding(start = 8.dp).weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Thu gọn" else "Mở bảng công việc",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                HorizontalDivider()
                segment.steps.forEachIndexed { index, step ->
                    if (index > 0) HorizontalDivider()
                    when (step) {
                        is WorkStep.Reasoning -> StepRow(
                            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                            title = "Suy nghĩ",
                            subtitle = firstLine(step.text, 80),
                            statusLabel = "Đã lưu",
                            statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { detailStep = step },
                        )
                        is WorkStep.Tool -> {
                            val name = displayName(step.call)
                            val summary = toolSummary(step.call.uid, name, step.call.argsJson)
                            val status = statusOf(step)
                            val stepProgress = progressMap[step.call.id]?.label()
                            val subtitle = when {
                                summary != name -> summary
                                step.result != null -> firstLine(step.result.text)
                                status == StepStatus.RUNNING -> stepProgress ?: "Đang chạy…"
                                status == StepStatus.WAITING -> "Chờ bạn duyệt…"
                                else -> "Chưa có kết quả"
                            }
                            val (statusLabel, statusColor) = when (status) {
                                StepStatus.WAITING -> "Chờ duyệt…" to MaterialTheme.colorScheme.tertiary
                                StepStatus.RUNNING -> (stepProgress ?: "Đang chạy…") to MaterialTheme.colorScheme.onSurfaceVariant
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
                                            if (step.result == null) {
                                                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                                            } else {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            }
                                    }
                                },
                                title = name,
                                subtitle = subtitle,
                                statusLabel = statusLabel,
                                statusColor = statusColor,
                                onClick = { detailStep = step },
                            )
                        }
                    }
                }
            }
        }
    }
    detailStep?.let { step ->
        WorkStepSheet(step = step, onDismiss = { detailStep = null })
    }
}

@Composable
private fun StepRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    statusLabel: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
    }
}

/**
 * Third level: a bottom sheet with one step's full content — a tool's
 * arguments and result, or a reasoning passage in full. Same sheet
 * pattern as ModelPickerSheet (skipPartiallyExpanded).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkStepSheet(step: WorkStep, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        when (step) {
            is WorkStep.Reasoning -> {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Suy nghĩ", style = MaterialTheme.typography.titleMedium)
                    Text(step.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is WorkStep.Tool -> {
                val container = LocalContext.current.container
                val name = step.call.uid?.let { container.registry.nameForUid(it) } ?: step.call.name
                val res = step.result
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            res == null -> "Chưa có kết quả"
                            res.text == ModelFacingText.INTERRUPTED -> "Đã dừng"
                            res.toolIsError -> "Lỗi"
                            else -> "Xong"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            res == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            res.text == ModelFacingText.INTERRUPTED -> MaterialTheme.colorScheme.onSurfaceVariant
                            res.toolIsError -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
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
