package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.ToolMode

/**
 * Shown on every screen when the AI wants to run a tool that is set to "Ask first".
 * Besides Allow / Deny there are two shortcuts so that a long job is not 10 separate questions:
 * "Allow all in this turn" and "Always allow this tool". Deleting is never covered by them: it is asked every time.
 */
@Composable
fun ConfirmationHost() {
    val container = LocalContext.current.container
    val broker = container.confirmations
    val pending by broker.pending.collectAsState()
    val req = pending.firstOrNull() ?: return
    val danger = req.risk == Risk.DELETE
    AlertDialog(
        onDismissRequest = { /* the user must choose Allow or Deny */ },
        title = { Text(if (danger) "The AI wants to DELETE data" else "The AI wants to do something") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Tool: ${req.toolName}", style = MaterialTheme.typography.labelLarge)
                Text(
                    req.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (pending.size > 1) {
                    Text(
                        "${pending.size - 1} more request(s) waiting.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (danger) {
                        Button(
                            onClick = { broker.resolve(req, true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Delete") }
                    } else {
                        Button(onClick = { broker.resolve(req, true) }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
                        OutlinedButton(onClick = { broker.allowAllInTurn(req) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow all in this turn")
                        }
                        TextButton(
                            onClick = {
                                container.registry.tool(req.uid)?.let { container.registry.setMode(it, ToolMode.AUTO) }
                                broker.resolve(req, true)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Always allow this tool") }
                    }
                    TextButton(onClick = { broker.resolve(req, false) }, modifier = Modifier.fillMaxWidth()) { Text("Deny") }
                }
                if (!danger) {
                    Text(
                        "\u201CAlways allow\u201D can be undone in Settings \u2192 Tools. Deleting is always asked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
    )
}
