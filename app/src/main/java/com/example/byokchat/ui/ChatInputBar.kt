package com.example.byokchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * The message box. One rounded block: the text field on top, and below it one row with
 * "+" (attach), the model name, and the send / stop button.
 */
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
    modelLabel: String,
    modelMissing: Boolean,
    onPickModel: () -> Unit,
    inputFocus: FocusRequester,
) {
    var attachMenu by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    Surface(tonalElevation = 3.dp) {
        // As a Scaffold bottomBar it must handle the navigation bar and the keyboard itself (order matters).
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (images.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    images.forEachIndexed { idx, path ->
                        // 76dp box: the picture sits bottom-left, the remove button's 48dp touch area sits top-right.
                        Box(Modifier.size(76.dp)) {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Image waiting to be sent",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.align(Alignment.BottomStart).size(64.dp).clip(RoundedCornerShape(10.dp)).clickable { viewerIndex = idx },
                            )
                            IconButton(
                                onClick = { onRemoveImage(path) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                // Only the small circle is drawn; the IconButton around it gives the standard 48dp touch target.
                                Box(
                                    Modifier.size(22.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove image",
                                        tint = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    TextField(
                        value = draft,
                        onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                        placeholder = { Text("Ask anything") },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.Add, contentDescription = "Attach image") }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(text = { Text("Choose from gallery") }, onClick = { attachMenu = false; onPickGallery() })
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = { attachMenu = false; onPickCamera() })
                            }
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            AssistChip(
                                onClick = onPickModel,
                                label = { Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                colors = if (modelMissing) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                        trailingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                },
                                modifier = Modifier.widthIn(max = 260.dp),
                            )
                        }
                        // One button, toggling state: while generating with
                        // nothing typed it is Stop; as soon as there is a
                        // draft (or images) it becomes Send, and sending
                        // while generating stops the old turn and sends the
                        // new message in the same tap.
                        if (generating && draft.isBlank() && images.isEmpty()) {
                            FilledIconButton(onClick = onStop) {
                                Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(3.dp)))
                            }
                        } else {
                            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() || images.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        }
    }
    viewerIndex?.let { idx -> ImageViewerDialog(images.map { File(it) }, idx) { viewerIndex = null } }
}
