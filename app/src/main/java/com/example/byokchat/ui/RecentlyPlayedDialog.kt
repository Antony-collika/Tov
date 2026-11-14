package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.byokchat.media.PlayedSource
import com.example.byokchat.media.PlayedTrack

/**
 * "Recently played" (menu ⋮, dưới Search): những bài AI đã phát trong cuộc
 * trò chuyện này, mới nhất trước. Bấm một bài để phát lại (app resolve lại
 * link stream mới, không dùng link cũ). Chưa phát bài nào thì báo rõ.
 */
@Composable
fun RecentlyPlayedDialog(
    tracks: List<PlayedTrack>,
    onDismiss: () -> Unit,
    onReplay: (PlayedTrack) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recently played") },
        text = {
            if (tracks.isEmpty()) {
                Text("Nothing has been played yet in this conversation.")
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(tracks, key = { it.source.name + ":" + it.key }) { track ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onReplay(track) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (track.source) {
                                    PlayedSource.LOCAL -> "Local"
                                    PlayedSource.YOUTUBE -> "YouTube"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
