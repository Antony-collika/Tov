package com.example.byokchat.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.chat.ModelFacingText
import com.example.byokchat.container

/** Status of one work step, derived exactly like the old per-tool cards did. */
private enum class StepStatus { RUNNING, WAITING, DONE, FAILED, INTERRUPTED }

/**
 * One stretch of work by one agent as inline text lines (chat redesign
 * 28/11/2026): no pill, no bubble — each reasoning passage or tool call
 * is one line in the same flow as the message, in the agent's colour.
 * While the agent works, the lines are open: one row per step, the
 * running step in the present tense, finished steps in the past tense
 * (via [pillLabel]), each row tappable to open its detail in the bottom
 * sheet (always 50% height, draggable to 100%). Once the work is done
 * the lines collapse into ONE line — the agent's name plus "Worked for
 * …" (tools) or "Thought for …" (reasoning only), with the elapsed time
 * frozen at the last activity; tapping it (or its chevron) re-opens the
 * step rows inline below it. A finished sub waiting for its turn to
 * speak, a failed run and a stopped run keep their old one-line labels
 * ("Đã xong · đang chờ X nói xong" / "Lỗi khi …" / "Đã dừng") with a
 * chevron to open the rows. Reasoning appears only when the provider
 * really returned readable text — never invented. The stream only
 * renders when the turn has tool calls or readable reasoning (a plain
 * answer has no segment, so no line); the grouping lives in
 * WorkSegments and is unchanged.
 */
@Composable
internal fun WorkStream(
    segment: WorkSegment,
    generating: Boolean,
    /** Phase 4: a finished sub waiting for its turn to speak — the name of the agent currently speaking (null = not waiting). */
    waitingForName: String? = null,
    /** The sender's member (for the agent colour); null falls back to the colour derived from the segment's sender id. */
    senderMember: GroupMember? = null,
) {
    val container = LocalContext.current.container
    val pending by container.confirmations.pending.collectAsState()
    val agentName = if (segment.sender == null) "Main" else subAgentMember(segment.sender).name
    val agentColor = senderMember?.let { memberColor(it) }
        ?: memberColor(subAgentMember(segment.sender ?: "main"))
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
    // The step the stream follows while the agent works: the latest one with
    // no result yet.
    val runningStep = segment.toolSteps.lastOrNull { statusOf(it) == StepStatus.RUNNING }
    val latestStep = segment.steps.lastOrNull()
    // Live = still working (running, or waiting for approval). A finished
    // sub waiting for its turn to speak is NOT live: it shows the single
    // collapsed line instead of the open rows.
    val isLive = segmentStatus == StepStatus.RUNNING || segmentStatus == StepStatus.WAITING

    // One step's row: its display status and its label (present tense
    // while it runs, past tense once done, plain phrase while it has not
    // started yet / failed / stopped).
    fun stepLine(step: WorkStep): Pair<StepStatus, String> {
        if (step is WorkStep.Reasoning) {
            val running = segmentStatus == StepStatus.RUNNING && step == latestStep && runningStep == null
            return if (running) StepStatus.RUNNING to pillLabel(step, running = true, done = false, agentNameFor)
            else StepStatus.DONE to pillLabel(step, running = false, done = true, agentNameFor)
        }
        step as WorkStep.Tool
        return when (val st = statusOf(step)) {
            StepStatus.RUNNING -> st to pillLabel(step, running = true, done = false, agentNameFor)
            StepStatus.DONE -> st to pillLabel(step, running = false, done = true, agentNameFor)
            else -> st to pillLabel(step, running = false, done = false, agentNameFor)
        }
    }

    // The collapsed line's sentence. Done: "Worked for …" when the
    // segment called tools, "Thought for …" for reasoning only — the
    // seconds are lastActivityAt - startedAt, frozen once done (no
    // ticking). Waiting / failed / stopped keep the old pill labels.
    val collapsedLabel = when {
        waitingForName != null -> "Đã xong · đang chờ $waitingForName nói xong"
        segmentStatus == StepStatus.FAILED -> latestStep?.let {
            "Lỗi khi " + stepPhrase(it, agentNameFor).replaceFirstChar { c -> c.lowercaseChar() }
        } ?: "Lỗi"
        segmentStatus == StepStatus.INTERRUPTED -> "Đã dừng"
        else -> {
            val elapsedMs = (segment.lastActivityAt - segment.startedAt).coerceAtLeast(0)
            (if (segment.hasTools) "Worked for " else "Thought for ") + formatElapsedEn(elapsedMs)
        }
    }

    var userExpanded by remember(segment.anchorId) { mutableStateOf(false) }
    var detailStep by remember(segment.anchorId) { mutableStateOf<WorkStep?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (isLive) {
            segment.steps.forEach { step ->
                val (status, label) = stepLine(step)
                WorkStepLine(status, label, agentColor) { detailStep = step }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().clickable { userExpanded = !userExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    waitingForName != null ->
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    segmentStatus == StepStatus.FAILED ->
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    segmentStatus == StepStatus.INTERRUPTED ->
                        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    else ->
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = agentColor, modifier = Modifier.size(16.dp))
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = agentColor)) { append(agentName) }
                        append(" $collapsedLabel")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (userExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (userExpanded) "Thu lại các bước làm việc của $agentName" else "Xem các bước làm việc của $agentName",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (userExpanded) {
                segment.steps.forEach { step ->
                    val (status, label) = stepLine(step)
                    WorkStepLine(status, label, agentColor) { detailStep = step }
                }
            }
        }
    }
    detailStep?.let { step ->
        WorkDetailSheet(
            step = step,
            agentNameFor = agentNameFor,
            displayName = ::displayName,
            statusOf = ::statusOf,
            onDismiss = { detailStep = null },
        )
    }
}

