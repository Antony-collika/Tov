package com.example.byokchat.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * The menu that opens when you press and hold a message: a floating dropdown with a white
 * background and elevation, one row per action. [lockedNote] explains why some rows are
 * greyed out (e.g. the AI is still answering). Place it inside a small Box anchored where
 * the menu should appear (e.g. the top-end corner of the message); the dropdown is a popup
 * and only exists in the tree while the menu is open.
 */
@Composable
internal fun MessageMenuDropdown(actions: List<MenuAction>, lockedNote: String?, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shadowElevation = 8.dp,
    ) {
        if (lockedNote != null && actions.any { !it.enabled }) {
            Text(
                lockedNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        actions.forEach { a ->
            val color = when {
                !a.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                a.destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            }
            DropdownMenuItem(
                text = { Text(a.label, color = color) },
                onClick = { onDismiss(); a.onClick() },
                leadingIcon = { Icon(a.icon, contentDescription = null, tint = color) },
                enabled = a.enabled,
            )
        }
    }
}
