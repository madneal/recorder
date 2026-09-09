package com.neal.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        val service: RecorderService
            get() = this@RecorderService
    }

    private val binder = LocalBinder()
    private lateinit var notificationManager: NotificationManager
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputFile: ParcelFileDescriptor? = null
    private var startedAt = 0L
    private var accumulatedMs = 0L
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "录音",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground("录音机已准备")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startRecording(): String? {
        if (recorder != null) return "已经在录音"

        return try {
            ensureForeground("正在录音")
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "录音_$name.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/Recordings"
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("无法创建音频文件")
            val file = contentResolver.openFileDescriptor(uri, "w")
                ?: error("无法打开音频文件")

            val mediaRecorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.fileDescriptor)
                prepare()
                start()
            }

            outputUri = uri
            outputFile = file
            recorder = mediaRecorder
            startedAt = SystemClock.elapsedRealtime()
            accumulatedMs = 0L
            paused = false
            updateNotification("正在录音")
            null
        } catch (error: Exception) {
            outputUri?.let { contentResolver.delete(it, null, null) }
            outputUri = null
            outputFile?.close()
            outputFile = null
            recorder?.release()
            recorder = null
            error.message ?: "启动录音失败"
        }
    }

    fun pauseRecording(): String? {
        val current = recorder ?: return "当前没有录音"
        if (paused) return null

        return try {
            current.pause()
            accumulatedMs += SystemClock.elapsedRealtime() - startedAt
            paused = true
            updateNotification("录音已暂停")
            null
        } catch (error: Exception) {
            error.message ?: "暂停失败"
        }
    }

    fun resumeRecording(): String? {
        val current = recorder ?: return "当前没有录音"
        if (!paused) return null

        return try {
            current.resume()
            startedAt = SystemClock.elapsedRealtime()
            paused = false
            updateNotification("正在录音")
            null
        } catch (error: Exception) {
            error.message ?: "继续录音失败"
        }
    }

    fun stopRecording(): String? {
        val current = recorder ?: return null
        val uri = outputUri
        var stopError: Exception? = null

        try {
            current.stop()
        } catch (error: Exception) {
            stopError = error
        } finally {
            current.reset()
            current.release()
            recorder = null
            outputFile?.close()
            outputFile = null
        }

        if (uri != null) {
            if (stopError == null) {
                contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            } else {
                contentResolver.delete(uri, null, null)
            }
        }

        outputUri = null
        paused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return stopError?.message
    }

    fun isRecording(): Boolean = recorder != null && !paused

    fun isPaused(): Boolean = recorder != null && paused

    fun elapsedMs(): Long {
        if (recorder == null) return 0L
        return accumulatedMs + if (paused) 0L else SystemClock.elapsedRealtime() - startedAt
    }

    private fun ensureForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("录音机")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        if (recorder != null) {
            stopRecording()
        }
        super.onDestroy()
    }
}
