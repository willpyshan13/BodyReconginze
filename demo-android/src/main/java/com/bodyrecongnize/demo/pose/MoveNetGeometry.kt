package com.bodyrecongnize.demo.pose

/** Square whole-frame letterbox. Preserve aspect ratio and map model y/x back to source pixels. */
data class MoveNetGeometry(val sourceWidth: Int, val sourceHeight: Int) {
    init { require(sourceWidth > 0 && sourceHeight > 0) }
    val side = maxOf(sourceWidth, sourceHeight).toFloat()
    val left = (sourceWidth - side) / 2f
    val top = (sourceHeight - side) / 2f
    fun sourceX(normalizedX: Float) = normalizedX * side + left
    fun sourceY(normalizedY: Float) = normalizedY * side + top
}
