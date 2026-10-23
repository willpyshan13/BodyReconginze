package com.bodyrecongnize.demo.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.bodyrecongnize.demo.PoseOverlayView
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MoveNet SinglePose Lightning float16 v4, local TFLite CPU/XNNPACK inference. */
class MoveNetDetector(context: Context) : AutoCloseable {
    private val model = context.assets.open("movenet_lightning.tflite").use { input ->
        val bytes = input.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().apply {
        setNumThreads(2)
        setUseXNNPACK(true)
        setUseNNAPI(false)
    })
    private val inputType = interpreter.getInputTensor(0).dataType()
    private val input = ByteBuffer.allocateDirect(192 * 192 * 3 * when (inputType) {
        DataType.UINT8 -> 1
        DataType.INT32, DataType.FLOAT32 -> 4
        else -> error("Unsupported MoveNet input: $inputType")
    }).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(17 * 3 * 4).order(ByteOrder.nativeOrder())
    private val image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(192 * 192)
    private val channelShifts = intArrayOf(16, 8, 0)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 192, 192, 3)))
        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 1, 17, 3)))
        check(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
        interpreter.allocateTensors()
    }

    fun detect(bitmap: Bitmap): List<PoseOverlayView.Landmark> {
        val geometry = MoveNetGeometry(bitmap.width, bitmap.height)
        val scale = 192f / geometry.side
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, null, RectF(-geometry.left * scale, -geometry.top * scale,
            (bitmap.width - geometry.left) * scale, (bitmap.height - geometry.top) * scale), paint)
        image.getPixels(pixels, 0, 192, 0, 0, 192, 192)
        input.rewind()
        // MoveNet consumes raw RGB values 0..255, not ImageNet-normalized data.
        for (color in pixels) {
            for (shift in channelShifts) {
                val channel = (color ushr shift) and 255
                when (inputType) {
                    DataType.UINT8 -> input.put(channel.toByte())
                    DataType.INT32 -> input.putInt(channel)
                    DataType.FLOAT32 -> input.putFloat(channel.toFloat())
                    else -> error("Unsupported input")
                }
            }
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        return List(17) { index ->
            val y = geometry.sourceY(output.float)
            val x = geometry.sourceX(output.float)
            val score = output.float
            PoseOverlayView.Landmark(PoseGeometry.COCO_TO_OVERLAY[index], x, y,
                if (x.isFinite() && y.isFinite() && score.isFinite() &&
                    x in 0f..bitmap.width.toFloat() && y in 0f..bitmap.height.toFloat()) score else 0f)
        }
    }

    override fun close() {
        interpreter.close()
        image.recycle()
    }
}
