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
import com.example.byokchat.tools.ConfirmRequest
import com.example.byokchat.tools.Risk

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

    private fun canNotify(manager: NotificationManagerCompat): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    /** "AI is waiting for your permission": shown when a tool needs confirmation while the app is in the background. */
    fun notifyConfirmation(req: ConfirmRequest) {
        val manager = NotificationManagerCompat.from(context)
        if (!canNotify(manager)) return

        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_CONFIRM,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun button(allow: Boolean): PendingIntent {
            val intent = Intent(context, ConfirmReceiver::class.java).apply {
                action = ConfirmReceiver.ACTION
                putExtra(EXTRA_REQUEST_ID, req.id)
                putExtra(EXTRA_ALLOW, allow)
            }
            return PendingIntent.getBroadcast(
                context,
                req.id * 2 + (if (allow) 1 else 0),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val danger = req.risk == Risk.DELETE
        val detail = req.detail.trim().take(400)
        val builder = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_stat_tov)
            .setContentTitle(if (danger) "AI wants to DELETE data" else "AI is waiting for your permission")
            .setContentText("${req.toolName}: ${detail.lineSequence().firstOrNull().orEmpty()}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${req.toolName}\n$detail"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .addAction(0, "Deny", button(false))
        // Deleting is never approved from a notification: open the app and read the details first.
        if (!danger) builder.addAction(0, "Allow", button(true))
        try {
            manager.notify(CONFIRM_NOTIFICATION_BASE + req.id, builder.build())
        } catch (e: SecurityException) {
            return
        }
    }

    fun cancelConfirmation(requestId: Int) {
        NotificationManagerCompat.from(context).cancel(CONFIRM_NOTIFICATION_BASE + requestId)
    }

    companion object {
        const val CHANNEL_CONFIRM = "confirm"
        const val EXTRA_REQUEST_ID = "confirmRequestId"
        const val EXTRA_ALLOW = "confirmAllow"
        const val CONFIRM_NOTIFICATION_BASE = 100_000
        private const val REQUEST_OPEN_CONFIRM = 7001

        const val CHANNEL_ANSWERS = "answers"
        const val CHANNEL_RUNNING = "running"
        const val CHANNEL_MEDIA = "media"
        const val EXTRA_CONVERSATION_ID = "conversationId"

        /** Tạo hai kênh thông báo (an toàn khi gọi nhiều lần). minSdk = 26 nên luôn có kênh. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ANSWERS, "AI answers", NotificationManager.IMPORTANCE_DEFAULT)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "Replying", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CONFIRM, "Permission requests", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MEDIA, "Music", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                }
            )
        }
    }
}
