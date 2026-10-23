package com.bodyrecongnize.demo.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bodyrecongnize.demo.PoseOverlayView
import java.nio.FloatBuffer

/** RTMPose-t BODY7/COCO-17, local CPU inference, for a single full-body subject in frame. */
class RtmposeDetector(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val bytes = context.assets.open("rtmpose_t.onnx").use { it.readBytes() }
        environment.createSession(bytes, options)
    }
    private val inputName = session.inputNames.single()
    private val inputBitmap = Bitmap.createBitmap(192, 256, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(192 * 256)
    private val values = FloatArray(3 * 192 * 256)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun detect(bitmap: Bitmap): List<PoseOverlayView.Landmark> {
        val geometry = PoseGeometry(bitmap.width, bitmap.height)
        val canvas = Canvas(inputBitmap)
        canvas.drawColor(Color.BLACK)
        // Affine image -> padded 192x256 crop, RGB (pipeline.json specifies to_rgb=true).
        val xScale = 192f / geometry.scaleWidth
        val yScale = 256f / geometry.scaleHeight
        canvas.drawBitmap(bitmap, null, RectF(
            -geometry.left * xScale, -geometry.top * yScale,
            (bitmap.width - geometry.left) * xScale, (bitmap.height - geometry.top) * yScale
        ), paint)
        inputBitmap.getPixels(pixels, 0, 192, 0, 0, 192, 256)
        val plane = pixels.size
        for (i in pixels.indices) {
            val color = pixels[i]
            values[i] = (((color ushr 16) and 255) - 123.675f) / 58.395f
            values[plane + i] = (((color ushr 8) and 255) - 116.28f) / 57.12f
            values[plane * 2 + i] = ((color and 255) - 103.53f) / 57.375f
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 3, 256, 192)).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val xs = (result[0].value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val ys = (result[1].value as Array<Array<FloatArray>>)[0]
                check(xs.size == 17 && ys.size == 17 && xs[0].size == 384 && ys[0].size == 512) {
                    "Unexpected RTMPose SimCC output shape"
                }
                return xs.indices.map { index ->
                    val xBin = xs[index].indices.maxBy { xs[index][it] }
                    val yBin = ys[index].indices.maxBy { ys[index][it] }
                    val score = minOf(xs[index][xBin], ys[index][yBin])
                    val x = geometry.sourceX(xBin)
                    val y = geometry.sourceY(yBin)
                    PoseOverlayView.Landmark(PoseGeometry.COCO_TO_OVERLAY[index], x, y,
                        if (x.isFinite() && y.isFinite() && x in 0f..bitmap.width.toFloat() &&
                            y in 0f..bitmap.height.toFloat()) score.coerceAtLeast(0f) else 0f)
                }
            }
        }
    }

    override fun close() {
        session.close()
        inputBitmap.recycle()
    }
}
