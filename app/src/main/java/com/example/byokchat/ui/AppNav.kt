package com.example.byokchat.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            ConversationListScreen(
                onOpen = { id -> nav.navigate("chat/$id") },
                onSettings = { nav.navigate("settings") },
                onSearch = { nav.navigate("search") },
            )
        }
        composable(
            "chat/{id}?msg={msg}&q={q}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("msg") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ChatScreen(
                conversationId = entry.arguments?.getString("id") ?: "",
                focusMessageId = entry.arguments?.getString("msg"),
                initialQuery = entry.arguments?.getString("q"),
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate("settings") },
                onOpenConversation = { id -> nav.navigate("chat/$id") },
            )
        }
        composable("search") {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpen = { cid, mid, q ->
                    val msg = if (mid != null) "&msg=" + Uri.encode(mid) else ""
                    nav.navigate("chat/$cid?q=" + Uri.encode(q) + msg)
                },
            )
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
    ConfirmationHost()
}
