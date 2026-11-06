package com.example.byokchat

import android.app.Application
import android.content.Context
import com.example.byokchat.chat.ChatEngine
import com.example.byokchat.chat.ChatOps
import com.example.byokchat.data.AppDatabase
import com.example.byokchat.data.ConversationRepository
import com.example.byokchat.data.SettingsRepository
import com.example.byokchat.tools.ConfirmationBroker
import com.example.byokchat.tools.PermissionBroker
import com.example.byokchat.tools.ToolEnv
import com.example.byokchat.tools.ToolPrefs
import com.example.byokchat.tools.ToolRegistry
import com.example.byokchat.tools.WorkspaceFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(Dispatchers.IO).launch {
            container.db.messages().markInterrupted()
            container.fs.refresh()
        }
    }
}

class AppContainer(val context: Context) {
    val db: AppDatabase = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val fs = WorkspaceFs(context, settings)
    val permissions = PermissionBroker(context)
    val confirmations = ConfirmationBroker()
    val toolPrefs = ToolPrefs(context)
    private val env = ToolEnv(context, fs, db, settings, permissions)
    val registry = ToolRegistry(env, toolPrefs, confirmations)
    val engine = ChatEngine(db, settings, registry)
    val conversations = ConversationRepository(db, settings)
    val ops = ChatOps(db)
}

val Context.container: AppContainer get() = (applicationContext as App).container
