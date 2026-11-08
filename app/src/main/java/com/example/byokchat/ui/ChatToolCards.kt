package com.example.byokchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.ai.AiToolCall
import com.example.byokchat.container
import com.example.byokchat.data.MessageEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val callsSer = ListSerializer(AiToolCall.serializer())

@Composable
internal fun ToolCards(m: MessageEntity, results: Map<String, MessageEntity>, generating: Boolean) {
    val container = LocalContext.current.container
    val calls = remember(m.toolCallsJson) {
        runCatching { Json.decodeFromString(callsSer, m.toolCallsJson ?: "[]") }.getOrDefault(emptyList())
    }
    calls.forEach { c ->
        val res = results[c.id]
        var expanded by remember(c.id) { mutableStateOf(false) }
        val name = c.uid?.let { container.registry.nameForUid(it) } ?: c.name
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    val (label, color) = when {
                        res == null && generating -> "đang chạy / chờ bạn xác nhận…" to MaterialTheme.colorScheme.onSurfaceVariant
                        res == null -> "chưa có kết quả" to MaterialTheme.colorScheme.onSurfaceVariant
                        res.toolIsError -> "lỗi / bị từ chối" to MaterialTheme.colorScheme.error
                        else -> "xong" to MaterialTheme.colorScheme.primary
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                }
                if (!expanded) {
                    Text(
                        c.argsJson.replace("\n", " ").take(140),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (res != null) {
                        Text(
                            res.text.replace("\n", " ").take(160),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text("Tham số:", style = MaterialTheme.typography.labelSmall)
                    Text(c.argsJson.take(3000), style = MaterialTheme.typography.bodySmall)
                    if (res != null) {
                        Text("Kết quả:", style = MaterialTheme.typography.labelSmall)
                        Text(res.text.take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
