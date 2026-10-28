package ru.example.okko

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer

internal object PlaybackHolder {
    @Volatile
    var player: ExoPlayer? = null
}

class PlaybackForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "okko_background_playback"
        private const val NOTIFICATION_ID = 4102
    }

    private var activePlayer: ExoPlayer? = null
    private var webAudioMode = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновое воспроизведение",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление во время воспроизведения аудио в фоне"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activePlayer = PlaybackHolder.player
        webAudioMode = intent?.getBooleanExtra("web_audio_mode", false) == true
        val targetActivity = if (webAudioMode) OkkoWebActivity::class.java else PlayerActivity::class.java
        val openPlayer = PendingIntent.getActivity(
            this,
            if (webAudioMode) 1 else 0,
            Intent(this, targetActivity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Okko")
            .setContentText("Воспроизведение звука в фоне")
            .setContentIntent(openPlayer)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        activePlayer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
