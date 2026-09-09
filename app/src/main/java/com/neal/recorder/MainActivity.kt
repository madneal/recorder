package com.neal.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private data class Recording(val name: String, val uri: Uri, val durationMs: Long)

    companion object {
        private const val RECORD_AUDIO_REQUEST = 100
    }

    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var listView: ListView
    private lateinit var playbackTitleText: TextView
    private lateinit var playbackTimeText: TextView
    private lateinit var playbackSeekBar: SeekBar
    private lateinit var playbackButton: Button
    private lateinit var playbackStopButton: Button
    private lateinit var markButton: Button
    private lateinit var markersContainer: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val markerPreferences by lazy {
        getSharedPreferences("recording_markers", MODE_PRIVATE)
    }
    private var service: RecorderService? = null
    private var serviceBound = false
    private var pendingStart = false
    private var player: MediaPlayer? = null
    private var activeRecording: Recording? = null
    private var userSeekingPlayback = false
    private var recordings = emptyList<Recording>()

    private val playbackRunnable = object : Runnable {
        override fun run() {
            val current = player
            if (current == null) return
            if (!userSeekingPlayback) updatePlaybackProgress()
            if (current.isPlaying) handler.postDelayed(this, 200)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RecorderService.LocalBinder).service
            serviceBound = true
            if (pendingStart) {
                pendingStart = false
                startRecordingNow()
            }
            updateControls()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            updateControls()
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateControls()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        refreshRecordings()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RecorderService::class.java)
        serviceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshRunnable)
        stopPlayback()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }

        val title = TextView(this).apply {
            text = "录音机"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            text = "准备就绪"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 24, 0, 4)
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        timerText = TextView(this).apply {
            text = "00:00"
            textSize = 40f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 4, 0, 20)
        }
        root.addView(timerText, LinearLayout.LayoutParams(-1, -2))

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }
        startButton = Button(this).apply {
            text = "开始"
            setOnClickListener { onStartClicked() }
        }
        pauseButton = Button(this).apply {
            text = "暂停"
            setOnClickListener { onPauseClicked() }
        }
        stopButton = Button(this).apply {
            text = "停止"
            setOnClickListener { onStopClicked() }
        }
        controls.addView(startButton)
        controls.addView(pauseButton)
        controls.addView(stopButton)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        playbackTitleText = TextView(this).apply {
            text = "未选择录音"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        root.addView(playbackTitleText, LinearLayout.LayoutParams(-1, -2))

        playbackTimeText = TextView(this).apply {
            text = "00:00 / 00:00"
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(playbackTimeText, LinearLayout.LayoutParams(-1, -2))

        playbackSeekBar = SeekBar(this).apply {
            max = 1
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        playbackTimeText.text =
                            "${formatDuration(progress.toLong())} / ${formatDuration((seekBar?.max ?: 0).toLong())}"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeekingPlayback = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeekingPlayback = false
                    player?.seekTo(seekBar?.progress ?: 0)
                    updatePlaybackProgress()
                }
            })
        }
        root.addView(playbackSeekBar, LinearLayout.LayoutParams(-1, -2))

        val playbackControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
        }
        playbackButton = Button(this).apply {
            text = "播放"
            isEnabled = false
            setOnClickListener { togglePlayback() }
        }
        playbackStopButton = Button(this).apply {
            text = "停止播放"
            isEnabled = false
            setOnClickListener { stopPlayback() }
        }
        markButton = Button(this).apply {
            text = "添加标记"
            isEnabled = false
            setOnClickListener { addMarker() }
        }
        playbackControls.addView(playbackButton)
        playbackControls.addView(playbackStopButton)
        playbackControls.addView(markButton)
        root.addView(playbackControls, LinearLayout.LayoutParams(-1, -2))

        val markerTitle = TextView(this).apply {
            text = "标记点（点击跳转）"
            setPadding(0, 12, 0, 4)
        }
        root.addView(markerTitle, LinearLayout.LayoutParams(-1, -2))

        val markerScroll = HorizontalScrollView(this)
        markersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        markerScroll.addView(markersContainer)
        root.addView(markerScroll, LinearLayout.LayoutParams(-1, -2))

        val listTitle = TextView(this).apply {
            text = "我的录音（点击播放，长按操作）"
            textSize = 18f
            setPadding(0, 28, 0, 8)
        }
        root.addView(listTitle, LinearLayout.LayoutParams(-1, -2))

        listView = ListView(this)
        listView.setOnItemClickListener { _, _, position, _ -> playRecording(recordings[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ -> showRecordingActions(recordings[position]); true }
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))

        return root
    }

    private fun onStartClicked() {
        if (!hasRecordPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
            return
        }

        startRecordingService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RECORD_AUDIO_REQUEST) return
        if (!hasRecordPermission()) {
            statusText.text = "需要麦克风权限才能录音"
            Toast.makeText(this, "请允许使用麦克风", Toast.LENGTH_LONG).show()
            return
        }
        startRecordingService()
    }

    private fun startRecordingService() {
        val intent = Intent(this, RecorderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        if (service == null) {
            pendingStart = true
        } else {
            startRecordingNow()
        }
    }

    private fun startRecordingNow() {
        val error = service?.startRecording()
        if (error != null) {
            statusText.text = error
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
        updateControls()
    }

    private fun onPauseClicked() {
        val current = service ?: return
        val error = if (current.isPaused()) {
            current.resumeRecording()
        } else {
            current.pauseRecording()
        }
        if (error != null) statusText.text = error
        updateControls()
    }

    private fun onStopClicked() {
        val error = service?.stopRecording()
        if (error != null) {
            statusText.text = "停止失败：$error"
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        } else {
            statusText.text = "已保存到 Music/Recordings"
        }
        refreshRecordings()
        updateControls()
    }

    private fun updateControls() {
        val current = service
        val recording = current?.isRecording() == true
        val paused = current?.isPaused() == true
        val active = recording || paused

        startButton.isEnabled = !active
        pauseButton.isEnabled = active
        stopButton.isEnabled = active
        pauseButton.text = if (paused) "继续" else "暂停"
        if (active) {
            statusText.text = if (paused) "已暂停" else "正在录音"
            timerText.text = formatDuration(current?.elapsedMs() ?: 0L)
        } else if (timerText.text.isNullOrBlank()) {
            timerText.text = "00:00"
        }
    }

    private fun refreshRecordings() {
        val result = mutableListOf<Recording>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
        val selectionArgs = arrayOf(packageName)
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += Recording(
                    cursor.getString(nameColumn),
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    cursor.getLong(durationColumn)
                )
            }
        }
        recordings = result
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            recordings.map { "${it.name}\n${formatDuration(it.durationMs)}" }
        )
    }

    private fun playRecording(recording: Recording) {
        stopPlayback()
        player = MediaPlayer.create(this, recording.uri)
        if (player == null) {
            Toast.makeText(this, "无法播放该录音", Toast.LENGTH_SHORT).show()
            return
        }
        activeRecording = recording
        playbackSeekBar.max = player?.duration?.coerceAtLeast(1) ?: 1
        playbackSeekBar.progress = 0
        playbackSeekBar.isEnabled = true
        playbackButton.isEnabled = true
        playbackStopButton.isEnabled = true
        markButton.isEnabled = true
        playbackTitleText.text = recording.name
        renderMarkers(recording)
        player?.setOnCompletionListener {
            handler.removeCallbacks(playbackRunnable)
            it.release()
            player = null
            playbackSeekBar.progress = playbackSeekBar.max
            playbackButton.text = "重新播放"
            playbackButton.isEnabled = true
            playbackStopButton.isEnabled = false
            markButton.isEnabled = false
            playbackTimeText.text =
                "${formatDuration(playbackSeekBar.max.toLong())} / ${formatDuration(playbackSeekBar.max.toLong())}"
            statusText.text = "播放完成"
        }
        player?.start()
        playbackButton.text = "暂停"
        statusText.text = "正在播放：${recording.name}"
        updatePlaybackProgress()
        handler.post(playbackRunnable)
    }

    private fun togglePlayback() {
        val current = player
        if (current == null) {
            activeRecording?.let { playRecording(it) }
            return
        }
        if (current.isPlaying) {
            current.pause()
            handler.removeCallbacks(playbackRunnable)
            playbackButton.text = "继续"
            statusText.text = "已暂停播放"
        } else {
            if (current.currentPosition >= current.duration) current.seekTo(0)
            current.start()
            handler.post(playbackRunnable)
            playbackButton.text = "暂停"
            statusText.text = "正在播放：${activeRecording?.name.orEmpty()}"
        }
        updatePlaybackProgress()
    }

    private fun stopPlayback() {
        handler.removeCallbacks(playbackRunnable)
        player?.setOnCompletionListener(null)
        player?.release()
        player = null
        activeRecording = null
        userSeekingPlayback = false
        if (::playbackTitleText.isInitialized) playbackTitleText.text = "未选择录音"
        if (::playbackTimeText.isInitialized) playbackTimeText.text = "00:00 / 00:00"
        if (::playbackSeekBar.isInitialized) playbackSeekBar.apply {
            progress = 0
            max = 1
            isEnabled = false
        }
        if (::playbackButton.isInitialized) playbackButton.apply {
            text = "播放"
            isEnabled = false
        }
        if (::playbackStopButton.isInitialized) playbackStopButton.isEnabled = false
        if (::markButton.isInitialized) markButton.isEnabled = false
        if (::markersContainer.isInitialized) renderMarkers(null)
    }

    private fun updatePlaybackProgress() {
        val current = player ?: return
        val duration = current.duration.coerceAtLeast(1)
        val position = current.currentPosition.coerceIn(0, duration)
        playbackSeekBar.max = duration
        if (!userSeekingPlayback) playbackSeekBar.progress = position
        playbackTimeText.text = "${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}"
    }

    private fun addMarker() {
        val current = player ?: return
        val recording = activeRecording ?: return
        val position = current.currentPosition.toLong()
        val markers = markersFor(recording).toMutableList()
        if (markers.any { kotlin.math.abs(it - position) < 500L }) {
            statusText.text = "该位置附近已有标记"
            return
        }
        markers += position
        saveMarkers(recording, markers)
        renderMarkers(recording)
        statusText.text = "已添加标记：${formatDuration(position)}"
    }

    private fun renderMarkers(recording: Recording?) {
        markersContainer.removeAllViews()
        val markers = recording?.let { markersFor(it) }.orEmpty()
        if (markers.isEmpty()) {
            markersContainer.addView(TextView(this).apply { text = "暂无标记点" })
            return
        }
        val currentRecording = recording ?: return
        markers.forEach { position ->
            markersContainer.addView(Button(this).apply {
                text = formatDuration(position)
                contentDescription = "跳转到 ${formatDuration(position)}"
                setOnClickListener {
                    if (activeRecording?.uri != currentRecording.uri || player == null) {
                        playRecording(currentRecording)
                    }
                    player?.seekTo(position.toInt())
                    updatePlaybackProgress()
                }
            })
        }
    }

    private fun markersFor(recording: Recording): List<Long> =
        markerPreferences.getStringSet(markerKey(recording), emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .sorted()

    private fun saveMarkers(recording: Recording, markers: List<Long>) {
        markerPreferences.edit()
            .putStringSet(markerKey(recording), markers.map(Long::toString).toSet())
            .apply()
    }

    private fun markerKey(recording: Recording): String = "markers:${recording.uri}"

    private fun clearMarkers(recording: Recording) {
        markerPreferences.edit().remove(markerKey(recording)).apply()
    }

    private fun shareRecording(recording: Recording) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, recording.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享录音"))
    }

    private fun showRecordingActions(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle(recording.name)
            .setItems(arrayOf("播放", "分享", "删除")) { _, which ->
                when (which) {
                    0 -> playRecording(recording)
                    1 -> shareRecording(recording)
                    2 -> confirmDelete(recording)
                }
            }
            .show()
    }

    private fun confirmDelete(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("删除录音？")
            .setMessage(recording.name)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteRecording(recording) }
            .show()
    }

    private fun deleteRecording(recording: Recording) {
        val deleted = contentResolver.delete(recording.uri, null, null)
        if (deleted > 0) {
            if (activeRecording?.uri == recording.uri) stopPlayback()
            clearMarkers(recording)
            statusText.text = "已删除：${recording.name}"
            refreshRecordings()
        } else {
            Toast.makeText(this, "删除失败：文件不存在或无法访问", Toast.LENGTH_LONG).show()
            refreshRecordings()
        }
    }

    private fun hasRecordPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0L) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }
}
