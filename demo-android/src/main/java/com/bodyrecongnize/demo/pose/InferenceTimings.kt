package com.bodyrecongnize.demo.pose

/** Per-frame end-to-end pose latency; exclude model initialization and first three warmup calls. */
class InferenceTimings {
    private val values = mutableListOf<Double>()
    private var calls = 0
    fun record(milliseconds: Double) {
        require(milliseconds.isFinite() && milliseconds >= 0) { "Invalid inference latency: $milliseconds ms" }
        if (calls++ >= 3) values += milliseconds
    }
    val count get() = values.size
    val mean get() = if (values.isEmpty()) 0.0 else values.average()
    val p95 get() = if (values.isEmpty()) 0.0 else values.sorted()[
        (kotlin.math.ceil(values.size * .95).toInt() - 1).coerceAtLeast(0)]
    fun summary(): String = if (values.isEmpty()) "预热中" else
        String.format(java.util.Locale.US, "平均 %.1f ms · P95 %.1f ms · %d 帧", mean, p95, count)
}
