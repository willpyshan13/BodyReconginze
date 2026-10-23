package com.bodyrecongnize.demo.coach

import org.junit.Assert.*
import org.junit.Test

class CoachSessionTest {
    private fun pose(reps: Int, progress: Float = 0f) = CoachSession.Observation(reps, progress, 170f, 170f, 80f, true)
    @Test fun timedPhasesNeverCreateRepetitionsAndRestHoldsSetUntilDeadline() {
        val coach = CoachSession(CoachSession.Plan(2, 2, 5000, 3000))
        coach.start(0)
        assertEquals(CoachSession.Stage.PREPARING, coach.tick(2999).stage)
        assertEquals(0, coach.tick(3000).totalReps)
        coach.observe(pose(1), 4000)
        val rest = coach.observe(pose(2), 5000)
        assertEquals(CoachSession.Stage.REST, rest.stage)
        assertEquals(1, rest.completedSets)
        assertEquals(2, coach.tick(9999).totalReps)
        assertEquals(0, coach.tick(10000).reps)
        coach.observe(pose(3), 11000)
        val end = coach.observe(pose(4), 12000)
        assertEquals(CoachSession.Stage.FINISHED, end.stage)
        assertTrue(end.completed)
        assertEquals(4, end.totalReps)
        assertEquals(80f, end.averageIndex!!, .001f)
    }
    @Test fun missingPoseAndRepeatedCounterDoNotIncreaseCount() {
        val coach = CoachSession(CoachSession.Plan(1, 3, 0, 0))
        coach.start(0); coach.tick(0)
        coach.observe(pose(1), 1000)
        repeat(5) { coach.observe(pose(1), 1100L + it * 100) }
        assertEquals(1, coach.observe(null, 2000).totalReps)
        assertEquals(1, coach.observe(pose(1), 2500).totalReps)
    }
    @Test fun pauseFreezesRestDeadlineAndExcludesPausedTime() {
        val coach = CoachSession(CoachSession.Plan(2, 1, 5000, 0))
        coach.start(0); coach.tick(0)
        coach.observe(pose(0), 0)
        coach.observe(pose(1), 1000)
        coach.pause(2000)
        assertEquals(CoachSession.Stage.PAUSED, coach.tick(20000).stage)
        coach.resume(20000)
        assertEquals(CoachSession.Stage.REST, coach.tick(23999).stage)
        assertEquals(CoachSession.Stage.ACTIVE, coach.tick(24000).stage)
        assertEquals(1000L, coach.snapshot(24000).activeMs)
    }
    @Test fun shallowAttemptGetsFeedbackWithoutInventingARepetition() {
        val coach = CoachSession(CoachSession.Plan(1, 2, 0, 0))
        coach.start(0); coach.tick(0)
        coach.observe(pose(0, .4f), 1000)
        val result = coach.observe(pose(0, .1f), 2000)
        assertEquals(1, result.shallowAttempts)
        assertEquals(0, result.totalReps)
    }
    @Test fun earlyFinishIsDistinguishedFromPlanCompletion() {
        val coach = CoachSession();coach.start(0)
        val stopped = coach.finish(1000)
        assertEquals(CoachSession.Stage.FINISHED, stopped.stage)
        assertFalse(stopped.completed)
        assertEquals(0, stopped.totalReps)
    }
    @Test fun delayedCameraResultsDuringRestDoNotLeakIntoTheNextSet() {
        val coach = CoachSession(CoachSession.Plan(2, 1, 1000, 0))
        coach.start(0); coach.tick(0)
        coach.observe(pose(1), 1000)
        coach.observe(pose(2), 1500) // already in flight when the first set ended
        coach.tick(2000)
        assertEquals(1, coach.observe(pose(2), 2100).totalReps)
        assertEquals(2, coach.observe(pose(3), 2500).totalReps)
    }
    @org.junit.Test fun skipRestStartsNextSetWithoutCreatingRepetitions() {
        val coach = CoachSession(CoachSession.Plan(2, 1, 30_000, 0))
        coach.start(0)
        coach.observe(CoachSession.Observation(0, 0f, 170f, 170f, 0f, true), 1)
        val rest = coach.observe(CoachSession.Observation(1, 0f, 170f, 170f, 90f, true), 2000)
        org.junit.Assert.assertEquals(CoachSession.Stage.REST, rest.stage)
        val next = coach.skipRest(3000)
        org.junit.Assert.assertEquals(CoachSession.Stage.ACTIVE, next.stage)
        org.junit.Assert.assertEquals(1, next.totalReps)
        org.junit.Assert.assertEquals(0, next.reps)
        org.junit.Assert.assertEquals(2, next.set)
    }

