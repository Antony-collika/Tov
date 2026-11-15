package com.example.byokchat.media

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.byokchat.MainActivity
import com.example.byokchat.R
import com.example.byokchat.chat.ChatNotifier
import com.example.byokchat.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Dịch vụ nền giữ cho nhạc chạy khi tắt màn hình / chuyển app (Đợt 2).
 * Theo mẫu GenerationService: ServiceCompat.startForeground với loại
 * mediaPlayback (bắt buộc từ Android 14, targetSdk 35), thông báo nhạc
 * có các nút điều khiển giống thanh mini-player.
 *
 * Service không giữ MediaPlayer — player ở MediaPlayback (AppContainer).
 * Service chỉ giữ vòng đời nền + thông báo; các nút thông báo gọi thẳng
 * vào controller. Nhạc dừng (hết batch, bấm X, lỗi) thì controller tự
 * tắt service; hệ thống giết service thì onDestroy dừng nhạc luôn để
 * không phát "mồ côi" không thông báo.
 */
class MediaPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var media: MediaPlayback

    override fun onCreate() {
        super.onCreate()
        media = applicationContext.container.media
        ChatNotifier.createChannels(this)
        // Thông báo cập nhật khi đổi bài hoặc đổi trạng thái phát/dừng;
        // vị trí phát (đổi 2 lần/giây) không cập nhật thông báo.
        scope.launch {
            media.state.distinctUntilChangedBy { Triple(it.current?.uri, it.playing, it.index) }
                .collect { state ->
                    if (state.visible) updateNotification(state)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Lên foreground ngay với thông báo hiện tại (bắt buộc trong ~10s
        // sau startForegroundService).
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(media.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        when (intent?.action) {
            ACTION_TOGGLE -> media.toggle()
            ACTION_NEXT -> media.next()
            ACTION_PREV -> media.previous()
            ACTION_STOP -> media.stop()
            else -> Unit // ACTION_START hoặc null: chỉ lên foreground.
        }
        if (!media.state.value.visible) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Hệ thống giết service: dừng nhạc luôn (best-effort).
        media.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(state: MediaState) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: MediaState): Notification {
        val track = state.current
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ChatNotifier.CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_tov)
            .setContentTitle(track?.name ?: "Music")
            .setContentText(if (track == null) "" else "Track ${state.label}")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV, REQUEST_PREV))
            .addAction(
                if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.playing) "Pause" else "Play",
                actionIntent(ACTION_TOGGLE, REQUEST_TOGGLE),
            )
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT, REQUEST_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_STOP, REQUEST_STOP))
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ACTION_START = "com.example.byokchat.media.ACTION_START"
        const val ACTION_TOGGLE = "com.example.byokchat.media.ACTION_TOGGLE"
        const val ACTION_NEXT = "com.example.byokchat.media.ACTION_NEXT"
        const val ACTION_PREV = "com.example.byokchat.media.ACTION_PREV"
        const val ACTION_STOP = "com.example.byokchat.media.ACTION_STOP"
        private const val NOTIFICATION_ID = 1002
        private const val REQUEST_OPEN = 8001
        private const val REQUEST_PREV = 8002
        private const val REQUEST_TOGGLE = 8003
        private const val REQUEST_NEXT = 8004
        private const val REQUEST_STOP = 8005
    }
}
