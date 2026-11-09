package com.example.byokchat.ui

import android.graphics.Color as AColor
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.byokchat.util.TextUtil
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/** Thông tin tô sáng kết quả tìm kiếm. currentOccurrence = chỗ khớp đang chọn trong tin nhắn này (-1 nếu không có). */
data class Highlight(val query: String, val currentOccurrence: Int = -1)

/** One Markwon instance shared by every message (building one per message was slow). Provided in AppNav. */
val LocalMarkwon = staticCompositionLocalOf<Markwon?> { null }

fun createMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        .build()

/**
 * LinkMovementMethod that still lets the TextView fire its long-click. It returns false for
 * ACTION_DOWN so the View can schedule its long-press check, while link taps keep working
 * (the UP event still goes through LinkMovementMethod and opens the link).
 */
private class LongPressFriendlyLinkMovementMethod : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        return if (event.action == MotionEvent.ACTION_DOWN) {
            super.onTouchEvent(widget, buffer, event)
            false
        } else {
            super.onTouchEvent(widget, buffer, event)
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    highlight: Highlight? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val markwon = LocalMarkwon.current ?: remember(context) { createMarkwon(context) }
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textColor = color.toArgb()
    val key = markdown + "\u0000" + (highlight?.query ?: "") + "\u0000" + (highlight?.currentOccurrence ?: -1)
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 16f
                setLineSpacing(0f, 1.15f)
                isLongClickable = true
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setLinkTextColor(linkColor)
            // Keep the listener fresh on every recomposition so it always calls the latest callback.
            if (onLongPress != null) {
                tv.setOnLongClickListener { onLongPress(); true }
            } else {
                tv.setOnLongClickListener(null)
            }
            if (tv.tag != key) {
                tv.tag = key
                markwon.setMarkdown(tv, markdown)
                if (highlight != null && highlight.query.isNotEmpty()) {
                    val sp = tv.text as? Spannable
                    if (sp != null) {
                        val hits = TextUtil.findAll(sp.toString(), highlight.query)
                        hits.forEachIndexed { i, start ->
                            val c = if (i == highlight.currentOccurrence) AColor.parseColor("#FF9800") else AColor.parseColor("#80FFEB3B")
                            sp.setSpan(BackgroundColorSpan(c), start, start + highlight.query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                // Markwon installs LinkMovementMethod when the text contains links; it consumes
                // ACTION_DOWN on link spans, which stops the View from firing its long-click.
                val mm = tv.movementMethod
                if (mm is LinkMovementMethod && mm !is LongPressFriendlyLinkMovementMethod) {
                    tv.movementMethod = LongPressFriendlyLinkMovementMethod()
                }
            }
        },
    )
}

/**
 * Hiển thị tin của AI. Khi đang tìm kiếm trong cuộc trò chuyện (highlight != null) dùng đường cũ: một khối Markdown duy nhất,
 * để giữ cách đánh số chỗ khớp. Ngược lại, khối code được tách riêng để có nút Sao chép và cuộn ngang.
 */
@Composable
fun MarkdownBody(
    text: String,
    color: Color,
    highlight: Highlight?,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
) {
    if (highlight != null) {
        MarkdownText(text, color, modifier, highlight, onLongPress)
        return
    }
    val segs = remember(text) { parseMarkdownSegments(text) }
    if (segs.none { it is MdSegment.Code }) {
        MarkdownText(text, color, modifier, onLongPress = onLongPress)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segs.forEach { seg ->
            when (seg) {
                is MdSegment.Text -> MarkdownText(seg.markdown, color, onLongPress = onLongPress)
                is MdSegment.Code -> CodeBlock(seg.language, seg.code)
            }
        }
    }
}
