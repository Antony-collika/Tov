package com.example.byokchat.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import com.example.byokchat.container
import kotlinx.coroutines.launch

/**
 * Home screen: the chat, with the conversation list in a drawer.
 * - Swipe right (or tap the menu icon) opens the drawer.
 * - Back on the chat opens the drawer; Back again while it was opened that way leaves the app.
 * - Conversations (including branches) are all equal: switching just shows another one, there is no back stack of chats.
 */
@Composable
fun ChatHost(
    conversationId: String,
    focusMessageId: String?,
    initialQuery: String?,
    branchFrom: String?,
    openNonce: Int,
    onOpenChat: (id: String, branchFrom: String?) -> Unit,
    onOpenSearchResult: (conversationId: String, messageId: String?, query: String?) -> Unit,
    onNewChat: () -> Unit,
    onBranchNoticeShown: () -> Unit,
    onSettings: () -> Unit,
    onOnboarding: () -> Unit,
    onProvider: (String?) -> Unit,
    onInspector: () -> Unit,
    onRequestLog: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var openedByBack by remember { mutableStateOf(false) }
    val drawerOpen = drawerState.currentValue == DrawerValue.Open

    // Hide the keyboard whenever the drawer opens
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            keyboard?.hide()
            focus.clearFocus()
        } else {
            openedByBack = false
        }
    }

    // Registered before the content so that handlers inside the chat (e.g. closing in-chat search) win.
    BackHandler(enabled = !drawerOpen) {
        openedByBack = true
        scope.launch { drawerState.open() }
    }
    BackHandler(enabled = drawerOpen) {
        if (openedByBack) (context as? Activity)?.finish() else scope.launch { drawerState.close() }
    }

    fun closeDrawer() = scope.launch { drawerState.close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ConversationDrawerContent(
                    isOpen = drawerOpen,
                    currentId = conversationId,
                    onSelect = { id ->
                        closeDrawer()
                        if (id != conversationId) onOpenChat(id, null)
                    },
                    onNewChat = {
                        closeDrawer()
                        scope.launch {
                            // Already on an empty chat: nothing to create.
                            val empty = container.db.messages().maxSeq(conversationId) == null
                            if (!empty) onNewChat()
                        }
                    },
                    onDeleted = { id -> if (id == conversationId) onNewChat() },
                    onOpenResult = { cid, mid, q ->
                        closeDrawer()
                        onOpenSearchResult(cid, mid, q)
                    },
                    onSettings = { closeDrawer(); onSettings() },
                )
            }
        },
    ) {
        // key(): every conversation (and every "open from search") gets its own fresh screen state (search, editing, scroll position...)
        key(conversationId, openNonce) {
            ChatScreen(
                conversationId = conversationId,
                focusMessageId = focusMessageId,
                initialQuery = initialQuery,
                branchFrom = branchFrom,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSettings = onSettings,
                onOpenBranch = { id -> onOpenChat(id, conversationId) },
                onOpenOriginal = { id -> onOpenChat(id, null) },
                onBranchNoticeShown = onBranchNoticeShown,
                onOnboarding = onOnboarding,
                onOpenProvider = onProvider,
                onNewChat = onNewChat,
                onOpenInspector = onInspector,
                onOpenRequestLog = onRequestLog,
            )
        }
    }
}
