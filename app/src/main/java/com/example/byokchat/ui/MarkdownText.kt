package com.example.byokchat.ui

import android.graphics.Color as AColor
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.byokchat.util.TextUtil
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/** Thông tin tô sáng kết quả tìm kiếm. currentOccurrence = chỗ khớp đang chọn trong tin nhắn này (-1 nếu không có). */
data class Highlight(val query: String, val currentOccurrence: Int = -1)

@Composable
fun MarkdownText(markdown: String, color: Color, modifier: Modifier = Modifier, highlight: Highlight? = null) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textColor = color.toArgb()
    val key = markdown + "\u0000" + (highlight?.query ?: "") + "\u0000" + (highlight?.currentOccurrence ?: -1)
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = 16f
                setLineSpacing(0f, 1.15f)
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setLinkTextColor(linkColor)
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
            }
        },
    )
}
