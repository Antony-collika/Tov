package com.example.byokchat.chat

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.byokchat.MainActivity
import com.example.byokchat.R

/**
 * Dịch vụ nền giữ cho AI tiếp tục trả lời khi tắt màn hình / chuyển app.
 * Chỉ chạy khi có ít nhất một cuộc trò chuyện đang được trả lời (App.kt bật/tắt).
 */
class GenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ChatNotifier.createChannels(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ChatNotifier.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_tov)
            .setContentTitle("Tov is replying…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    /** Android 15 giới hạn thời gian của dịch vụ loại dataSync; hết giờ thì phải dừng. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}