/**
 * One inline work row: a 16dp status icon, the step's label in the
 * agent's colour, and a grey chevron at the end. Tapping the row opens
 * the step's detail in the bottom sheet.
 */
@Composable
private fun WorkStepLine(
    status: StepStatus,
    label: String,
    agentColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (status) {
            StepStatus.RUNNING, StepStatus.WAITING ->
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            StepStatus.FAILED ->
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            StepStatus.INTERRUPTED ->
                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            StepStatus.DONE ->
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = agentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Second level: the table of steps, expanded INLINE at the pill's
 * position in the chat (18/11/2026, "Luồng pill nở tại chỗ") — no
 * bottom sheet for the list anymore. Max height is 8x the pill's
 * height (~384dp, ~6-7 rows): shorter lists wrap to content, longer
 * ones scroll inside the table, with a hint that more rows remain.
 * Tap a step to open its detail in a bottom sheet.
 */
@Composable
private fun WorkStepTable(
    segment: WorkSegment,
    agentName: String,
    agentNameFor: (String) -> String,
    statusOf: (WorkStep.Tool) -> StepStatus,
    progressLabel: (WorkStep.Tool) -> String?,
    onStepClick: (WorkStep) -> Unit,
) {
    // Rows are ~48dp and the header ~50dp, so 384dp shows the header
    // plus ~6-7 rows; more than 7 steps means the table scrolls.
    val visibleRows = 7
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 384.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$agentName — các bước đã làm", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Theo thứ tự thời gian · chạm vào một việc để xem chi tiết",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            segment.steps.forEach { s ->
                when (s) {
                    is WorkStep.Reasoning -> StepRow(
                        icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                        title = stepPhrase(s, agentNameFor),
                        meta = "Xong",
                        metaColor = MaterialTheme.colorScheme.primary,
                        onClick = { onStepClick(s) },
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
                            onClick = { onStepClick(s) },
                        )
                    }
                }
                HorizontalDivider()
            }
            if (segment.steps.size > visibleRows) {
                Text(
                    "Còn ${segment.steps.size - visibleRows} việc nữa — cuộn xuống để xem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Third level: one step's detail as a bottom sheet. The sheet always
 * opens at a FIXED 50% height — its content is forced to at least
 * half the screen, so even a short detail leaves empty space below
 * instead of sticking to the bottom edge, and it can always be
 * dragged up to 100% (Claude style, 18/11/2026). Drag down returns
 * to 50%, further down dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkDetailSheet(
    step: WorkStep,
    agentNameFor: (String) -> String,
    displayName: (AiToolCall) -> String,
    statusOf: (WorkStep.Tool) -> StepStatus,
    onDismiss: () -> Unit,
) {
    // skipPartiallyExpanded = false: the sheet opens at the 50% detent
    // and can be dragged up to full height (the expanded state still
    // leaves the status bar visible).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = screenHeight * 0.5f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            WorkStepDetail(
                step = step,
                agentNameFor = agentNameFor,
                displayName = displayName,
                statusOf = statusOf,
                onBack = onDismiss,
            )
        }
    }
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
