package com.bodyrecongnize.demo.coach

import org.junit.Assert.*
import org.junit.Test

class CameraAlignmentTest {
    private val readyPose = CoachSession.Observation(0, 0f, 170f, 170f, 0f, true)

    @Test fun startingNeedsRepeatedStableAndRecentObservations() {
        val alignment = CameraAlignment()
        alignment.observe(readyPose, 0)
        assertFalse(alignment.ready(800))
        alignment.observe(readyPose, 800)
        assertTrue(alignment.ready(800))
        assertFalse(alignment.ready(2000))
    }

    @Test fun missingJointsAndWrongStartingPoseRequireRealignment() {
        val alignment = CameraAlignment()
        alignment.observe(readyPose, 0); alignment.observe(readyPose, 800)
        alignment.observe(null, 900)
        assertFalse(alignment.ready(900))
        alignment.observe(readyPose, 1000)
        alignment.observe(readyPose.copy(progress = 1f), 1800)
        assertFalse(alignment.ready(1800))
        alignment.observe(readyPose.copy(knownPhase = false), 1900)
        assertFalse(alignment.ready(2600))
    }

    @Test fun cameraSwitchAndLongGapsCannotReuseOldReadiness() {
        val alignment = CameraAlignment()
        alignment.observe(readyPose, 0); alignment.observe(readyPose, 800)
        alignment.reset()
        assertFalse(alignment.ready(800))
        alignment.observe(readyPose, 1000); alignment.observe(readyPose, 3000)
        assertFalse(alignment.ready(3000))
    }
}
