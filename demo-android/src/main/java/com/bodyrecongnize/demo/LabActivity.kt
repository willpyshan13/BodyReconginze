package com.bodyrecongnize.demo

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bodyrecongnize.demo.lab.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

private data class DeviceVideo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val dateAdded: Long
)

class LabActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: LabStore
    private lateinit var runner: LabRunner
    private lateinit var shell: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var host: FrameLayout
    private lateinit var busyText: TextView
    private lateinit var headerBack: Button
    private lateinit var headerTitle: TextView
    private lateinit var headerStatus: TextView
    private var runs = listOf<LabRun>()
    private var run: LabRun? = null
    private var page = "auto"
    private var sourceReturnPage = "auto"
    private var sourceMode = "device"
    private var deviceVideos = listOf<DeviceVideo>()
    private var loadingVideos = false
    private var selectedDeviceVideo: DeviceVideo? = null
    private var previewTexture: TextureView? = null
    private var previewPoseOverlay: PoseOverlayView? = null
    private var previewPoseRun: LabRun? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var previewButton: Button? = null
    private var previewTime: TextView? = null
    private var previewSeek: SeekBar? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewPrepared = false
    private var previewPlayWhenReady = false
    private var previewDurationMs = 0
    private var previewDragging = false
    private var pendingSourceUri: Uri? = null
    private var analyzedSourceUri: Uri? = null
    private var analyzedSourceRun: LabRun? = null
    private var frameIndex = 0
    private var selectedEvent = 0
    private var selectedSegment = 0
    private var referenceId: String? = null
    private var referenceEventId: String? = null
    private var phase = 50
    private var condition = "中心遮挡"
    @Volatile private var cancelled = false
    @Volatile private var recording = false
    private var busy = false
    private var drawnPage = ""
    private var provider: ProcessCameraProvider? = null
    private var cameraBegan = 0L
    private var cameraId = ""
    private val captured = mutableListOf<LabFrame>()
    private var cameraPreview: PreviewView? = null
    private val chat = mutableListOf<Triple<String, String, String?>>()
    private val green = 0xff215e40.toInt(); private val ink = 0xff19291f.toInt()
    private val paper = 0xfff5f6f2.toInt(); private val muted = 0xff6a776e.toInt(); private val wash = 0xffe8efdf.toInt()
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasVideoPermission()) refreshDeviceVideos()
        draw()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else notice("未获得相机权限，可以使用内置或导入视频。") }
    private val names = listOf("auto" to "自动识别", "compare" to "动作对比", "replay" to "问题回放", "mixed" to "连续动作", "stability" to "稳定性", "chat" to "训练问答")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LabStore(this); runner = LabRunner(this, store)
        runs = store.recent(); run = runs.firstOrNull()
        run?.let { frameIndex = it.events.firstOrNull { e -> e.complete }?.peak ?: 0 }
        window.statusBarColor = paper; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        shell = column().apply { setBackgroundColor(paper); setPadding(0, 0, 0, dp(48)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(6)) }
        headerBack = button("‹ 训练") { handleHeaderBack() }
        header.addView(headerBack, LinearLayout.LayoutParams(-2, -2))
        headerTitle = text("律动 / AI 实验室", 18f)
        header.addView(headerTitle, LinearLayout.LayoutParams(0, -2, 1f))
        headerStatus = text("静音", 12f, muted)
        header.addView(headerStatus); shell.addView(header)
        tabs = column(); shell.addView(tabs)
        host = FrameLayout(this); shell.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(shell); draw()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == "source") {
                    if (busy) cancelSourceAnalysis() else closeVideoSource()
                } else leave()
            }
        })
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun background(color: Int, radius: Int = 16) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Float = 14f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
        if (size >= 20) typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 13f; minHeight = dp(48)
        setTextColor(if (primary) Color.WHITE else ink); background = background(if (primary) green else Color.WHITE, 12)
        stateListAnimator = null; elevation = 0f
        setPadding(dp(12), dp(9), dp(12), dp(9)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun pair(body: LinearLayout, a: View, b: View) {
        body.addView(LinearLayout(this).apply {
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5); topMargin = dp(8) })
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5); topMargin = dp(8) })
        })
    }
    private fun signal(body: LinearLayout, title: String, detail: String) {
        body.addView(column().apply {
            background = background(wash); setPadding(dp(16), dp(8), dp(16), dp(10))
            addView(text(title, 22f, green)); addView(text(detail, 13f, muted))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
    }
    private fun notice(value: String) { AlertDialog.Builder(this).setTitle("AI实验室").setMessage(value).setPositiveButton("知道了", null).show() }
    private fun draw() {
        syncHeader()
        if (busy) return
        val oldScroll = (host.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        val keepScroll = drawnPage == page
        drawnPage = page
        if (page == "source") {
            tabs.visibility = View.GONE
            stopVideoPreview()
            host.removeAllViews()
            host.addView(sourcePage())
            return
        }
        tabs.visibility = View.VISIBLE
        tabs.removeAllViews()
        names.chunked(3).forEach { row -> tabs.addView(LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(5))
            row.forEach { (id, label) -> addView(button(label, page == id) { page = id; draw() }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { marginEnd = dp(4) }) }
        }) }
        host.removeAllViews()
        val body = column().apply { setPadding(dp(22), dp(12), dp(22), dp(24)) }
        if (page == "chat") { host.addView(chatPage()); return }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        host.addView(scroll)
        if (keepScroll) scroll.post { scroll.scrollTo(0, oldScroll) }
        body.addView(text("实际输入 · 本地时序规则实验分类", 11f, muted))
        val r = run
        if (r != null) body.addView(button("记录 ${r.id.take(6)} · ${r.source} · ${r.condition}  ›") { chooseRun() })
        when (page) {
            "auto" -> autoPage(body, r)
            "compare" -> comparePage(body, r)
            "replay" -> replayPage(body, r)
            "mixed" -> mixedPage(body, r)
            "stability" -> stabilityPage(body, r)
        }
    }
    private fun noData(body: LinearLayout) {
        signal(body, "还没有实际分析记录", "先分析视频或摄像头采样。分类、回放、对比和问答将共同引用同一条记录。")
        sourceButtons(body)
    }
    private fun sourceButtons(body: LinearLayout) {
        body.addView(button("分析内置真人深蹲视频", true) { startAnalysis(null) })
        pair(body, button("导入本地视频") { openVideoSource("device") }, button("摄像头采样") { startCamera() })
        body.addView(text("最多分析60秒，每200ms采样一帧；只读取视频画面，不播放声音。结果保留最近5条。", 12f, muted))
    }
    private fun syncHeader() {
        val inSource = page == "source"
        headerBack.text = if (inSource) if (busy) "‹ 视频" else "‹ 实验室" else "‹ 训练"
        headerTitle.text = when {
            inSource && busy -> "正在识别"
            inSource -> "选择视频"
            else -> "律动 / AI 实验室"
        }
        headerStatus.text = if (inSource) "静音 · 本机" else "静音"
        headerBack.setOnClickListener { handleHeaderBack() }
    }
    private fun handleHeaderBack() {
        if (page == "source") {
            if (busy) cancelSourceAnalysis() else closeVideoSource()
        } else leave()
    }
    private fun openVideoSource(mode: String) {
        if (busy) return
        sourceReturnPage = page
        sourceMode = mode
        page = "source"
        if (mode == "device" && hasVideoPermission() && deviceVideos.isEmpty()) refreshDeviceVideos()
        draw()
    }
    private fun closeVideoSource() {
        stopVideoPreview()
        page = sourceReturnPage
        draw()
    }
    private fun cancelSourceAnalysis() {
        cancelled = true
        if (::busyText.isInitialized) busyText.text = "正在停止并清理未完成分析…"
    }
    private fun hasVideoPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 -> ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VIDEO") == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 -> ContextCompat.checkSelfPermission(this, "android.permission.READ_MEDIA_VIDEO") == PackageManager.PERMISSION_GRANTED
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestVideoPermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            Build.VERSION.SDK_INT >= 33 -> arrayOf("android.permission.READ_MEDIA_VIDEO")
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermission.launch(permissions)
    }
    private fun refreshDeviceVideos() {
        loadingVideos = true
        worker.execute {
            val result = queryDeviceVideos()
            runOnUiThread {
                loadingVideos = false
                if (isDestroyed || page != "source") return@runOnUiThread
                deviceVideos = result
                val selectedUri = selectedDeviceVideo?.uri
                selectedDeviceVideo = result.firstOrNull { it.uri == selectedUri }
                    ?: result.firstOrNull { it.name.equals("AI_LAB_SQUAT.mp4", true) }
                    ?: result.firstOrNull()
                draw()
            }
        }
    }
    private fun queryDeviceVideos(): List<DeviceVideo> {
        if (!hasVideoPermission()) return emptyList()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED
        )
        val items = mutableListOf<DeviceVideo>()
        runCatching {
            contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                fun value(name: String) = cursor.getColumnIndex(name)
                val nameColumn = value(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = value(MediaStore.Video.Media.DURATION)
                val sizeColumn = value(MediaStore.Video.Media.SIZE)
                val widthColumn = value(MediaStore.Video.Media.WIDTH)
                val heightColumn = value(MediaStore.Video.Media.HEIGHT)
                val folderColumn = value(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dateColumn = value(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext() && items.size < 80) {
                    val id = cursor.getLong(idColumn)
                    items.add(DeviceVideo(
                        ContentUris.withAppendedId(collection, id),
                        if (nameColumn >= 0) cursor.getString(nameColumn) ?: "未命名视频" else "未命名视频",
                        if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L,
                        if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L,
                        if (widthColumn >= 0) cursor.getInt(widthColumn) else 0,
                        if (heightColumn >= 0) cursor.getInt(heightColumn) else 0,
                        if (folderColumn >= 0) cursor.getString(folderColumn).orEmpty() else "",
                        if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L
                    ))
                }
            }
        }
        return items.sortedWith(compareByDescending<DeviceVideo> { it.name.equals("AI_LAB_SQUAT.mp4", true) }.thenByDescending { it.dateAdded })
    }
    private fun sourcePage(): View {
        val body = column().apply { setPadding(dp(20), dp(4), dp(20), dp(20)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        body.addView(text("选择一段真实视频", 27f))
        body.addView(text("开始识别后会把姿态骨架同步叠加到视频画面；全程静音。返回键会回到实验室。", 13f, muted))
        pair(body,
            button("内置样片", sourceMode == "builtin") { sourceMode = "builtin"; draw() },
            button("本机视频", sourceMode == "device") { sourceMode = "device"; if (hasVideoPermission()) refreshDeviceVideos(); draw() })
        if (sourceMode == "builtin") {
            drawBuiltInSource(body)
        } else {
            drawDeviceSource(body)
        }
        return scroll
    }
    private fun drawBuiltInSource(body: LinearLayout) {
        val uri = Uri.parse("android.resource://$packageName/${R.raw.real_squats_demo}")
        val poseRun = analyzedSourceRun?.takeIf { analyzedSourceUri == uri }
        body.addView(text("真人深蹲 · 内置样片", 19f))
        body.addView(text("31秒 · 深蹲动作 · 视频内置于应用", 12f, muted))
        addPreview(body, uri, "内置真人深蹲视频", poseRun)
        addRecognitionSettings(body)
        poseRun?.let { signal(body, "姿态识别已完成", "${it.frames.size}帧 · 已识别 ${it.completed} 次 · 播放时骨架会叠加在视频上") }
        body.addView(button(if (poseRun == null) "开始识别这段视频" else "重新识别这段视频", true) { startAnalysis(uri) })
    }
    private fun drawDeviceSource(body: LinearLayout) {
        if (!hasVideoPermission()) {
            signal(body, "允许访问本机视频", "只在你点击允许后读取视频名称和预览画面；识别在设备本地运行。")
            body.addView(button("允许读取本机视频", true) { requestVideoPermission() })
            body.addView(button("改用内置真人样片") { sourceMode = "builtin"; draw() })
            return
        }
        if (loadingVideos) {
            signal(body, "正在读取本机视频", "正在从媒体库加载视频名称和时长…")
            return
        }
        if (deviceVideos.isEmpty()) {
            signal(body, "还没有可选的视频", "视频需已保存在手机相册或媒体库中。也可以先用应用内置样片。")
            body.addView(button("重新扫描本机视频") { refreshDeviceVideos() })
            body.addView(button("改用内置真人样片") { sourceMode = "builtin"; draw() })
            return
        }
        val current = selectedDeviceVideo ?: deviceVideos.first()
        selectedDeviceVideo = current
        body.addView(text("本机视频 · ${deviceVideos.size}段", 18f))
        deviceVideos.forEach { video ->
            val selected = video.uri == current.uri
            body.addView(button("${if (selected) "✓ " else ""}${video.name}\n${video.folder.ifBlank { "本机媒体库" }} · ${formatDuration(video.durationMs)} · ${formatSize(video.sizeBytes)}${if (video.width > 0 && video.height > 0) " · ${video.width}×${video.height}" else ""}", selected) {
                if (selectedDeviceVideo?.uri != video.uri) {
                    analyzedSourceUri = null
                    analyzedSourceRun = null
                }
                selectedDeviceVideo = video
                draw()
            })
        }
        body.addView(text("已选择 · ${current.name}", 18f))
        val poseRun = analyzedSourceRun?.takeIf { analyzedSourceUri == current.uri }
        addPreview(body, current.uri, current.name, poseRun)
        addRecognitionSettings(body)
        poseRun?.let { signal(body, "姿态识别已完成", "${it.frames.size}帧 · 已识别 ${it.completed} 次 · 播放时骨架会叠加在视频上") }
        body.addView(button(if (poseRun == null) "开始识别这段视频" else "重新识别这段视频", true) { startAnalysis(current.uri) })
    }
    private fun addPreview(body: LinearLayout, uri: Uri, label: String, poseRun: LabRun? = null) {
        val card = FrameLayout(this).apply { background = background(0xff101712.toInt(), 16) }
        val video = TextureView(this)
        val previewHeight = dp(420)
        card.addView(video, FrameLayout.LayoutParams(-1, -1))
        val placeholder = TextView(this).apply {
            text = if (poseRun == null) "▶\n静音预览 · 点击下方播放" else "正在准备姿态叠加…"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setBackgroundColor(0x66101712)
        }
        card.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        val poseOverlay = PoseOverlayView(this).apply {
            videoOverlay = true
            visibility = if (poseRun == null) View.GONE else View.VISIBLE
        }
        card.addView(poseOverlay, FrameLayout.LayoutParams(-1, -1))
        body.addView(card, LinearLayout.LayoutParams(-1, previewHeight).apply { topMargin = dp(10) })
        previewTexture = video; previewPoseOverlay = poseOverlay; previewPoseRun = poseRun
        previewPrepared = false; previewPlayWhenReady = poseRun != null
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(texture); previewSurface = surface
                val player = MediaPlayer(); previewPlayer = player
                try {
                    player.setDataSource(this@LabActivity, uri)
                    player.setSurface(surface)
                    player.setVolume(0f, 0f)
                    player.setOnPreparedListener { prepared ->
                        prepared.setVolume(0f, 0f)
                        previewDurationMs = prepared.duration.coerceAtLeast(0)
                        previewPrepared = true
                        fitPreviewAspect(video, prepared)
                        previewSeek?.max = previewDurationMs.coerceAtLeast(1)
                        previewTime?.text = "00:00 / ${formatDuration(previewDurationMs.toLong())}"
                        if (previewPlayWhenReady) {
                            placeholder.visibility = View.GONE
                            prepared.setVolume(0f, 0f); prepared.start(); updatePreviewPose(prepared.currentPosition.toLong()); updatePreviewProgress()
                        }
                    }
                    player.setOnCompletionListener {
                        previewButton?.text = if (previewPoseRun == null) "重新播放静音预览" else "重新播放识别叠加"
                        previewHandler.removeCallbacksAndMessages(null)
                        previewTime?.text = "${formatDuration(previewDurationMs.toLong())} / ${formatDuration(previewDurationMs.toLong())}"
                        updatePreviewPose(previewDurationMs.toLong())
                    }
                    player.setOnErrorListener { _, _, _ ->
                        placeholder.text = "当前视频无法预览\n仍可直接开始识别"
                        true
                    }
                    player.prepareAsync()
                } catch (_: Exception) {
                    runCatching { player.release() }
                    previewPlayer = null
                    placeholder.text = "当前视频无法预览\n仍可直接开始识别"
                }
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                previewPlayer?.takeIf { previewPrepared }?.let { fitPreviewAspect(video, it) }
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = true
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val play = button(if (poseRun == null) "播放静音预览" else "播放识别叠加") {
            if (!previewPrepared) { previewPlayWhenReady = true; return@button }
            val player = previewPlayer ?: return@button
            if (player.isPlaying) {
                player.pause(); previewButton?.text = if (previewPoseRun == null) "继续静音预览" else "继续识别叠加"; previewHandler.removeCallbacksAndMessages(null)
            } else {
                placeholder.visibility = View.GONE; player.setVolume(0f, 0f)
                if (player.currentPosition >= previewDurationMs && previewDurationMs > 0) player.seekTo(0)
                player.start(); updatePreviewPose(player.currentPosition.toLong()); previewButton?.text = if (previewPoseRun == null) "暂停预览" else "暂停识别叠加"; updatePreviewProgress()
            }
        }
        previewButton = play
        controls.addView(play, LinearLayout.LayoutParams(dp(154), -2))
        previewTime = text("00:00 / ${formatDuration(previewDurationMs.toLong())}", 12f, muted)
        controls.addView(previewTime, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        body.addView(controls)
        previewSeek = SeekBar(this).apply {
            max = previewDurationMs.coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) previewTime?.text = "${formatDuration(value.toLong())} / ${formatDuration(previewDurationMs.toLong())}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { previewDragging = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    previewDragging = false
                    if (previewPrepared) {
                        previewPlayer?.seekTo(bar?.progress ?: 0)
                        updatePreviewPose((bar?.progress ?: 0).toLong())
                    }
                }
            })
        }
        body.addView(previewSeek)
        body.addView(text("预览：$label", 11f, muted))
    }
    private fun fitPreviewAspect(view: TextureView, player: MediaPlayer) {
        val videoWidth = player.videoWidth.toFloat()
        val videoHeight = player.videoHeight.toFloat()
        val card = view.parent as? FrameLayout ?: return
        val body = card.parent as? LinearLayout ?: return
        if (videoWidth <= 0f || videoHeight <= 0f) return

        val availableWidth = (body.width - body.paddingLeft - body.paddingRight).coerceAtLeast(1)
        val maxHeight = dp(420)
        val scale = minOf(availableWidth / videoWidth, maxHeight / videoHeight)
        val fittedWidth = (videoWidth * scale).toInt().coerceAtLeast(1)
        val fittedHeight = (videoHeight * scale).toInt().coerceAtLeast(1)
        card.layoutParams = LinearLayout.LayoutParams(fittedWidth, fittedHeight).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        }
        card.requestLayout()
        view.post {
            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()
            if (viewWidth <= 0f || viewHeight <= 0f) return@post
            val viewAspect = viewWidth / viewHeight
            val videoAspect = videoWidth / videoHeight
            val scaleX = if (videoAspect < viewAspect) videoAspect / viewAspect else 1f
            val scaleY = if (videoAspect > viewAspect) viewAspect / videoAspect else 1f
            view.setTransform(Matrix().apply { setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f) })
        }
    }
    private fun addRecognitionSettings(body: LinearLayout) {
        body.addView(text("识别设置", 18f))
        signal(body, "自动识别动作", "离线姿态估计 · 每200ms采样一帧 · 最多分析60秒")
        body.addView(text("视频只用于读取画面；预览保持静音。分析结果保存在本机。", 12f, muted))
    }
    private fun updatePreviewProgress() {
        previewHandler.removeCallbacksAndMessages(null)
        var lastUiRefresh = 0L
        val tick = object : Runnable {
            override fun run() {
                val player = previewPlayer ?: return
                if (!player.isPlaying) return
                val position = player.currentPosition
                updatePreviewPose(position.toLong())
                val now = SystemClock.uptimeMillis()
                if (now - lastUiRefresh >= 200) {
                    if (!previewDragging) previewSeek?.progress = position
                    previewTime?.text = "${formatDuration(position.toLong())} / ${formatDuration(previewDurationMs.toLong())}"
                    lastUiRefresh = now
                }
                previewHandler.postDelayed(this, 33)
            }
        }
        previewHandler.post(tick)
        previewButton?.text = if (previewPoseRun == null) "暂停预览" else "暂停识别叠加"
    }
    private fun updatePreviewPose(positionMs: Long) {
        val poseRun = previewPoseRun ?: return
        val overlay = previewPoseOverlay ?: return
        val frames = poseRun.frames
        if (frames.isEmpty()) return
        val sample = LabPosePlayback.sample(frames, positionMs)
        if (sample == null) {
            val first = frames.first()
            val label = if (positionMs >= frames.last().timeMs + 200) "此处超出已分析片段" else "当前时段暂无可靠骨架"
            overlay.showPoseOverlay(first.width, first.height, emptyList(), label)
            return
        }
        val points = (0..32).map { i ->
            val offset = i * 3
            PoseOverlayView.Landmark(i, sample.points[offset] * sample.width,
                sample.points[offset + 1] * sample.height, sample.points[offset + 2])
        }
        overlay.showPoseOverlay(sample.width, sample.height, points, "${LabAnalyzer.time(positionMs)} · 姿态回放")
    }
    private fun stopVideoPreview() {
        previewHandler.removeCallbacksAndMessages(null)
        previewPlayer?.let { player -> runCatching { player.stop() }; runCatching { player.release() } }
        previewSurface?.release()
        previewPoseOverlay?.clearPose()
        previewTexture = null; previewPoseOverlay = null; previewPoseRun = null
        previewPlayer = null; previewSurface = null; previewButton = null; previewTime = null; previewSeek = null
        previewPrepared = false; previewPlayWhenReady = false; previewDurationMs = 0
    }
    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
        else -> "$bytes B"
    }
    private fun autoPage(body: LinearLayout, r: LabRun?) {
        body.addView(text("不用先选动作，\n看看它能不能认出来。", 29f))
        if (r == null) { noData(body); return }
        val index = frameIndex.coerceIn(r.frames.indices)
        val segment = r.segments.firstOrNull { index in it.start..it.end }
        showFrame(body, r, index)
        signal(body, segment?.kind?.label ?: "暂不确定 / 过渡", "当前位置 ${LabAnalyzer.time(r.frames[index].timeMs)} · 离线回溯确认，不是预先指定动作。")
        body.addView(text("本条记录完整计数 ${r.completed} 次", 24f))
        LabKind.values().forEach { kind ->
            val events = r.events.filter { it.complete && it.kind == kind }
            if (events.isNotEmpty()) body.addView(text("${kind.label}：${events.size} 次 · 规则匹配分 %.2f".format(events.map { it.match }.average()), 14f))
        }
        slider(body, "浏览输入时间", index, r.frames.lastIndex) { frameIndex = it; draw() }
        body.addView(text("分类依据完整周期的关节变化和身体方向。规则匹配分不是概率或准确率；未知动作、遮挡、视角变化可能无法分类。", 12f, muted))
        sourceButtons(body)
    }
    private fun event(r: LabRun) = r.events.getOrNull(selectedEvent.coerceIn(0, (r.events.size - 1).coerceAtLeast(0)))
    private fun showFrame(body: LinearLayout, r: LabRun, index: Int, height: Int = 420) {
        val f = r.frames[index.coerceIn(r.frames.indices)]
        val bitmap = BitmapFactory.decodeFile(store.image(r, f).absolutePath)
        if (bitmap == null) { body.addView(text("本地帧已不存在，请重新分析。")); return }
        val view = PoseOverlayView(this)
        view.showPose(bitmap, (0..32).map { i -> PoseOverlayView.Landmark(i, f.points[i * 3] * f.width, f.points[i * 3 + 1] * f.height, f.points[i * 3 + 2]) }, label = "${LabAnalyzer.time(f.timeMs)} · 实际保存帧")
        body.addView(view, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(12) })
        view.post {
            val availableWidth = body.width - body.paddingLeft - body.paddingRight
            if (availableWidth <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return@post
            val scale = minOf(availableWidth.toFloat() / bitmap.width, dp(height).toFloat() / bitmap.height)
            val frameWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val frameHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            view.layoutParams = LinearLayout.LayoutParams(frameWidth, frameHeight).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        }
    }
    private fun slider(body: LinearLayout, caption: String, value: Int, max: Int, change: (Int) -> Unit) {
        body.addView(text(caption, 12f, muted))
        body.addView(SeekBar(this).apply {
            this.max = max.coerceAtLeast(1); progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) {}
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { change(bar!!.progress.coerceAtMost(max)) }
            })
        })
    }
    private fun selectEvent(r: LabRun, completeOnly: Boolean = false) {
        if (r.events.isEmpty()) { notice("没有可追溯的动作事件。未确认类型的片段仍可在连续动作页浏览。"); return }
        val options = r.events.withIndex().filter { !completeOnly || it.value.complete }
        AlertDialog.Builder(this).setTitle("选择动作事件").setItems(options.map { (i, e) ->
            "${i + 1}. ${e.kind.label} · ${status(e)} · ${LabAnalyzer.time(r.frames[e.start].timeMs)}"
        }.toTypedArray()) { _, index -> selectedEvent = options[index].index; frameIndex = options[index].value.peak; draw() }.show()
    }
    private fun status(e: LabEvent) = when (e.status) { "complete" -> "完整动作"; "shallow" -> "幅度未达条件"; "missing" -> "关节点缺失"; "unfinished" -> "未观察到返回"; "discontinuity" -> "剪辑接缝中断"; else -> "未确认计数" }
    private fun replayPage(body: LinearLayout, r: LabRun?) {
        body.addView(text("为什么没算，\n回到那一刻看看。", 29f))
        if (r == null) { noData(body); return }
        val e = event(r)
        if (e == null) {
            signal(body, "没有已确认类别的动作事件", "可以浏览原始采样帧；不会把未知片段解释成某种动作错误。")
            showFrame(body, r, frameIndex.coerceIn(r.frames.indices))
            slider(body, "浏览原始采样帧", frameIndex.coerceIn(r.frames.indices), r.frames.lastIndex) { frameIndex = it; draw() }; return
        }
        body.addView(button("事件 ${r.events.indexOf(e) + 1} · ${e.kind.label} · ${status(e)}  ›") { selectEvent(r) })
        val index = frameIndex.coerceIn(e.start, e.end)
        showFrame(body, r, index)
        body.addView(text("${LabAnalyzer.time(r.frames[e.start].timeMs)}—${LabAnalyzer.time(r.frames[e.end].timeMs)} · 200ms采样回放", 12f, muted))
        slider(body, "片段关键帧 ${index - e.start + 1}/${e.end - e.start + 1}", index - e.start, e.end - e.start) { frameIndex = e.start + it; draw() }
        signal(body, status(e), e.reason)
        pair(body, button("上一关键帧") { frameIndex = (index - 1).coerceAtLeast(e.start); draw() }, button("下一关键帧") { frameIndex = (index + 1).coerceAtMost(e.end); draw() })
        body.addView(button("问教练：这次为什么这样判断？", true) { page = "chat"; draw() })
        body.addView(text("事件ID ${e.id} · 原记录 ${r.id.take(8)}。失败片段的动作类别仅根据邻近已确认动作推断。", 11f, muted))
    }
    private fun angle(frame: LabFrame, kind: LabKind) = when (kind) { LabKind.SQUAT -> frame.knee; LabKind.ARM_RAISE, LabKind.LATERAL -> frame.shoulder; else -> frame.elbow }
    private fun align(e: LabEvent, percentage: Int): Int = if (percentage <= 50) e.start + (e.peak - e.start) * percentage / 50 else e.peak + (e.end - e.peak) * (percentage - 50) / 50
    private fun comparePage(body: LinearLayout, r: LabRun?) {
        body.addView(text("同一动作阶段，\n差别放在一起看。", 29f))
        if (r == null) { noData(body); return }
        val e = event(r)?.takeIf { it.complete } ?: r.events.firstOrNull { it.complete }
        if (e == null) { signal(body, "还没有完整动作", "需要先确认至少一次完整动作，再指定参考。不会生成标准骨架代替实测。"); return }
        body.addView(button("本次：${e.kind.label} · 事件 ${r.events.indexOf(e) + 1}  ›") { selectEvent(r, true) })
        val refRun = runs.firstOrNull { it.id == referenceId } ?: r
        val ref = refRun.events.firstOrNull { it.id == referenceEventId && it.kind == e.kind && it.complete }
            ?: refRun.events.firstOrNull { it.kind == e.kind && it.complete }
            ?: e
        val actualRefRun = if (refRun.events.any { it.id == ref.id }) refRun else r
        body.addView(button("参考：记录 ${actualRefRun.id.take(6)} · 事件 ${actualRefRun.events.indexOf(ref) + 1}  ›") { chooseReference(e.kind) })
        val refIndex = align(ref, phase); val index = align(e, phase)
        body.addView(text("参考动作 · 手动指定", 14f, muted)); showFrame(body, actualRefRun, refIndex, 180)
        body.addView(text("本次动作", 14f, muted)); showFrame(body, r, index, 180)
        slider(body, "阶段 ${phase}% · 起点0 / 峰值50 / 返回100", phase, 100) { phase = it; draw() }
        val a = angle(actualRefRun.frames[refIndex], ref.kind); val b = angle(r.frames[index], e.kind)
        signal(body, if (a != null && b != null) "关节角相差 %.1f°".format(abs(a - b)) else "本阶段关节不清楚",
            "参考 ${a?.toInt() ?: "--"}° / 本次 ${b?.toInt() ?: "--"}°\n往返时间：参考 ${duration(actualRefRun, ref)} / 本次 ${duration(r, e)}")
        body.addView(text(if (actualRefRun.id == r.id && ref.id == e.id) "当前选中了同一次动作，差异为零是预期结果。可分析另一段视频后改选参考。" else "按起点、峰值和返回分段线性对齐，角度比较不受图像缩放影响。参考由用户指定，不表示它是合格标准，差异不等于姿势错误。", 12f, muted))
    }
    private fun duration(r: LabRun, e: LabEvent) = "%.1fs".format((r.frames[e.end].timeMs - r.frames[e.start].timeMs) / 1000f)
    private fun chooseReference(kind: LabKind) {
        val options = runs.flatMap { r -> r.events.filter { it.complete && it.kind == kind }.map { r to it } }
        AlertDialog.Builder(this).setTitle("选择同动作参考（不代表标准）").setItems(options.map { (r, e) ->
            "${r.source} · ${r.condition} · ${r.id.take(5)} · ${LabAnalyzer.time(r.frames[e.start].timeMs)}"
        }.toTypedArray()) { _, i -> referenceId = options[i].first.id; referenceEventId = options[i].second.id; draw() }.show()
    }
    private fun mixedPage(body: LinearLayout, r: LabRun?) {
        body.addView(text("动作变了，\n识别和计数也跟着变。", 29f))
        if (r == null) { noData(body); return }
        body.addView(text("${LabAnalyzer.time(r.durationMs)} · ${r.segments.size}个片段 · 确认${r.completed}次", 14f, muted))
        val selected = r.segments.getOrNull(selectedSegment.coerceIn(0, (r.segments.size - 1).coerceAtLeast(0)))
        if (selected != null) {
            showFrame(body, r, (selected.start + selected.end) / 2, 205)
            signal(body, selected.kind?.label ?: "未知 / 过渡", "${LabAnalyzer.time(r.frames[selected.start].timeMs)}—${LabAnalyzer.time(r.frames[selected.end].timeMs)}")
        }
        val labels = readLabels(r)
        r.segments.forEachIndexed { i, s ->
            val count = r.events.count { it.complete && it.id in s.eventIds }
            val manual = labels.optJSONObject("${s.start}-${s.end}")
            body.addView(button("${i + 1}. ${s.kind?.label ?: "未知/过渡"} · ${if (s.kind == null) "不计数" else "$count 次"}\n${LabAnalyzer.time(r.frames[s.start].timeMs)}—${LabAnalyzer.time(r.frames[s.end].timeMs)}", selectedSegment == i) { selectedSegment = i; draw() })
            body.addView(button(if (manual == null) "添加本段人工标注  ›" else "人工：${manual.optString("kind")} ${manual.optInt("count")}次 / 模型：$count 次  ›") { annotateSegment(r, s) })
        }
        body.addView(text("未知区间保留，类别只在完整周期后回溯确认。逐段人工标注独立保存；没有标注就不报告准确率。", 12f, muted))
        body.addView(button("导入混合动作视频", true) { openVideoSource("device") })
    }
    private fun readLabels(r: LabRun) = runCatching { JSONObject(java.io.File(store.folder(r.id), "labels.json").readText()) }.getOrDefault(JSONObject())
    private fun annotateSegment(r: LabRun, s: LabSegment) {
        val fields = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val kinds = listOf("未知/过渡") + LabKind.values().map { it.label }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@LabActivity, android.R.layout.simple_spinner_dropdown_item, kinds) }
        val old = readLabels(r).optJSONObject("${s.start}-${s.end}")
        spinner.setSelection(kinds.indexOf(old?.optString("kind") ?: (s.kind?.label ?: "未知/过渡")).coerceAtLeast(0))
        val count = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "人工完整次数"; if (old != null) setText(old.optInt("count").toString()) }
        fields.addView(text("人工确认本段类别与完整次数")); fields.addView(spinner); fields.addView(count)
        val dialog = AlertDialog.Builder(this).setTitle("人工标注 · 不修改模型结果").setView(fields).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = count.text.toString().toIntOrNull(); if (n == null || n !in 0..1000) { count.error = "请输入0—1000"; return@setOnClickListener }
            val labels = readLabels(r).put("${s.start}-${s.end}", JSONObject().put("kind", spinner.selectedItem.toString()).put("count", n))
            java.io.File(store.folder(r.id), "labels.json").writeText(labels.toString()); dialog.dismiss(); draw()
        } }; dialog.show()
    }
    private fun stabilityPage(body: LinearLayout, r: LabRun?) {
        body.addView(text("换个条件，\n结果还稳不稳？", 29f))
        if (r == null) { noData(body); return }
        signal(body, "当前条件 · ${r.condition}", "记录 ${r.id.take(8)} · 输入 ${r.frames.size} 帧 · 实际分析结果")
        val expected = r.expected
        val visible = r.frames.count { frame -> when (expected) {
            LabKind.CURL -> intArrayOf(11,12,13,14,15,16).all { frame.valid(it) }
            LabKind.ARM_RAISE -> intArrayOf(11,12,13,14,15,16,23,24).all { frame.valid(it) }
            LabKind.PUSH_UP -> intArrayOf(11,13,15,23).all { frame.valid(it) } || intArrayOf(12,14,16,24).all { frame.valid(it) }
            LabKind.LATERAL -> intArrayOf(11,12,13,14,23,24).all { frame.valid(it) }
            else -> intArrayOf(23,24,25,26,27,28).all { frame.valid(it) }
        } }
        val counts = if (expected == null) r.completed else r.events.count { it.complete && it.kind == expected }
        body.addView(text("人工完整次数：${r.truth ?: "未标注"} / 模型：$counts 次", 17f))
        body.addView(text("评估动作：${expected?.label ?: "全部类别（关节点覆盖暂按髋膝踝）"}", 12f, muted))
        if (r.truth != null) body.addView(text("计数差 ${counts - r.truth} 次（不是准确率）", 15f, green))
        body.addView(text("所需关节点可见：$visible / ${r.frames.size} 帧\n未知/过渡：${r.unknownFrames} / ${r.frames.size} 帧\n总处理耗时：${r.elapsedMs}ms\n每帧姿态推理均值：%.1fms".format(r.frames.map { it.inferenceMs }.average()), 15f))
        body.addView(button("标注当前样片实际动作和次数  ›") { annotateTruth(r) })
        body.addView(text("在同一组实际保存帧上重新运行识别", 17f))
        listOf("原片重测", "中心遮挡", "低分辨率", "降低亮度").chunked(2).forEach { options ->
            pair(body, button(options[0], condition == options[0]) { condition = options[0]; draw() }, button(options[1], condition == options[1]) { condition = options[1]; draw() })
        }
        body.addView(button("短时遮挡 · 仅2.6—3.4秒", condition == "短时遮挡") { condition = "短时遮挡"; draw() })
        body.addView(button("运行：$condition", true) { runTest(r) })
        val baseline = runs.firstOrNull { it.id == r.baselineId }
        if (baseline != null) body.addView(text("对照原记录 ${baseline.id.take(8)}：${baseline.completed} 次 → 当前 ${r.completed} 次。时间戳一致；变换条件已写入记录。", 13f, green))
        body.addView(text("遮挡为中部黑块，短时遮挡仅作用于2.6—3.4秒，亮度为原片30%，低分辨率为宽高各1/4。这些是合成压力测试，不代表真实拍摄视角。正面/侧面或远距离测试请导入对应实拍视频，单张视频不能生成真实新视角。", 12f, muted))
        body.addView(button("导入其他角度的实拍视频") { openVideoSource("device") })
    }
    private fun annotateTruth(r: LabRun) {
        val fields = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val options = listOf("全部类别") + LabKind.values().map { it.label }
        val kind = Spinner(this).apply { adapter = ArrayAdapter(this@LabActivity, android.R.layout.simple_spinner_dropdown_item, options); setSelection((r.expected?.ordinal?.plus(1)) ?: 0) }
        val count = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "人工完整次数"; if (r.truth != null) setText(r.truth.toString()) }
        fields.addView(text("请观看原片后标注；不会覆盖模型结果。")); fields.addView(kind); fields.addView(count)
        val dialog = AlertDialog.Builder(this).setTitle("人工标注").setView(fields).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = count.text.toString().toIntOrNull(); if (n == null || n !in 0..1000) { count.error = "请输入0—1000"; return@setOnClickListener }
            val changed = r.copy(truth = n, expected = LabKind.values().getOrNull(kind.selectedItemPosition - 1))
            store.save(changed); run = changed; runs = store.recent(); dialog.dismiss(); draw()
        } }; dialog.show()
    }
    private fun chatPage(): View {
        val body = column().apply { setPadding(dp(22), dp(12), dp(22), dp(16)) }
        body.addView(text("回答，能找到对应依据。", 26f)); body.addView(text("本地预设与规则 · 不接大模型", 12f, muted))
        val r = run
        body.addView(button(if (r == null) "未选择分析记录" else "记录 ${r.id.take(6)} · ${r.source}  ›") { chooseRun() })
        if (r != null) body.addView(button("关联事件：${event(r)?.let { "${r.events.indexOf(it) + 1}. ${it.kind.label} · ${status(it)}" } ?: "暂无事件"}  ›") { selectEvent(r) })
        val messages = column(); val scroll = ScrollView(this).apply { addView(messages) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun drawMessages() {
            messages.removeAllViews()
            chat.forEach { (who, message, eventId) ->
                val bubble = column().apply { background = background(if (who == "你") wash else Color.WHITE); setPadding(dp(13), dp(8), dp(13), dp(8)); contentDescription = "$who：$message" }
                bubble.addView(text(message, 15f))
                if (eventId != null && r != null) bubble.addView(button("关联证据 · 查看该次片段  ›") {
                    val index = r.events.indexOfFirst { it.id == eventId }
                    if (index >= 0) { selectedEvent = index; frameIndex = r.events[index].peak; page = "replay"; draw() }
                })
                messages.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); if (who == "你") marginStart = dp(35) else marginEnd = dp(20) })
            }
            if (chat.isEmpty()) messages.addView(text(if (r == null) "先分析一段实际输入，再针对记录提问。" else "可以问“这次为什么这样判断”或“第2次为什么没算”。解释将关联到保存的原始帧。", 15f, muted))
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val input = EditText(this).apply { hint = "围绕本次记录提问"; textSize = 16f; setTextColor(ink); maxLines = 3 }
        fun send(question: String) {
            if (question.isBlank()) return
            val ordinal = Regex("第\\s*(\\d+)\\s*次").find(question)?.groupValues?.get(1)?.toIntOrNull()
            val chosen = if (ordinal != null) r?.events?.getOrNull(ordinal - 1) else r?.let { event(it) }
            val answer = if (ordinal != null && chosen == null) "当前记录没有第${ordinal}个动作事件，无法给出该次解释。" else LabAnalyzer.answer(question, chosen, r)
            chat.add(Triple("你", question.take(500), null)); chat.add(Triple("教练", answer, chosen?.id))
            while (chat.size > 40) chat.removeAt(0)
            input.text.clear(); drawMessages()
        }
        pair(body, button("这次为什么这样判断？") { send("这次为什么这样判断？") }, button("确认了几次？") { send("确认了几次？") })
        val compose = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        compose.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); compose.addView(button("发送", true) { send(input.text.toString()) }, LinearLayout.LayoutParams(dp(70), -2))
        body.addView(compose); drawMessages(); return body
    }
    private fun chooseRun() {
        runs = store.recent()
        if (runs.isEmpty()) { page = "auto"; draw(); return }
        AlertDialog.Builder(this).setTitle("选择真实分析记录（最近5条）").setItems(runs.map { "${it.id.take(6)} · ${it.source} · ${it.condition} · ${it.completed}次" }.toTypedArray()) { _, i ->
            run = runs[i]; selectedEvent = 0; selectedSegment = 0; frameIndex = run!!.events.firstOrNull()?.peak ?: 0; chat.clear(); draw()
        }.show()
    }
    private fun startBusy(message: String) {
        busy = true; cancelled = false; syncHeader(); tabs.visibility = View.GONE; host.removeAllViews()
        val body = column().apply { setPadding(dp(24), dp(20), dp(24), dp(20)) }
        body.addView(text("正在分析真实输入", 27f)); busyText = text(message, 16f, green); body.addView(busyText)
        body.addView(ProgressBar(this)); body.addView(text("首次姿态推理可能稍慢。只保存实际观测，不生成演示结果。", 13f, muted))
        body.addView(button(if (page == "source") "取消识别并返回视频选择" else "取消本次分析") {
            cancelled = true; busyText.text = "正在停止并清理未完成分析…"; if (recording) stopCamera()
        })
        host.addView(body)
    }
    private fun startAnalysis(uri: Uri?) {
        if (busy) return
        pendingSourceUri = if (page == "source") uri else null
        stopVideoPreview()
        startBusy("准备视频帧…")
        worker.execute { try {
            val result = runner.analyze(uri, { cancelled }, { message -> runOnUiThread { if (!isDestroyed) busyText.text = message } })
            completed(result)
        } catch (e: Exception) { failed(e) } }
    }
    private fun runTest(r: LabRun) {
        if (busy) return
        val selected = condition; startBusy("$selected · 准备相同输入帧…")
        worker.execute { try {
            val result = runner.test(r, selected, { cancelled }, { message -> runOnUiThread { if (!isDestroyed) busyText.text = message } })
            completed(result)
        } catch (e: Exception) { failed(e) } }
    }
    private fun completed(result: LabRun) { runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        run = result; runs = store.recent(); busy = false
        if (page == "source") {
            analyzedSourceUri = pendingSourceUri
            analyzedSourceRun = result
            pendingSourceUri = null
        } else {
            pendingSourceUri = null
        }
        tabs.visibility = if (page == "source") View.GONE else View.VISIBLE; syncHeader()
        selectedEvent = 0; selectedSegment = 0; frameIndex = result.events.firstOrNull { it.complete }?.peak ?: 0; chat.clear(); draw()
    } }
    private fun failed(error: Exception) { runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        pendingSourceUri = null
        busy = false; tabs.visibility = if (page == "source") View.GONE else View.VISIBLE; syncHeader(); draw()
        if (error !is InterruptedException && !cancelled) notice("分析未完成：${error.message}")
    } }
    private fun startCamera() {
        if (busy) return
        if ((getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.isEmpty()) { notice("设备未检测到摄像头。可先用内置视频或导入视频验证。"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { cameraPermission.launch(Manifest.permission.CAMERA); return }
        startBusy("正在连接摄像头…"); recording = true; cameraId = UUID.randomUUID().toString(); cameraBegan = SystemClock.elapsedRealtime(); captured.clear()
        val body = host.getChildAt(0) as LinearLayout
        cameraPreview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
        body.addView(cameraPreview, 2, LinearLayout.LayoutParams(-1, dp(300)))
        body.addView(button("停止采样并分析", true) { stopCamera() })
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || !recording) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                var last = -200L
                val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(480, 640)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { image ->
                    try {
                        val timestamp = SystemClock.elapsedRealtime() - cameraBegan
                        if (recording && !cancelled && timestamp - last >= 200) {
                            last = timestamp
                            val media = image.image ?: return@setAnalyzer
                            val raw = LabVideoReader.imageBitmap(media)
                            val rotation = image.imageInfo.rotationDegrees
                            val bitmap = if (rotation != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== raw) raw.recycle() } else raw
                            runner.collect(cameraId, bitmap, timestamp, captured)
                            runOnUiThread { if (!isDestroyed && recording) busyText.text = "已保存${captured.size}帧 · ${LabAnalyzer.time(timestamp)}（最多60秒）" }
                            if (timestamp >= 59800 || captured.size >= 300) runOnUiThread { stopCamera() }
                        }
                    } catch (e: Exception) { runOnUiThread { cancelled = true; stopCamera(); notice("摄像头采样失败：${e.message}") } }
                    finally { image.close() }
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(cameraPreview!!.surfaceProvider) }
                cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) { cancelled = true; stopCamera(); notice("摄像头启动失败：${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun stopCamera() {
        if (!recording) return
        recording = false; provider?.unbindAll()
        worker.execute { try {
            if (cancelled || captured.isEmpty()) { store.folder(cameraId).deleteRecursively(); throw InterruptedException() }
            completed(runner.finish(cameraId, "摄像头采样", "原片", captured.toList(), cameraBegan))
        } catch (e: Exception) { failed(e) } }
    }
    private fun leave() { cancelled = true; if (recording) stopCamera(); finish() }
    override fun onStop() { if (recording) stopCamera(); super.onStop() }
    override fun onDestroy() {
        cancelled = true; provider?.unbindAll(); recording = false
        worker.execute { runner.close() }; worker.shutdown(); super.onDestroy()
    }
}