    @Test fun noPoseAndUnknownStartingPostureDoNotAccumulateTrainingTime() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 0))
        coach.start(0); coach.tick(0)
        assertEquals(0L, coach.observe(null, 10000).activeMs)
        coach.observe(pose(0).copy(knownPhase = false), 11000)
        assertEquals(0L, coach.tick(20000).activeMs)
    }

    @Test fun trackingTimeStopsAfterMissingInputInsteadOfCountingAnUnlimitedGap() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 0))
        coach.start(0); coach.observe(pose(0), 0)
        assertEquals(1000L, coach.tick(10000).activeMs)
        coach.observe(null, 11000)
        assertEquals(1000L, coach.tick(20000).activeMs)
    }

    @Test fun correctionRemainsVisibleBrieflyAndItsEvidenceSurvivesNormalFramesAndPause() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 0))
        coach.start(0)
        coach.observe(pose(0, .4f), 1000)
        coach.observe(pose(0, .1f), 2000)
        val ordinary = coach.observe(pose(0), 2200)
        assertTrue(ordinary.hint.contains("未达到"))
        val later = coach.observe(pose(0), 6000)
        assertFalse(later.hint.contains("未达到"))
        val paused = coach.pause(6100)
        assertEquals(CoachSession.FeedbackKind.RANGE, paused.feedback.single().kind)
        assertEquals(2000L, paused.feedback.single().timeMs)
    }

    @Test fun videoPaceUsesInputTimeEvenWhenInferenceRunsSlowly() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 0))
        coach.start(0)
        coach.observe(pose(0).copy(sourceTimeMs = 0), 0)
        coach.observe(pose(0, 1f).copy(sourceTimeMs = 200), 2000)
        val result = coach.observe(pose(1).copy(sourceTimeMs = 800), 5000)
        assertEquals(1, result.fastReps)
        assertEquals(800L, result.feedback.single().timeMs)
    }

    @Test fun symmetryWarningNeedsBothSidesAndPersistence() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 0))
        coach.start(0)
        val uneven = pose(0, .4f).copy(leftAngle = 90f, rightAngle = 170f)
        coach.observe(uneven.copy(symmetryAvailable = false), 1000)
        assertTrue(coach.observe(uneven.copy(symmetryAvailable = false), 2000).feedback.isEmpty())
        coach.observe(uneven, 2100)
        assertTrue(coach.observe(uneven, 2200).feedback.isEmpty())
        assertEquals(CoachSession.FeedbackKind.SYMMETRY, coach.observe(uneven, 2700).feedback.single().kind)
        assertEquals(1, coach.observe(uneven, 3000).feedback.size)
    }

    @Test fun oneVisibleSideCanCountButCannotProduceASymmetryIndex() {
        val coach = CoachSession(CoachSession.Plan(1, 1, 0, 0), ExerciseGuide.all[3])
        coach.start(0)
        val result = coach.observe(pose(1).copy(symmetryAvailable = false), 1000)
        assertEquals(1, result.totalReps)
        assertNull(result.averageIndex)
    }

    @Test fun resettingInputCounterKeepsAcceptedTrainingTotalsWithoutDoubleCounting() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 0))
        coach.start(0)
        coach.observe(pose(0), 0)
        assertEquals(1, coach.observe(pose(1), 1000).totalReps)
        coach.rebaseCounter()
        assertEquals(1, coach.observe(pose(0), 1100).totalReps)
        assertEquals(2, coach.observe(pose(1), 2000).totalReps)
    }
}
