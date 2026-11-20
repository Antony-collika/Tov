package com.example.byokchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The "Context window" dialog (20/11/2026): a donut — red = used, light blue =
 * still available — over the global limit the user sets with the slider below.
 * It used to live inside the round ContextRing button in the top bar; the ring is gone,
 * so the dialog is now opened from the ⋮ menu instead.
 *
 * - `used` is the REAL count the provider reported on the last Main turn
 *   (input + output; reasoning is already inside output), unless `isEstimate`
 *   is true — then it is the old RequestAssembler estimate and the dialog says so.
 * - `limit` is a reference number the user picks (one number for every chat
 *   and provider, default 100k), changed live by the slider. It is ONLY for
 *   comparison — the dialog never warns and never changes how the app sends
 *   requests. No nearly-full warning, no "New chat" offer: a BYOK user
 *   already knows their provider and model (20/11/2026).
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Context window", modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Donut trái, chú thích phải như mẫu — với size vừa điện thoại
                // (23/11/2026): donut 96dp, chữ chú thích 13sp/12sp, và cột
                // nội dung fillMaxWidth để weight chia đúng chỗ. Lần trước
                // thiếu fillMaxWidth nên weight bị bóp về 0, chữ vỡ thành
                // từng chữ cái một dòng.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier.size(96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(96.dp)) {
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
                                fontSize = 15.sp,
                            )
                            Text(
                                "/ ${formatExactTokens(limit)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
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

                // Thanh trượt đặt giới hạn toàn cục.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Context window length", fontWeight = FontWeight.Bold)
                    Text(
                        "Pick a reference length to compare against. Display only — it does not change how the app works.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Nhãn con số nổi trên đúng vị trí núm trượt.
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = BiasAlignment(
                            horizontalBias = -1f + 2f * stopIndex.toFloat() / (stops.size - 1),
                            verticalBias = 0f,
                        ),
                    ) {
                        Text(
                            formatShortTokens(stops[stopIndex]),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Slider(
                        value = stopIndex.toFloat(),
                        onValueChange = { v ->
                            onLimitChange?.invoke(stops[v.roundToInt().coerceIn(0, stops.size - 1)])
                        },
                        onValueChangeFinished = { onLimitChangeFinished?.invoke() },
                        valueRange = 0f..(stops.size - 1).toFloat(),
                        steps = stops.size - 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            formatShortTokens(stops.first()),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            formatShortTokens(stops.last()),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Ghi chú theo mẫu.
                Text(
                    "A larger context window can handle longer conversations but may use more memory.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp),
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (dropped > 0) {
                        Text(
                            "The $dropped oldest turn(s) are no longer sent to the AI so the chat fits the limit.",
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        if (isEstimate) "This is an estimate."
                        else "Counted by the provider on the last turn.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (onOpenMessages != null) {
                    TextButton(onClick = { onDismiss(); onOpenMessages() }) { Text("Chat History") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun LegendRow(dotColor: Color, label: String, value: String, pct: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Column(horizontalAlignment = Alignment.End) {
            Text(value, fontSize = 12.sp, textAlign = TextAlign.End)
            Text(
                pct,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}
