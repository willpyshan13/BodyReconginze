package com.bodyrecongnize.demo.lab

import org.junit.Assert.*
import org.junit.Test

class LabPosePlaybackTest {
    private fun frame(time: Long, x: Float = .2f, cut: Boolean = false): LabFrame {
        val points = FloatArray(99)
        points[0] = x; points[1] = .5f; points[2] = .9f
        return LabFrame(time, 100, 200, points, "f.png", 1.0, cut)
    }

    @Test fun unanalysedTailDoesNotFreezeTheFinalSkeleton() {
        val frames = listOf(frame(0), frame(200))
        assertNotNull(LabPosePlayback.sample(frames, 399))
        assertNull(LabPosePlayback.sample(frames, 400))
        assertNull(LabPosePlayback.sample(frames, 60000))
    }

    @Test fun normalFramesInterpolateButCutsNeverCreateAnArtificialPose() {
        val first = frame(0)
        val next = frame(200, .6f)
        assertEquals(.4f, LabPosePlayback.sample(listOf(first, next), 100)!!.points[0], .001f)
        assertNull(LabPosePlayback.sample(listOf(first, next.copy(cut = true)), 100))
        assertNotNull(LabPosePlayback.sample(listOf(first, next.copy(cut = true)), 200))
    }

    @Test fun oneMissingEndpointCannotCreateAConfidentInterpolatedJoint() {
        val hidden = frame(200).also { it.points[2] = 0f }
        assertEquals(0f, LabPosePlayback.sample(listOf(frame(0), hidden), 100)!!.points[2], 0f)
    }

    @Test fun missingTimeRangesAreNotFilledByLongInterpolation() {
        val frames = listOf(frame(0), frame(1000))
        assertNotNull(LabPosePlayback.sample(frames, 100))
        assertNull(LabPosePlayback.sample(frames, 500))
    }
}
