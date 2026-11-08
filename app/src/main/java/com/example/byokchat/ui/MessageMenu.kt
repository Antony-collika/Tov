package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One row of the message menu. A disabled row is shown greyed out (used while the AI is answering). */
internal class MenuAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The menu that opens when you press and hold a message: a bottom sheet with one row per action.
 * [lockedNote] explains why some rows are greyed out (e.g. the AI is still answering).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageMenuSheet(actions: List<MenuAction>, lockedNote: String?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            if (lockedNote != null && actions.any { !it.enabled }) {
                Text(
                    lockedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            actions.forEach { a ->
                val color = when {
                    !a.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    a.destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = a.enabled) { onDismiss(); a.onClick() }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(a.icon, contentDescription = null, tint = color)
                    Spacer(Modifier.width(16.dp))
                    Text(a.label, color = color, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
