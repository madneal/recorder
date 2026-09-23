package com.neal.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder

class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_PLAY_PAUSE = "com.neal.recorder.PLAY_PAUSE"
        private const val ACTION_STOP = "com.neal.recorder.STOP_PLAYBACK"
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService
            get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private lateinit var notificationManager: NotificationManager
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private var playingName = ""
    private var completed = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "播放",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayback()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun play(uri: Uri, name: String): String? {
        stopPlayer()
        val newPlayer = try {
            MediaPlayer.create(this, uri)
        } catch (error: Exception) {
            return error.message ?: "无法播放该录音"
        } ?: return "无法播放该录音"

        player = newPlayer
        playingUri = uri
        playingName = name
        completed = false
        newPlayer.setOnCompletionListener {
            stopPlayer()
            completed = true
            notificationManager.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        newPlayer.start()
        updateNotification()
        return null
    }

    fun pausePlayback() {
        player?.takeIf { it.isPlaying }?.pause()
        updateNotification()
    }

    fun resumePlayback() {
        val current = player ?: return
        if (completed || current.currentPosition >= current.duration) {
            current.seekTo(0)
            completed = false
        }
        current.start()
        updateNotification()
    }

    fun togglePlayback() {
        val current = player
        if (current == null) {
            val uri = playingUri ?: return
            play(uri, playingName)
            return
        }
        if (current.isPlaying) pausePlayback() else resumePlayback()
    }

    fun stopPlayback() {
        stopPlayer()
        completed = false
        playingUri = null
        playingName = ""
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun seekTo(positionMs: Int) {
        val current = player ?: return
        current.seekTo(positionMs.coerceIn(0, current.duration))
        completed = false
        updateNotification()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun currentPosition(): Int = player?.runCatching { currentPosition }?.getOrDefault(0) ?: 0

    fun duration(): Int = player?.runCatching { duration }?.getOrDefault(0) ?: 0

    fun currentUri(): Uri? = playingUri

    fun currentName(): String = playingName

    fun isCompleted(): Boolean = completed

    private fun stopPlayer() {
        player?.setOnCompletionListener(null)
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    private fun updateNotification() {
        if (player == null) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseTitle = if (isPlaying()) "暂停" else "继续"

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("recorder")
            .setContentText(playingName)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    playPauseTitle,
                    playPauseIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "停止",
                    stopIntent
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}
