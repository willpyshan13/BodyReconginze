package com.bodyrecongnize.demo.coach

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Prerecorded offline Chinese prompts; does not require a system TTS service or network. */
class CoachSpeaker(private val context: Context, private val onError: (String) -> Unit) : AutoCloseable {
    private var player: MediaPlayer? = null
    fun play(cue: String) {
        stop()
        try {
            val file = File(context.cacheDir, "coach_$cue.wav")
            if (!file.exists()) context.assets.open("coach_voice/$cue.wav").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener { if (player === it) it.start() }
            next.setOnCompletionListener { if (player === it) { it.release(); player = null } }
            next.setOnErrorListener { media, _, _ ->
                if (player === media) { media.release(); player = null }
                onError("语音播放失败，文字提示继续显示")
                true
            }
            next.prepareAsync()
        } catch (error: Exception) {
            stop()
            onError("语音暂不可用：${error.message}")
        }
    }
    fun stop() { player?.release(); player = null }
    override fun close() = stop()
}
