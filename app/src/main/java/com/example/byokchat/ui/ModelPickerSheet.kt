package com.example.byokchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.data.ProviderConfig

/** Khung trượt từ dưới lên để chọn mô hình cho cuộc trò chuyện hiện tại. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    providerId: String,
    model: String,
    refreshing: Boolean,
    onSelect: (providerId: String, model: String) -> Unit,
    onTogglePin: (providerId: String, model: String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Agent settings only: when set, the sheet offers a "Follow Main's
     * model" row on top (an agent with no own model follows Main). Null
     * = the chat picker, no such row.
     */
    onFollowMain: (() -> Unit)? = null,
    followMainSelected: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (providers.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No AI connected yet", style = MaterialTheme.typography.titleMedium)
                Text("Connect an AI (OpenAI, Gemini, or a compatible server...) to choose a model.")
                Button(onClick = onConnect) { Text("Connect AI") }
            }
        } else {
            PickerBody(providers, providerId, model, refreshing, onSelect, onTogglePin, onFollowMain, followMainSelected)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerBody(
    providers: List<ProviderConfig>,
    providerId: String,
    model: String,
    refreshing: Boolean,
    onSelect: (String, String) -> Unit,
    onTogglePin: (String, String) -> Unit,
    onFollowMain: (() -> Unit)? = null,
    followMainSelected: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    // Chip lọc provider: null = "Tất cả". Gấp/mở nhóm: chỉ provider đang
    // dùng mở sẵn lúc mở sheet, các provider khác gấp lại; đóng sheet rồi
    // mở lại thì về mặc định này.
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var collapsed by remember { mutableStateOf(defaultCollapsedProviders(providers, providerId)) }
    val q = query.trim()
    // Ghim ở trên cùng, gom từ mọi provider (mỗi hàng ghi rõ provider của nó).
    val pinned = visiblePinnedModels(providers, query, providerFilter)
    val groups = visibleModelGroups(providers, query, providerFilter)
    // Nhập tay một tên mô hình thì áp cho provider của cuộc trò chuyện hiện tại.
    val manualProvider = providers.firstOrNull { it.id == providerId } ?: providers.first()

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Choose a model", style = MaterialTheme.typography.titleMedium)
        if (refreshing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Updating model list…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search models") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = providerFilter == null,
                onClick = { providerFilter = null },
                label = { Text("Tất cả") },
            )
            providers.forEach { p ->
                FilterChip(
                    selected = providerFilter == p.id,
                    onClick = {
                        providerFilter = p.id
                        collapsed = collapsed - p.id // Chip chọn provider nào thì nhóm đó tự mở ra.
                    },
                    label = { Text(p.name) },
                )
            }
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (onFollowMain != null) {
                item(key = "follow-main") {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (followMainSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable(onClick = onFollowMain)
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            Text(
                                "Follow Main's model",
                                fontWeight = if (followMainSelected) FontWeight.Bold else null,
                            )
                            Text(
                                "Use whatever model Main is using right now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                item(key = "h-pin") { SectionLabel("Pinned") }
                items(pinned, key = { (p, m) -> "pin:${p.id}:$m" }) { (p, m) ->
                    ModelRow(m, subtitle = p.name, selected = p.id == providerId && m == model, pinned = true,
                        onClick = { onSelect(p.id, m) }, onTogglePin = { onTogglePin(p.id, m) })
                }
            }
            groups.forEach { g ->
                val expanded = isGroupExpanded(g.providerId, query, collapsed)
                val count = if (q.isEmpty()) g.totalModels else g.models.size
                item(key = "h:${g.providerId}") {
                    ProviderSectionHeader(
                        name = g.providerName,
                        count = count,
                        expanded = expanded,
                        onToggle = {
                            collapsed = if (g.providerId in collapsed) collapsed - g.providerId else collapsed + g.providerId
                        },
                    )
                }
                if (expanded) {
                    if (g.models.isEmpty()) {
                        item(key = "empty:${g.providerId}") {
                            Text(
                                "No models saved yet. You can enter a model name in the row at the bottom.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(g.models, key = { m -> "all:${g.providerId}:$m" }) { m ->
                        ModelRow(m, subtitle = g.providerName, selected = g.providerId == providerId && m == model, pinned = m in g.pinnedModels,
                            onClick = { onSelect(g.providerId, m) }, onTogglePin = { onTogglePin(g.providerId, m) })
                    }
                }
            }
            item(key = "manual") {
                Text(
                    "Enter another model name…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { manual = true }.padding(vertical = 14.dp, horizontal = 12.dp),
                )
            }
        }
    }

    if (manual) {
        var typed by remember { mutableStateOf(if (manualProvider.id == providerId) model else "") }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Enter a model name") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { manual = false; onSelect(manualProvider.id, typed.trim()) },
                    enabled = typed.isNotBlank(),
                ) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
        )
    }
}

/** Một nhóm provider như hiện trong sheet chọn model, sau khi đã lọc. */
internal data class ModelPickerGroup(
    val providerId: String,
    val providerName: String,
    /** Model sẽ hiện trong nhóm sau khi lọc theo tìm kiếm + chip provider. */
    val models: List<String>,
    /** Model đã ghim của provider (để vẽ sao ghim ở mỗi hàng). */
    val pinnedModels: List<String>,
    /** Tổng số model của provider (hiện ở tiêu đề nhóm khi không tìm kiếm). */
    val totalModels: Int,
)

/**
 * Nhóm provider hiện trong sheet sau khi lọc theo ô tìm kiếm và chip
 * provider. Tìm kiếm khớp cả tên model lẫn tên provider — gõ tên provider
 * thì hiện toàn bộ model của provider đó. Lúc đang tìm kiếm, nhóm nào lọc
 * ra rỗng thì bị ẩn hẳn (không hiện tiêu đề nhóm hay "No models match.").
 * Thứ tự provider và thứ tự model trong nhóm giữ nguyên như trong kho.
 */
internal fun visibleModelGroups(
    providers: List<ProviderConfig>,
    query: String,
    providerFilter: String?,
): List<ModelPickerGroup> {
    val q = query.trim()
    return providers.mapNotNull { p ->
        if (providerFilter != null && p.id != providerFilter) return@mapNotNull null
        val providerMatches = q.isEmpty() || p.name.contains(q, ignoreCase = true)
        val models = if (providerMatches) p.models else p.models.filter { it.contains(q, ignoreCase = true) }
        if (q.isNotEmpty() && models.isEmpty()) return@mapNotNull null
        ModelPickerGroup(p.id, p.name, models, p.pinnedModels, p.models.size)
    }
}

/**
 * Model đã ghim sẽ hiện ở mục Pinned, lọc theo cùng một luật như danh
 * sách chính (tìm kiếm khớp tên model hoặc tên provider; chip provider
 * chỉ giữ lại model đã ghim của provider đó).
 */
internal fun visiblePinnedModels(
    providers: List<ProviderConfig>,
    query: String,
    providerFilter: String?,
): List<Pair<ProviderConfig, String>> {
    val q = query.trim()
    return providers.flatMap { p ->
        if (providerFilter != null && p.id != providerFilter) return@flatMap emptyList()
        val providerMatches = q.isEmpty() || p.name.contains(q, ignoreCase = true)
        val models = if (providerMatches) p.pinnedModels else p.pinnedModels.filter { it.contains(q, ignoreCase = true) }
        models.map { p to it }
    }
}

/** Mặc định lúc mở sheet: chỉ provider đang dùng mở, các provider khác gấp lại. */
internal fun defaultCollapsedProviders(
    providers: List<ProviderConfig>,
    currentProviderId: String,
): Set<String> = providers.filter { it.id != currentProviderId }.map { it.id }.toSet()

/**
 * Một nhóm có mở hay không: lúc đang tìm kiếm thì nhóm có kết quả luôn
 * mở (người dùng vẫn bấm tiêu đề nhóm để gấp/mở như thường — trạng thái
 * gấp/mở có hiệu lực trở lại khi xóa ô tìm kiếm).
 */
internal fun isGroupExpanded(providerId: String, query: String, collapsed: Set<String>): Boolean =
    query.trim().isNotEmpty() || providerId !in collapsed

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ProviderSectionHeader(name: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$name · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse $name" else "Expand $name",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelRow(model: String, subtitle: String?, selected: Boolean, pinned: Boolean, onClick: () -> Unit, onTogglePin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                model,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else null,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (pinned) "Unpin model" else "Pin model",
            )
        }
    }
}
