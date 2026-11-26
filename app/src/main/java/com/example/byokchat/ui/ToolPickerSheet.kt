package com.example.byokchat.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.example.byokchat.tools.Tool
import com.example.byokchat.tools.ToolGroup

/** How much of a group (or of all tools) is selected. Pure, tested. */
internal enum class SelectionState { NONE, SOME, ALL }

internal fun selectionState(total: Int, selectedCount: Int): SelectionState = when {
    selectedCount <= 0 -> SelectionState.NONE
    selectedCount >= total -> SelectionState.ALL
    else -> SelectionState.SOME
}

internal fun toToggleableState(state: SelectionState): ToggleableState = when (state) {
    SelectionState.NONE -> ToggleableState.Off
    SelectionState.SOME -> ToggleableState.Indeterminate
    SelectionState.ALL -> ToggleableState.On
}

/**
 * Tools grouped by [ToolGroup], in group order, registry order inside a
 * group. Pure. Groups with no tools are left out.
 */
internal fun groupTools(tools: List<Tool>): List<Pair<ToolGroup, List<Tool>>> =
    ToolGroup.entries.mapNotNull { group ->
        val inGroup = tools.filter { it.group == group }
        if (inGroup.isEmpty()) null else group to inGroup
    }

/** Toggle one tool in the selection. Pure. */
internal fun toggleOne(selected: Set<String>, uid: String): Set<String> =
    if (uid in selected) selected - uid else selected + uid

/**
 * Toggle a whole group: all selected -> deselect all; anything else ->
 * select all. Pure.
 */
internal fun toggleGroup(selected: Set<String>, groupUids: List<String>): Set<String> =
    if (groupUids.all { it in selected }) selected - groupUids.toSet() else selected + groupUids

/** Toggle everything in the sheet: all selected -> deselect all; anything else -> select all. Pure. */
internal fun toggleAll(selected: Set<String>, allUids: List<String>): Set<String> =
    if (allUids.all { it in selected }) selected - allUids.toSet() else selected + allUids

/**
 * Bulk selection (All row, section checkboxes) is only allowed while the
 * search box is empty (Duy, 17/11/2026): search only picks single tools;
 * to select a whole section or all tools, clear the search first.
 */
internal fun bulkSelectionAllowed(query: String): Boolean = query.trim().isEmpty()

/**
 * Bottom sheet for picking tools — the same shape as the chat's
 * [ModelPickerSheet]: slide up, search box, tick to choose. Used by the
 * agent settings screen (sub's tools, without sub.*) and by the Tools
 * card editor (Main's tools, all of them).
 *
 * Layout (Duy, 17/11/2026): an "All" row on top, then one section per
 * tool group with its own checkbox (all / none / some — tapping a
 * partial section selects the whole section), then the tools. While
 * searching, the All row and the section checkboxes are hidden: search
 * only picks single tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(
    tools: List<Tool>,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        var query by remember { mutableStateOf("") }
        val q = query.trim()
        val searching = !bulkSelectionAllowed(query)
        val shown = if (q.isEmpty()) tools else tools.filter {
            it.defaultName.contains(q, ignoreCase = true) || it.uid.contains(q, ignoreCase = true)
        }
        val groups = groupTools(tools)
        val allUids = tools.map { it.uid }
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
                if (!searching) {
                    item(key = "all") {
                        val state = selectionState(allUids.size, selected.count { it in allUids.toSet() })
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelectionChange(toggleAll(selected, allUids))
                            },
                        ) {
                            TriStateCheckbox(
                                state = toToggleableState(state),
                                onClick = { onSelectionChange(toggleAll(selected, allUids)) },
                            )
                            Column {
                                Text(
                                    if (state == SelectionState.ALL) "Deselect all" else "Select all",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${selected.size} of ${allUids.size} selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    groups.forEach { (group, groupTools) ->
                        val groupUids = groupTools.map { it.uid }
                        item(key = "h_" + group.name) {
                            val state = selectionState(groupUids.size, groupUids.count { it in selected })
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onSelectionChange(toggleGroup(selected, groupUids))
                                },
                            ) {
                                TriStateCheckbox(
                                    state = toToggleableState(state),
                                    onClick = { onSelectionChange(toggleGroup(selected, groupUids)) },
                                )
                                Text(
                                    "${group.label} (${groupUids.count { it in selected }}/${groupUids.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                        }
                        items(groupTools, key = { it.uid }) { tool ->
                            ToolRow(tool, selected, onSelectionChange)
                        }
                    }
                } else {
                    items(shown, key = { it.uid }) { tool ->
                        ToolRow(tool, selected, onSelectionChange)
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun ToolRow(
    tool: Tool,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = tool.uid in selected,
            onCheckedChange = { onSelectionChange(toggleOne(selected, tool.uid)) },
        )
        Column {
            Text(tool.defaultName, style = MaterialTheme.typography.bodyMedium)
            Text(tool.uid, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
