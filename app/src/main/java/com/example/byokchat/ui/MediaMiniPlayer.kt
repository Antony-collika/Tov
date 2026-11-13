package com.example.byokchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.byokchat.AppContainer
import com.example.byokchat.media.MediaState
import com.example.byokchat.media.formatMediaTime

/**
 * Màu thanh trượt theo mẫu "Gradient" Duy duyệt 2026-11-13: giữ nguyên
 * gradient hồng → tím → xanh như mẫu, KHÔNG chỉnh về tông tím của app
 * (Duy: sau này làm theme cho app, tạm để gradient vậy đã).
 */
private val GradientPink = Color(0xFFEC4899)
private val GradientPurple = Color(0xFF8B5CF6)
private val GradientBlue = Color(0xFF4F46E5)
private val SliderTrackUnplayed = Color(0xFFE4E1F0)
private val SliderThumbBlue = Color(0xFF4F46E5)

/**
 * Thanh mini-player (kế hoạch media player, Đợt 3): chỉ đúng một hàng
 * điều khiển — previous | pause | next | time | thanh ngang | X — nằm ở
 * đầu vùng nội dung chat, ngay dưới top bar. KHÔNG có hàng tên bài phía
 * trên (Duy 2026-11-13: bỏ vì tốn diện tích, đây không phải app nhạc);
 * tên bài chỉ hiện trong thông báo nhạc và câu trả lời của AI.
 *
 * [MediaMiniPlayerHost] tự đọc trạng thái phát nhạc nên chỉ mình nó
 * recompose khi vị trí phát đổi (2 lần/giây), không kéo cả màn chat theo.
 */
@Composable
fun MediaMiniPlayerHost(container: AppContainer, modifier: Modifier = Modifier) {
    val mediaState by container.media.state.collectAsState()
    if (!mediaState.visible) return
    MediaMiniPlayerBar(
        state = mediaState,
        onPrevious = { container.media.previous() },
        onToggle = { container.media.toggle() },
        onNext = { container.media.next() },
        onSeek = { container.media.seekTo(it) },
        onClose = { container.media.stop() },
        modifier = modifier,
    )
}

@Composable
fun MediaMiniPlayerBar(
    state: MediaState,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Vị trí đang kéo (chưa thả): hiện vị trí kéo thay vì vị trí phát thật.
    var dragPosition by remember { mutableFloatStateOf(-1f) }
    val position = if (dragPosition >= 0f) dragPosition.toInt() else state.positionMs
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track")
            }
            IconButton(onClick = onToggle) {
                // Đang phát thì hiện pause, đang dừng tạm thì hiện play (bấm để phát tiếp).
                if (state.playing) {
                    Icon(Icons.Filled.Pause, contentDescription = "Pause")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next track")
            }
            Text(
                text = "${formatMediaTime(position)} / ${formatMediaTime(state.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(min = 76.dp),
            )
            // Thanh trượt kiểu mẫu "Gradient" Duy duyệt: rãnh mảnh, phần đã
            // phát gradient hồng → tím → xanh, nút kéo tròn xanh viền trắng.
            // Chỉ đổi hình thức; cách kéo/tua giữ nguyên như cũ.
            Slider(
                value = position.toFloat(),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    if (dragPosition >= 0f) {
                        onSeek(dragPosition.toInt())
                        dragPosition = -1f
                    }
                },
                valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
                thumb = {
                    Box(
                        Modifier
                            .size(20.dp)
                            .background(SliderThumbBlue, CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                    )
                },
                track = { sliderState ->
                    val range = sliderState.valueRange
                    val fraction = if (range.endInclusive > range.start) {
                        ((sliderState.value - range.start) /
                            (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        // Rãnh chưa phát: lavender nhạt, toàn chiều rộng.
                        drawRoundRect(
                            color = SliderTrackUnplayed,
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                        // Rãnh đã phát: gradient, kết thúc đúng tâm nút kéo.
                        val thumbRadiusPx = 10.dp.toPx()
                        val centerX = thumbRadiusPx + fraction * (size.width - thumbRadiusPx * 2)
                        if (centerX > 0f) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(GradientPink, GradientPurple, GradientBlue),
                                    startX = 0f,
                                    endX = centerX,
                                ),
                                size = Size(centerX, size.height),
                                cornerRadius = CornerRadius(size.height / 2),
                            )
                        }
                    }
                },
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Stop music")
            }
        }
    }
}
