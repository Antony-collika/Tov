package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.util.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class SearchHit(
    val conversationId: String,
    val title: String,
    val messageId: String?,
    val role: String,
    val snippet: AnnotatedString,
)

private fun snippetOf(text: String, query: String): AnnotatedString {
    val clean = text.replace('\n', ' ')
    val hits = TextUtil.findAll(clean, query)
    val first = hits.firstOrNull() ?: return AnnotatedString(clean.take(120))
    val from = maxOf(0, first - 40)
    val to = minOf(clean.length, first + query.length + 80)
    val prefix = if (from > 0) "…" else ""
    val piece = clean.substring(from, to) + if (to < clean.length) "…" else ""
    return buildAnnotatedString {
        append(prefix + piece)
        TextUtil.findAll(piece, query).forEach { s ->
            addStyle(
                SpanStyle(background = Color(0x99FFEB3B), color = Color.Black),
                prefix.length + s, minOf(prefix.length + piece.length, prefix.length + s + query.length),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (conversationId: String, messageId: String?, query: String) -> Unit) {
    val db = LocalContext.current.container.db
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250) // chờ người dùng gõ xong
        searching = true
        results = withContext(Dispatchers.Default) {
            val convs = db.conversations().observeAllOnce().associateBy { it.id }
            val allMessages = db.messages().allChatMessages()
            // Empty drafts (no messages) are not real conversations: never offer them
            val nonEmpty = allMessages.map { it.conversationId }.toSet()
            val out = mutableListOf<SearchHit>()
            // khớp theo tên cuộc trò chuyện
            convs.values.filter { it.id in nonEmpty }.sortedByDescending { it.updatedAt }.forEach { c ->
                if (TextUtil.findAll(c.title, query).isNotEmpty()) out.add(SearchHit(c.id, c.title, null, "title", snippetOf(c.title, query)))
            }
            // khớp theo nội dung hiện tại của tin nhắn (gồm cả chỗ đã sửa tay)
            allMessages
                .sortedByDescending { it.createdAt }
                .forEach { m ->
                    val c = convs[m.conversationId] ?: return@forEach
                    if (out.size < 300 && TextUtil.findAll(m.text, query).isNotEmpty()) {
                        out.add(SearchHit(c.id, c.title, m.id, m.role, snippetOf(m.text, query)))
                    }
                }
            out
        }
        searching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Tìm trong tất cả cuộc trò chuyện") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                query.isBlank() -> Text(
                    "Nhập từ khóa để tìm (không phân biệt hoa thường và dấu).",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results.isEmpty() && !searching -> Text(
                    "Không tìm thấy kết quả nào.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { (it.messageId ?: "t_" + it.conversationId) }) { h ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(h.conversationId, h.messageId, query) },
                            overlineContent = {
                                Text(
                                    h.title + when (h.role) { "user" -> " · bạn"; "assistant" -> " · AI"; else -> "" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            headlineContent = { Text(h.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
