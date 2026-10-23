package com.bodyrecongnize.demo.pose

import org.junit.Assert.*
import org.junit.Test

class InferenceTimingsTest {
    @Test fun warmupDoesNotDistortLatencyAndTailIsRetained() {
        val timings = InferenceTimings()
        repeat(3) { timings.record(1000.0) }
        assertEquals(0, timings.count)
        (1..20).forEach { timings.record(it.toDouble()) }
        assertEquals(20, timings.count)
        assertEquals(10.5, timings.mean, .0001)
        assertEquals(19.0, timings.p95, .0001)
    }
}
