package com.example.byokchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.byokchat.container
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.delay

// Palette for the redesigned Members screen (dac-ta-members.md).
private val Ink = Color(0xFF191C20)
private val SubGrey = Color(0xFF6B7280)
private val BrandBlue = Color(0xFF2563EB)
private val StopRed = Color(0xFFD32F2F)
private val TokenCardBg = Color(0xFFEEF2FF)
private val TokenTrack = Color(0xFFDCE3F5)
private val RunningBg = Color(0xFFE6F4EA)
private val RunningText = Color(0xFF137333)
private val TimerGrey = Color(0xFF8A8D94)
private val RowLine = Color(0xFFF2F2F5)
private val SessionBorder = Color(0xFFE6E4EA)
private val TopBarLine = Color(0xFFF0F0F3)
private val RoundsText = Color(0xFF33383F)
private val MemberBarTrack = Color(0xFFECECF1)

private fun formatGrouped(value: Int): String =
    NumberFormat.getNumberInstance(Locale.US).format(value)

private enum class MemberTab { Available, Working }

/**
 * Members screen (UI/UX redesign 27/11/2026, per dac-ta-members.md):
 * top bar with a blue Invite button, a Session card, two tabs
 * (Available first and open by default, Working second), one member
 * row per agent in the open tab, and a Token budget card at the bottom.
 * Journal UI and the Safety ceilings block are gone. Tapping anywhere
 * on a member's row opens that agent's settings; tapping Main's avatar
 * opens the session's Request Control Panel instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(
    conversationId: String,
    onBack: () -> Unit,
    onAgentSettings: (String) -> Unit,
    onAddAgent: () -> Unit,
    onRequestPanel: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val vm: ChatViewModel = viewModel(key = conversationId, factory = viewModelFactory {
        initializer { ChatViewModel(container, conversationId) }
    })
    val members by vm.members.collectAsState()
    val sessionInfo by vm.sessionInfo.collectAsState()
    // Ticking clock for the session elapsed time and the per-sub timers.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    var selectedTab by remember { mutableStateOf(MemberTab.Available) }
    val workingMembers = members.filter { it.status == MemberStatus.WORKING }
    val availableMembers = members.filter { it.status != MemberStatus.WORKING }
    val visibleMembers = if (selectedTab == MemberTab.Available) availableMembers else workingMembers
    val anyWorking = workingMembers.isNotEmpty()

    Scaffold(
        containerColor = Color.White,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text("Members", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Button(
                            onClick = onAddAgent,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BrandBlue,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Invite", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                )
                HorizontalDivider(color = TopBarLine, thickness = 1.dp)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            sessionInfo?.let { info ->
                item {
                    SessionCard(info = info, memberCount = members.size, now = now, anyWorking = anyWorking)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            item {
                Box(Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        Modifier.align(Alignment.BottomCenter),
                        thickness = 1.dp,
                        color = TopBarLine,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        MemberTabItem(
                            title = "Available (${availableMembers.size})",
                            selected = selectedTab == MemberTab.Available,
                            onClick = { selectedTab = MemberTab.Available },
                        )
                        Spacer(Modifier.width(28.dp))
                        MemberTabItem(
                            title = "Working (${workingMembers.size})",
                            selected = selectedTab == MemberTab.Working,
                            onClick = { selectedTab = MemberTab.Working },
                        )
                    }
                }
            }
            items(visibleMembers, key = { it.id }) { member ->
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(13.dp))
                    MemberRow(
                        member = member,
                        now = now,
                        isWorkingTab = selectedTab == MemberTab.Working,
                        onOpenSettings = { onAgentSettings(member.id) },
                        onOpenRequestPanel = onRequestPanel,
                        // Main has no sub-run, so stopSub("main") would be a no-op:
                        // Main's Stop stops Main's current streaming reply only
                        // (ChatViewModel.stop), never the subs.
                        onStop = { if (member.id == "main") vm.stop() else vm.stopSub(member.id) },
                    )
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(thickness = 1.dp, color = RowLine)
                }
            }
            sessionInfo?.let { info ->
                item { Spacer(Modifier.height(16.dp)) }
                item { TokenBudgetCard(info = info) }
            }
        }
    }
}

@Composable
private fun MemberTabItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) BrandBlue else SubGrey,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) BrandBlue else Color.Transparent),
        )
    }
}

@Composable
private fun SessionCard(info: SessionInfo, memberCount: Int, now: Long, anyWorking: Boolean) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SessionBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Session ID", fontSize = 13.sp, color = SubGrey)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.AccessTime,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    formatElapsed(now - info.startedAt),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                if (anyWorking) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = RunningBg, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            "● Running",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RunningText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                info.sessionShortId,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Ink,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Started ${formatClock(info.startedAt)} · $memberCount members · Peak: ${info.peakMembers} members",
                fontSize = 13.5.sp,
                color = SubGrey,
                maxLines = 1,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 1.dp, color = RowLine)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionStat(
                    value = "${info.messageCount}",
                    label = "Messages",
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(40.dp).background(RowLine))
                SessionStat(
                    value = "$memberCount",
                    label = "Members",
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(40.dp).background(RowLine))
                SessionStat(
                    value = "${sessionTokenPercent(info.sessionTokensUsed, info.sessionTokenBudget)}%",
                    label = "Token budget",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SessionStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(label, fontSize = 12.sp, color = SubGrey)
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    now: Long,
    isWorkingTab: Boolean,
    onOpenSettings: () -> Unit,
    onOpenRequestPanel: () -> Unit,
    onStop: () -> Unit,
) {
    val tappable = !member.isUser
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable(onClick = onOpenSettings) else Modifier),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MemberRowAvatar(
            member = member,
            onAvatarClick = if (member.id == "main") onOpenRequestPanel else null,
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (!member.enabled) {
                    Spacer(Modifier.width(8.dp))
                    Text("Off", fontSize = 12.sp, color = SubGrey)
                }
                if (member.startedAt != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatElapsed(now - member.startedAt),
                        fontSize = 12.sp,
                        color = TimerGrey,
                    )
                }
            }
            if (isWorkingTab) {
                if (member.currentTask != null) {
                    Text(member.currentTask, fontSize = 13.5.sp, color = SubGrey)
                }
            } else {
                Text(member.role, fontSize = 13.5.sp, color = SubGrey)
            }
            if (member.roundsUsed != null && member.roundCap != null &&
                member.tokensUsed != null && member.tokenBudget != null
            ) {
                Text(
                    "Round ${member.roundsUsed}/${member.roundCap} · Tokens ~${formatGrouped(member.tokensUsed)} / ${formatGrouped(member.tokenBudget)}",
                    fontSize = 13.5.sp,
                    color = RoundsText,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MemberBarTrack),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                (member.tokensUsed.toFloat() / member.tokenBudget.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(memberColor(member)),
                    )
                }
            }
        }
        if (isWorkingTab) {
            Surface(
                onClick = onStop,
                color = StopRed,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                Text(
                    "Stop",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Round avatar with the member's initial and a status dot at the bottom right. */
