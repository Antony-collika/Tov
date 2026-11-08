package com.example.byokchat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.byokchat.chat.ChatEngine
import com.example.byokchat.chat.ChatNotifier
import com.example.byokchat.chat.GenerationService
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        keepServiceWhileAnswering()
    }

    /** Có cuộc trò chuyện đang được AI trả lời -> bật dịch vụ nền; hết -> tắt. */
    private fun keepServiceWhileAnswering() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            container.engine.activeConversations
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collectLatest { active ->
                    val intent = Intent(this@App, GenerationService::class.java)
                    if (active) {
                        try {
                            ContextCompat.startForegroundService(this@App, intent)
                            container.foregroundServiceError.value = null
                        } catch (e: IllegalStateException) {
                            // Hệ thống từ chối bật dịch vụ nền: việc trả lời vẫn tiếp tục như cũ, nhưng ghi lại lý do.
                            val reason = e.javaClass.simpleName + ": " + (e.message ?: "không rõ lý do")
                            container.foregroundServiceError.value = reason
                            Log.w("Tov", "Không bật được dịch vụ nền: $reason")
                        }
                    } else {
                        // Chờ một chút để dịch vụ kịp báo "đang chạy" với hệ thống trước khi tắt (tránh lỗi khi bấm Dừng ngay).
                        delay(1500)
                        stopService(intent)
                    }
                }
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
    val notifier = ChatNotifier(context)
    val engine = ChatEngine(db, settings, registry, notifier)

    /** Mở cuộc trò chuyện được yêu cầu từ thông báo (MainActivity ghi vào, AppNav đọc rồi xóa). */
    val pendingConversation = MutableStateFlow<String?>(null)

    /** Lý do hệ thống từ chối bật dịch vụ nền (nếu có), để tra cứu khi báo cáo. */
    val foregroundServiceError = MutableStateFlow<String?>(null)
    val conversations = ConversationRepository(db, settings)
    val ops = ChatOps(db)
}

val Context.container: AppContainer get() = (applicationContext as App).container
