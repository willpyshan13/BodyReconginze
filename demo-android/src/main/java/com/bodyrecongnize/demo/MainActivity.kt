package com.bodyrecongnize.demo

import android.Manifest
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bodyrecongnize.BodySession
import com.bodyrecongnize.demo.coach.BundledSquatFrames
import com.bodyrecongnize.demo.pose.RtmposeDetector
import com.bodyrecongnize.demo.pose.MoveNetDetector
import com.bodyrecongnize.demo.pose.VideoPoseMode
import com.bodyrecongnize.demo.pose.VideoPoseEngine
import com.bodyrecongnize.demo.pose.InferenceTimings
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.android.gms.tasks.Tasks
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val benchmarkExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionLock = Any()

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    @Volatile private var videoGeneration = 0
    private var activeVideoUri: Uri? = null
    private lateinit var videoEngineSpinner: Spinner
    private var rtmposeDetector: RtmposeDetector? = null
    private var moveNetDetector: MoveNetDetector? = null
    private lateinit var mainScrollView: ScrollView
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView
    private lateinit var exerciseSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var benchmarkView: TextView
    private lateinit var photoPicker: ActivityResultLauncher<String>
    private lateinit var videoPicker: ActivityResultLauncher<String>
    private val exercises = BodySession.Exercise.values().toList()
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var poseDetector: PoseDetector
    private var bodySession = BodySession(BodySession.Exercise.SQUAT)
    @Volatile private var cameraStarted = false

    private data class PoseBenchmarkOutput(
        val report: String,
        val image: Bitmap?,
        val landmarks: List<PoseOverlayView.Landmark>
    )

    private data class VideoAnalysisOutput(
        val report: String,
        val image: Bitmap?,
        val landmarks: List<PoseOverlayView.Landmark>
    )

    private data class BundledPoseSample(
        val title: String,
        val drawableId: Int,
        val exercise: BodySession.Exercise
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                benchmarkView.text = "已取消照片测试"
            } else {
                runPoseBenchmark(uri)
            }
        }
        videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                benchmarkView.text = "已取消视频测试"
            } else {
                runVideoActionRecognition(uri)
            }
        }
        makeUi()
        poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .setPreferredHardwareConfigs(PoseDetectorOptions.CPU)
                .build()
        )
        startCameraWhenReady()
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xff10141b.toInt())
        }
        val title = TextView(this).apply {
            text = "肢体动作识别演示"
            textSize = 22f
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "姿态模型已内置，请允许相机权限"
            textSize = 14f
            setTextColor(0xffb9c5d5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        poseOverlayView = PoseOverlayView(this).apply { visibility = View.GONE }
        val previewContainer = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(poseOverlayView, FrameLayout.LayoutParams(-1, -1))
        }
        exerciseSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, exercises.map { it.label })
            setSelection(0)
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    synchronized(sessionLock) {
                        bodySession.close()
                        bodySession = BodySession(exercises[position])
                    }
                    resultView.text = "次数 0     当前分数 --\n完成一组完整动作后显示评分"
                }
            })
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(exerciseSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        val resetButton = Button(this).apply {
            text = "重置计数"
            setOnClickListener {
                synchronized(sessionLock) { bodySession.reset() }
                resultView.text = "次数 0     当前分数 --\n已重置"
            }
        }
        controls.addView(resetButton)
        resultView = TextView(this).apply {
            text = "次数 0     当前分数 --\n等待人体进入画面"
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        startButton = Button(this).apply {
            text = "开始相机"
            setOnClickListener {
                if (cameraStarted) stopCamera() else startCameraWhenReady()
            }
        }
        val benchmarkButton = Button(this).apply {
            text = "主板性能测试"
            setOnClickListener { runBoardBenchmark() }
        }
        val poseBenchmarkButton = Button(this).apply {
            text = "选择内置动作样例"
            setOnClickListener { chooseBundledPoseSample() }
        }
        val photoBenchmarkButton = Button(this).apply {
            text = "选照片估分并测速"
            setOnClickListener {
                if (cameraStarted) stopCamera()
                photoPicker.launch("image/*")
            }
        }
        val realVideoButton = Button(this).apply {
            text = "播放真人连续动作视频"
            setOnClickListener {
                runVideoActionRecognition(Uri.parse("android.resource://$packageName/${R.raw.real_squats_demo}"))
            }
        }
        val bundledVideoButton = Button(this).apply {
            text = "播放60秒随机动作视频"
            setOnClickListener {
                runVideoActionRecognition(Uri.parse("android.resource://$packageName/${R.raw.five_actions_demo}"))
            }
        }
        val videoBenchmarkButton = Button(this).apply {
            text = "选择视频并显示肢体"
            setOnClickListener {
                if (cameraStarted) stopCamera()
                videoPicker.launch("video/*")
            }
        }
        benchmarkView = TextView(this).apply {
            text = "点击“主板性能测试”查看 CPU、内存和 C++ 评分速度。\n可从内置样例或自己的照片查看单帧姿态，或播放视频并直接查看肢体骨架。"
            textSize = 14f
            setTextColor(0xffb9c5d5.toInt())
            setPadding(8, 12, 8, 12)
        }
        val videoEngineLabel = TextView(this).apply {
            text = "视频识别引擎 · 请让单人全身入镜"
            setTextColor(0xffb9c5d5.toInt())
        }
        videoEngineSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                VideoPoseMode.values().map { it.label })
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.setTextColor(0xffffffff.toInt())
                    activeVideoUri?.let { runVideoActionRecognition(it) }
                }
            })
        }
        val displayPreferences = getSharedPreferences("skeleton_display", Context.MODE_PRIVATE)
        val skeletonSwitches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mlKitSwitch = CheckBox(this).apply {
            text = "ML Kit · 绿"
            setTextColor(0xff59f28c.toInt())
            isChecked = displayPreferences.getBoolean("mlKit", true)
        }
        val rtmposeSwitch = CheckBox(this).apply {
            text = "RTMPose · 蓝"
            setTextColor(0xff40c9ff.toInt())
            isChecked = displayPreferences.getBoolean("rtmpose", true)
        }
        val moveNetSwitch = CheckBox(this).apply {
            text = "MoveNet · 紫"
            setTextColor(0xffed77ff.toInt())
            isChecked = displayPreferences.getBoolean("moveNet", true)
        }
        fun updateSkeletonVisibility() {
            poseOverlayView.setSkeletonVisibility(
                mlKitSwitch.isChecked, rtmposeSwitch.isChecked, moveNetSwitch.isChecked)
            displayPreferences.edit()
                .putBoolean("mlKit", mlKitSwitch.isChecked)
                .putBoolean("rtmpose", rtmposeSwitch.isChecked)
                .putBoolean("moveNet", moveNetSwitch.isChecked)
                .apply()
        }
        listOf(mlKitSwitch, rtmposeSwitch, moveNetSwitch).forEach { toggle ->
            toggle.textSize = 13f
            toggle.setOnCheckedChangeListener { _, _ -> updateSkeletonVisibility() }
            skeletonSwitches.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
        }
        updateSkeletonVisibility()
        root.addView(title)
        root.addView(Button(this).apply {
            text = "返回律动训练首页"
            setOnClickListener { startActivity(Intent(this@MainActivity, CoachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        })
        root.addView(statusView)
        root.addView(previewContainer, LinearLayout.LayoutParams(
            -1, (resources.displayMetrics.heightPixels * 0.55f).toInt()
        ))
        root.addView(skeletonSwitches)
        root.addView(controls)
        root.addView(resultView)
        root.addView(startButton)
        root.addView(benchmarkButton)
        root.addView(poseBenchmarkButton)
        root.addView(photoBenchmarkButton)
        root.addView(videoEngineLabel)
        root.addView(videoEngineSpinner)
        root.addView(realVideoButton)
        root.addView(bundledVideoButton)
        root.addView(videoBenchmarkButton)
        root.addView(benchmarkView)
        mainScrollView = ScrollView(this).apply { addView(root) }
        setContentView(mainScrollView)
    }

    private fun runBoardBenchmark() {
        if (cameraStarted) stopCamera()
        benchmarkView.text = "正在测量设备信息和动作评分速度…"
        benchmarkExecutor.execute {
            val device = readDeviceSummary()
            val scoringLines = exercises.joinToString("\n") { exercise ->
                val micros = benchmarkScoring(exercise)
                "${exercise.label}：${format(micros)} ms/帧（约 ${format(1000.0 / micros)} 帧/秒）"
            }
            val report = "$device\n\nC++ 动作评分（每帧耗时，中位数）：\n$scoringLines\n" +
                "当前进程内存：${Debug.getPss() / 1024} MB\n\n" +
                "要测完整姿态模型，请再选一张光线充足、全身清晰可见的照片。"
            mainHandler.post { benchmarkView.text = report }
        }
    }

    private fun benchmarkScoring(exercise: BodySession.Exercise): Double {
        val landmarks = syntheticLandmarks()
        val rounds = DoubleArray(5)
        repeat(rounds.size) { round ->
            BodySession(exercise).use { session ->
                var timestamp = 1_000L
                repeat(100) { session.process(landmarks, 640, 480, timestamp.also { timestamp += 33 }) }
                val start = System.nanoTime()
                repeat(2_000) { session.process(landmarks, 640, 480, timestamp.also { timestamp += 33 }) }
                rounds[round] = (System.nanoTime() - start) / 2_000_000.0
            }
        }
        rounds.sort()
        return rounds[rounds.size / 2]
    }

    private fun syntheticLandmarks(): FloatArray {
        val points = FloatArray(99)
        for (index in 0 until 33) {
            points[index * 3] = 0.5f
            points[index * 3 + 1] = 0.5f
            points[index * 3 + 2] = 0.95f
        }
        fun point(index: Int, x: Float, y: Float) {
            points[index * 3] = x
            points[index * 3 + 1] = y
        }
        point(11, 0.38f, 0.28f); point(12, 0.62f, 0.28f)
        point(13, 0.27f, 0.43f); point(14, 0.73f, 0.43f)
        point(15, 0.20f, 0.58f); point(16, 0.80f, 0.58f)
        point(23, 0.42f, 0.50f); point(24, 0.58f, 0.50f)
        point(25, 0.40f, 0.69f); point(26, 0.60f, 0.69f)
        point(27, 0.36f, 0.88f); point(28, 0.64f, 0.88f)
        return points
    }

    private fun runPoseBenchmark(uri: Uri) {
        benchmarkView.text = "正在读取照片并运行姿态模型…"
        clearPosePreview()
        val exercise = selectedExercise()
        benchmarkExecutor.execute {
            var bitmap: Bitmap? = null
            val output = try {
                bitmap = decodeBenchmarkImage(uri)
                benchmarkDetector(bitmap!!, exercise)
            } catch (error: Exception) {
                PoseBenchmarkOutput(
                    "照片测试失败：${error.cause?.message ?: error.message ?: "无法读取照片"}",
                    bitmap?.copy(Bitmap.Config.ARGB_8888, false), emptyList()
                )
            } finally {
                bitmap?.recycle()
            }
            mainHandler.post { showPoseBenchmarkOutput(output) }
        }
    }

    private fun runVideoActionRecognition(uri: Uri) {
        if (cameraStarted) stopCamera()
        clearPosePreview()
        val generation = videoGeneration
        activeVideoUri = uri
        val mode = VideoPoseMode.values()[videoEngineSpinner.selectedItemPosition]
        val engineLabel = mode.label
        previewView.visibility = View.GONE
        poseOverlayView.visibility = View.VISIBLE
        mainScrollView.smoothScrollTo(0, 0)
        statusView.text = "正在准备 $engineLabel"
        resultView.text = "视频姿态识别\n绿色 ML Kit · 蓝色 RTMPose · 紫色 MoveNet"
        benchmarkView.text = "初始化模型…所有推理均在本机 CPU 完成。"
        val timings = mode.engines.associateWith { InferenceTimings() }
        fun latencyReport() = buildString {
            mode.engines.forEach { append("${it.title}：${timings.getValue(it).summary()}\n") }
            append("耗时含各模型前处理、推理和关键点解码；不含读帧、绘制和前3帧预热。\n")
            append("播放仍每200ms取一帧，设备较慢时减速。置信度阈值不同，骨架差异不代表准确率。")
        }
        benchmarkExecutor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                cameraExecutor.submit {}.get()
                if (VideoPoseEngine.RTMPOSE in mode.engines && rtmposeDetector == null) {
                    rtmposeDetector = RtmposeDetector(this)
                }
                if (VideoPoseEngine.MOVENET in mode.engines && moveNetDetector == null) {
                    moveNetDetector = MoveNetDetector(this)
                }
                if (generation != videoGeneration) return@execute
                val bundledFrames = if (uri.scheme == "android.resource" &&
                    uri.lastPathSegment == R.raw.real_squats_demo.toString()) BundledSquatFrames(this) else null
                val source = if (uri.scheme == "android.resource") {
                    val file = java.io.File(cacheDir, "video_${uri.lastPathSegment}.mp4")
                    requireNotNull(contentResolver.openInputStream(uri)) { "无法打开内置视频" }.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    Uri.fromFile(file)
                } else uri
                if (bundledFrames == null) retriever.setDataSource(this, source)
                val duration = bundledFrames?.durationMs ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: error("无法读取视频时长")
                val limit = duration.coerceAtMost(MAX_VIDEO_ANALYSIS_MS)
                var timeMs = 0L
                var frameNumber = 0
                val latencyFrames = org.json.JSONArray()
                while (timeMs < limit && generation == videoGeneration && !Thread.currentThread().isInterrupted) {
                    val started = SystemClock.elapsedRealtime()
                    val frame = bundledFrames?.frame((timeMs / VIDEO_FRAME_INTERVAL_MS).toInt())
                        ?: retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?.let { scaleBitmapToLongSide(it, MAX_VIDEO_FRAME_SIDE) }
                    if (frame != null) {
                        val poses = mutableMapOf<VideoPoseEngine, List<PoseOverlayView.Landmark>>()
                        val milliseconds = mutableMapOf<VideoPoseEngine, Double>()
                        try {
                            for (engine in mode.executionOrder(frameNumber)) {
                                val begin = SystemClock.elapsedRealtimeNanos()
                                poses[engine] = when (engine) {
                                    VideoPoseEngine.ML_KIT -> poseOverlayLandmarks(Tasks.await(
                                        poseDetector.process(InputImage.fromBitmap(frame, 0))))
                                    VideoPoseEngine.RTMPOSE -> rtmposeDetector!!.detect(frame)
                                    VideoPoseEngine.MOVENET -> moveNetDetector!!.detect(frame)
                                }
                                val elapsed = (SystemClock.elapsedRealtimeNanos() - begin) / 1_000_000.0
                                milliseconds[engine] = elapsed
                                timings.getValue(engine).record(elapsed)
                            }
                        } catch (error: Exception) { frame.recycle(); throw error }
                        val mlPoints = poses[VideoPoseEngine.ML_KIT].orEmpty()
                        val rtPoints = poses[VideoPoseEngine.RTMPOSE].orEmpty()
                        val movePoints = poses[VideoPoseEngine.MOVENET].orEmpty()
                        latencyFrames.put(org.json.JSONObject().apply {
                            put("timestampMs", timeMs)
                            put("warmup", frameNumber < 3)
                            put("imageWidth", frame.width)
                            put("imageHeight", frame.height)
                            put("executionOrder", org.json.JSONArray(mode.executionOrder(frameNumber).map { it.name }))
                            for (engine in mode.engines) {
                                val points = poses.getValue(engine)
                                val joints = org.json.JSONArray().apply {
                                    points.filter { it.index in COMMON_BODY_INDICES && it.x.isFinite() &&
                                        it.y.isFinite() && it.confidence.isFinite() }.forEach { joint ->
                                        put(org.json.JSONArray(listOf(joint.index, joint.x / frame.width,
                                            joint.y / frame.height, joint.confidence)))
                                    }
                                }
                                put("${engine.recordPrefix}Joints", joints)
                                put("${engine.recordPrefix}Ms", milliseconds.getValue(engine))
                                val threshold = if (engine == VideoPoseEngine.ML_KIT) .5f else .3f
                                put("${engine.recordPrefix}VisibleBodyPoints", points.count {
                                    it.index in COMMON_BODY_INDICES && it.confidence >= threshold
                                })
                            }
                        })
                        frameNumber++
                        val report = latencyReport()
                        val shown = java.util.concurrent.CountDownLatch(1)
                        val position = timeMs
                        mainHandler.post {
                            try {
                                if (generation == videoGeneration && !isFinishing && !isDestroyed) {
                                    poseOverlayView.showPose(frame, mlPoints, rtPoints, engineLabel, movePoints)
                                    benchmarkView.text = report
                                    statusView.text = "肢体识别播放 ${formatVideoTime(position)} / ${formatVideoTime(limit)} · $engineLabel"
                                } else frame.recycle()
                            } finally { shown.countDown() }
                        }
                        // Keep at most one pending frame, with its matching landmarks.
                        shown.await()
                    }
                    val remaining = VIDEO_FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - started)
                    if (remaining > 0) Thread.sleep(remaining)
                    timeMs += VIDEO_FRAME_INTERVAL_MS
                }
                if (generation == videoGeneration) {
                    val savedReport = org.json.JSONObject().apply {
                        put("engine", engineLabel)
                        put("source", uri.toString())
                        put("frameIntervalMs", VIDEO_FRAME_INTERVAL_MS)
                        put("frames", latencyFrames)
                        put("summary", latencyReport())
                        put("rtmposeRoi", "whole-frame padded 1.25; single person only; no person detector")
                        put("moveNetRoi", "whole-frame square letterbox; no temporal crop tracking")
                        put("moveNetRuntime", "TensorFlow Lite 2.10.0 CPU/XNNPACK, 2 threads")
                    }
                    java.io.File(filesDir, "video_comparison.json").writeText(savedReport.toString(2))
                }
                mainHandler.post {
                    if (generation == videoGeneration && !isDestroyed) {
                        statusView.text = "肢体识别播放结束 · 点击视频按钮可重播"
                        benchmarkView.text = latencyReport() + if (duration > limit) "\n已播放前60秒。" else ""
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Video pose comparison failed in ${mode.name}", error)
                mainHandler.post {
                    if (generation == videoGeneration && !isDestroyed) statusView.text = "视频肢体识别失败：${error.message}"
                }
            } catch (error: LinkageError) {
                mainHandler.post {
                    if (generation == videoGeneration && !isDestroyed) {
                        statusView.text = "当前设备无法加载所选模型，请切换其他识别引擎"
                        benchmarkView.text = "模型运行库加载失败：${error.message}"
                    }
                }
            } finally { retriever.release() }
        }
    }

    private fun analyzeVideoActions(uri: Uri): VideoAnalysisOutput {
        val retriever = MediaMetadataRetriever()
        val sessions = exercises.associateWith { BodySession(it) }
        var previewBitmap: Bitmap? = null
        var previewLandmarks: List<PoseOverlayView.Landmark> = emptyList()
        try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()?.coerceAtLeast(1L) ?: error("视频时长读取失败")
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
            val analyzedDurationMs = durationMs.coerceAtMost(MAX_VIDEO_ANALYSIS_MS)
            val sampleCount = ((analyzedDurationMs + VIDEO_FRAME_INTERVAL_MS - 1) /
                VIDEO_FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)

            val counts = exercises.associateWith { 0 }.toMutableMap()
            val events = mutableListOf<String>()
            var poseFrames = 0
            var lastSquatRepetitions = 0
            var lastArmCycleRepetitions = 0
            var lastElbowCycleRepetitions = 0
            var seenArmRaiseRepetitions = 0
            var seenLateralRaiseRepetitions = 0
            var seenBicepCurlRepetitions = 0
            var seenPushUpRepetitions = 0
            var armPeakAngle = 0f
            var elbowTorsoTiltTotal = 0.0
            var elbowTorsoTiltFrames = 0

            fun record(exercise: BodySession.Exercise, timeMs: Long) {
                counts[exercise] = (counts[exercise] ?: 0) + 1
                if (events.size < MAX_REPORTED_VIDEO_EVENTS) {
                    events += "${formatVideoTime(timeMs)} ${exercise.label}"
                }
            }

            for (frameIndex in 0 until sampleCount) {
                val timeMs = frameIndex * VIDEO_FRAME_INTERVAL_MS
                var frame = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                frame = rotateVideoFrame(frame, rotation)
                val analysisBitmap = scaleBitmapToLongSide(frame, MAX_VIDEO_FRAME_SIDE)
                val pose = try {
                    Tasks.await(poseDetector.process(InputImage.fromBitmap(analysisBitmap, 0)))
                } catch (error: Exception) {
                    analysisBitmap.recycle()
                    throw error
                }

                if (pose.allPoseLandmarks.isNotEmpty()) {
                    poseFrames++
                    val values = poseLandmarkValues(pose, analysisBitmap.width, analysisBitmap.height)
                    val results = mutableMapOf<BodySession.Exercise, BodySession.Result>()
                    exercises.forEach { exercise ->
                        runCatching {
                            sessions.getValue(exercise).process(
                                values, analysisBitmap.width, analysisBitmap.height, timeMs
                            )
                        }.onSuccess { results[exercise] = it }
                    }

                    val squatRepetitions = results[BodySession.Exercise.SQUAT]?.repetitions
                        ?: lastSquatRepetitions
                    if (squatRepetitions > lastSquatRepetitions) {
                        repeat(squatRepetitions - lastSquatRepetitions) {
                            record(BodySession.Exercise.SQUAT, timeMs)
                        }
                        lastSquatRepetitions = squatRepetitions
                    }

                    val armRaise = results[BodySession.Exercise.ARM_RAISE]
                    val lateralRaise = results[BodySession.Exercise.LATERAL_RAISE]
                    armRaise?.let { seenArmRaiseRepetitions = it.repetitions }
                    lateralRaise?.let { seenLateralRaiseRepetitions = it.repetitions }
                    val armTargetActive = armRaise?.phase == BodySession.Phase.UP ||
                        lateralRaise?.phase == BodySession.Phase.UP
                    if (armTargetActive) {
                        val angles = listOfNotNull(armRaise, lateralRaise)
                            .map { (it.leftAngleDeg + it.rightAngleDeg) / 2f }
                        if (angles.isNotEmpty()) armPeakAngle = maxOf(armPeakAngle, angles.max())
                    }
                    val armCycleRepetitions = maxOf(
                        seenArmRaiseRepetitions, seenLateralRaiseRepetitions
                    )
                    if (armCycleRepetitions > lastArmCycleRepetitions) {
                        val kind = if (armPeakAngle >= ARM_RAISE_CLASSIFICATION_ANGLE_DEG) {
                            BodySession.Exercise.ARM_RAISE
                        } else {
                            BodySession.Exercise.LATERAL_RAISE
                        }
                        repeat(armCycleRepetitions - lastArmCycleRepetitions) {
                            record(kind, timeMs)
                        }
                        lastArmCycleRepetitions = armCycleRepetitions
                        armPeakAngle = 0f
                    }

                    val bicepCurl = results[BodySession.Exercise.BICEP_CURL]
                    val pushUp = results[BodySession.Exercise.PUSH_UP]
                    bicepCurl?.let { seenBicepCurlRepetitions = it.repetitions }
                    pushUp?.let { seenPushUpRepetitions = it.repetitions }
                    val elbowTargetActive = bicepCurl?.phase == BodySession.Phase.UP ||
                        pushUp?.phase == BodySession.Phase.DOWN
                    if (elbowTargetActive) {
                        torsoTiltDegrees(values, analysisBitmap.width, analysisBitmap.height)?.let {
                            elbowTorsoTiltTotal += it
                            elbowTorsoTiltFrames++
                        }
                    }
                    val elbowCycleRepetitions = maxOf(
                        seenBicepCurlRepetitions, seenPushUpRepetitions
                    )
                    if (elbowCycleRepetitions > lastElbowCycleRepetitions) {
                        val meanTilt = if (elbowTorsoTiltFrames == 0) 90.0 else
                            elbowTorsoTiltTotal / elbowTorsoTiltFrames
                        val kind = if (meanTilt < CURL_TORSO_TILT_LIMIT_DEG) {
                            BodySession.Exercise.BICEP_CURL
                        } else {
                            BodySession.Exercise.PUSH_UP
                        }
                        repeat(elbowCycleRepetitions - lastElbowCycleRepetitions) {
                            record(kind, timeMs)
                        }
                        lastElbowCycleRepetitions = elbowCycleRepetitions
                        elbowTorsoTiltTotal = 0.0
                        elbowTorsoTiltFrames = 0
                    }

                    previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                    previewBitmap = analysisBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    previewLandmarks = poseOverlayLandmarks(pose)
                }
                analysisBitmap.recycle()

                if (frameIndex % VIDEO_PROGRESS_UPDATE_INTERVAL == 0) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            benchmarkView.text = "正在分析视频：${formatVideoTime(timeMs)} / " +
                                "${formatVideoTime(analyzedDurationMs)}，已检测 $poseFrames 帧…"
                        }
                    }
                }
            }

            val countsText = exercises.joinToString("\n") {
                "${it.label}：${counts[it] ?: 0} 次"
            }
            val eventText = if (events.isEmpty()) "没有发现完整往返动作。请选一段人物全身清晰、动作完整的视频。"
                else "动作片段：\n${events.joinToString("\n")}" +
                    if (events.size == MAX_REPORTED_VIDEO_EVENTS) "\n（仅显示前 $MAX_REPORTED_VIDEO_EVENTS 个片段）" else ""
            val durationNote = if (durationMs > analyzedDurationMs) {
                "\n视频超过 ${formatVideoTime(MAX_VIDEO_ANALYSIS_MS)}，本次只分析前 ${formatVideoTime(analyzedDurationMs)}。"
            } else ""
            val report = "视频动作识别（角度规则）\n" +
                "分析时长 ${formatVideoTime(analyzedDurationMs)} / ${formatVideoTime(durationMs)}，" +
                "检测到人体 $poseFrames / $sampleCount 帧。$durationNote\n\n" +
                "$countsText\n\n$eventText\n\n" +
                "分类依据：膝关节屈伸识别深蹲；手臂最高位置区分双臂上举和侧平举；" +
                "肘关节屈伸结合躯干方向区分弯举和俯卧撑。结果适合快速试跑，遮挡或大幅转身时可能漏计。"
            val output = VideoAnalysisOutput(report, previewBitmap, previewLandmarks)
            previewBitmap = null
            return output
        } catch (error: Exception) {
            previewBitmap?.takeIf { !it.isRecycled }?.recycle()
            throw error
        } finally {
            sessions.values.forEach { it.close() }
            retriever.release()
        }
    }

    private fun showVideoAnalysisOutput(output: VideoAnalysisOutput) {
        benchmarkView.text = output.report
        statusView.text = "视频识别完成"
        output.image?.let { bitmap ->
            previewView.visibility = View.GONE
            poseOverlayView.showPose(bitmap, output.landmarks)
            statusView.text = "视频抽帧骨架预览 · 绿线连接关节，黄点标出关键点"
            mainScrollView.post { mainScrollView.smoothScrollTo(0, 0) }
        }
    }

    private fun rotateVideoFrame(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun scaleBitmapToLongSide(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longSide
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }

    private fun poseLandmarkValues(pose: Pose, width: Int, height: Int): FloatArray {
        val values = FloatArray(99)
        for (index in 0 until 33) {
            val landmark = pose.getPoseLandmark(index) ?: continue
            val x = landmark.position.x / width
            val y = landmark.position.y / height
            val confidence = landmark.inFrameLikelihood
            if (x in 0f..1f && y in 0f..1f && confidence in 0.5f..1f) {
                values[index * 3] = x
                values[index * 3 + 1] = y
                values[index * 3 + 2] = confidence
            }
        }
        return values
    }

    private fun poseOverlayLandmarks(pose: Pose) = pose.allPoseLandmarks.map { landmark ->
        PoseOverlayView.Landmark(
            landmark.landmarkType,
            landmark.position.x,
            landmark.position.y,
            landmark.inFrameLikelihood
        )
    }

    private fun torsoTiltDegrees(values: FloatArray, width: Int, height: Int): Double? {
        val required = intArrayOf(11, 12, 23, 24)
        if (required.any { values[it * 3 + 2] < 0.5f }) return null
        val shoulderX = (values[11 * 3] + values[12 * 3]) * 0.5f * width
        val shoulderY = (values[11 * 3 + 1] + values[12 * 3 + 1]) * 0.5f * height
        val hipX = (values[23 * 3] + values[24 * 3]) * 0.5f * width
        val hipY = (values[23 * 3 + 1] + values[24 * 3 + 1]) * 0.5f * height
        val dx = shoulderX - hipX
        val dy = shoulderY - hipY
        if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < 1.0) return null
        return Math.toDegrees(
            kotlin.math.atan2(kotlin.math.abs(dx).toDouble(), kotlin.math.abs(dy).toDouble())
        )
    }

    private fun formatVideoTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1_000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun chooseBundledPoseSample() {
        val samples = listOf(
            BundledPoseSample("深蹲 · 站立起始", R.drawable.performance_sample_standing, BodySession.Exercise.SQUAT),
            BundledPoseSample("深蹲 · 半蹲", R.drawable.performance_sample_half_squat, BodySession.Exercise.SQUAT),
            BundledPoseSample("深蹲 · 下蹲底部", R.drawable.performance_sample_generated, BodySession.Exercise.SQUAT),
            BundledPoseSample("双臂上举 · 手臂过头", R.drawable.performance_sample_arm_raise, BodySession.Exercise.ARM_RAISE)
        )
        AlertDialog.Builder(this)
            .setTitle("选择测试照片")
            .setItems(samples.map { it.title }.toTypedArray()) { _, index ->
                runBundledPoseBenchmark(samples[index])
            }
            .show()
    }

    private fun runBundledPoseBenchmark(sample: BundledPoseSample) {
        if (cameraStarted) stopCamera()
        benchmarkView.text = "正在运行内置样例：${sample.title}…"
        clearPosePreview()
        benchmarkExecutor.execute {
            val bitmap = BitmapFactory.decodeResource(resources, sample.drawableId)
            val output = try {
                requireNotNull(bitmap) { "内置样例读取失败" }
                benchmarkDetector(bitmap, sample.exercise).let { result ->
                    result.copy(report = "测试图片：${sample.title}\n${result.report}")
                }
            } catch (error: Exception) {
                PoseBenchmarkOutput(
                    "样例测试失败：${error.cause?.message ?: error.message ?: "未知错误"}",
                    bitmap?.copy(Bitmap.Config.ARGB_8888, false), emptyList()
                )
            } finally {
                bitmap?.recycle()
            }
            mainHandler.post { showPoseBenchmarkOutput(output) }
        }
    }

    private fun clearPosePreview() {
        videoGeneration++
        activeVideoUri = null
        poseOverlayView.videoOverlay = false
        poseOverlayView.clearPose()
        poseOverlayView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
    }

    private fun showPoseBenchmarkOutput(output: PoseBenchmarkOutput) {
        benchmarkView.text = output.report
        output.image?.let { bitmap ->
            previewView.visibility = View.GONE
            poseOverlayView.showPose(bitmap, output.landmarks)
            statusView.text = if (output.landmarks.isEmpty()) {
                "照片预览 · 未检测到骨架关键点"
            } else {
                "照片与识别骨架预览 · 绿线连接关节，黄点标出关键点"
            }
            mainScrollView.post { mainScrollView.smoothScrollTo(0, 0) }
        }
    }

    private fun decodeBenchmarkImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开照片" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片格式无法识别" }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 640 || bounds.outHeight / sampleSize > 640) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开照片" }
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "照片解码失败" }
        }
        val largestSide = maxOf(decoded.width, decoded.height)
        if (largestSide <= 640) return decoded
        val scale = 640f / largestSide
        val resized = Bitmap.createScaledBitmap(decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    private fun selectedExercise() = exercises[exerciseSpinner.selectedItemPosition.coerceAtLeast(0)]

    private fun benchmarkDetector(bitmap: Bitmap, exercise: BodySession.Exercise): PoseBenchmarkOutput {
        val samples = LongArray(20)
        var detections = 0
        var lastPose: Pose? = null
        repeat(3) { Tasks.await(poseDetector.process(InputImage.fromBitmap(bitmap, 0))) }
        repeat(samples.size) { index ->
            val start = System.nanoTime()
            val pose = Tasks.await(poseDetector.process(InputImage.fromBitmap(bitmap, 0)))
            samples[index] = System.nanoTime() - start
            if (pose.allPoseLandmarks.isNotEmpty()) {
                detections++
                lastPose = pose
            }
        }
        samples.sort()
        val meanMs = samples.average() / 1_000_000.0
        val p95Ms = samples[(samples.size * 0.95).toInt().coerceAtMost(samples.lastIndex)] / 1_000_000.0
        val speedNote = when {
            meanMs <= 33.0 -> "单张耗时对应 30 FPS 以上的处理能力。"
            meanMs <= 66.0 -> "单张耗时对应约 15–30 FPS，适合降低相机分析帧率后实时运行。"
            meanMs <= 100.0 -> "单张耗时对应约 10–15 FPS，实时动作反馈会偏慢。"
            else -> "单张耗时低于 10 FPS，建议换更强的推理设备或更轻的模型。"
        }
        val detectionNote = if (detections == samples.size) "照片中连续检测到人体，结果可用于评估姿态识别速度。"
            else "仅 $detections/${samples.size} 次检测到人体；请换一张正面、无遮挡的全身照以评估完整姿态识别速度。"
        val scoreReport = lastPose?.let { pose ->
            runCatching { scoreSinglePose(pose, bitmap, exercise) }
                .getOrElse { error ->
                    "单帧演示指数计算失败：${error.message ?: "所需关节关键点置信度不足"}。请确保全身完整入镜、光线充足。"
                }
        } ?: "没有检测到人体，暂时无法计算演示指数；请换一张全身清晰可见的照片。"
        val points = lastPose?.allPoseLandmarks?.map { landmark ->
            PoseOverlayView.Landmark(
                landmark.landmarkType,
                landmark.position.x,
                landmark.position.y,
                landmark.inFrameLikelihood
            )
        } ?: emptyList()
        val report = "姿态模型测试（${bitmap.width}×${bitmap.height}，20 次）：\n" +
            "平均 ${format(meanMs)} ms/张，P95 ${format(p95Ms)} ms/张，约 ${format(1000.0 / meanMs)} 张/秒。\n" +
            "$speedNote\n本次检测到人体：$detections/${samples.size}。$detectionNote\n\n" +
            "$scoreReport\n这是基于关节角度的规则演示指数，不评价膝盖轨迹、躯干稳定、脚掌受力或动作过程，不能当作完整动作质量分。\n\n" +
            readDeviceSummary()
        return PoseBenchmarkOutput(report, bitmap.copy(Bitmap.Config.ARGB_8888, false), points)
    }

    private fun scoreSinglePose(
        pose: Pose, bitmap: Bitmap, exercise: BodySession.Exercise
    ): String {
        val landmarks = FloatArray(99)
        for (index in 0 until 33) {
            val point = pose.getPoseLandmark(index) ?: continue
            val x = point.position.x / bitmap.width
            val y = point.position.y / bitmap.height
            val confidence = point.inFrameLikelihood
            if (x in 0f..1f && y in 0f..1f && confidence in 0.5f..1f) {
                landmarks[index * 3] = x
                landmarks[index * 3 + 1] = y
                landmarks[index * 3 + 2] = confidence
            }
        }
        val minConfidence = if (exercise == BodySession.Exercise.PUSH_UP) {
            maxOf(
                intArrayOf(11, 13, 15, 23).minOf { landmarks[it * 3 + 2] },
                intArrayOf(12, 14, 16, 24).minOf { landmarks[it * 3 + 2] }
            )
        } else {
            exercise.requiredLandmarks.minOf { landmarks[it * 3 + 2] }
        }
        val result = BodySession(exercise).use { session ->
            session.process(landmarks, bitmap.width, bitmap.height, SystemClock.elapsedRealtime())
        }
        val exerciseName = exercise.label
        val angleName = exercise.angleName
        val symmetryLimit = exercise.symmetryLimitDeg.toDouble()
        val angleDifference = kotlin.math.abs(result.leftAngleDeg - result.rightAngleDeg).toDouble()
        val symmetryScore = (100.0 - angleDifference / symmetryLimit * 100.0)
            .coerceIn(0.0, 100.0)
        val rangeScore = result.progress.toDouble() * 100.0
        return "单帧姿势演示指数（$exerciseName）：${result.liveScore.toInt()} / 100\n" +
            "分项：动作幅度 ${format(rangeScore)}，左右对称性 ${format(symmetryScore)}（权重 80% / 20%）。\n" +
            "左右${angleName} ${result.leftAngleDeg.toInt()}° / ${result.rightAngleDeg.toInt()}°，" +
            "关键点最低置信度 ${format(minConfidence * 100.0)}%。\n" +
            "该指数对应当前单帧姿势；内置样例可用于对比起始、半程和目标姿势。"
    }

    private fun readDeviceSummary(): String {
        val cpuInfo = runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrDefault("")
        val a53Count = Regex("(?im)^CPU part\\s*:\\s*0xd03").findAll(cpuInfo).count()
        val a72Count = Regex("(?im)^CPU part\\s*:\\s*0xd08").findAll(cpuInfo).count()
        val totalKb = runCatching {
            Regex("MemTotal:\\s+(\\d+)").find(java.io.File("/proc/meminfo").readText())
                ?.groupValues?.get(1)?.toLong()
        }.getOrNull()
        val available = ActivityManager.MemoryInfo().also {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }.availMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuText = when {
            a53Count > 0 && a72Count > 0 -> "Cortex-A53×$a53Count + A72×$a72Count"
            else -> "$cores 个可用 CPU 核心"
        }
        val memoryText = totalKb?.let { "${format(it / 1024.0 / 1024.0)} GB" } ?: "未知"
        val a53Mhz = readFrequencyMhz(0)
        val a72Mhz = readFrequencyMhz(4)
        val frequencyText = if (a53Mhz != null && a72Mhz != null) "，最高频率约 $a53Mhz/$a72Mhz MHz" else ""
        val appPss = Debug.getPss() / 1024
        return "设备：${Build.MODEL} / ${Build.HARDWARE}\n" +
            "系统：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})，${Build.SUPPORTED_ABIS.firstOrNull() ?: "ABI 未知"}\n" +
            "CPU：$cpuText（$cores 个逻辑核）$frequencyText\n" +
            "内存：总计 $memoryText，当前可用 ${available} MB，应用占用约 ${appPss} MB"
    }

    private fun readFrequencyMhz(core: Int): Int? = runCatching {
        val khz = java.io.File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
        khz / 1000
    }.getOrNull()

    private fun format(value: Double) = String.format(Locale.US, "%.2f", value)

    private fun startCameraWhenReady() {
        clearPosePreview()
        val cameraIds = try {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList
        } catch (error: Exception) {
            Log.e(TAG, "Cannot enumerate cameras", error)
            showStatus("无法读取摄像头：${error.message ?: "请检查设备"}")
            return
        }
        if (cameraIds.isEmpty()) {
            showStatus("设备未检测到摄像头，请连接摄像头后重试")
            return
        }
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }
        if (cameraStarted) return
        try {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                if (isFinishing || isDestroyed) return@addListener
                try {
                    val provider = providerFuture.get()
                    val selector = when {
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> throw IllegalStateException("设备没有可用摄像头")
                    }
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, selector, preview, analysis)
                    cameraProvider = provider
                    poseOverlayView.clearPose()
                    poseOverlayView.visibility = View.GONE
                    previewView.visibility = View.VISIBLE
                    cameraStarted = true
                    startButton.text = "停止相机"
                    statusView.text = "相机已启动，请站到全身可见的位置"
                } catch (error: Exception) {
                    Log.e(TAG, "Cannot start camera", error)
                    showStatus("摄像头启动失败，请检查设备摄像头后重试")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (error: Exception) {
            Log.e(TAG, "Cannot initialize CameraX", error)
            showStatus("摄像头初始化失败，请检查设备摄像头后重试")
        }
    }

    private fun analyzeFrame(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val width = if (rotation == 90 || rotation == 270) image.height else image.width
        val height = if (rotation == 90 || rotation == 270) image.width else image.height
        val timestampMs = SystemClock.uptimeMillis()
        try {
            poseDetector.process(InputImage.fromMediaImage(mediaImage, rotation))
                .addOnSuccessListener { pose -> onPoseResult(pose, width, height, timestampMs) }
                .addOnFailureListener { error -> showStatus("姿态识别错误：${error.message ?: "未知错误"}") }
                .addOnCompleteListener { image.close() }
        } catch (error: Exception) {
            showStatus("相机帧处理失败：${error.message ?: "未知错误"}")
            image.close()
        }
    }

    private fun onPoseResult(pose: Pose, width: Int, height: Int, timestampMs: Long) {
        if (pose.allPoseLandmarks.isEmpty()) {
            showStatus("没有检测到完整人体，请调整距离和光线")
            return
        }
        val values = FloatArray(99)
        for (i in 0 until 33) {
            val landmark = pose.getPoseLandmark(i) ?: continue
            values[i * 3] = landmark.position.x / width
            values[i * 3 + 1] = landmark.position.y / height
            values[i * 3 + 2] = landmark.inFrameLikelihood
        }
        try {
            val processed = synchronized(sessionLock) {
                bodySession.process(values, width, height, timestampMs)
            }
            mainHandler.post {
                val startPhase = selectedExercise().startPhase
                val phaseLabel = when {
                    processed.phase == BodySession.Phase.UNKNOWN -> "动作过程中"
                    processed.phase == startPhase -> "起始姿势"
                    else -> "动作目标位"
                }
                statusView.text = "识别中 · $phaseLabel · 进度 ${(processed.progress * 100).toInt()}%"
                resultView.text = "次数 ${processed.repetitions}     当前分数 ${processed.lastRepScore.toInt()}\n左/右${selectedExercise().angleName} ${processed.leftAngleDeg.toInt()}° / ${processed.rightAngleDeg.toInt()}°"
            }
        } catch (error: IllegalArgumentException) {
            showStatus("关键点不足或时间戳无效，请保持全身在画面内")
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraStarted = false
        startButton.text = "开始相机"
        statusView.text = "相机已停止"
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showStatus(message: String) { mainHandler.post { statusView.text = message } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCameraWhenReady()
        else showStatus("需要相机权限才能检测动作")
    }

    override fun onStop() {
        videoGeneration++
        activeVideoUri = null
        super.onStop()
    }

    override fun onDestroy() {
        videoGeneration++
        stopCamera()
        synchronized(sessionLock) { bodySession.close() }
        cameraExecutor.shutdown()
        benchmarkExecutor.execute {
            cameraExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            poseDetector.close()
            rtmposeDetector?.close()
            rtmposeDetector = null
            moveNetDetector?.close()
            moveNetDetector = null
        }
        benchmarkExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private val COMMON_BODY_INDICES = setOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        private const val TAG = "BodyDemo"
        private const val CAMERA_REQUEST = 41
        private const val VIDEO_FRAME_INTERVAL_MS = 200L
        private const val MAX_VIDEO_ANALYSIS_MS = 60_000L
        private const val MAX_VIDEO_FRAME_SIDE = 640
        private const val VIDEO_PROGRESS_UPDATE_INTERVAL = 10
        private const val MAX_REPORTED_VIDEO_EVENTS = 24
        private const val ARM_RAISE_CLASSIFICATION_ANGLE_DEG = 130f
        private const val CURL_TORSO_TILT_LIMIT_DEG = 45.0
    }
}
