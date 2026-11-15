package com.example.byokchat.ui

import android.content.Context
import android.graphics.Color as AColor
import android.text.Spannable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.byokchat.util.TextUtil
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thông tin tô sáng kết quả tìm kiếm. currentOccurrence = chỗ khớp đang chọn trong tin nhắn này (-1 nếu không có). */
data class Highlight(val query: String, val currentOccurrence: Int = -1)

/** One Markwon instance shared by every message (building one per message was slow). Provided in AppNav. */
val LocalMarkwon = staticCompositionLocalOf<Markwon?> { null }

fun createMarkwon(context: Context): Markwon {
    // TextView in MarkdownText uses 16sp, so LaTeX is rendered at the same size.
    val textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics,
    )
    return Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(LinkifyPlugin.create())
        // Required for inline $$...$$; blocks ($$ on their own lines) work without it.
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(
            JLatexMathPlugin.create(
                textSizePx,
                object : JLatexMathPlugin.BuilderConfigure {
                    override fun configureBuilder(builder: JLatexMathPlugin.Builder) {
                        builder.inlinesEnabled(true)
                    }
                },
            ),
        )
        .build()
}

// ---------- Chuẩn hóa công thức LaTeX mà AI hay viết ----------

/** `$$` as a plain string (in Kotlin source `$` must be escaped). */
private const val LATEX_DELIM = "\$\$"

private val MATH_WORD_RX = Regex("\\b(sin|cos|tan|log|sqrt|frac|pi|theta|alpha|beta|sum|int|lim)\\b")

private fun isMathLike(inner: String): Boolean =
    inner.contains('\\') || inner.contains('^') || inner.contains('_') || inner.contains('{') ||
        (inner.length == 1 && inner[0].isLetter()) ||
        MATH_WORD_RX.containsMatchIn(inner)

/**
 * Convert single-dollar math `$...$` to `$$...$$` (what Markwon's LaTeX plugin
 * understands). A `$` followed by a digit is a price, not math, so it is left
 * alone — and a non-math `$...$` pair is never consumed, so a stray price `$`
 * cannot swallow the opening `$` of a real formula later in the line.
 */
private fun convertSingleDollarMath(text: String): String {
    if (!text.contains('$')) return text
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        if (text.startsWith(LATEX_DELIM, i)) {
            out.append(LATEX_DELIM)
            i += 2
            continue
        }
        if (text[i] == '$' && i + 1 < text.length && !text[i + 1].isDigit() &&
            text[i + 1] != ' ' && text[i + 1] != '\n'
        ) {
            val close = text.indexOf('$', i + 1)
            if (close > i + 1 && close - i - 1 <= 200 &&
                (close + 1 >= text.length || text[close + 1] != '$')
            ) {
                val inner = text.substring(i + 1, close)
                if (!inner.contains('\n') && isMathLike(inner)) {
                    out.append(LATEX_DELIM).append(inner).append(LATEX_DELIM)
                    i = close + 1
                    continue
                }
            }
        }
        out.append(text[i])
        i++
    }
    return out.toString()
}

/**
 * Normalize the LaTeX delimiters LLMs actually write so the LaTeX plugin can
 * parse them. Without this, the exact text from the screenshot —
 * `1. Trigonometric harmony: $$` followed by the formula on the next lines —
 * is not a block the plugin recognizes, because its opening `$$` is not on
 * its own line.
 * - `\(...\)` -> `$$...$$`, `\[...\]` -> a `$$` block.
 * - Single-dollar math -> `$$...$$` (see [convertSingleDollarMath]).
 * - A `$$` at the end of a line, or a `$$` with formula text after it on the
 *   same line, is moved onto its own line to open/close a display block.
 * A complete `$$...$$` on one line is left alone (inline formula).
 * Lines containing a backtick are left alone so inline code is not touched.
 */
