package com.bodyrecongnize.demo.coach

/** Readiness needs repeated, recent observations of the selected exercise's start pose. */
class CameraAlignment(private val stableMs: Long = 700, private val freshnessMs: Long = 1000) {
    private var since = -1L
    private var last = -1L
    private var samples = 0
    fun reset() { since = -1; last = -1; samples = 0 }
    fun observe(observation: CoachSession.Observation?, now: Long) {
        if (observation == null || !observation.knownPhase || observation.progress > .2f) {
            reset(); return
        }
        if (last < 0 || now - last > freshnessMs) { since = now; samples = 0 }
        last = now; samples++
    }
    fun ready(now: Long) = samples >= 2 && since >= 0 && last - since >= stableMs && now - last <= freshnessMs
}
