package com.example.byokchat.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.byokchat.data.images
import java.io.File

@Composable
internal fun InputBar(
    draft: String,
    onDraft: (String) -> Unit,
    images: List<String>,
    onRemoveImage: (String) -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(8.dp)) {
            if (images.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { path ->
                        Box {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Ảnh chờ gửi",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                            )
                            IconButton(
                                onClick = { onRemoveImage(path) },
                                modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), CircleShape),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Bỏ ảnh", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.Add, contentDescription = "Đính kèm ảnh") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Chọn từ thư viện") }, onClick = { attachMenu = false; onPickGallery() })
                        DropdownMenuItem(text = { Text("Chụp ảnh") }, onClick = { attachMenu = false; onPickCamera() })
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nhắn tin cho AI...") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                )
                Box(Modifier.padding(start = 6.dp, bottom = 4.dp)) {
                    if (generating) {
                        FilledIconButton(onClick = onStop) {
                            Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(3.dp)))
                        }
                    } else {
                        FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() || images.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi")
                        }
                    }
                }
            }
        }
    }
}
