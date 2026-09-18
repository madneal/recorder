package com.neal.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private data class Recording(
        val name: String,
        val uri: Uri,
        val durationMs: Long,
        val recordedAtMs: Long
    )
    private data class Marker(val positionMs: Long, val label: String)
    private data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val fileName: String
    )

    companion object {
        private const val RECORD_AUDIO_REQUEST = 100
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/madneal/recorder/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
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
    private var updateCheckInProgress = false
    private var updateDownloadId = -1L
    private var pendingInstallUri: Uri? = null
    private var updateReceiverRegistered = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != updateDownloadId) return

            val manager = getSystemService(DownloadManager::class.java)
            var successful = false
            manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    successful = statusColumn >= 0 &&
                        cursor.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL
                }
            }
            val downloadedUri = if (successful) manager.getUriForDownloadedFile(downloadId) else null
            updateDownloadId = -1L
            runOnUiThread {
                if (downloadedUri != null) {
                    installUpdate(downloadedUri)
                } else {
                    Toast.makeText(this@MainActivity, "更新下载失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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
        registerUpdateReceiver()
        refreshRecordings()
        handler.post { checkForUpdates(showNoUpdate = false) }
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

    override fun onResume() {
        super.onResume()
        val uri = pendingInstallUri ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingInstallUri = null
            launchInstaller(uri)
        }
    }

    override fun onDestroy() {
        if (updateReceiverRegistered) {
            unregisterReceiver(updateReceiver)
            updateReceiverRegistered = false
        }
        super.onDestroy()
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

        val updateButton = Button(this).apply {
            text = "检查更新"
            setOnClickListener { checkForUpdates(showNoUpdate = true) }
        }
        root.addView(updateButton, LinearLayout.LayoutParams(-1, -2))

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

    private fun registerUpdateReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        updateReceiverRegistered = true
    }

    private fun checkForUpdates(showNoUpdate: Boolean) {
        if (updateCheckInProgress) return
        updateCheckInProgress = true
        Thread {
            val update = runCatching {
                fetchLatestRelease()
            }.getOrNull()?.takeIf { isNewerVersion(it.versionName, BuildConfig.VERSION_NAME) }

            runOnUiThread {
                updateCheckInProgress = false
                if (update != null) {
                    showUpdateDialog(update)
                } else if (showNoUpdate) {
                    Toast.makeText(this, "当前已经是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val connection = (URL(RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Recorder-Android-App")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = JSONObject(body)
            val versionName = release.getString("tag_name").removePrefix("v")
            val assets = release.getJSONArray("assets")
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val fileName = asset.getString("name")
                if (fileName.startsWith("recorder-") && fileName.endsWith(".apk")) {
                    return UpdateInfo(
                        versionName,
                        asset.getString("browser_download_url"),
                        fileName
                    )
                }
            }
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val remoteParts = remote.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val localParts = local.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val count = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until count) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${update.versionName}")
            .setMessage("当前版本 v${BuildConfig.VERSION_NAME}，是否下载并安装更新？")
            .setNegativeButton("稍后", null)
            .setPositiveButton("下载更新") { _, _ -> downloadUpdate(update) }
            .show()
    }

    private fun downloadUpdate(update: UpdateInfo) {
        if (updateDownloadId != -1L) {
            Toast.makeText(this, "更新正在下载", Toast.LENGTH_SHORT).show()
            return
        }
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl)).apply {
            setTitle("录音机 v${update.versionName}")
            setDescription("正在下载更新包")
            setMimeType(APK_MIME_TYPE)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                update.fileName
            )
        }
        updateDownloadId = getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, "已开始下载更新", Toast.LENGTH_SHORT).show()
    }

    private fun installUpdate(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallUri = uri
            Toast.makeText(this, "请允许录音机安装未知来源应用", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        launchInstaller(uri)
    }

    private fun launchInstaller(uri: Uri) {
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
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
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED
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
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += Recording(
                    cursor.getString(nameColumn),
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    cursor.getLong(durationColumn),
                    cursor.getLong(dateAddedColumn) * 1000L
                )
            }
        }
        recordings = result
        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            recordings.map {
                "${it.name}\n时长 ${formatDuration(it.durationMs)} · ${formatRecordedAt(it.recordedAtMs)}"
            }
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
        if (markers.any { kotlin.math.abs(it.positionMs - position) < 500L }) {
            statusText.text = "该位置附近已有标记"
            return
        }

        val input = EditText(this).apply {
            hint = "例如：重点内容"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("添加标记")
            .setMessage("时间：${formatDuration(position)}")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val label = input.text.toString().trim().ifEmpty {
                    "标记 ${formatDuration(position)}"
                }
                markers += Marker(position, label)
                saveMarkers(recording, markers)
                renderMarkers(recording)
                statusText.text = "已添加标记：$label"
            }
            .show()
    }

    private fun renderMarkers(recording: Recording?) {
        markersContainer.removeAllViews()
        val markers = recording?.let { markersFor(it) }.orEmpty()
        if (markers.isEmpty()) {
            markersContainer.addView(TextView(this).apply { text = "暂无标记点" })
            return
        }
        val currentRecording = recording ?: return
        markers.forEach { marker ->
            val time = formatDuration(marker.positionMs)
            markersContainer.addView(Button(this).apply {
                text = "${marker.label}\n$time"
                contentDescription = "${marker.label}，跳转到 $time"
                setOnClickListener {
                    if (activeRecording?.uri != currentRecording.uri || player == null) {
                        playRecording(currentRecording)
                    }
                    player?.seekTo(marker.positionMs.toInt())
                    updatePlaybackProgress()
                }
            })
        }
    }

    private fun markersFor(recording: Recording): List<Marker> {
        val encoded = markerPreferences.getString(markerDataKey(recording), null)
        if (encoded != null) {
            return runCatching {
                val array = JSONArray(encoded)
                val result = mutableListOf<Marker>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val position = item.optLong("positionMs", -1L)
                    if (position >= 0L) {
                        result += Marker(position, item.optString("label"))
                    }
                }
                result.sortedBy { it.positionMs }
            }.getOrDefault(emptyList())
        }

        return runCatching {
            markerPreferences.getStringSet(markerKey(recording), emptySet())
                .orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .map { Marker(it, "") }
                .sortedBy { it.positionMs }
        }.getOrDefault(emptyList())
    }

    private fun saveMarkers(recording: Recording, markers: List<Marker>) {
        val array = JSONArray()
        markers.sortedBy { it.positionMs }.forEach { marker ->
            array.put(
                JSONObject()
                    .put("positionMs", marker.positionMs)
                    .put("label", marker.label)
            )
        }
        markerPreferences.edit()
            .putString(markerDataKey(recording), array.toString())
            .apply()
    }

    private fun markerKey(recording: Recording): String = "markers:${recording.uri}"

    private fun markerDataKey(recording: Recording): String = "marker_data:${recording.uri}"

    private fun clearMarkers(recording: Recording) {
        markerPreferences.edit()
            .remove(markerKey(recording))
            .remove(markerDataKey(recording))
            .apply()
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
            .setItems(arrayOf("播放", "重命名", "分享", "删除")) { _, which ->
                when (which) {
                    0 -> playRecording(recording)
                    1 -> confirmRename(recording)
                    2 -> shareRecording(recording)
                    3 -> confirmDelete(recording)
                }
            }
            .show()
    }

    private fun confirmRename(recording: Recording) {
        val baseName = if (recording.name.endsWith(".m4a", ignoreCase = true)) {
            recording.name.dropLast(4)
        } else {
            recording.name
        }
        val input = EditText(this).apply {
            setText(baseName)
            setSelection(length())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("重命名录音")
            .setMessage("文件格式为 M4A，扩展名会自动保留")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                renameRecording(recording, input.text.toString())
            }
            .show()
    }

    private fun renameRecording(recording: Recording, inputName: String) {
        val trimmedName = inputName.trim()
        if (trimmedName.isEmpty() || trimmedName == "." || trimmedName == ".." ||
            trimmedName.contains('/') || trimmedName.contains('\\')
        ) {
            Toast.makeText(this, "名称不能为空，也不能包含路径分隔符", Toast.LENGTH_LONG).show()
            return
        }
        val newName = if (trimmedName.endsWith(".m4a", ignoreCase = true)) {
            trimmedName
        } else {
            "$trimmedName.m4a"
        }
        if (newName == recording.name) return

        val updated = contentResolver.update(
            recording.uri,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
            },
            null,
            null
        )
        if (updated > 0) {
            val renamed = recording.copy(name = newName)
            if (activeRecording?.uri == recording.uri) {
                activeRecording = renamed
                playbackTitleText.text = newName
            }
            statusText.text = "已重命名：$newName"
            refreshRecordings()
        } else {
            Toast.makeText(this, "重命名失败：文件不存在或无法访问", Toast.LENGTH_LONG).show()
        }
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

    private fun formatRecordedAt(milliseconds: Long): String {
        if (milliseconds <= 0L) return "时间未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(milliseconds))
    }
}
