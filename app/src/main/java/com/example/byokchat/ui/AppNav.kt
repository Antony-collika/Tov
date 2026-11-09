package com.example.byokchat.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.byokchat.container
import kotlinx.coroutines.launch

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    // One Markwon for the whole app (building one per message was slow)
    val markwon = remember { createMarkwon(context) }
    // New users (no provider yet, welcome screen not finished/skipped) start at the welcome screen.
    val start = remember { if (settings.providers.value.isEmpty() && !settings.onboardingDone.value) "onboarding" else "chat" }

    // Which conversation the chat screen shows. Saved so it survives rotation / process restart.
    var chatId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var initialQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var branchFrom by rememberSaveable { mutableStateOf<String?>(null) }
    // Changes on every openChat() so that opening a search result in the conversation that is already shown still refreshes it.
    var openNonce by rememberSaveable { mutableIntStateOf(0) }

    fun openChat(id: String, msg: String? = null, q: String? = null, from: String? = null) {
        chatId = id
        focusMsg = msg
        initialQuery = q
        branchFrom = from
        openNonce++
    }

    fun newChat() {
        scope.launch { openChat(container.conversations.create()) }
    }

    // The app opens straight into a (new) chat.
    LaunchedEffect(Unit) {
        val cur = chatId
        if (cur == null || container.db.conversations().get(cur) == null) {
            val fresh = container.conversations.create()
            // Do not overwrite a conversation that was opened in the meantime (e.g. from a notification)
            if (chatId == null || chatId == cur) openChat(fresh)
        }
    }

    // Leave the welcome screen: back to the chat (no welcome screen left on the stack), optionally on the new conversation.
    fun leaveOnboarding(id: String?) {
        if (id != null) openChat(id)
        if (!nav.popBackStack("chat", inclusive = false)) {
            nav.navigate("chat") { popUpTo("onboarding") { inclusive = true } }
        }
    }

    CompositionLocalProvider(LocalMarkwon provides markwon) {
        NavHost(navController = nav, startDestination = start) {
            composable("onboarding") {
                OnboardingScreen(onExit = { id -> leaveOnboarding(id) })
            }
            composable("chat") {
                val id = chatId
                if (id == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    ChatHost(
                        conversationId = id,
                        focusMessageId = focusMsg,
                        initialQuery = initialQuery,
                        branchFrom = branchFrom,
                        openNonce = openNonce,
                        onOpenChat = { newId, from -> openChat(newId, from = from) },
                        onOpenSearchResult = { cid, mid, q -> openChat(cid, msg = mid, q = q) },
                        onNewChat = { newChat() },
                        onBranchNoticeShown = { branchFrom = null },
                        onSettings = { nav.navigate("settings") },
                        onOnboarding = { nav.navigate("onboarding") },
                        onProvider = { pid -> nav.navigate(if (pid == null) "providers" else "provider?id=$pid") },
                    )
                }
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onProviders = { nav.navigate("providers") },
                    onTools = { nav.navigate("tools") },
                    onNotes = { nav.navigate("notes") },
                )
            }
            composable("tools") {
                ToolsScreen(onBack = { nav.popBackStack() }, onOpen = { uid -> nav.navigate("tool/" + Uri.encode(uid)) })
            }
            composable("tool/{uid}", arguments = listOf(navArgument("uid") { type = NavType.StringType })) { entry ->
                ToolDetailScreen(uid = entry.arguments?.getString("uid") ?: "", onBack = { nav.popBackStack() })
            }
            composable("notes") { NotesScreen(onBack = { nav.popBackStack() }) }
            composable("providers") {
                ProvidersScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { type -> nav.navigate("provider?type=${type.name}") },
                    onEdit = { id -> nav.navigate("provider?id=$id") },
                )
            }
            composable(
                "provider?id={id}&type={type}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                ProviderEditScreen(
                    providerId = entry.arguments?.getString("id"),
                    newType = entry.arguments?.getString("type"),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }

    // Open a conversation from a notification (ignored if it no longer exists)
    val pending by container.pendingConversation.collectAsState()
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        container.pendingConversation.value = null
        if (container.db.conversations().get(id) == null) return@LaunchedEffect
        openChat(id)
        nav.popBackStack("chat", inclusive = false)
    }
    ConfirmationHost()
}
