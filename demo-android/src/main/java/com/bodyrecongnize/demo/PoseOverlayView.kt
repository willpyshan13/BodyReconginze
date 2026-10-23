package com.bodyrecongnize.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Shows a still photo with ML Kit pose landmarks drawn in source-image coordinates. */
class PoseOverlayView(context: Context) : View(context) {
    data class Landmark(val index: Int, val x: Float, val y: Float, val confidence: Float)

    private var image: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    var videoOverlay = false
    var retainImageOnDetach = false
    private var landmarks: List<Landmark> = emptyList()
    private var comparisonLandmarks: List<Landmark> = emptyList()
    private var thirdLandmarks: List<Landmark> = emptyList()
    private var caption: String? = null
    private var showMlKit = true
    private var showRtmpose = true
    private var showMoveNet = true

    fun setSkeletonVisibility(mlKit: Boolean, rtmpose: Boolean, moveNet: Boolean) {
        showMlKit = mlKit
        showRtmpose = rtmpose
        showMoveNet = moveNet
        invalidate()
    }
    private val thirdBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffed77ff.toInt()
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val thirdPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffed77ff.toInt() }
    private val comparisonBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff40c9ff.toInt()
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val comparisonPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff40c9ff.toInt() }
    private val density = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff59f28c.toInt()
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffcf5a.toInt()
        style = Paint.Style.FILL
    }
    private val pointOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff102018.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xcc10141b.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt()
        textSize = 14f * density
    }

    fun showPose(bitmap: Bitmap, points: List<Landmark>,
                 comparisonPoints: List<Landmark> = emptyList(), label: String? = null,
                 thirdPoints: List<Landmark> = emptyList()) {
        image?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        image = bitmap
        sourceWidth = bitmap.width
        sourceHeight = bitmap.height
        landmarks = points
        comparisonLandmarks = comparisonPoints
        caption = label
        thirdLandmarks = thirdPoints
        visibility = VISIBLE
        invalidate()
    }

    fun showPoseOverlay(width: Int, height: Int, points: List<Landmark>, label: String? = null) {
        image?.takeIf { !it.isRecycled }?.recycle()
        image = null
        sourceWidth = width
        sourceHeight = height
        landmarks = points
        comparisonLandmarks = emptyList()
        thirdLandmarks = emptyList()
        caption = label
        videoOverlay = true
        visibility = VISIBLE
        invalidate()
    }

    fun clearPose() {
        image?.takeIf { !it.isRecycled }?.recycle()
        image = null
        sourceWidth = 0
        sourceHeight = 0
        landmarks = emptyList()
        comparisonLandmarks = emptyList()
        thirdLandmarks = emptyList()
        caption = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!videoOverlay) canvas.drawColor(0xff080b10.toInt())
        val bitmap = image
        if (bitmap?.isRecycled == true || width == 0 || height == 0) return
        val imageWidth = bitmap?.width ?: sourceWidth
        val imageHeight = bitmap?.height ?: sourceHeight
        if (imageWidth <= 0 || imageHeight <= 0) return

        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val drawWidth = imageWidth * scale
        val drawHeight = imageHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        val destination = RectF(left, top, left + drawWidth, top + drawHeight)
        if (!videoOverlay && bitmap != null) canvas.drawBitmap(bitmap, null, destination, imagePaint)

        fun drawSkeleton(points: List<Landmark>, threshold: Float, bones: Paint, dots: Paint, radius: Float) {
            fun screenPoint(index: Int): Pair<Float, Float>? {
                val point = points.firstOrNull { it.index == index } ?: return null
                if (point.confidence < threshold || !point.x.isFinite() || !point.y.isFinite()) return null
                return left + point.x * scale to top + point.y * scale
            }
            BONES.forEach { (start, end) ->
                val a = screenPoint(start) ?: return@forEach
                val b = screenPoint(end) ?: return@forEach
                canvas.drawLine(a.first, a.second, b.first, b.second, bones)
            }
            points.forEach { point ->
                val position = screenPoint(point.index) ?: return@forEach
                canvas.drawCircle(position.first, position.second, radius * density, dots)
                canvas.drawCircle(position.first, position.second, radius * density, pointOutlinePaint)
            }
        }
        if (showMlKit) drawSkeleton(landmarks, MIN_CONFIDENCE, bonePaint, pointPaint, 5f)
        if (showRtmpose) drawSkeleton(comparisonLandmarks, .3f, comparisonBonePaint, comparisonPointPaint, 3.5f)
        if (showMoveNet) drawSkeleton(thirdLandmarks, .3f, thirdBonePaint, thirdPointPaint, 3f)

        val label = if (!showMlKit && !showRtmpose && !showMoveNet) "骨架已隐藏"
            else caption ?: if (landmarks.any { it.confidence >= MIN_CONFIDENCE }) "识别骨架 · 绿线/黄点"
            else "未检测到骨架关键点"
        val padding = 9f * density
        val labelWidth = labelPaint.measureText(label)
        val labelHeight = labelPaint.textSize + padding * 1.5f
        canvas.drawRoundRect(
            padding, padding, padding * 2 + labelWidth, padding + labelHeight,
            8f * density, 8f * density, labelBackgroundPaint
        )
        canvas.drawText(label, padding * 1.5f, padding + labelHeight - padding * 0.6f, labelPaint)
    }

    override fun onDetachedFromWindow() {
        if (!retainImageOnDetach) clearPose()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.5f
        val BONES = arrayOf(
            11 to 12,
            11 to 13, 13 to 15,
            12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 27 to 29, 29 to 31,
            24 to 26, 26 to 28, 28 to 30, 30 to 32
        )
    }
}
