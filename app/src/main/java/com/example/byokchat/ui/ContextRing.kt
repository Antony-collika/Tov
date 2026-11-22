package com.example.byokchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.byokchat.data.ContextLimits
import java.util.Locale
import kotlin.math.roundToInt

/** Số token viết đầy đủ, kiểu "1,000" / "100,000" (khác formatTokens: "1k" làm tròn quá thô cho hộp này). */
private fun formatExactTokens(n: Int): String = String.format(Locale.US, "%,d", n)

/** Số token ngắn cho nhãn thanh trượt, kiểu "32K" / "500K" / "1M". */
private fun formatShortTokens(n: Int): String = when {
    n >= 1_000_000 -> "1M"
    n >= 1_000 -> "${n / 1_000}K"
    else -> "$n"
}

/**
 * The "Context window" dialog (22/11/2026, rebuilt to match Duy's sample):
 * a donut — red = used, light blue = still available — over the global
 * reference number the user picks with the slider below.
 *
 * Layout follows the sample, not the stock Material3 dialog: a custom
 * Dialog (usePlatformDefaultWidth = false, ~92% of screen width) with
 * tight 10dp gaps, a large donut (~108dp, centre number the biggest
 * bold text in the dialog), a legend whose label baseline aligns with
 * the value line, a custom continuous slider with a large round thumb
 * and an attached value bubble, and a filled "Close" button.
 *
 * - `used` is the REAL count the provider reported on the last Main turn
 *   (input + output; reasoning is already inside output), unless `isEstimate`
 *   is true — then it is the old RequestAssembler estimate and the dialog says so.
 * - `limit` is a reference number the user picks (one number for every chat
 *   and provider, default 100k), changed live by the slider. It is ONLY for
 *   comparison — the dialog never warns and never changes how the app sends
 *   requests. No nearly-full warning, no "New chat" offer: a BYOK user
 *   already knows their provider and model (20/11/2026).
 *
 * History of the sizing bug (do NOT repeat it): the donut was shrunk
 * 120dp -> 96dp -> 80dp just to make "Available" fit on one line, which
 * shrank the centre number to 13sp and made the donut look lost. This
 * build fixes the legend layout instead (value column measured first,
 * label takes the rest, wider custom dialog) and keeps the donut large.
 */
@Composable
fun ChatMemoryDialog(
    used: Int,
    limit: Int,
    dropped: Int,
    isEstimate: Boolean = true,
    open: Boolean,
    onDismiss: () -> Unit,
    /** Opens the Messages screen ("Chat History") for this chat (null = no button). */
    onOpenMessages: (() -> Unit)? = null,
    /** User moved the slider — the new limit is saved immediately so the donut updates live. */
    onLimitChange: ((Int) -> Unit)? = null,
    /** User released the slider — the caller recomputes the stats for the new limit. */
    onLimitChangeFinished: (() -> Unit)? = null,
) {
    if (!open || limit <= 0) return
    val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val available = maxOf(limit - used, 0)
    val usedPct = String.format(Locale.US, "%.1f%%", used.toFloat() / limit.toFloat() * 100)
    val availablePct = String.format(Locale.US, "%.1f%%", available.toFloat() / limit.toFloat() * 100)
    val usedColor = Color(0xFFE85D5D)
    val availableColor = Color(0xFFBFE6F2)
    val stops = ContextLimits.STOPS
    val stopIndex = stops.indices.minByOrNull { kotlin.math.abs(stops[it] - limit) } ?: 2
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Tiêu đề + nút X trong vòng tròn xám, như mẫu.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Context window",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Donut trái, chú thích phải — donut to (~108dp, ~37%
                // bề rộng nội dung), số giữa là chữ to nhất trong hộp.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(108.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(108.dp)) {
                            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Butt)
                            drawArc(
                                color = availableColor,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = stroke,
                            )
                            if (fraction > 0f) {
                                drawArc(
                                    color = usedColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f * fraction,
                                    useCenter = false,
                                    style = stroke,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                formatExactTokens(used),
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                maxLines = 1,
                            )
                            Text(
                                "/ ${formatExactTokens(limit)}",
                                fontSize = 11.sp,
                                color = onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LegendRow(
                            dotColor = usedColor,
                            label = "Used",
                            value = "${formatExactTokens(used)} tokens",
                            pct = usedPct,
                        )
                        LegendRow(
                            dotColor = availableColor,
                            label = "Available",
                            value = "${formatExactTokens(available)} tokens",
                            pct = availablePct,
                        )
                    }
                }

                // Đường kẻ mỏng ngăn donut với phần slider, như mẫu.
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // Thanh trượt chọn số tham chiếu.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Context window length", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    // (22/11/2026, Duy): chỉ một dòng ngắn, không còn câu
                    // dài 3 dòng "Pick a reference length..." nữa.
                    Text(
                        "Reference only",
                        fontSize = 12.sp,
                        color = onSurfaceVariant,
                        maxLines = 1,
                    )
                    ReferenceSlider(
                        stopIndex = stopIndex,
                        stops = stops,
                        onLimitChange = onLimitChange,
                        onLimitChangeFinished = onLimitChangeFinished,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            formatShortTokens(stops.first()),
                            fontSize = 12.sp,
                            color = onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            formatShortTokens(stops.last()),
                            fontSize = 12.sp,
                            color = onSurfaceVariant,
                        )
                    }
                }

                // Ghi chú theo mẫu, chữ nhỏ, tối đa 2 dòng, có icon "i".
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "A larger context window can handle longer conversations but may use more memory.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = onSurfaceVariant,
                        maxLines = 2,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (dropped > 0) {
                        Text(
                            "The $dropped oldest turn(s) are no longer sent to the AI so the chat fits the limit.",
                            fontSize = 11.sp,
                        )
                    }
                    Text(
                        if (isEstimate) "This is an estimate."
                        else "Counted by the provider on the last turn.",
                        fontSize = 11.sp,
                        color = onSurfaceVariant,
                    )
                }

                // Chân hộp: "Chat History" là chữ tím, "Close" là nút
                // tím đầy — hành động chính, như mẫu.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onOpenMessages != null) {
                        TextButton(onClick = { onDismiss(); onOpenMessages() }) {
                            Text("Chat History", color = primary)
                        }
                    }
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 20.dp,
                            vertical = 0.dp,
                        ),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Close", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Một hàng chú thích: chấm màu + nhãn bên trái, số token đậm + %
 * bên phải. Nhãn căn theo đầu hàng (thẳng hàng với dòng số, không
 * nằm giữa số và % như bản cũ), cột số đo trước nên nhãn lấy phần
 * còn lại — "Available" vẫn vừa một dòng ngay cả ở nấc 1M
 * ("1,000,000 tokens").
 */
