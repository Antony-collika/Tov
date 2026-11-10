package com.example.byokchat.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The "Chat memory" explanation dialog: how much of the AI's "memory" this chat has used.
 * It used to live inside the round ContextRing button in the top bar; the ring is gone,
 * so the dialog is now opened from the ⋮ menu instead.
 * Above 90% the explanation offers [onNewChat] ("New chat": an empty chat; it is NOT a branch).
 */
@Composable
fun ChatMemoryDialog(
    used: Int,
    limit: Int,
    dropped: Int,
    open: Boolean,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    /** Opens the "What the AI will see" Inspector for this chat (null = no button). */
    onOpenInspector: (() -> Unit)? = null,
) {
    if (!open || limit <= 0) return
    val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    val nearlyFull = fraction > 0.9f
    val message = buildString {
        append("The AI can remember about ${formatTokens(limit)} tokens (pieces of text) in one chat. ")
        append("About ${formatTokens(used)} are used so far.")
        if (dropped > 0) append("\n\nThe $dropped oldest turn(s) are no longer sent to the AI so the chat fits the limit.")
        if (nearlyFull) append("\n\nThis chat is almost full. A new chat will give the AI a clean memory.")
        append("\n\nThis is an estimate.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat memory") },
        text = { Text(message) },
        confirmButton = {
            Row {
                if (onOpenInspector != null) {
                    TextButton(onClick = { onDismiss(); onOpenInspector() }) { Text("What the AI will see") }
                }
                if (nearlyFull) {
                    TextButton(onClick = { onDismiss(); onNewChat() }) { Text("New chat") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        },
        dismissButton = if (nearlyFull) {
            { TextButton(onClick = onDismiss) { Text("Close") } }
        } else null,
    )
}
