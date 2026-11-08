package com.example.byokchat.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.byokchat.MainActivity
import com.example.byokchat.R

/** Cho biết app đang hiện trên màn hình hay không (MainActivity cập nhật ở onStart / onStop). */
object AppVisibility {
    @Volatile
    var inForeground: Boolean = false
}

/** Gửi thông báo "AI đã trả lời xong" khi người dùng đang ở app khác. */
class ChatNotifier(private val context: Context) {

    init {
        createChannels(context)
    }

    fun notifyAnswer(conversationId: String, title: String, preview: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pending = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ANSWERS)
            .setSmallIcon(R.drawable.ic_stat_tov)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(conversationId.hashCode(), notification)
        } catch (e: SecurityException) {
            // Quyền thông báo vừa bị thu hồi: bỏ qua thông báo này, không ảnh hưởng việc trả lời.
            return
        }
    }

    companion object {
        const val CHANNEL_ANSWERS = "answers"
        const val CHANNEL_RUNNING = "running"
        const val EXTRA_CONVERSATION_ID = "conversationId"

        /** Tạo hai kênh thông báo (an toàn khi gọi nhiều lần). minSdk = 26 nên luôn có kênh. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ANSWERS, "Câu trả lời của AI", NotificationManager.IMPORTANCE_DEFAULT)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "Đang trả lời", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                }
            )
        }
    }
}
