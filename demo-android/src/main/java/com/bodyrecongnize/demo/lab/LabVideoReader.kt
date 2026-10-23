package com.bodyrecongnize.demo.lab

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer

/** Decode sequentially: some API25 retrievers return a repeated keyframe for every requested time. */
class LabVideoReader(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var rotation = 0
    var durationMs = 60_000L
        private set
    init {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { file ->
                if (file.declaredLength >= 0) extractor.setDataSource(file.fileDescriptor, file.startOffset, file.declaredLength)
                else extractor.setDataSource(file.fileDescriptor)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("没有视频轨道")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationMs =
                (format.getLong(MediaFormat.KEY_DURATION) / 1000).coerceIn(1, 60_000)
            rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(format, null, null, 0); it.start()
            }
        } catch (error: Exception) { close(); throw error }
    }
    fun read(cancelled: () -> Boolean, consume: (Bitmap, Long) -> Unit) {
        val decoder = codec ?: error("解码器已关闭")
        var inputDone = false; var outputDone = false; var nextTime = 0L; var firstTime = -1L
        val info = MediaCodec.BufferInfo(); var idleAt = SystemClock.elapsedRealtime()
        while (!outputDone && !cancelled()) {
            if (!inputDone) {
                val input = decoder.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = decoder.getInputBuffer(input)!!.apply { clear() }
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) { decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val out = decoder.dequeueOutputBuffer(info, 10_000)
            if (out >= 0) {
                idleAt = SystemClock.elapsedRealtime()
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        if (firstTime < 0) firstTime = info.presentationTimeUs
                        val time = (info.presentationTimeUs - firstTime) / 1000
                        if (time >= 60000) outputDone = true
                        if (!outputDone && time >= nextTime) {
                            val image = decoder.getOutputImage(out) ?: error("设备解码器不支持帧图像输出，请改用兼容视频；不会用重复帧代替")
                            val raw = try { imageBitmap(image) } finally { image.close() }
                            val rotated = if (rotation % 360 != 0) {
                                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
                            } else raw
                            val scale = minOf(1f, 640f / maxOf(rotated.width, rotated.height))
                            val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true).also { rotated.recycle() } else rotated
                            consume(bitmap, time)
                            nextTime = time + 200
                            if (time >= 59_800) outputDone = true
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } finally { decoder.releaseOutputBuffer(out, false) }
            }
            if (SystemClock.elapsedRealtime() - idleAt > 15_000) error("视频解码超时")
        }
    }
    override fun close() {
        codec?.let { runCatching { it.stop() }; it.release() }; codec = null
        extractor.release()
    }
    companion object {
        fun imageBitmap(image: Image): Bitmap {
            val crop = image.cropRect
            val scale = minOf(1f, 640f / maxOf(crop.width(), crop.height()))
            val w = (crop.width() * scale).toInt().coerceAtLeast(1); val h = (crop.height() * scale).toInt().coerceAtLeast(1)
            val planes = image.planes; require(planes.size >= 3) { "不支持的视频像素格式" }
            val rgb = IntArray(w * h)
            fun sample(buffer: ByteBuffer, row: Int, pixel: Int, x: Int, y: Int): Int = buffer.get(buffer.position() + y * row + x * pixel).toInt() and 255
            for (y in 0 until h) for (x in 0 until w) {
                val xx = x * crop.width() / w + crop.left; val yy = y * crop.height() / h + crop.top
                val yp = planes[0]; val up = planes[1]; val vp = planes[2]
                val luminance = (sample(yp.buffer, yp.rowStride, yp.pixelStride, xx, yy) - 16).coerceAtLeast(0)
                val u = sample(up.buffer, up.rowStride, up.pixelStride, xx / 2, yy / 2) - 128
                val v = sample(vp.buffer, vp.rowStride, vp.pixelStride, xx / 2, yy / 2) - 128
                val r = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
                rgb[y * w + x] = Color.rgb(r, g, b)
            }
            return Bitmap.createBitmap(rgb, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
