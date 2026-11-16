package com.example.byokchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.byokchat.tools.Tool

/**
 * Bottom sheet for picking an agent's tools — the same shape as the chat's
 * [ModelPickerSheet]: slide up, search box, tick to choose. The agent
 * settings screen shows a one-line summary instead of the whole list, and
 * opens this sheet to change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(
    tools: List<Tool>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        var query by remember { mutableStateOf("") }
        val q = query.trim()
        val shown = if (q.isEmpty()) tools else tools.filter {
            it.defaultName.contains(q, ignoreCase = true) || it.uid.contains(q, ignoreCase = true)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Choose tools", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search tools") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No tools match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(shown, key = { it.uid }) { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tool.uid in selected,
                            onCheckedChange = { onToggle(tool.uid) },
                        )
                        Column {
                            Text(tool.defaultName, style = MaterialTheme.typography.bodyMedium)
                            Text(tool.uid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Done")
            }
        }
    }
}

/**
 * One-line summary for the agent settings screen: how many tools, by
 * name (an unknown uid — e.g. from a linked file — is shown as-is). Pure.
 */
internal fun toolSelectionSummary(selected: Set<String>, namesByUid: Map<String, String>): String {
    if (selected.isEmpty()) return "No tools selected"
    val names = selected.map { namesByUid[it] ?: it }
    val noun = if (selected.size == 1) "tool" else "tools"
    return "${selected.size} $noun: ${names.joinToString(", ")}"
}
