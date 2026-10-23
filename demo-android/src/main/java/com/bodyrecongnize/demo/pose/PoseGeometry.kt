package com.bodyrecongnize.demo.pose

/** Whole-frame, single-person ROI with the padding used by the official RTMPose demo. */
data class PoseGeometry(val sourceWidth: Int, val sourceHeight: Int) {
    init { require(sourceWidth > 0 && sourceHeight > 0) }
    val scaleWidth = maxOf(sourceWidth * 1.25f, sourceHeight * 1.25f * INPUT_WIDTH / INPUT_HEIGHT)
    val scaleHeight = scaleWidth * INPUT_HEIGHT / INPUT_WIDTH
    val left = (sourceWidth - scaleWidth) / 2f
    val top = (sourceHeight - scaleHeight) / 2f
    fun sourceX(bin: Int) = bin / 2f / INPUT_WIDTH * scaleWidth + left
    fun sourceY(bin: Int) = bin / 2f / INPUT_HEIGHT * scaleHeight + top

    companion object {
        const val INPUT_WIDTH = 192
        const val INPUT_HEIGHT = 256
        // COCO-17 indices -> existing MediaPipe/ML Kit overlay indices. No invented foot/hand points.
        val COCO_TO_OVERLAY = intArrayOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
    }
}
