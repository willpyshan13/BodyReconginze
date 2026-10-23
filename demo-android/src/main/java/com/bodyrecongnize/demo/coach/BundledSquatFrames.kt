package com.bodyrecongnize.demo.coach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/** Exact-time frames decoded from the bundled continuous real video, not generated pose images. */
class BundledSquatFrames(private val context: Context) {
    private val manifest = JSONObject(context.assets.open("real_squat_frames/index.json").bufferedReader().use { it.readText() })
    val durationMs = manifest.getLong("durationMs")
    private val frames = manifest.getJSONArray("frames")
    val count = frames.length()
    val cutFrameIndices: Set<Int> = manifest.optJSONArray("cutFrameIndices")?.let { cuts ->
        (0 until cuts.length()).map { cuts.getInt(it) }.toSet()
    }.orEmpty()
    fun frame(index: Int): Bitmap {
        val name = frames.getJSONObject(index % count).getString("file")
        return context.assets.open("real_squat_frames/$name").use {
            requireNotNull(BitmapFactory.decodeStream(it)) { "Unable to decode bundled training frame" }
        }
    }
}
