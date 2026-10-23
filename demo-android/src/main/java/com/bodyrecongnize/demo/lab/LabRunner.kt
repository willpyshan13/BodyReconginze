package com.bodyrecongnize.demo.lab

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import com.bodyrecongnize.BodySession
import com.bodyrecongnize.demo.coach.BundledSquatFrames
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import java.util.UUID

/** Single-worker only. Each output retains exactly the image actually sent to pose inference. */
class LabRunner(private val context: Context, private val store: LabStore) : AutoCloseable {
    private val detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
        .setPreferredHardwareConfigs(PoseDetectorOptions.CPU).build())
    fun collect(id: String, bitmap: Bitmap, timeMs: Long, frames: MutableList<LabFrame>, cut: Boolean = false) {
        try {
            val began = SystemClock.elapsedRealtimeNanos()
            val pose = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 15, java.util.concurrent.TimeUnit.SECONDS)
            val elapsed = (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
            val points = FloatArray(99)
            pose.allPoseLandmarks.forEach { p ->
                val i = p.landmarkType; val x = p.position.x / bitmap.width; val y = p.position.y / bitmap.height
                if (i in 0..32 && x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f && p.inFrameLikelihood in 0f..1f) {
                    points[i * 3] = x; points[i * 3 + 1] = y; points[i * 3 + 2] = p.inFrameLikelihood
                }
            }
            val folder = store.folder(id).apply { mkdirs() }
            val name = "frame_%04d.png".format(frames.size)
            File(folder, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            frames.add(LabFrame(maxOf(timeMs, (frames.lastOrNull()?.timeMs ?: -1) + 1), bitmap.width, bitmap.height, points, name, elapsed, cut))
        } finally { bitmap.recycle() }
    }
    fun finish(id: String, source: String, condition: String, frames: List<LabFrame>, began: Long,
               baselineId: String? = null, truth: Int? = null, expected: LabKind? = null): LabRun {
        require(frames.isNotEmpty()) { "没有取得有效视频帧" }
        val measures = LabKind.values().associateWith { kind ->
            BodySession(BodySession.Exercise.values().first { it.id == kind.nativeId }).use { counter ->
                var offset = 0; var previousReps = 0
                frames.map { frame ->
                    if (frame.cut) { offset += previousReps; previousReps = 0; counter.reset() }
                    try {
                        val r = counter.process(frame.points, frame.width, frame.height, frame.timeMs)
                        previousReps = r.repetitions
                        LabMeasure(offset + r.repetitions, r.progress, r.leftAngleDeg, r.rightAngleDeg, r.phase != BodySession.Phase.UNKNOWN)
                    } catch (_: IllegalArgumentException) { null }
                }
            }
        }
        val (events, segments) = LabAnalyzer.analyze(frames, measures)
        return LabRun(id, source, condition, frames.toList(), events, segments, SystemClock.elapsedRealtime() - began,
            System.currentTimeMillis(), truth, expected, baselineId).also { store.save(it) }
    }
    fun analyze(uri: Uri?, cancelled: () -> Boolean, update: (String) -> Unit): LabRun {
        val id = UUID.randomUUID().toString(); val frames = mutableListOf<LabFrame>(); val began = SystemClock.elapsedRealtime()
        try {
            if (uri == null) {
                val bundle = BundledSquatFrames(context)
                repeat(bundle.count) { index ->
                    if (cancelled()) throw InterruptedException()
                    collect(id, bundle.frame(index), index * 200L, frames, index in bundle.cutFrameIndices)
                    if (index % 5 == 0) update("提取真实关节点 ${index + 1}/${bundle.count} 帧")
                }
            } else {
                // Known bundled clip remains a cut clip when imported under another filename.
                val manifest = org.json.JSONObject(context.assets.open("real_squat_frames/index.json").bufferedReader().use { it.readText() })
                val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                val hash = if (length in 0..134217728L) context.contentResolver.openInputStream(uri)?.use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    while (true) { if (cancelled()) throw InterruptedException(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                } else null
                val cuts = if (hash == manifest.optString("sourceVideoSha256")) manifest.getJSONArray("cutTimesMs").let { a -> (0 until a.length()).map { a.getLong(it) } } else emptyList()
                LabVideoReader(context, uri).use { reader ->
                reader.read({ cancelled() || frames.size >= 300 }) { bitmap, timestamp ->
                    if (cancelled()) { bitmap.recycle(); throw InterruptedException() }
                    val cut = cuts.any { it > (frames.lastOrNull()?.timeMs ?: -1L) && it <= timestamp }
                    collect(id, bitmap, timestamp, frames, cut)
                    if (frames.size % 5 == 0) update("已分析 ${frames.size} 帧 · ${LabAnalyzer.time(timestamp)}")
                }
            }
            }
            if (cancelled()) throw InterruptedException()
            update("确认动作片段与计数事件…")
            return finish(id, if (uri == null) "内置真人深蹲视频" else "导入视频 · 前60秒", "原片", frames, began)
        } catch (e: Exception) { store.folder(id).deleteRecursively(); throw e }
    }
    fun test(baseline: LabRun, condition: String, cancelled: () -> Boolean, update: (String) -> Unit): LabRun {
        val id = UUID.randomUUID().toString(); val frames = mutableListOf<LabFrame>(); val began = SystemClock.elapsedRealtime()
        try {
            baseline.frames.forEachIndexed { i, frame ->
                if (cancelled()) throw InterruptedException()
                val original = BitmapFactory.decodeFile(store.image(baseline, frame).absolutePath) ?: error("参考帧已不存在，请重新分析")
                val transformed = transform(original, condition, frame.timeMs)
                if (transformed !== original) original.recycle()
                collect(id, transformed, frame.timeMs, frames, frame.cut)
                if (i % 5 == 0) update("$condition · 实际重新识别 ${i + 1}/${baseline.frames.size} 帧")
            }
            if (cancelled()) throw InterruptedException()
            return finish(id, baseline.source, condition, frames, began, baseline.id, baseline.truth, baseline.expected)
        } catch (e: Exception) { store.folder(id).deleteRecursively(); throw e }
    }
    private fun transform(input: Bitmap, condition: String, timeMs: Long): Bitmap {
        return when (condition) {
            "短时遮挡" -> if (timeMs in 2600..3400) transform(input, "中心遮挡", timeMs) else input.copy(Bitmap.Config.ARGB_8888, false)
            "中心遮挡" -> input.copy(Bitmap.Config.ARGB_8888, true).also { output ->
                Canvas(output).drawRect(output.width * .25f, output.height * .4f, output.width * .75f, output.height * .8f, Paint().apply { color = Color.BLACK })
            }
            "降低亮度" -> Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888).also { output ->
                Canvas(output).drawBitmap(input, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(.3f,0f,0f,0f,0f, 0f,.3f,0f,0f,0f, 0f,0f,.3f,0f,0f, 0f,0f,0f,1f,0f))) })
            }
            "低分辨率" -> Bitmap.createScaledBitmap(input, (input.width / 4).coerceAtLeast(1), (input.height / 4).coerceAtLeast(1), true)
            else -> input.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
    override fun close() { detector.close() }
}
