package com.bodyrecongnize.demo.lab

/** Interpolation is confined to saved, continuous input; it never extends a last pose indefinitely. */
object LabPosePlayback {
    data class Sample(val width: Int, val height: Int, val points: FloatArray)

    fun sample(frames: List<LabFrame>, positionMs: Long): Sample? {
        if (frames.isEmpty() || positionMs < frames.first().timeMs ||
            positionMs >= frames.last().timeMs + 200) return null
        val search = frames.binarySearchBy(positionMs) { it.timeMs }
        if (search >= 0) return frames[search].let { Sample(it.width, it.height, it.points) }
        val next = -search - 1
        val before = frames[(next - 1).coerceAtLeast(0)]
        if (next >= frames.size) return Sample(before.width, before.height, before.points)
        val after = frames[next]
        if (after.cut) return null
        if (after.timeMs - before.timeMs > 400 || before.width != after.width || before.height != after.height) {
            return if (positionMs < before.timeMs + 200) Sample(before.width, before.height, before.points) else null
        }
        val fraction = (positionMs - before.timeMs).toFloat() / (after.timeMs - before.timeMs)
        val points = FloatArray(99)
        for (i in 0..32) {
            val offset = i * 3
            if (!before.valid(i) || !after.valid(i)) continue
            for (axis in 0..2) points[offset + axis] = before.points[offset + axis] +
                (after.points[offset + axis] - before.points[offset + axis]) * fraction
        }
        return Sample(before.width, before.height, points)
    }
}
