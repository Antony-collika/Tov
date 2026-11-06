package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.byokchat.container
import com.example.byokchat.tools.Risk
import com.example.byokchat.tools.Tool
import com.example.byokchat.tools.ToolGroup
import com.example.byokchat.tools.ToolMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val registry = LocalContext.current.container.registry
    val container = LocalContext.current.container
    val prefs by container.toolPrefs.prefs.collectAsState()
    val workspaceOk by container.fs.accessible.collectAsState()
    val searchKey by container.settings.searchApiKey.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý công cụ") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Bấm vào một công cụ để xem UID, đổi tên/mô tả mà AI nhìn thấy, bật/tắt và chọn mức kiểm soát. Thay đổi có hiệu lực từ lần gửi kế tiếp.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            ToolGroup.entries.forEach { group ->
                val tools = registry.all.filter { it.group == group }
                item(key = "h_" + group.name) { SectionTitle("${group.label} (${tools.size})") }
                items(tools, key = { it.uid }) { t ->
                    // đọc prefs/workspaceOk/searchKey để cập nhật khi thay đổi
                    @Suppress("UNUSED_VARIABLE") val touch = prefs.size + (if (workspaceOk) 1 else 0) + searchKey.length
                    val mode = registry.mode(t)
                    val name = registry.effectiveName(t)
                    ListItem(
                        modifier = Modifier.clickable { onOpen(t.uid) },
                        headlineContent = { Text(name) },
                        supportingContent = {
                            val blocked = mode != ToolMode.OFF && !registry.isAvailable(t)
                            Text(
                                t.uid + (if (name != t.defaultName) " · đã đổi tên" else "") +
                                    (if (blocked) " · đang bị chặn: " + (if (t.needsWorkspace && !workspaceOk) "chưa chọn workspace" else t.unavailableHint ?: "chưa sẵn sàng") else "")
                            )
                        },
                        trailingContent = { ModeBadge(mode, t.risk) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModeBadge(mode: ToolMode, risk: Risk) {
    val color = when (mode) {
        ToolMode.AUTO -> MaterialTheme.colorScheme.primary
        ToolMode.ASK -> MaterialTheme.colorScheme.tertiary
        ToolMode.OFF -> MaterialTheme.colorScheme.outline
    }
    Text(
        mode.label + if (risk == Risk.DELETE && mode != ToolMode.OFF) " (luôn)" else "",
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailScreen(uid: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val registry = container.registry
    val tool: Tool? = registry.tool(uid)
    val prefs by container.toolPrefs.prefs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tool == null) "Công cụ" else registry.effectiveName(tool)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
            )
        },
    ) { padding ->
        if (tool == null) {
            Text("Không tìm thấy công cụ này.", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        // đọc prefs để giao diện cập nhật
        val mode = remember(prefs) { registry.mode(tool) }
        var name by remember(uid) { mutableStateOf(registry.effectiveName(tool)) }
        var desc by remember(uid) { mutableStateOf(registry.effectiveDescription(tool)) }
        val nameError = registry.validateName(tool, name)
        val descError = registry.validateDescription(desc)
        val dirty = name.trim() != registry.effectiveName(tool) || desc.trim() != registry.effectiveDescription(tool)

        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("UID (cố định, do app đặt)", style = MaterialTheme.typography.labelLarge)
            Text(tool.uid, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text("Mô tả gốc (chỉ để xem)", style = MaterialTheme.typography.labelLarge)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Text(tool.defaultDescription, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Tên gốc: ${tool.defaultName} · Loại: " + when (tool.risk) {
                    Risk.READ -> "chỉ đọc"
                    Risk.WRITE -> "ghi/sửa/di chuyển"
                    Risk.DELETE -> "xóa (luôn hỏi xác nhận)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bật công cụ này", modifier = Modifier.weight(1f))
                Switch(
                    checked = mode != ToolMode.OFF,
                    onCheckedChange = { on -> registry.setMode(tool, if (on) tool.defaultMode else ToolMode.OFF) },
                )
            }
            Text("Mức kiểm soát", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = if (tool.risk == Risk.DELETE) listOf(ToolMode.ASK, ToolMode.OFF) else ToolMode.entries.toList()
                options.forEach { m ->
                    FilterChip(selected = mode == m, onClick = { registry.setMode(tool, m) }, label = { Text(m.label) })
                }
            }
            if (tool.risk == Risk.DELETE) {
                Text("Công cụ xóa luôn hỏi bạn trước khi thực hiện.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text("Những gì AI nhìn thấy", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tên công cụ") },
                singleLine = true,
                isError = nameError != null,
                supportingText = { if (nameError != null) Text(nameError) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("Mô tả cho AI") },
                isError = descError != null,
                supportingText = { if (descError != null) Text(descError) },
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = dirty && nameError == null && descError == null,
                    onClick = { registry.setNameAndDescription(tool, name, desc); name = name.trim(); desc = desc.trim() },
                ) { Text("Lưu") }
                OutlinedButton(onClick = {
                    registry.restoreDefaults(tool)
                    name = tool.defaultName
                    desc = tool.defaultDescription
                }) { Text("Khôi phục mặc định") }
            }
            Text(
                "Tham số kỹ thuật và phần hoạt động của công cụ do app tự lo; bạn chỉ chỉnh tên và mô tả. Khi AI gọi theo tên mới, app tự quy về UID, nên các cuộc trò chuyện cũ không bị hỏng.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
