package com.bodyrecongnize.demo.lab

import kotlin.math.*

data class LabFrame(val timeMs: Long, val width: Int, val height: Int, val points: FloatArray,
                    val image: String, val inferenceMs: Double, val cut: Boolean = false) {
    fun valid(index: Int) = points[index * 3 + 2] >= .5f
    fun angle(a: Int, pivot: Int, b: Int): Float? {
        if (!valid(a) || !valid(pivot) || !valid(b)) return null
        val aspect = width.toFloat() / height
        val ax = (points[a * 3] - points[pivot * 3]) * aspect
        val ay = points[a * 3 + 1] - points[pivot * 3 + 1]
        val bx = (points[b * 3] - points[pivot * 3]) * aspect
        val by = points[b * 3 + 1] - points[pivot * 3 + 1]
        val length = hypot(ax, ay) * hypot(bx, by)
        if (length < .000001f) return null
        return (acos(((ax * bx + ay * by) / length).coerceIn(-1f, 1f)) * 180 / PI).toFloat()
    }
    private fun average(a: Float?, b: Float?) = if (a != null && b != null) (a + b) / 2 else null
    val knee get() = average(angle(23, 25, 27), angle(24, 26, 28))
    val elbow get() = average(angle(11, 13, 15), angle(12, 14, 16))
    val shoulder get() = average(angle(13, 11, 23), angle(14, 12, 24))
    val horizontal: Boolean? get() {
        if (!intArrayOf(11, 12, 23, 24).all { valid(it) }) return null
        val dx = abs((points[11 * 3] + points[12 * 3] - points[23 * 3] - points[24 * 3]) * width)
        val dy = abs((points[11 * 3 + 1] + points[12 * 3 + 1] - points[23 * 3 + 1] - points[24 * 3 + 1]) * height)
        return dx > dy * 1.2f
    }
}

enum class LabKind(val nativeId: Int, val label: String, val threshold: Float) {
    SQUAT(1, "深蹲", .7f), ARM_RAISE(2, "双臂上举", .8f), CURL(3, "哑铃弯举", .8f),
    PUSH_UP(4, "俯卧撑", .7f), LATERAL(5, "侧平举", .8f)
}
data class LabMeasure(val reps: Int, val progress: Float, val left: Float, val right: Float, val known: Boolean)
data class LabEvent(val id: String, val kind: LabKind, val start: Int, val end: Int,
                    val peak: Int, val peakProgress: Float, val status: String, val reason: String,
                    val match: Float = 0f) {
    val complete get() = status == "complete"
}
data class LabSegment(val kind: LabKind?, val start: Int, val end: Int, val eventIds: List<String>)
data class LabRun(val id: String, val source: String, val condition: String, val frames: List<LabFrame>,
                  val events: List<LabEvent>, val segments: List<LabSegment>, val elapsedMs: Long,
                  val createdAt: Long, val truth: Int? = null, val expected: LabKind? = null,
                  val baselineId: String? = null) {
    val completed get() = events.count { it.complete }
    val durationMs get() = frames.lastOrNull()?.timeMs?.plus(200) ?: 0L
    val unknownFrames get() = segments.filter { it.kind == null }.sumOf { it.end - it.start + 1 }
}
