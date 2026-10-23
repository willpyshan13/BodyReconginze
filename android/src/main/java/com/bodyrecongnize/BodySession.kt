package com.bodyrecongnize

/** Input is 33 MediaPipe Pose landmarks, flattened as x, y, confidence. */
class BodySession(exercise: Exercise) : AutoCloseable {
    enum class Exercise(
        val id: Int,
        val label: String,
        val angleName: String,
        val startPhase: Phase,
        val requiredLandmarks: IntArray,
        val symmetryLimitDeg: Float
    ) {
        SQUAT(1, "深蹲", "膝角", Phase.UP, intArrayOf(23, 24, 25, 26, 27, 28), 45f),
        ARM_RAISE(2, "双臂上举", "肩角", Phase.DOWN, intArrayOf(11, 12, 13, 14, 15, 16, 23, 24), 60f),
        BICEP_CURL(3, "哑铃弯举", "肘角", Phase.DOWN, intArrayOf(11, 12, 13, 14, 15, 16), 60f),
        PUSH_UP(4, "俯卧撑", "肘角", Phase.UP, intArrayOf(11, 12, 13, 14, 15, 16, 23, 24), 45f),
        LATERAL_RAISE(5, "侧平举", "肩角", Phase.DOWN, intArrayOf(11, 12, 13, 14, 23, 24), 45f)
    }
    enum class Phase { UNKNOWN, UP, DOWN }

    data class Result(
        val phase: Phase,
        val repetitions: Int,
        val progress: Float,
        val liveScore: Float,
        val lastRepScore: Float,
        val leftAngleDeg: Float,
        val rightAngleDeg: Float
    )

    private var handle: Long = nativeCreate(exercise.id).also {
        require(it != 0L) { "Invalid exercise or native allocation failed" }
    }

    @Synchronized fun process(
        landmarks: FloatArray, imageWidth: Int, imageHeight: Int, timestampMs: Long
    ): Result {
        check(handle != 0L) { "Session is closed" }
        require(landmarks.size == 99) { "Expected 33 x/y/confidence triplets" }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        val output = nativeProcess(handle, landmarks, imageWidth, imageHeight, timestampMs)
            ?: error("Native processing failed")
        when (output[0].toInt()) {
            1 -> throw IllegalArgumentException("Invalid frame or timestamp")
            2 -> throw IllegalArgumentException("Required landmarks have low confidence")
            3 -> throw IllegalArgumentException("Timestamps must increase")
        }
        return Result(Phase.values()[output[1].toInt()], output[2].toInt(),
            output[3], output[4], output[5], output[6], output[7])
    }

    @Synchronized fun reset() {
        check(handle != 0L) { "Session is closed" }
        nativeReset(handle)
    }

    @Synchronized override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeCreate(exercise: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeReset(handle: Long)
    private external fun nativeProcess(
        handle: Long, landmarks: FloatArray, imageWidth: Int, imageHeight: Int,
        timestampMs: Long
    ): FloatArray?

    companion object { init { System.loadLibrary("body_recongnize_jni") } }
}