@Composable
private fun LegendRow(dotColor: Color, label: String, value: String, pct: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            Text(
                pct,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

/**
 * Thanh trượt tự vẽ, theo mẫu: track liền mạch (không chấm nấc),
 * dày 8dp, nửa trái tím, nửa phải xám nhạt; núm tròn tím 24dp có
 * viền trắng dày; nhãn giá trị nền tím đậm chữ trắng dính sát trên
 * núm, có mũi nhọn chỉ xuống núm. Kéo hoặc chạm vào track đều
 * nhảy tới nấc gần nhất.
 */
@Composable
private fun ReferenceSlider(
    stopIndex: Int,
    stops: List<Int>,
    onLimitChange: ((Int) -> Unit)?,
    onLimitChangeFinished: (() -> Unit)?,
) {
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val thumbRadiusPx = with(density) { 12.dp.toPx() }
        val trackCenterYPx = with(density) { 40.dp.toPx() }
        val trackTopPx = trackCenterYPx - with(density) { 4.dp.toPx() }
        fun indexFromX(x: Float): Int {
            val fraction = ((x - thumbRadiusPx) / (trackWidthPx - 2 * thumbRadiusPx))
                .coerceIn(0f, 1f)
            return (fraction * (stops.size - 1)).roundToInt()
                .coerceIn(0, stops.size - 1)
        }
        val fraction = stopIndex.toFloat() / (stops.size - 1)
        val thumbCenterXPx = thumbRadiusPx + fraction * (trackWidthPx - 2 * thumbRadiusPx)
        var bubbleWidthPx by remember { mutableStateOf(0) }
        val bubbleXPx = (thumbCenterXPx - bubbleWidthPx / 2f)
            .coerceIn(0f, maxOf(trackWidthPx - bubbleWidthPx, 0f))
        val thumbWidthDp = with(density) { thumbCenterXPx.toDp() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .pointerInput(trackWidthPx) {
                    detectTapGestures { offset ->
                        val idx = indexFromX(offset.x)
                        if (idx != stopIndex) onLimitChange?.invoke(stops[idx])
                        onLimitChangeFinished?.invoke()
                    }
                }
                .pointerInput(trackWidthPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onLimitChangeFinished?.invoke() },
                    ) { change, _ ->
                        val idx = indexFromX(change.position.x)
                        if (idx != stopIndex) onLimitChange?.invoke(stops[idx])
                    }
                },
        ) {
            // Nhãn giá trị dính sát trên núm.
            Column(
                modifier = Modifier
                    .offset { IntOffset(bubbleXPx.roundToInt(), 0) }
                    .onSizeChanged { bubbleWidthPx = it.width },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formatShortTokens(stops[stopIndex]),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(primary)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                // Mũi nhọn chỉ xuống núm (hình vuông xoay 45°, nửa
                // trên chìm sau nhãn, nửa dưới nhô ra thành mũi).
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .offset(y = (-5).dp)
                        .rotate(45f)
                        .background(primary),
                )
            }
            // Track: phần chưa chọn (xám nhạt, toàn bề rộng).
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, trackTopPx.roundToInt()) }
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            // Track: phần đã chọn (tím, từ đầu tới tâm núm).
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, trackTopPx.roundToInt()) }
                    .width(thumbWidthDp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(primary),
            )
            // Núm tròn tím 24dp, viền trắng 3dp, có bóng: vòng ngoài
            // trắng 24dp, lõi tím 18dp ở giữa.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (thumbCenterXPx - thumbRadiusPx).roundToInt(),
                            (trackCenterYPx - thumbRadiusPx).roundToInt(),
                        )
                    }
                    .shadow(4.dp, CircleShape)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(primary),
                )
            }
        }
    }
}
