package com.example.byokchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============ Group chat members (Phase 1: UI shell, no real sub-agents yet) ============
//
// Every chat is a group chat: it starts with 2 members (you + Main) and
// sub-agents join later when Main calls them. This file holds the member
// model, the pure grouping for the Members screen, and the shared avatar /
// typing-indicator pieces. It knows nothing about the engine; the ViewModel
// feeds it the live member list (Main's status follows `generating`).

/** What a member is doing right now. */
enum class MemberStatus {
    IDLE, WORKING, WAITING, DONE, NEEDS_INPUT,
}

/** One member of the chat: you, Main, or (later) a sub-agent. */
data class GroupMember(
    val id: String,
    val name: String,
    val role: String,
    val status: MemberStatus,
    /** What the member is doing right now ("Answering…"); null when idle. */
    val currentTask: String? = null,
    val isUser: Boolean = false,
)

/** The two members every chat starts with. */
fun defaultMembers(): List<GroupMember> = listOf(
    GroupMember(id = "you", name = "You", role = "You", status = MemberStatus.IDLE, isUser = true),
    GroupMember(id = "main", name = "Main", role = "Main agent", status = MemberStatus.IDLE),
)

/** One section of the Members screen: a title and the members in it. */
data class MemberGroup(val title: String, val members: List<GroupMember>)

/**
 * Groups members for the Members screen, most urgent first; empty groups are
 * skipped so the screen never shows an empty heading.
 */
fun groupMembers(members: List<GroupMember>): List<MemberGroup> {
    val order = listOf(
        MemberStatus.NEEDS_INPUT to "Needs you",
        MemberStatus.WORKING to "Working",
        MemberStatus.WAITING to "Waiting",
        MemberStatus.DONE to "Done",
        MemberStatus.IDLE to "Available",
    )
    return order.mapNotNull { (status, title) ->
        val inGroup = members.filter { it.status == status }
        if (inGroup.isEmpty()) null else MemberGroup(title, inGroup)
    }
}

/** Status-dot colour: working is yellow, as in the group-chat design. */
fun memberStatusColor(status: MemberStatus): Color = when (status) {
    MemberStatus.WORKING -> Color(0xFFF9A825)
    MemberStatus.IDLE -> Color(0xFF9E9E9E)
    MemberStatus.WAITING -> Color(0xFF7E57C2)
    MemberStatus.DONE -> Color(0xFF43A047)
    MemberStatus.NEEDS_INPUT -> Color(0xFFE53935)
}

/** Small label for a status, shown under the member's name. */
fun memberStatusLabel(status: MemberStatus): String = when (status) {
    MemberStatus.WORKING -> "Working"
    MemberStatus.IDLE -> "Available"
    MemberStatus.WAITING -> "Waiting"
    MemberStatus.DONE -> "Done"
    MemberStatus.NEEDS_INPUT -> "Needs you"
}

// A fixed palette so each member keeps its own colour (stable across runs:
// it only depends on the member id, so later sub-agents get one too).
private val AVATAR_COLORS = listOf(
    Color(0xFF5B8DEF),
    Color(0xFF7E57C2),
    Color(0xFF26A69A),
    Color(0xFFEF6C00),
    Color(0xFF8D6E63),
)

private fun avatarColorFor(id: String): Color {
    val idx = (id.hashCode() and Int.MAX_VALUE) % AVATAR_COLORS.size
    return AVATAR_COLORS[idx]
}

/**
 * Round avatar with the member's initial and a status dot at the bottom
 * right. [onClick] makes it tappable (the Members screen uses it to open
 * that agent's settings).
 */
@Composable
fun MemberAvatar(
    member: GroupMember,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier = modifier.size(size + 4.dp)) {
        Surface(
            shape = CircleShape,
            color = avatarColorFor(member.id),
            modifier = Modifier
                .size(size)
                .align(Alignment.TopStart)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    member.name.firstOrNull()?.uppercase().orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(11.dp)
                .clip(CircleShape)
                .background(memberStatusColor(member.status))
                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

/** "Main is typing…" bubble, shown while an agent is working but has no visible message yet. */
@Composable
fun TypingIndicatorRow(name: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(
            GroupMember(id = "main", name = name, role = "Main agent", status = MemberStatus.WORKING),
            size = 28.dp,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$name is typing", style = MaterialTheme.typography.bodyMedium)
                AnimatedDots()
            }
        }
    }
}

@Composable
private fun AnimatedDots() {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, delayMillis = i * 170),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$i",
            )
            Text(
                ".",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.alpha(alpha).padding(start = 1.dp),
            )
        }
    }
}