private fun normalizeLatex(markdown: String): String {
    if (!markdown.contains('$') && !markdown.contains('\\')) return markdown
    var text = markdown
    text = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)
        .replace(text) { LATEX_DELIM + it.groupValues[1] + LATEX_DELIM }
    text = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
        .replace(text) { LATEX_DELIM + "\n" + it.groupValues[1] + "\n" + LATEX_DELIM }
    text = convertSingleDollarMath(text)
    val out = ArrayList<String>()
    var inBlock = false
    for (line in text.lines()) {
        if (line.contains('`')) {
            out.add(line)
            continue
        }
        val trimmed = line.trim()
        if (!inBlock) {
            if (trimmed == LATEX_DELIM) {
                inBlock = true
                out.add(line)
                continue
            }
            val first = line.indexOf(LATEX_DELIM)
            if (first >= 0) {
                if (line.indexOf(LATEX_DELIM, first + 2) >= 0) {
                    out.add(line) // complete $$...$$ on one line: inline formula
                    continue
                }
                val before = line.substring(0, first).trimEnd()
                val after = line.substring(first + 2).trim()
                if (before.isNotEmpty()) out.add(before)
                out.add(LATEX_DELIM)
                if (after.isNotEmpty()) out.add(after)
                inBlock = true
                continue
            }
            out.add(line)
        } else {
            if (trimmed == LATEX_DELIM) {
                inBlock = false
                out.add(line)
                continue
            }
            val idx = line.indexOf(LATEX_DELIM)
            if (idx >= 0) {
                val before = line.substring(0, idx).trimEnd()
                if (before.isNotEmpty()) out.add(before)
                out.add(LATEX_DELIM)
                val after = line.substring(idx + 2).trim()
                if (after.isNotEmpty()) out.add(after)
                inBlock = false
                continue
            }
            out.add(line)
        }
    }
    return out.joinToString("\n")
}

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
    // Normalize LaTeX delimiters once; every parse path below uses this text,
    // so the cache key and the displayed text always agree.
    val normalizedMarkdown = remember(markdown) { normalizeLatex(markdown) }
    val key = RenderKey(normalizedMarkdown, query, highlight?.currentOccurrence ?: -1)
    // Đường bất đồng bộ: đang stream, không tô sáng, và chữ đủ dài.
    val asyncParse = streaming && query.isEmpty() && normalizedMarkdown.length >= ASYNC_PARSE_MIN_CHARS
    // Kết quả phân tích nền gần nhất. Khi có chữ mới, chữ cũ vẫn hiện cho đến khi kết quả mới xong.
    var parsed by remember { mutableStateOf<Spanned?>(null) }
    LaunchedEffect(normalizedMarkdown, asyncParse) {
        parsed = if (asyncParse) withContext(Dispatchers.Default) { markwon.toMarkdown(normalizedMarkdown) } else null
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
                val spanned = if (query.isEmpty() && !streaming && isCacheable(normalizedMarkdown)) cachedParse(markwon, normalizedMarkdown) else null
                if (spanned != null) markwon.setParsedMarkdown(tv, spanned) else markwon.setMarkdown(tv, normalizedMarkdown)
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
    if (segs.none { it is MdSegment.Code || it is MdSegment.Image }) {
        MarkdownText(text, color, modifier, onLongPress = onLongPress, anchorCoordinates = anchorCoordinates, streaming = streaming)
        return
    }
    // Ảnh trong tin nhắn: bấm để xem toàn màn hình (dùng chung ImageViewerDialog với ảnh đính kèm).
    val imageUrls = segs.filterIsInstance<MdSegment.Image>().map { it.url }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
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
                is MdSegment.Image -> {
                    val idx = segs.take(i).count { it is MdSegment.Image }
                    AsyncImage(
                        model = seg.url,
                        contentDescription = "Image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewerIndex = idx },
                    )
                }
            }
        }
    }
    viewerIndex?.let { idx -> ImageViewerDialog(imageUrls, idx) { viewerIndex = null } }
}
