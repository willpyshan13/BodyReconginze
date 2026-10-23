package com.bodyrecongnize.demo.coach

import org.junit.Assert.*
import org.junit.Test

class MultiExerciseCoachTest {
    private fun observation(reps: Int, progress: Float) = CoachSession.Observation(reps, progress, 100f, 100f, 90f, true)
    @Test fun allExercisesUseTheirOwnPreparationAndCompleteOnlyWithCounter() {
        ExerciseGuide.all.forEach { guide ->
            val coach = CoachSession(CoachSession.Plan(1, 1, 0, 0), guide)
            assertEquals(guide.preparation, coach.start(0).hint)
            coach.observe(observation(0, 0f), 1)
            coach.observe(observation(0, 1f), 500)
            assertEquals(0, coach.snapshot(500).totalReps)
            assertTrue(coach.observe(observation(1, 0f), 1600).completed)
        }
    }
    @Test fun armRaiseBelowNativeThresholdIsIncomplete() {
        val coach = CoachSession(CoachSession.Plan(1, 1, 0, 0), ExerciseGuide.all[1])
        coach.start(0)
        coach.observe(observation(0, 0f), 1)
        coach.observe(observation(0, .75f), 500)
        val result = coach.observe(observation(0, 0f), 1500)
        assertEquals(1, result.shallowAttempts)
        assertEquals(0, result.totalReps)
    }
    @Test fun missingLandmarksUseSelectedExerciseGuidance() {
        val coach = CoachSession(CoachSession.Plan(1, 1, 0, 0), ExerciseGuide.all[2])
        coach.start(0)
        assertTrue(coach.observe(null, 1).hint.contains("手腕"))
    }
}
