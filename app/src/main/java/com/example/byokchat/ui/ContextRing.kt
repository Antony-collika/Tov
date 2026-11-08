package com.example.byokchat.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A small ring showing how much of the AI's "memory" this chat has used. Tap it to read an explanation.
 * Above 90% the explanation offers [onNewChat] ("New chat": an empty chat; it is NOT a branch).
 */
@Composable
fun ContextRing(used: Int, limit: Int, dropped: Int, onNewChat: () -> Unit) {
    if (limit <= 0) return
    var open by remember { mutableStateOf(false) }
    val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val nearlyFull = fraction > 0.9f
    val color = when {
        nearlyFull -> MaterialTheme.colorScheme.error
        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    IconButton(onClick = { open = true }) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(24.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 3.dp,
        )
    }
    if (open) {
        val message = buildString {
            append("The AI can remember about ${formatTokens(limit)} tokens (pieces of text) in one chat. ")
            append("About ${formatTokens(used)} are used so far.")
            if (dropped > 0) append("\n\nThe $dropped oldest turn(s) are no longer sent to the AI so the chat fits the limit.")
            if (nearlyFull) append("\n\nThis chat is almost full. A new chat will give the AI a clean memory.")
            append("\n\nThis is an estimate.")
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Chat memory") },
            text = { Text(message) },
            confirmButton = {
                if (nearlyFull) {
                    TextButton(onClick = { open = false; onNewChat() }) { Text("New chat") }
                } else {
                    TextButton(onClick = { open = false }) { Text("Close") }
                }
            },
            dismissButton = if (nearlyFull) {
                { TextButton(onClick = { open = false }) { Text("Close") } }
            } else null,
        )
    }
}
