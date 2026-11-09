package com.example.byokchat.ui

import android.content.Context
import android.graphics.Color as AColor
import android.text.Spannable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.util.LruCache
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.byokchat.util.TextUtil
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

// ---------- Làm nhẹ việc vẽ câu trả lời ----------

/** Chữ đang stream đủ dài thì phân tích Markdown ở luồng nền; ngắn hơn thì phân tích ngay (rất nhanh). */
private const val ASYNC_PARSE_MIN_CHARS = 1500

/** Tổng số ký tự Markdown đã phân tích được giữ lại, để lần sau khỏi phải phân tích lại. */
private const val PARSED_CACHE_MAX_CHARS = 300_000

/** Kết quả phân tích, đo theo độ dài chuỗi gốc. LruCache tự đồng bộ nên dùng chung được. */
private val parsedCache = object : LruCache<String, Spanned>(PARSED_CACHE_MAX_CHARS) {
    override fun sizeOf(key: String, value: Spanned): Int = key.length.coerceAtLeast(1)
}

/** Bảng và danh sách việc cần không đưa vào cache (giữ đúng cách hiển thị cũ). */
private fun isCacheable(markdown: String): Boolean =
    !markdown.contains('|') && !markdown.contains("[ ]") && !markdown.contains("[x]")

/** Khóa cho biết TextView đang hiện đúng nội dung nào. Thay cho chuỗi ghép mới mỗi lần vẽ. */
private data class RenderKey(val markdown: String, val query: String, val occurrence: Int)

private fun cachedParse(markwon: Markwon, markdown: String): Spanned {
    parsedCache.get(markdown)?.let { return it }
    val parsed = markwon.toMarkdown(markdown)
    parsedCache.put(markdown, parsed)
    return parsed
}

/** Sau mỗi lần đặt chữ, giữ LongPressFriendlyLinkMovementMethod khi có link (để nhấn giữ vẫn mở menu). */
private fun keepLongPressFriendlyMovement(tv: TextView) {
    val mm = tv.movementMethod
    if (mm is LinkMovementMethod && mm !is LongPressFriendlyLinkMovementMethod) {
        tv.movementMethod = LongPressFriendlyLinkMovementMethod()
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier,
    highlight: Highlight? = null,
    onLongPress: ((Offset?) -> Unit)? = null,
    // Coordinates of the outer Box that anchors the long-press menu (see
    // AssistantMessage). The TextView's own touch position is converted into that
    // space so the menu opens where the finger is, not at a fixed corner.
    anchorCoordinates: LayoutCoordinates? = null,
    // True while the AI is still writing this text. Long texts are then parsed in the background.
    streaming: Boolean = false,
) {
    val context = LocalContext.current
    val markwon = LocalMarkwon.current ?: remember(context) { createMarkwon(context) }
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textColor = color.toArgb()
    val query = highlight?.query.orEmpty()
    val key = RenderKey(markdown, query, highlight?.currentOccurrence ?: -1)
    // Đường bất đồng bộ: đang stream, không tô sáng, và chữ đủ dài.
    val asyncParse = streaming && query.isEmpty() && markdown.length >= ASYNC_PARSE_MIN_CHARS
    // Kết quả phân tích nền gần nhất. Khi có chữ mới, chữ cũ vẫn hiện cho đến khi kết quả mới xong.
    var parsed by remember { mutableStateOf<Spanned?>(null) }
    LaunchedEffect(markdown, asyncParse) {
        parsed = if (asyncParse) withContext(Dispatchers.Default) { markwon.toMarkdown(markdown) } else null
    }
    // Layout coordinates of this TextView, for converting a touch position into
    // the anchor Box's space. Set after layout, before any user interaction.
    var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Last ACTION_DOWN position, already converted into the anchor Box's space
    // (null when the conversion was not possible, e.g. before first layout).
    var lastDownInBox by remember { mutableStateOf<Offset?>(null) }
    AndroidView(
        modifier = modifier.fillMaxWidth().onGloballyPositioned { textCoords = it },
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
            // Keep the listeners fresh on every recomposition so they always call the latest callback.
            if (onLongPress != null) {
                // The View swallows the touch before Compose's pointer input, so the
                // press position must be recorded here. Returning false lets the
                // TextView still handle the touch (links, long-press scheduling).
                tv.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        val raw = Offset(event.x, event.y)
                        lastDownInBox = anchorCoordinates?.let { box ->
                            textCoords?.let { text -> box.windowToLocal(text.localToWindow(raw)) }
                        }
                    }
                    false
                }
                tv.setOnLongClickListener { onLongPress(lastDownInBox); true }
            } else {
                tv.setOnTouchListener(null)
                tv.setOnLongClickListener(null)
            }

            val shown = parsed
            if (asyncParse && shown != null) {
                // Chỉ thay chữ khi kết quả mới khác với cái đang hiện (so bằng tham chiếu, rất nhanh).
                if (tv.tag !== shown) {
                    markwon.setParsedMarkdown(tv, shown)
                    tv.tag = shown
                    keepLongPressFriendlyMovement(tv)
                }
            } else if (tv.tag != key) {
                tv.tag = key
                // Tin đang stream không đưa vào cache (mỗi lần chữ đổi là một khóa mới, làm đầy cache vô ích).
                val spanned = if (query.isEmpty() && !streaming && isCacheable(markdown)) cachedParse(markwon, markdown) else null
                if (spanned != null) markwon.setParsedMarkdown(tv, spanned) else markwon.setMarkdown(tv, markdown)
                if (highlight != null && query.isNotEmpty()) {
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
                keepLongPressFriendlyMovement(tv)
            }
        },
    )
}

/**
 * Hiển thị tin của AI. Khi đang tìm kiếm trong cuộc trò chuyện (highlight != null) dùng đường cũ: một khối Markdown duy nhất,
 * để giữ cách đánh số chỗ khớp. Ngược lại, khối code được tách riêng để có nút Sao chép và cuộn ngang.
 * [streaming]: tin đang được AI viết (chỉ đoạn chữ cuối cùng mới đổi liên tục nên chỉ đoạn đó được phân tích nền).
 */
@Composable
fun MarkdownBody(
    text: String,
    color: Color,
    highlight: Highlight?,
    modifier: Modifier = Modifier,
    onLongPress: ((Offset?) -> Unit)? = null,
    anchorCoordinates: LayoutCoordinates? = null,
    streaming: Boolean = false,
) {
    if (highlight != null) {
        MarkdownText(text, color, modifier, highlight, onLongPress, anchorCoordinates, streaming)
        return
    }
    val segs = remember(text) { parseMarkdownSegments(text) }
    if (segs.none { it is MdSegment.Code }) {
        MarkdownText(text, color, modifier, onLongPress = onLongPress, anchorCoordinates = anchorCoordinates, streaming = streaming)
        return
    }
    // Chỉ đoạn chữ cuối cùng còn đang được viết; các đoạn trước đã xong.
    val lastText = segs.indexOfLast { it is MdSegment.Text }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segs.forEachIndexed { i, seg ->
            when (seg) {
                is MdSegment.Text -> MarkdownText(
                    seg.markdown, color,
                    onLongPress = onLongPress,
                    anchorCoordinates = anchorCoordinates,
                    streaming = streaming && i == lastText,
                )
                is MdSegment.Code -> CodeBlock(seg.language, seg.code)
            }
        }
    }
}
