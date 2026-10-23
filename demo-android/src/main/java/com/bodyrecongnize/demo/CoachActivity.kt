package com.bodyrecongnize.demo

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bodyrecongnize.BodySession
import com.bodyrecongnize.demo.coach.*
import com.bodyrecongnize.demo.lab.LabVideoReader
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class CoachActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val processor = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var acceptFrames = false
    private lateinit var detector: PoseDetector
    private lateinit var history: CoachHistory
    private lateinit var preview: PreviewView
    private lateinit var overlay: PoseOverlayView
    private lateinit var stageView: TextView
    private lateinit var sourceView: TextView
    private lateinit var countsView: TextView
    private lateinit var hintView: TextView
    private lateinit var feedbackDetailView: TextView
    private lateinit var anglesView: TextView
    private lateinit var summaryView: TextView
    private lateinit var exerciseButton: Button
    private var exercise = BodySession.Exercise.SQUAT
    private val guide get() = ExerciseGuide.all[exercise.ordinal]
    private lateinit var planButton: Button
    private lateinit var demoButton: Button
    private lateinit var cameraButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var alignmentButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var videoTimeline: LinearLayout
    private lateinit var videoTimeView: TextView
    private lateinit var videoProgress: ProgressBar
    private var videoDurationMs = 60_000L
    private lateinit var progress: ProgressBar
    private var plan = CoachSession.Plan(2, 2, 5000, 3000)
    private var coach = CoachSession(plan, guide)
    private var provider: ProcessCameraProvider? = null
    private var cameraCounter: BodySession? = null
    private var liveCanvas: Bitmap? = null // owned/recycled by the overlay
    private var source = ""
    private var startedAt = 0L
    private var sessionId = ""
    private var saved = false
    private var mirrored = false
    private var closing = false
    private val localChat = LocalCoachChat()
    private data class ChatMessage(val who: String, val message: String, val evidence: CoachSession.Feedback? = null)
    private val chatMessages = mutableListOf<ChatMessage>()
    private var chatDialog: AlertDialog? = null
    private var inputMessage: String? = null
    private var summaryDialog: AlertDialog? = null
    private var evidenceDialog: AlertDialog? = null
    private val alignment = CameraAlignment()
    private var aligningCamera = false
    private var resumeAfterAlignment = false
    private var preferredLens = CameraSelector.LENS_FACING_FRONT
    private var lastRenderedStage = CoachSession.Stage.IDLE
    @Volatile private var inferenceEpoch = 0
    @Volatile private var resetCounterOnFrame = false
    private var currentVideoUri: Uri? = null
    private var bundledVideo = false
    private var inputLabel = ""
    private var inputEndReason: String? = null
    private var inForeground = true
    private var bodyWeightDraft = ""
    private var weightEntry: EditText? = null
    private var calorieBasis: TrainingCalories.Basis? = null
    private lateinit var calorieViews: CalorieCardViews
    private val importVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !ongoing()) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startTraining(true, uri)
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startTraining(false) else { inputMessage = "需要相机权限，也可以先体验内置深蹲视频"; navigate("setup") }
    }
    private val pulse = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                if (aligningCamera) render(coach.snapshot(SystemClock.elapsedRealtime()))
                else if (coach.stage in listOf(CoachSession.Stage.PREPARING, CoachSession.Stage.ACTIVE, CoachSession.Stage.REST)) {
                    render(coach.tick(SystemClock.elapsedRealtime()))
                }
                main.postDelayed(this, 200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = CoachHistory(this)
        val prefs = getPreferences(Context.MODE_PRIVATE)
        bodyWeightDraft = prefs.getString("bodyWeightKg", "").orEmpty().takeIf { TrainingCalories.parseWeight(it).valid }.orEmpty()
        exercise = BodySession.Exercise.values().getOrElse(prefs.getInt("exercise", 0)) { BodySession.Exercise.SQUAT }
        plan = CoachSession.Plan(prefs.getInt("sets", 2).coerceIn(1, 10), prefs.getInt("reps", 2).coerceIn(1, 30), prefs.getLong("rest", 5000).coerceIn(0, 300000))
        coach = CoachSession(plan, guide)
        detector = PoseDetection.getClient(PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU).build())
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == "training" && ongoing()) confirmEnd()
                else if (screen != "home") navigate("home")
                else if (ongoing()) navigate("training") else finish()
            }
        })
        main.post(pulse)
    }

    private val paper = 0xfff5f6f2.toInt()
    private val ink = 0xff17251e.toInt()
    private val subdued = 0xff6c776f.toInt()
    private val green = 0xff215e40.toInt()
    private val wash = 0xffe8efdf.toInt()
    private lateinit var pageHost: FrameLayout
    private lateinit var navigation: LinearLayout
    private lateinit var trainingPage: LinearLayout
    private lateinit var capture: FrameLayout
    private lateinit var sessionMeta: TextView
    private lateinit var restAction: Button
    private var screen = "home"
    private var recordsSource = "摄像头训练"
    private var showAngles = false
    private var lastObservation: CoachSession.Observation? = null
    private var resumeAfterChat = false
    private var coachHintBeforeChat = ""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 18) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float = 14f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(7), 0, dp(7))
        if (size >= 22) typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
    }
    private fun action(value: String, primary: Boolean = false, callback: () -> Unit) = Button(this).apply {
        text = value; textSize = 15f; isAllCaps = false; minHeight = dp(52); stateListAnimator = null; elevation = 0f
        setTextColor(if (primary) android.graphics.Color.WHITE else ink)
        background = shape(if (primary) green else android.graphics.Color.WHITE, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        setOnClickListener { callback() }
    }
    private fun line(title: String, detail: String, callback: () -> Unit): View = column().apply {
        setPadding(dp(16), dp(9), dp(16), dp(9)); background = shape(android.graphics.Color.WHITE, 16)
        addView(label("$title   ›", 17f)); addView(label(detail, 12f, subdued))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        isClickable = true; isFocusable = true; setOnClickListener { callback() }
    }
    private fun addPair(parent: LinearLayout, a: View, b: View) {
        parent.addView(LinearLayout(this).apply {
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6); topMargin = dp(10) })
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6); topMargin = dp(10) })
        })
    }
    private data class CalorieCardViews(val card: LinearLayout, val title: TextView, val value: TextView,
                                       val unit: TextView, val note: TextView, val button: Button, val details: TextView)

    private fun newCalorieCard(): CalorieCardViews {
        val card = column().apply { background = shape(wash, 18); setPadding(dp(16), dp(6), dp(16), dp(12)) }
        val title = label("热量消耗（估算）", 13f)
        val details = label("", 12f, subdued).apply { visibility = View.GONE }
        val button = action("查看依据") {
            val expanded = details.visibility != View.VISIBLE
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            (card.tag as? CalorieCardViews)?.button?.text = if (expanded) "收起依据" else "查看依据"
        }.apply {
            background = null; setTextColor(green); textSize = 12f; minHeight = dp(44)
            setPadding(dp(6), 0, 0, 0)
        }
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button, LinearLayout.LayoutParams(-2, -2))
        })
        val value = label("—", 38f)
        val unit = label("千卡", 12f, subdued).apply { setPadding(dp(8), 0, 0, 0) }
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM; isBaselineAligned = true
            addView(value); addView(unit)
        })
        val note = label("", 11f, subdued)
        card.addView(note); card.addView(details)
        return CalorieCardViews(card, title, value, unit, note, button, details).also { card.tag = it }
    }

    private fun fillCalorieCard(views: CalorieCardViews, estimate: TrainingCalories.Estimate, note: String) {
        val video = estimate.status == TrainingCalories.Status.VIDEO
        val estimated = estimate.status == TrainingCalories.Status.ESTIMATED
        views.title.text = if (video) "视频分析" else "热量消耗（估算）"
        views.value.text = estimate.valueLabel
        views.unit.visibility = if (video) View.GONE else View.VISIBLE
        views.note.text = note
        views.button.visibility = if (estimated) View.VISIBLE else View.GONE
        views.details.text = estimate.explanation()
        if (!estimated) views.details.visibility = View.GONE
    }

    private fun weightCard(): View = column().apply {
        background = shape(android.graphics.Color.WHITE, 18); setPadding(dp(16), dp(10), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(8) }
        addView(label("你的体重 · 选填", 15f))
        val field = EditText(this@CoachActivity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true); textSize = 30f; setTextColor(ink); setHintTextColor(subdued)
            hint = "输入体重"; contentDescription = "体重，单位千克，可选填"
            setText(bodyWeightDraft); isEnabled = !ongoing()
        }
        weightEntry = field
        addView(LinearLayout(this@CoachActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, -2, 1f)); addView(label("kg", 16f, subdued))
        })
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                bodyWeightDraft = s?.toString().orEmpty()
                val parsed = TrainingCalories.parseWeight(bodyWeightDraft)
                if (parsed.valid) {
                    field.error = null
                    getPreferences(Context.MODE_PRIVATE).edit().apply {
                        if (parsed.kilograms == null) remove("bodyWeightKg")
                        else putString("bodyWeightKg", TrainingCalories.formatWeight(parsed.kilograms))
                    }.apply()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        addView(label("保存在本机，用于估算训练消耗。不填写也能训练，消耗显示“—”。", 12f, subdued))
        TrainingCalories.referenceFor(exercise.name)?.let {
            addView(label("估算参考 · ${it.label}${if (it.approximate) "（类别近似）" else ""}", 12f, green))
        }
    }

    private fun sessionCalories(state: CoachSession.Snapshot) = calorieBasis?.estimate(state.activeMs)
        ?: TrainingCalories.Estimate(TrainingCalories.Status.MISSING_WEIGHT, state.activeMs)

    private fun recordCalories(record: JSONObject): TrainingCalories.Estimate {
        val fields = record.optJSONObject("calorieEstimate")?.let { json ->
            json.keys().asSequence().associateWith { key -> json.opt(key).takeUnless { it === JSONObject.NULL } }
        }
        return TrainingCalories.fromStored(if (record.optString("source") == "摄像头训练")
            TrainingCalories.Source.CAMERA else TrainingCalories.Source.VIDEO, fields)
    }
    private fun buildUi() {
        window.statusBarColor = paper; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val shell = column().apply {
            setBackgroundColor(paper)
            setPadding(0, 0, 0, dp(48))
            setOnApplyWindowInsetsListener { view, insets ->
                // Some embedded Android builds report zero bottom inset despite an inaccessible strip.
                view.setPadding(0, 0, 0, maxOf(dp(48), insets.systemWindowInsetBottom))
                insets
            }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        header.addView(label("律动 / FIT", 17f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("设置 · 静音") { navigate("settings") }.apply { textSize = 12f; minHeight = dp(44) }, LinearLayout.LayoutParams(-2, -2))
        shell.addView(header)
        pageHost = FrameLayout(this)
        shell.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = LinearLayout(this).apply { setPadding(dp(12), dp(4), dp(12), dp(10)); setBackgroundColor(android.graphics.Color.WHITE) }
        shell.addView(navigation)
        // Stable camera/overlay instances survive navigation; only the surrounding page changes.
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = PoseOverlayView(this).apply { retainImageOnDetach = true }
        trainingPage = column()
        sourceView = label("", 12f, subdued); stageView = label("", 24f)
        trainingPage.addView(sourceView); trainingPage.addView(stageView)
        calorieViews = newCalorieCard()
        trainingPage.addView(calorieViews.card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        capture = FrameLayout(this).apply {
            background = shape(0xff272e2a.toInt(), 20); clipToOutline = true
            addView(preview, FrameLayout.LayoutParams(-1, -1)); addView(this@CoachActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        }
        switchCameraButton = action("前置 ⇄") { switchCamera() }.apply { textSize = 12f; minHeight = dp(40) }
        capture.addView(switchCameraButton, FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(10); marginEnd = dp(10)
        })
        videoTimeline = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4))
            background = shape(0xdd17251e.toInt(), 10)
        }
        videoTimeView = label("00:00 / 01:00", 11f, android.graphics.Color.WHITE)
        videoTimeline.addView(videoTimeView)
        videoProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(0xff80cf9e.toInt())
        }
        videoTimeline.addView(videoProgress, LinearLayout.LayoutParams(0, dp(4), 1f).apply { marginStart = dp(12) })
        capture.addView(videoTimeline, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(10)
        })
        trainingPage.addView(capture, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * .36f).toInt()))
        alignmentButton = action("请先调整站位", true) { beginAlignedTraining() }
        trainingPage.addView(alignmentButton)
        countsView = label("", 48f).apply { gravity = Gravity.CENTER }
        trainingPage.addView(countsView)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { progressTintList = android.content.res.ColorStateList.valueOf(green) }
        trainingPage.addView(progress, LinearLayout.LayoutParams(-1, dp(5)))
        val feedbackCard = column().apply {
            background = shape(android.graphics.Color.WHITE, 18)
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        feedbackCard.addView(label("教练实时提示", 12f, green))
        hintView = label("", 17f, ink).apply { setPadding(0, dp(2), 0, dp(4)) }
        feedbackCard.addView(hintView)
        feedbackDetailView = label("提示根据当前关键点与角度规则生成", 12f, subdued)
        feedbackCard.addView(feedbackDetailView)
        trainingPage.addView(feedbackCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        trainingPage.addView(action("问问教练  ·  本地文字对话") { openCoachChat() })
        sessionMeta = label("", 12f, subdued); trainingPage.addView(sessionMeta)
        trainingPage.addView(action("关节详情 ⌄") { showAngles = !showAngles; anglesView.visibility = if (showAngles) View.VISIBLE else View.GONE })
        anglesView = label("等待识别关节点", 13f, subdued).apply { visibility = View.GONE }; trainingPage.addView(anglesView)
        pauseButton = action("暂停训练") {
            val now = SystemClock.elapsedRealtime()
            if (coach.stage == CoachSession.Stage.PAUSED) inputMessage = null
            render(if (coach.stage == CoachSession.Stage.PAUSED) coach.resume(now) else coach.pause(now))
        }
        stopButton = action("结束训练", true) { confirmEnd() }
        addPair(trainingPage, pauseButton, stopButton)
        restAction = action("提前开始下一组", true) { render(coach.skipRest(SystemClock.elapsedRealtime())) }
        trainingPage.addView(restAction)
        summaryView = label("", 16f)
        // Retain controls referenced by selection logic; visible setup is rendered separately.
        planButton = action("") { choosePlan() }; exerciseButton = action("") { chooseExercise() }
        demoButton = action("") { startTraining(true) }; cameraButton = action("") { startTraining(false) }
        setContentView(shell)
        render(coach.snapshot(SystemClock.elapsedRealtime())); navigate("home")
    }
    private fun ongoing() = coach.stage !in listOf(CoachSession.Stage.IDLE, CoachSession.Stage.FINISHED)
    private fun navigate(destination: String) {
        if (aligningCamera && destination != "training") {
            aligningCamera = false; stopInput()
        }
        if (screen == "training" && destination != "training" && ongoing()) render(coach.pause(SystemClock.elapsedRealtime()))
        screen = destination
        if (screen != "setup") weightEntry = null
        // Detach stable training page before discarding its old scroll parent.
        (trainingPage.parent as? android.view.ViewGroup)?.removeView(trainingPage)
        pageHost.removeAllViews()
        val body = column().apply { setPadding(dp(24), dp(10), dp(24), dp(24)) }
        if (screen == "chat") {
            pageHost.addView(chatPage())
        } else {
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
            pageHost.addView(scroll)
            when (screen) {
                "home" -> {
                    body.addView(label("MOVE AT YOUR OWN PACE", 11f, subdued))
                    body.addView(label("今天，也为自己\n动一动。", 34f))
                    body.addView(label("选择一个动作，从舒服的节奏开始。", 14f, subdued))
                    val hero = column().apply { background = shape(wash, 22); setPadding(dp(22), dp(18), dp(22), dp(22)) }
                    hero.addView(label(if (ongoing()) "正在进行的训练" else "你的训练目标", 12f, subdued))
                    hero.addView(label("${exercise.label}\n${plan.sets} 组 × ${plan.reps} 次", 27f))
                    hero.addView(label("组间休息 ${plan.restMs / 1000} 秒 · 手动设置", 13f, subdued))
                    hero.addView(action(if (ongoing()) "返回当前训练  ↗" else "开始训练  ↗", true) { navigate(if (ongoing()) "training" else "setup") })
                    body.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
                    body.addView(line("AI训练实验室", "自动识别 · 动作对比 · 问题回放 · 连续动作 · 稳定性 · 问答") {
                        startActivity(android.content.Intent(this, LabActivity::class.java))
                    })
                    body.addView(label("动作库", 21f))
                    BodySession.Exercise.values().forEach { kind ->
                        body.addView(line(kind.label, ExerciseGuide.all[kind.ordinal].preparation) {
                            if (ongoing()) { Toast.makeText(this, "请先结束当前训练，再切换动作", Toast.LENGTH_SHORT).show() }
                            else { exercise = kind; resetSelection(); navigate("setup") }
                        })
                    }
                }
                "setup" -> {
                    body.addView(action("‹ 返回训练") { navigate("home") })
                    body.addView(label("MAKE IT YOURS", 11f, subdued)); body.addView(label("准备练${exercise.label}", 30f))
                    body.addView(label("本次目标由你设定。", 14f, subdued))
                    body.addView(line("训练动作 · ${exercise.label}", "选择五种本地识别动作") { chooseExercise() })
                    body.addView(line("${plan.sets} 组 × ${plan.reps} 次", "组间休息 ${plan.restMs / 1000} 秒 · 点此修改") { choosePlan() })
                    body.addView(weightCard())
                    body.addView(label(guide.preparation, 16f, green))
                    if (inputMessage != null) body.addView(label(inputMessage!!, 15f, 0xff9b5514.toInt()))
                    body.addView(action("摄像头 · 先调整站位", true) { startTraining(false) })
                    body.addView(action("暂不填写体重，开始训练") {
                        bodyWeightDraft = ""; weightEntry?.setText("")
                        getPreferences(Context.MODE_PRIVATE).edit().remove("bodyWeightKg").apply()
                        startTraining(false)
                    })
                    body.addView(action("导入我的视频 · 在训练页识别") { importVideo.launch(arrayOf("video/*")) })
                    if (exercise == BodySession.Exercise.SQUAT) body.addView(action("内置真人视频 · 演示体验") { startTraining(true) })
                    body.addView(label("选择与视频一致的训练动作。导入视频按帧识别前60秒，反馈和对话都在训练页；视频记录与本人训练分开。", 12f, subdued))
                }
                "training" -> {
                    body.addView(trainingPage)
                    if (source == "摄像头训练" && ongoing() && cameraCounter == null) {
                        aligningCamera = true; resumeAfterAlignment = true; alignment.reset()
                        bindCamera(generation)
                    }
                    render(coach.snapshot(SystemClock.elapsedRealtime()))
                }
                "summary" -> {
                    val s = coach.snapshot(SystemClock.elapsedRealtime())
                    val calories = sessionCalories(s)
                    body.addView(label(if (s.completed) "SESSION COMPLETE" else "SESSION SAVED", 11f, subdued))
                    body.addView(label(if (source == "真人视频演示") "视频分析结束" else "本次训练", 32f))
                    body.addView(label("${exercise.label} · $source · ${if (s.completed) "计划完成" else if (inputEndReason != null) "视频识别结束" else "提前结束"}", 13f, subdued))
                    inputEndReason?.let { body.addView(label(it, 14f, green)) }
                    val energy = newCalorieCard()
                    fillCalorieCard(energy, calories, if (calories.status == TrainingCalories.Status.ESTIMATED)
                        "有效训练时段总消耗，含静息消耗" else calories.summaryLabel)
                    energy.button.visibility = View.GONE
                    body.addView(energy.card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                    body.addView(label("${s.totalReps} 次    ${s.activeMs / 1000} 秒", 32f))
                    body.addView(label("完整动作                     训练时间", 12f, subdued))
                    body.addView(label("完成 ${s.completedSets} / ${plan.sets} 组", 22f))
                    if (calories.status == TrainingCalories.Status.ESTIMATED) {
                        body.addView(label("这次如何估算", 17f)); body.addView(label(calories.explanation(), 12f, subdued))
                    }
                    body.addView(line("本次动作反馈", "未达计数幅度 ${s.shallowAttempts} 次 · 节奏较快 ${s.fastReps} 次") {
                        AlertDialog.Builder(this).setTitle("本次动作反馈").setMessage(summaryText(s)).setPositiveButton("关闭", null).show()
                    })
                    body.addView(label("规则反馈仅供参考，不代表完整姿势评价。视频演示不计入本人训练。", 13f, subdued))
                    body.addView(action("查看训练记录", true) { recordsSource = source; navigate("records") }); body.addView(action("返回首页") { navigate("home") })
                }
                "records" -> renderRecords(body)
                "settings" -> {
                    body.addView(label("设置", 32f)); body.addView(label("始终静音 · 本地识别 · 数据保存在设备", 14f, subdued))
                    body.addView(line("调试工具", "模型对比、骨骼显示和推理耗时") {
                        startActivity(android.content.Intent(this, MainActivity::class.java))
                    })
                    body.addView(line("关于律动", "当前支持五种动作和本地预设文字对话。") {
                        AlertDialog.Builder(this).setTitle("本地健身教练").setMessage("通过姿态模型与角度规则识别完整动作；不检测器械，不提供完整姿势诊断。对话来自本地话术，尚未接入大模型或个性化计划。全程静音。").setPositiveButton("知道了", null).show()
                    })
                    body.addView(action("返回训练") { navigate("home") })
                }
            }
        }
        navigation.removeAllViews()
        listOf("home" to "训练", "chat" to "教练", "records" to "记录").forEach { (target, name) ->
            navigation.addView(action(name) { navigate(target) }.apply {
                background = shape(if (screen == target) wash else android.graphics.Color.WHITE, 12)
                setTextColor(if (screen == target) green else subdued)
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
    }
    private fun confirmEnd() {
        if (aligningCamera && !ongoing()) {
            aligningCamera = false; stopInput(); navigate("setup"); return
        }
        if (!ongoing()) { navigate("home"); return }
        render(coach.pause(SystemClock.elapsedRealtime()))
        AlertDialog.Builder(this).setTitle("结束本次训练？").setMessage("已完成的次数会保存，记录将标记为提前结束。")
            .setPositiveButton("结束并保存") { _, _ -> finishTraining(); navigate("summary") }
            .setNegativeButton("返回训练", null).show()
    }
    private fun renderRecords(body: LinearLayout) {
        body.addView(label("YOUR OWN RHYTHM", 11f, subdued)); body.addView(label("看见你的积累", 32f))
        body.addView(label("最近 20 条本机记录", 13f, subdued))
        addPair(body, action("本人训练", recordsSource == "摄像头训练") { recordsSource = "摄像头训练"; navigate("records") },
            action("视频演示", recordsSource == "真人视频演示") { recordsSource = "真人视频演示"; navigate("records") })
        val records = history.entries().filter { it.optString("source") == recordsSource }
        if (records.isEmpty()) {
            body.addView(label("从第一次开始", 25f)); body.addView(label("完成训练后，次数和时间都会保存在这里。", 15f, subdued))
            body.addView(action("去训练", true) { navigate("home") })
        } else {
            body.addView(label("${records.size} 次训练   ·   ${records.sumOf { it.optInt("totalReps") }} 次完整动作", 20f))
            val date = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
            records.forEach { record ->
                val calories = recordCalories(record)
                val reason = record.optString("inputEndReason").takeUnless { it.isBlank() || it == "null" }
                val status = if (record.optBoolean("completed")) "计划完成" else if (reason != null) "视频识别结束" else "提前结束"
                body.addView(line("${record.optString("exerciseLabel", "深蹲")} · ${record.optInt("totalReps")} 次",
                    "${date.format(Date(record.optLong("startedAt")))} · $status\n${calories.summaryLabel}") {
                    AlertDialog.Builder(this).setTitle("训练详情 · ${record.optString("exerciseLabel", "深蹲")}")
                        .setMessage("${record.optString("source")} · ${record.optString("inputLabel")}\n${reason ?: status}\n完整动作 ${record.optInt("totalReps")} 次\n完成 ${record.optInt("completedSets")}/${record.optInt("sets")} 组\n训练计时 ${record.optLong("activeMs") / 1000} 秒\n${calories.summaryLabel}\n${if (calories.status == TrainingCalories.Status.ESTIMATED) calories.explanation() + "\n" else ""}未达计数幅度 ${record.optInt("shallowAttempts")} 次\n节奏较快 ${record.optInt("fastReps")} 次\n规则反馈不代表完整姿势评价。")
                        .setPositiveButton("关闭", null).show()
                })
            }
        }
        body.addView(label(if (recordsSource == "真人视频演示") "视频演示不计入本人训练统计。" else "以上仅统计最近保留的本人训练记录。", 12f, subdued))
    }
    private fun chatPage(): View {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label("和教练聊一聊", 30f)); body.addView(label("本地预设回复 · 纯文字交流", 12f, subdued))
        val messages = column()
        val scroll = ScrollView(this).apply { addView(messages) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (chatMessages.isEmpty()) chatMessages.add(ChatMessage("教练", "你好，今天想从哪个动作开始？可以问我训练进度，或者聊聊完整动作怎么计数。"))
        fun draw() {
            messages.removeAllViews()
            chatMessages.forEach { (who, message, evidence) ->
                val bubble = label(message, 16f).apply {
                    contentDescription = "$who：$message"
                    setPadding(dp(16), dp(13), dp(16), dp(13)); background = shape(if (who == "你") wash else android.graphics.Color.WHITE, 18)
                }
                messages.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(12); if (who == "你") marginStart = dp(45) else marginEnd = dp(25)
                })
                if (evidence != null && currentVideoUri != null) messages.addView(action("查看该次视频片段  ›") { showEvidence(evidence) }.apply { textSize = 12f })
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val input = EditText(this).apply { hint = "想问教练什么？"; textSize = 16f; maxLines = 3; setTextColor(ink); setHintTextColor(subdued); background = shape(android.graphics.Color.WHITE, 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        fun send(value: String) {
            val message = value.trim().take(500); if (message.isEmpty()) return
            val s = coach.snapshot(SystemClock.elapsedRealtime())
            val context = chatContext(s)
            chatMessages.add(ChatMessage("你", message))
            chatMessages.add(ChatMessage("教练", localChat.reply(message, context), localChat.evidenceFor(message, context)))
            while (chatMessages.size > 40) chatMessages.removeAt(0)
            input.text.clear(); draw()
        }
        val quick = LinearLayout(this)
        listOf("还剩几次", "为什么没算", "随机聊聊").forEach { q -> quick.addView(action(q) { send(q) }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4); topMargin = dp(8) }) }
        body.addView(quick)
        val compose = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        compose.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); compose.addView(action("发送", true) { send(input.text.toString()) }, LinearLayout.LayoutParams(dp(74), -2).apply { marginStart = dp(8) })
        body.addView(compose)
        draw(); return body
    }

    /** Opens a training-aware, offline dialogue without leaving the live session. */
    private fun openCoachChat() {
        if (chatDialog?.isShowing == true) return
        resumeAfterChat = ongoing() && coach.stage != CoachSession.Stage.PAUSED
        if (resumeAfterChat) {
            coachHintBeforeChat = coach.snapshot(SystemClock.elapsedRealtime()).hint
            render(coach.pause(SystemClock.elapsedRealtime()))
        }

        val body = column().apply { setPadding(dp(18), dp(4), dp(18), dp(8)) }
        body.addView(label("本地文字回复 · 结合本次训练记录", 12f, subdued))
        body.addView(label(if (resumeAfterChat) "聊天时训练已暂停，关闭后继续。" else "训练保持暂停，返回后可手动继续。", 13f, green))
        val messages = column()
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(messages) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (chatMessages.isEmpty()) {
            chatMessages.add(ChatMessage("教练", "我会结合当前${exercise.label}的次数、组数和识别提示回答。你可以问刚才哪里要调整，或为什么没有计数。"))
        }
        fun drawMessages() {
            messages.removeAllViews()
            chatMessages.forEach { (who, message, evidence) ->
                val bubble = label(message, 15f).apply {
                    contentDescription = "$who：$message"
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    background = shape(if (who == "你") wash else android.graphics.Color.WHITE, 16)
                }
                messages.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    if (who == "你") marginStart = dp(32) else marginEnd = dp(20)
                })
                if (evidence != null && currentVideoUri != null) messages.addView(action("查看该次视频片段  ›") { showEvidence(evidence) }.apply { textSize = 12f })
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val input = EditText(this).apply {
            hint = "问问训练进度或动作提示"; textSize = 15f; maxLines = 2
            setTextColor(ink); setHintTextColor(subdued); background = shape(0xfff5f6f2.toInt(), 14)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        fun send(value: String) {
            val message = value.trim().take(500)
            if (message.isEmpty()) return
            val state = coach.snapshot(SystemClock.elapsedRealtime())
            val context = chatContext(state, if (resumeAfterChat) coachHintBeforeChat else state.hint)
            chatMessages.add(ChatMessage("你", message))
            chatMessages.add(ChatMessage("教练", localChat.reply(message, context), localChat.evidenceFor(message, context)))
            while (chatMessages.size > 40) chatMessages.removeAt(0)
            input.text.clear()
            drawMessages()
        }
        val quick = LinearLayout(this)
        listOf("刚才哪里要调整？", "为什么没计数？", "还剩几次？").forEach { question ->
            quick.addView(action(question) { send(question) }.apply { textSize = 11f; minHeight = dp(46) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4); topMargin = dp(8) })
        }
        body.addView(quick)
        val compose = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        compose.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        compose.addView(action("发送", true) { send(input.text.toString()) },
            LinearLayout.LayoutParams(dp(76), -2).apply { marginStart = dp(8) })
        body.addView(compose)
        drawMessages()

        val dialog = AlertDialog.Builder(this)
            .setTitle("训练教练")
            .setView(body)
            .setNegativeButton(if (resumeAfterChat) "继续训练" else "返回训练", null)
            .create()
        chatDialog = dialog
        dialog.setOnDismissListener {
            chatDialog = null
            if (resumeAfterChat && ongoing() && coach.stage == CoachSession.Stage.PAUSED) {
                render(coach.resume(SystemClock.elapsedRealtime()))
            }
            resumeAfterChat = false
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(-1, (resources.displayMetrics.heightPixels * .78f).toInt())
            window.setGravity(Gravity.BOTTOM)
        }
    }

    private fun chatContext(state: CoachSession.Snapshot, hint: String = inputMessage ?: state.hint) =
        LocalCoachChat.Context(exercise.label, stageLabel(state), state.reps, plan.reps,
            state.totalReps, state.set, plan.sets, hint, source, state.activeMs / 1000, state.feedback,
            if (sessionId.isNotEmpty()) sessionCalories(state) else null)

    private fun stageLabel(state: CoachSession.Snapshot) = when (state.stage) {
        CoachSession.Stage.IDLE -> "未开始"
        CoachSession.Stage.PREPARING -> "准备中"
        CoachSession.Stage.ACTIVE -> "训练中"
        CoachSession.Stage.REST -> "组间休息"
        CoachSession.Stage.PAUSED -> "已暂停"
        CoachSession.Stage.FINISHED -> if (state.completed) "计划完成" else "已结束"
    }

    private fun showEvidence(entry: CoachSession.Feedback) {
        val uri = currentVideoUri ?: return
        evidenceDialog?.dismiss()
        val body = column().apply { setPadding(dp(16), dp(6), dp(16), dp(10)) }
        val video = VideoView(this)
        body.addView(FrameLayout(this).apply {
            setBackgroundColor(0xff101712.toInt())
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, dp(260)))
        body.addView(label("第${entry.set}组 · ${entry.message}", 15f))
        body.addView(label("原片静音回放 · 不重复计数", 12f, subdued))
        var start = 0
        var end = 0
        val watch = object : Runnable {
            override fun run() {
                if (evidenceDialog?.isShowing != true) return
                if (end > 0 && video.currentPosition >= end) video.pause()
                else if (video.isPlaying) main.postDelayed(this, 100)
            }
        }
        body.addView(action("重看该次片段") {
            video.seekTo(start); video.start(); main.removeCallbacks(watch); main.postDelayed(watch, 100)
        })
        val dialog = AlertDialog.Builder(this).setTitle("该次反馈的视频依据").setView(body)
            .setNegativeButton("返回对话", null).create()
        evidenceDialog = dialog
        dialog.setOnDismissListener {
            main.removeCallbacks(watch); video.stopPlayback(); evidenceDialog = null
        }
        video.setOnPreparedListener { player ->
            player.setVolume(0f, 0f)
            val duration = player.duration.coerceAtLeast(1)
            val point = if (bundledVideo) (entry.timeMs % duration).toInt() else entry.timeMs.coerceAtMost(duration.toLong()).toInt()
            start = (point - 2500).coerceAtLeast(0)
            end = (point + 1000).coerceAtMost(duration)
            video.seekTo(start); video.start(); main.postDelayed(watch, 100)
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "原片暂时无法回放，文字反馈记录仍然保留", Toast.LENGTH_SHORT).show()
            true
        }
        dialog.show()
        video.setVideoURI(uri)
    }

    private fun planLabel() = "测试计划：${plan.sets}组 × ${plan.reps}次 · 休息${plan.restMs / 1000}秒"
    private fun choosePlan() {
        if (coach.stage !in listOf(CoachSession.Stage.IDLE, CoachSession.Stage.FINISHED)) return
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 10, 32, 10) }
        fun number(label: String, value: Int): EditText {
            fields.addView(TextView(this).apply { text = label })
            return EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(value.toString()); fields.addView(this) }
        }
        val sets = number("组数（1—10）", plan.sets)
        val reps = number("每组次数（1—30）", plan.reps)
        val rest = number("组间休息秒数（0—300）", (plan.restMs / 1000).toInt())
        val dialog = AlertDialog.Builder(this).setTitle("自定义训练目标").setView(fields)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = sets.text.toString().toIntOrNull(); val b = reps.text.toString().toIntOrNull(); val c = rest.text.toString().toIntOrNull()
                if (a == null || a !in 1..10) { sets.error = "请输入1—10"; return@setOnClickListener }
                if (b == null || b !in 1..30) { reps.error = "请输入1—30"; return@setOnClickListener }
                if (c == null || c !in 0..300) { rest.error = "请输入0—300"; return@setOnClickListener }
                plan = CoachSession.Plan(a, b, c * 1000L)
                resetSelection(); dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun resetSelection() {
        coach = CoachSession(plan, guide); sessionId = ""; inputMessage = null; source = ""
        calorieBasis = null
        localChat.reset(); chatMessages.clear()
        lastObservation = null
        planButton.text = planLabel(); exerciseButton.text = "训练动作：${exercise.label}"
        overlay.clearPose(); anglesView.text = guide.preparation
        getPreferences(Context.MODE_PRIVATE).edit().putInt("exercise", exercise.ordinal)
            .putInt("sets", plan.sets).putInt("reps", plan.reps).putLong("rest", plan.restMs).apply()
        render(coach.snapshot(SystemClock.elapsedRealtime()))
        if (::pageHost.isInitialized) navigate(screen)
    }
    private fun chooseExercise() {
        AlertDialog.Builder(this).setTitle("选择训练动作")
            .setSingleChoiceItems(BodySession.Exercise.values().map { it.label }.toTypedArray(), exercise.ordinal) { dialog, index ->
                exercise = BodySession.Exercise.values()[index]; resetSelection(); dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun startTraining(demo: Boolean, uri: Uri? = null) {
        if (demo && uri == null && exercise != BodySession.Exercise.SQUAT) { hintView.text = "内置真人视频只有深蹲，请为${exercise.label}使用摄像头或导入视频"; return }
        val weight = TrainingCalories.parseWeight(bodyWeightDraft)
        if (!demo && !weight.valid) {
            if (screen != "setup") navigate("setup")
            weightEntry?.apply { error = TrainingCalories.WEIGHT_ERROR; requestFocus() }
            return
        }
        if (!demo) {
            val cameras = (getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList
            if (cameras.isEmpty()) {
                inputMessage = "设备没有检测到摄像头，请连接后重试；可先使用真人视频教练测试"
                hintView.text = inputMessage
                navigate("setup")
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cameraPermission.launch(Manifest.permission.CAMERA); return
            }
        }
        stopInput()
        inputMessage = null
        inputEndReason = null
        videoDurationMs = 60_000L
        videoProgress.progress = 0; videoTimeView.text = "00:00 / 01:00"
        inferenceEpoch++; resetCounterOnFrame = true
        val token = generation
        source = if (demo) "真人视频演示" else "摄像头训练"
        calorieBasis = TrainingCalories.forSession(exercise.name,
            if (demo) TrainingCalories.Source.VIDEO else TrainingCalories.Source.CAMERA, weight.kilograms)
        calorieViews.details.visibility = View.GONE; calorieViews.button.text = "查看依据"
        bundledVideo = demo && uri == null
        currentVideoUri = if (bundledVideo) Uri.parse("android.resource://$packageName/${R.raw.real_squats_demo}") else uri
        inputLabel = if (!demo) "摄像头训练" else if (bundledVideo) "内置深蹲 · 循环演示" else runCatching {
            contentResolver.query(uri!!, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: "导入视频"
        sourceView.text = if (demo) "${exercise.label} · $inputLabel · 识别片中人物 · ${if (bundledVideo) "循环体验" else "前60秒按帧识别"}" else "${exercise.label} · 摄像头训练 · ${guide.preparation}"
        sessionId = ""; saved = false
        localChat.reset(); chatMessages.clear()
        lastObservation = null
        coach = CoachSession(plan, guide)
        lastRenderedStage = CoachSession.Stage.IDLE
        aligningCamera = !demo; resumeAfterAlignment = false; alignment.reset()
        overlay.clearPose(); liveCanvas = null
        overlay.videoOverlay = !demo
        preview.visibility = if (demo) View.GONE else View.VISIBLE
        navigate("training")
        if (demo) {
            startedAt = System.currentTimeMillis(); sessionId = UUID.randomUUID().toString()
            render(coach.start(SystemClock.elapsedRealtime()))
            if (uri == null) runDemo(token) else runImportedVideo(uri, token)
        } else {
            render(coach.snapshot(SystemClock.elapsedRealtime()))
            bindCamera(token)
        }
    }

    private fun beginAlignedTraining() {
        val now = SystemClock.elapsedRealtime()
        if (!aligningCamera || !alignment.ready(now)) return
        aligningCamera = false
        inferenceEpoch++; resetCounterOnFrame = true; coach.rebaseCounter()
        if (resumeAfterAlignment) render(coach.resume(now))
        else {
            startedAt = System.currentTimeMillis(); sessionId = UUID.randomUUID().toString(); saved = false
            render(coach.start(now))
        }
    }

    private fun switchCamera() {
        val cameraProvider = provider ?: return
        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) || !cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) return
        val resume = ongoing()
        if (resume) render(coach.pause(SystemClock.elapsedRealtime()))
        preferredLens = if (preferredLens == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        stopInput()
        inferenceEpoch++; resetCounterOnFrame = true; coach.rebaseCounter()
        aligningCamera = true; resumeAfterAlignment = resume; alignment.reset(); lastObservation = null
        overlay.clearPose(); liveCanvas = null
        render(coach.snapshot(SystemClock.elapsedRealtime()))
        bindCamera(generation)
    }

    private fun runImportedVideo(uri: Uri, token: Int) {
        processor.execute {
            try {
                BodySession(exercise).use { counter ->
                    LabVideoReader(this, uri).use { reader ->
                        main.post { if (generation == token) videoDurationMs = reader.durationMs }
                        reader.read({ generation != token }) { bitmap, timestamp ->
                            while (generation == token && !acceptFrames) Thread.sleep(50)
                            if (generation != token) { bitmap.recycle(); return@read }
                            val began = SystemClock.elapsedRealtime()
                            val epoch = inferenceEpoch
                            val pose = try { Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0))) }
                                catch (error: Exception) { bitmap.recycle(); throw error }
                            val observation = processPose(pose, bitmap.width, bitmap.height, timestamp, counter, epoch)
                            val points = overlayPoints(pose)
                            val shown = java.util.concurrent.CountDownLatch(1)
                            main.post {
                                try {
                                    if (generation == token && inferenceEpoch == epoch && !isDestroyed) {
                                        overlay.showPose(bitmap, points, label = "${timestamp / 1000}秒 · $inputLabel")
                                        deliver(observation, timestamp)
                                    } else bitmap.recycle()
                                } finally { shown.countDown() }
                            }
                            shown.await()
                            val wait = 200L - (SystemClock.elapsedRealtime() - began)
                            if (wait > 0) Thread.sleep(wait)
                        }
                    }
                }
                main.post { if (generation == token && !isDestroyed) {
                    inputEndReason = "已到视频末尾或前60秒分析上限，保存实际识别到的次数。"
                    finishTraining()
                } }
            } catch (error: Exception) {
                main.post { if (generation == token && !isDestroyed) inputError("视频识别未完成：${error.message}") }
            }
        }
    }

    private fun runDemo(token: Int) {
        processor.execute {
            try {
                val frames = BundledSquatFrames(this)
                main.post { if (generation == token) videoDurationMs = frames.durationMs }
                BodySession(exercise).use { counter ->
                    var frameIndex = 0
                    while (generation == token && !Thread.currentThread().isInterrupted) {
                        if (!acceptFrames) { Thread.sleep(50); continue }
                        val began = SystemClock.elapsedRealtime()
                        val epoch = inferenceEpoch
                        if (frameIndex > 0 && (frameIndex % frames.count == 0 || frameIndex % frames.count in frames.cutFrameIndices)) counter.reset()
                        val bitmap = frames.frame(frameIndex)
                        val pose = try { Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0))) }
                        catch (error: Exception) { bitmap.recycle(); throw error }
                        val observation = processPose(pose, bitmap.width, bitmap.height, frameIndex * 200L, counter, epoch)
                        val points = overlayPoints(pose)
                        val shown = java.util.concurrent.CountDownLatch(1)
                        main.post {
                            try {
                                if (generation == token && inferenceEpoch == epoch && !isDestroyed) {
                                    overlay.showPose(bitmap, points, label = "真人运动 · 实际关节识别")
                                    deliver(observation, frameIndex * 200L)
                                } else bitmap.recycle()
                            } finally { shown.countDown() }
                        }
                        shown.await()
                        frameIndex++
                        val wait = 200L - (SystemClock.elapsedRealtime() - began)
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
            } catch (error: Exception) {
                main.post { if (generation == token) inputError("视频教练测试失败：${error.message}") }
            }
        }
    }

    private fun bindCamera(token: Int) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (generation != token || isDestroyed) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                val preferred = if (preferredLens == CameraSelector.LENS_FACING_FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val selector = if (cameraProvider.hasCamera(preferred)) preferred else
                    if (preferredLens == CameraSelector.LENS_FACING_FRONT) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                mirrored = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                preferredLens = if (mirrored) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                switchCameraButton.text = if (mirrored) "前置 ⇄" else "后置 ⇄"
                switchCameraButton.isEnabled = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                val counter = BodySession(exercise)
                cameraCounter = counter
                val imageAnalysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                imageAnalysis.setAnalyzer(processor) { image ->
                    try {
                        if (generation == token && acceptFrames) {
                            val epoch = inferenceEpoch
                            val media = image.image
                            if (media != null) {
                                val rotation = image.imageInfo.rotationDegrees
                                val width = if (rotation == 90 || rotation == 270) image.height else image.width
                                val height = if (rotation == 90 || rotation == 270) image.width else image.height
                                val pose = Tasks.await(detector.process(InputImage.fromMediaImage(media, rotation)))
                                val observation = processPose(pose, width, height, SystemClock.elapsedRealtime(), counter, epoch)
                                val points = overlayPoints(pose).map { if (mirrored) it.copy(x = width - it.x) else it }
                                main.post {
                                    if (generation == token && inferenceEpoch == epoch && !isDestroyed) {
                                        var canvas = liveCanvas
                                        if (canvas == null || canvas.isRecycled || canvas.width != width || canvas.height != height) {
                                            canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                            liveCanvas = canvas
                                        }
                                        overlay.showPose(canvas, points, label = "摄像头肢体识别")
                                        deliver(observation)
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        main.post { if (generation == token) {
                            inputMessage = "相机识别暂不可用：${error.message}。请检查后再继续。"
                            render(coach.pause(SystemClock.elapsedRealtime()))
                        } }
                    } finally { image.close() }
                }
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, cameraPreview, imageAnalysis)
            } catch (error: Exception) { inputError("摄像头启动失败：${error.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processPose(pose: Pose, width: Int, height: Int, timestamp: Long, counter: BodySession, epoch: Int): CoachSession.Observation? {
        if (epoch != inferenceEpoch) return null
        if (resetCounterOnFrame) { counter.reset(); resetCounterOnFrame = false }
        val values = FloatArray(99)
        pose.allPoseLandmarks.forEach { point ->
            val i = point.landmarkType
            val x = point.position.x / width; val y = point.position.y / height
            if (i in 0..32 && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && point.inFrameLikelihood in .5f..1f) {
                values[i * 3] = x; values[i * 3 + 1] = y; values[i * 3 + 2] = point.inFrameLikelihood
            }
        }
        return try {
            val result = counter.process(values, width, height, timestamp)
            if (epoch != inferenceEpoch) { resetCounterOnFrame = true; return null }
            val bilateral = exercise != BodySession.Exercise.PUSH_UP ||
                intArrayOf(11, 12, 13, 14, 15, 16, 23, 24).all { values[it * 3 + 2] >= .5f }
            CoachSession.Observation(result.repetitions, result.progress, result.leftAngleDeg,
                result.rightAngleDeg, result.lastRepScore, result.phase != BodySession.Phase.UNKNOWN,
                bilateral, if (source == "真人视频演示") timestamp else null)
        } catch (_: IllegalArgumentException) { null }
    }
    private fun overlayPoints(pose: Pose) = pose.allPoseLandmarks.map {
        PoseOverlayView.Landmark(it.landmarkType, it.position.x, it.position.y, it.inFrameLikelihood)
    }
    private fun deliver(observation: CoachSession.Observation?, videoTimeMs: Long? = null) {
        lastObservation = observation
        if (videoTimeMs != null) {
            val duration = videoDurationMs.coerceAtLeast(1)
            val position = if (bundledVideo) videoTimeMs % duration else videoTimeMs.coerceAtMost(duration)
            fun time(value: Long) = "%02d:%02d".format(value / 60000, value / 1000 % 60)
            videoTimeView.text = "${time(position)} / ${time(duration)}"
            videoProgress.max = duration.toInt(); videoProgress.progress = position.toInt()
        }
        if (aligningCamera) alignment.observe(observation, SystemClock.elapsedRealtime())
        anglesView.text = observation?.let { "左/右${exercise.angleName} ${it.leftAngle.toInt()}° / ${it.rightAngle.toInt()}° · 当前幅度 ${(it.progress * 100).toInt()}%" }
            ?: "${guide.visibility}关键点不清楚，本帧不计数"
        render(coach.observe(observation, SystemClock.elapsedRealtime()))
    }

    private fun render(state: CoachSession.Snapshot) {
        if (state.stage == CoachSession.Stage.ACTIVE && lastRenderedStage != CoachSession.Stage.ACTIVE) {
            inferenceEpoch++; resetCounterOnFrame = true; coach.rebaseCounter()
        }
        lastRenderedStage = state.stage
        val isCamera = source == "摄像头训练"
        val ready = alignment.ready(SystemClock.elapsedRealtime())
        acceptFrames = aligningCamera || state.stage == CoachSession.Stage.ACTIVE ||
            (isCamera && state.stage == CoachSession.Stage.PREPARING)
        window.decorView.keepScreenOn = inForeground && screen == "training" && (aligningCamera || state.stage in
            listOf(CoachSession.Stage.PREPARING, CoachSession.Stage.ACTIVE, CoachSession.Stage.REST))
        stageView.text = if (aligningCamera) "先把自己放进画面里" else when (state.stage) {
            CoachSession.Stage.IDLE -> "准备训练${exercise.label}"
            CoachSession.Stage.PREPARING -> "准备 ${state.remainingSeconds} 秒"
            CoachSession.Stage.ACTIVE -> "第${state.set}组 · 训练中"
            CoachSession.Stage.REST -> "组间休息 ${state.remainingSeconds} 秒"
            CoachSession.Stage.PAUSED -> "已暂停"
            CoachSession.Stage.FINISHED -> if (state.completed) "计划完成" else "本次测试已结束"
        }
        val calories = sessionCalories(state)
        val calorieNote = when {
            calories.status != TrainingCalories.Status.ESTIMATED -> calories.summaryLabel
            aligningCamera || state.stage == CoachSession.Stage.IDLE -> "站位准备，不累计消耗"
            state.stage == CoachSession.Stage.PREPARING -> "准备中，消耗尚未累计"
            state.stage == CoachSession.Stage.PAUSED -> "已暂停，消耗停止累计"
            state.stage == CoachSession.Stage.REST -> "组间休息，消耗停止累计"
            state.stage == CoachSession.Stage.FINISHED -> "有效训练时段总消耗，含静息消耗"
            else -> "按有效训练时长累计"
        }
        fillCalorieCard(calorieViews, calories, calorieNote)
        alignmentButton.visibility = if (aligningCamera) View.VISIBLE else View.GONE
        alignmentButton.isEnabled = aligningCamera && ready
        alignmentButton.text = if (!ready) "请保持${guide.startPose}，让所需关节入镜" else
            if (resumeAfterAlignment) "站位已就绪 · 继续训练" else "站位已就绪 · 开始3秒准备"
        switchCameraButton.visibility = if (isCamera) View.VISIBLE else View.GONE
        videoTimeline.visibility = if (currentVideoUri != null) View.VISIBLE else View.GONE
        countsView.visibility = if (aligningCamera) View.GONE else View.VISIBLE
        progress.visibility = if (aligningCamera) View.GONE else View.VISIBLE
        countsView.text = if (state.stage == CoachSession.Stage.REST || state.stage == CoachSession.Stage.PREPARING) "${state.remainingSeconds} 秒" else "${state.reps.toString().padStart(2, '0')} / ${plan.reps} 次"
        sessionMeta.text = "第 ${state.set} / ${plan.sets} 组 · 累计 ${state.totalReps} 次 · 训练计时 ${state.activeMs / 1000} 秒"
        capture.visibility = if (!aligningCamera && (state.stage == CoachSession.Stage.REST ||
            state.stage == CoachSession.Stage.PREPARING && !isCamera)) View.GONE else View.VISIBLE
        restAction.visibility = if (state.stage == CoachSession.Stage.REST) View.VISIBLE else View.GONE
        progress.max = plan.reps; progress.progress = state.reps
        hintView.text = inputMessage ?: if (aligningCamera) {
            if (ready) "身体已完整入镜，准备好后点开始" else guide.preparation
        } else state.hint
        feedbackDetailView.text = when {
            inputMessage != null -> "处理设备状态时暂停姿态结论；可以调整画面后重试"
            state.stage == CoachSession.Stage.ACTIVE && lastObservation == null -> "没有足够清晰的关键点时，不会判断动作问题或增加次数"
            state.hint.contains("左右角度差") -> "依据可见关节的左右角度差提示；请检查站位与两侧动作是否同步"
            state.hint.contains("未达到") -> "这次幅度未达到计数规则；请在舒适范围内完成动作，不必勉强"
            state.hint.contains("较快") -> "节奏提示来自动作计数间隔，不是完整姿势评分"
            else -> "本地姿态识别与角度规则提示，仅供训练参考"
        }
        summaryView.visibility = if (state.stage == CoachSession.Stage.FINISHED) View.VISIBLE else View.GONE
        if (state.stage == CoachSession.Stage.FINISHED) summaryView.text = summaryText(state)
        val idle = state.stage in listOf(CoachSession.Stage.IDLE, CoachSession.Stage.FINISHED)
        demoButton.isEnabled = idle && exercise == BodySession.Exercise.SQUAT; cameraButton.isEnabled = idle; planButton.isEnabled = idle; exerciseButton.isEnabled = idle
        pauseButton.isEnabled = !idle && !aligningCamera; stopButton.isEnabled = !idle || aligningCamera
        stopButton.text = if (aligningCamera && !ongoing()) "返回设置" else "结束训练"
        pauseButton.text = if (state.stage == CoachSession.Stage.PAUSED) "继续训练" else "暂停训练"
        if (state.stage == CoachSession.Stage.FINISHED && sessionId.isNotEmpty() && !saved) {
            save(state)
            stopInput()
            if (screen == "training") navigate("summary")
        }
    }
    private fun finishTraining() {
        if (coach.stage != CoachSession.Stage.IDLE) render(coach.finish(SystemClock.elapsedRealtime()))
        stopInput()
    }
    private fun stopInput() {
        generation++; acceptFrames = false
        provider?.unbindAll()
        val old = cameraCounter; cameraCounter = null
        if (old != null && !processor.isShutdown) processor.execute { old.close() }
    }
    private fun inputError(message: String) {
        inputMessage = message
        if (coach.snapshot(SystemClock.elapsedRealtime()).totalReps == 0) saved = true
        finishTraining()
        navigate("setup")
    }
    private fun save(state: CoachSession.Snapshot) {
        if (saved) return
        saved = true
        history.save(JSONObject().apply {
            put("exercise", exercise.name); put("exerciseLabel", exercise.label); put("angleName", exercise.angleName)
            put("id", sessionId); put("startedAt", startedAt); put("source", source)
            put("inputLabel", inputLabel); put("inputEndReason", inputEndReason ?: JSONObject.NULL)
            put("completed", state.completed); put("totalReps", state.totalReps)
            put("sets", plan.sets); put("repsPerSet", plan.reps); put("completedSets", state.completedSets)
            put("activeMs", state.activeMs); put("restMs", plan.restMs)
            put("calorieEstimate", JSONObject(sessionCalories(state).toFields()))
            put("amplitudeSymmetryIndex", state.averageIndex ?: JSONObject.NULL)
            put("minimumJointAngle", state.minimumKnee ?: JSONObject.NULL)
            put("shallowAttempts", state.shallowAttempts); put("fastReps", state.fastReps)
            put("feedbackEvents", org.json.JSONArray(state.feedback.map { entry -> JSONObject().apply {
                put("id", entry.id); put("kind", entry.kind.name); put("set", entry.set)
                put("repetition", entry.repetition ?: JSONObject.NULL); put("timeMs", entry.timeMs)
                put("message", entry.message)
            } }))
            put("scoring", "heuristic amplitude/symmetry only, not exercise-quality assessment")
        })
    }
    private fun summaryText(state: CoachSession.Snapshot): String {
        val average = state.averageIndex?.toInt()?.toString() ?: "--"
        return "本次总结 · ${exercise.label} · $source\n完整动作 ${state.totalReps} 次，完成 ${state.completedSets}/${plan.sets} 组\n" +
            "训练计时 ${state.activeMs / 1000} 秒 · 幅度/对称演示指数 $average / 100\n" +
            sessionCalories(state).summaryLabel + "\n" +
            "未达计数幅度 ${state.shallowAttempts} 次 · 节奏较快 ${state.fastReps} 次\n" +
            "演示与本人训练分开标记。指数不是完整动作质量评价。"
    }
    override fun onStart() { super.onStart(); inForeground = true }
    override fun onStop() {
        inForeground = false
        resumeAfterChat = false
        evidenceDialog?.dismiss()
        chatDialog?.dismiss()
        if (coach.stage !in listOf(CoachSession.Stage.IDLE, CoachSession.Stage.FINISHED)) {
            render(coach.pause(SystemClock.elapsedRealtime()))
        }
        super.onStop()
    }
    override fun onDestroy() {
        closing = true
        if (!saved && sessionId.isNotEmpty()) save(coach.finish(SystemClock.elapsedRealtime()))
        main.removeCallbacks(pulse)
        summaryDialog?.dismiss()
        evidenceDialog?.dismiss()
        chatDialog?.dismiss()
        stopInput()
        overlay.clearPose()
        processor.execute { detector.close() }
        processor.shutdown()
        super.onDestroy()
    }
}
