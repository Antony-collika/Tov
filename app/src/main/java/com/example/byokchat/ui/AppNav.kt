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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    // One Markwon for the whole app (building one per message was slow)
    val markwon = remember { createMarkwon(context) }

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

    // Chờ kho mã hóa đọc xong (xem SettingsRepository.ready). Trong lúc chờ chỉ hiện nền trống,
    // nên không có màn chào hay thẻ "No AI connected" chớp lên với người dùng cũ.
    // Việc tạo/mở cuộc chat ở trên vẫn chạy song song.
    val ready by settings.ready.collectAsState()
    if (!ready) {
        Box(Modifier.fillMaxSize())
        return
    }

    // New users (no provider yet, welcome screen not finished/skipped) start at the welcome screen.
    // Tính sau khi đã sẵn sàng, vì khi đó mới biết có nhà cung cấp hay không.
    val start = remember { if (settings.providers.value.isEmpty() && !settings.onboardingDone.value) "onboarding" else "chat" }

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
                        onSettings = {
                            // Đợt fix 11/11/2026 (B2): mang id session theo đường
                            // Settings → panel, nhưng chỉ khi session đã có lượt
                            // chat (session trống thì Preview/Messages cũng không
                            // có gì để xem nên không cần mang id).
                            scope.launch {
                                val hasTurns = withContext(Dispatchers.IO) {
                                    container.db.messages().maxSeq(id) != null
                                }
                                nav.navigate(if (hasTurns) "settings?id=$id" else "settings")
                            }
                        },
                        onOnboarding = { nav.navigate("onboarding") },
                        onProvider = { pid -> nav.navigate(if (pid == null) "providers" else "provider?id=$pid") },
                        onInspector = { nav.navigate("inspector/$id") },
                        onRequestLog = { nav.navigate("request-log") },
                        onPanel = { nav.navigate("panel?id=$id") },
                        onOpenStorage = { nav.navigate("settings-storage") },
                    )
                }
            }
            composable(
                "settings?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                // Đợt fix 11/11/2026 (B2): id session mang từ màn chat (chỉ có
                // khi session đã có lượt chat) để mục Request panel mở đúng
                // panel của session đó — Preview và Manage messages mới hiện.
                val sid = entry.arguments?.getString("id")
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onProviders = { nav.navigate("providers") },
                    onTools = { nav.navigate("tools") },
                    onNotes = { nav.navigate("notes") },
                    onRequestPanel = { nav.navigate(if (sid != null) "panel?id=$sid" else "panel") },
                    onWebSearch = { nav.navigate("settings-web-search") },
                    onStorage = { nav.navigate("settings-storage") },
                    onAppearance = { nav.navigate("settings-appearance") },
                )
            }
            composable("settings-web-search") { SettingsWebSearchScreen(onBack = { nav.popBackStack() }) }
            composable("settings-storage") { SettingsStorageScreen(onBack = { nav.popBackStack() }) }
            composable("settings-appearance") { SettingsAppearanceScreen(onBack = { nav.popBackStack() }) }
            composable(
                "panel?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                RequestPanelScreen(
                    conversationId = id,
                    onBack = { nav.popBackStack() },
                    onEditCard = { cardId -> nav.navigate("card-edit/$cardId" + if (id != null) "?cid=$id" else "") },
                    onOpenTools = { nav.navigate("tools") },
                    onOpenInspector = id?.let { { nav.navigate("inspector/$id") } },
                    onOpenRequestLog = { nav.navigate("request-log") },
                )
            }
            composable(
                "card-edit/{cardId}?cid={cid}",
                arguments = listOf(
                    navArgument("cardId") { type = NavType.StringType },
                    navArgument("cid") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                CardEditScreen(
                    cardId = entry.arguments?.getString("cardId") ?: "",
                    onBack = { nav.popBackStack() },
                    // Đợt 4: chỉ thẻ History của một cuộc cụ thể mới mở được màn
                    // Messages (panel mở từ Settings thì không có cuộc nào).
                    onOpenMessages = entry.arguments?.getString("cid")?.let { cid -> { nav.navigate("messages/$cid") } },
                )
            }
            composable("messages/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                MessagesScreen(
                    conversationId = entry.arguments?.getString("id") ?: "",
                    // Back quay về Request panel, không dừng ở màn sửa thẻ
                    // History (như FPilot): pop màn Messages rồi pop card-edit.
                    onBack = { nav.popBackStack(); nav.popBackStack() },
                )
            }
            composable("tools") {
                ToolsScreen(onBack = { nav.popBackStack() }, onOpen = { uid -> nav.navigate("tool/" + Uri.encode(uid)) })
            }
            composable("tool/{uid}", arguments = listOf(navArgument("uid") { type = NavType.StringType })) { entry ->
                ToolDetailScreen(uid = entry.arguments?.getString("uid") ?: "", onBack = { nav.popBackStack() })
            }
            composable("notes") { NotesScreen(onBack = { nav.popBackStack() }) }
            composable("inspector/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                RequestInspectorScreen(
                    conversationId = entry.arguments?.getString("id") ?: "",
                    onBack = { nav.popBackStack() },
                )
            }
            composable("request-log") { RequestLogScreen(onBack = { nav.popBackStack() }) }
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
