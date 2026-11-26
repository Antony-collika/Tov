package com.example.byokchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
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
 * One menu row rendered inline (same window, no popup): the white-card
 * @mention picker uses this so it looks exactly like the long-press
 * menu's rows — same colours, same 48dp height, same icon + label.
 * [MessageMenuDropdown] is untouched; the long-press menu keeps its
 * DropdownMenu behaviour.
 */
@Composable
internal fun MenuActionRow(action: MenuAction) {
    val color = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = action.enabled) { action.onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(action.icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(12.dp))
        Text(action.label, color = color)
    }
}

/**
 * The @mention picker's card: the same white card with 8dp elevation
 * as the long-press menu ([MessageMenuDropdown]), rendered inline
 * above the input instead of in a popup — a popup steals focus from
 * the text field and hides the keyboard (26/11/2026).
 */
@Composable
internal fun MentionPickerCard(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    Surface(
        color = Color.White,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            actions.forEach { MenuActionRow(it) }
        }
    }
}

/**
 * The menu that opens when you press and hold a message: a floating dropdown with a white
 * background and elevation, one row per action. [lockedNote] explains why some rows are
 * greyed out (e.g. the AI is still answering). Place it inside a small Box anchored where
 * the menu should appear; the dropdown is a popup and only exists in the tree while the
 * menu is open. [offset] shifts the menu from its default position (top aligned to the
 * anchor's bottom, start aligned to the anchor's start) — with a zero-size anchor at the
 * message's top-start, pass the press position here and the menu opens at the finger.
 */
@Composable
internal fun MessageMenuDropdown(actions: List<MenuAction>, lockedNote: String?, offset: DpOffset = DpOffset.Zero, onDismiss: () -> Unit) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = offset,
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