@Composable
private fun MemberRowAvatar(member: GroupMember, onAvatarClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(if (onAvatarClick != null) Modifier.clickable(onClick = onAvatarClick) else Modifier),
    ) {
        Surface(
            shape = CircleShape,
            color = memberColor(member),
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.TopStart),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    member.name.firstOrNull()?.uppercase().orEmpty(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(memberStatusColor(member.status))
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

/** Stack of three coins, drawn with Canvas (no such icon in Material). */
@Composable
private fun CoinsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp, 16.dp)) {
        val coinWidth = size.width
        val coinHeight = 6.dp.toPx()
        val stroke = 1.6.dp.toPx()
        // Bottom coin first, so each upper coin covers the top arc of the one below.
        for (i in 2 downTo 0) {
            val top = i * 5.dp.toPx()
            drawOval(
                color = TokenCardBg,
                topLeft = Offset(0f, top),
                size = Size(coinWidth, coinHeight),
            )
            drawOval(
                color = Ink,
                topLeft = Offset(0f, top),
                size = Size(coinWidth, coinHeight),
                style = Stroke(width = stroke),
            )
        }
    }
}

@Composable
private fun TokenBudgetCard(info: SessionInfo) {
    Surface(
        color = TokenCardBg,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinsIcon()
                Spacer(Modifier.width(8.dp))
                Text("Token budget", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = SubGrey,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (info.sessionTokenBudget > 0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatGrouped(info.sessionTokensUsed),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                    )
                    Text(
                        " / ${formatGrouped(info.sessionTokenBudget)} tokens",
                        fontSize = 14.sp,
                        color = SubGrey,
                    )
                }
            } else {
                Text("no agent budget set", fontSize = 14.sp, color = SubGrey)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TokenTrack),
            ) {
                val fraction = if (info.sessionTokenBudget > 0) {
                    (info.sessionTokensUsed.toFloat() / info.sessionTokenBudget).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(BrandBlue),
                )
            }
        }
    }
}
