package com.bodyrecongnize.demo.coach

import org.junit.Assert.*
import org.junit.Test

class TrainingCaloriesTest {
    private fun basis(weight: Double? = 60.0) = TrainingCalories.forSession("SQUAT", TrainingCalories.Source.CAMERA, weight)
    private fun pose(reps: Int = 0) = CoachSession.Observation(reps, 0f, 170f, 170f, 80f, true)

    @Test fun estimatesUseKilogramsAndActiveMillisecondsWithoutRoundingBeforeStorage() {
        assertEquals(.945, basis().estimate(18_000).kcal!!, .000001)
        assertEquals(4.2, basis().estimate(80_000).kcal!!, .000001)
        assertEquals(1.18125, basis(75.0).estimate(18_000).kcal!!, .000001)
        assertEquals("≈ 0.9", basis().estimate(18_000).valueLabel)
        assertEquals("<0.1", basis().estimate(100).valueLabel)
        assertEquals("≈ 0.0", basis().estimate(0).valueLabel)
    }

    @Test fun optionalWeightAcceptsLocalDecimalAndRejectsInvalidOrOverpreciseValues() {
        assertTrue(TrainingCalories.parseWeight(" ").valid)
        assertNull(TrainingCalories.parseWeight("").kilograms)
        assertEquals(60.5, TrainingCalories.parseWeight("60,5").kilograms!!, 0.0)
        assertEquals(20.0, TrainingCalories.parseWeight("20.0").kilograms!!, 0.0)
        assertEquals(300.0, TrainingCalories.parseWeight("300").kilograms!!, 0.0)
        listOf("0", "19.9", "300.1", "-60", "NaN", "Infinity", "6e1", "60.55", ".", "60.").forEach {
            assertFalse("Accepted $it", TrainingCalories.parseWeight(it).valid)
        }
    }

    @Test fun missingWeightAndUnsupportedExerciseNeverBecomeZeroCalorieMeasurements() {
        val missing = basis(null).estimate(60_000)
        assertEquals(TrainingCalories.Status.MISSING_WEIGHT, missing.status)
        assertNull(missing.kcal)
        assertEquals("—", missing.valueLabel)
        assertNull(basis(Double.NaN).estimate(60_000).kcal)
        val unknown = TrainingCalories.forSession("FUTURE_EXERCISE", TrainingCalories.Source.CAMERA, 60.0).estimate(60_000)
        assertEquals(TrainingCalories.Status.UNSUPPORTED, unknown.status)
        assertNull(unknown.kcal)
    }

    @Test fun bothVideoSourcesHaveNoPersonalCaloriesOrBodyWeightEvenWithValidInputs() {
        val video = TrainingCalories.forSession("SQUAT", TrainingCalories.Source.VIDEO, 60.0)
        assertNull(video.weightKg)
        assertNull(video.reference)
        val result = video.estimate(60_000)
        assertEquals(TrainingCalories.Status.VIDEO, result.status)
        assertNull(result.kcal)
        assertNull(result.toFields()["weightKg"])
        assertNull(TrainingCalories.fromStored(TrainingCalories.Source.VIDEO, basis().estimate(60_000).toFields()).kcal)
    }

    @Test fun allExistingExercisesHaveAnExplicitReferenceAndCategoryProxiesAreLabelled() {
        assertEquals("02056", TrainingCalories.referenceFor("PUSH_UP")!!.code)
        listOf("ARM_RAISE", "BICEP_CURL", "LATERAL_RAISE").forEach {
            assertTrue(TrainingCalories.referenceFor(it)!!.approximate)
            assertTrue(TrainingCalories.forSession(it, TrainingCalories.Source.CAMERA, 60.0).estimate(1000).explanation().contains("类别近似"))
        }
    }

    @Test fun poseGapsCountdownAndPauseDoNotContinuouslyIncreaseEnergy() {
        val coach = CoachSession(CoachSession.Plan(1, 5, 0, 3000))
        coach.start(0)
        assertEquals(0.0, basis().estimate(coach.tick(3000).activeMs).kcal!!, 0.0)
        coach.observe(pose(), 3000)
        val tracked = basis().estimate(coach.tick(4000).activeMs)
        assertEquals(.0525, tracked.kcal!!, .000001)
        assertEquals(tracked.kcal, basis().estimate(coach.tick(10_000).activeMs).kcal)
        coach.observe(null, 10_000)
        assertEquals(tracked.kcal, basis().estimate(coach.tick(20_000).activeMs).kcal)
        coach.observe(pose(), 20_000)
        val paused = basis().estimate(coach.pause(20_500).activeMs)
        assertEquals(paused.kcal, basis().estimate(coach.tick(40_000).activeMs).kcal)
        coach.resume(40_000)
        coach.observe(pose(), 40_000)
        assertTrue(basis().estimate(coach.tick(40_500).activeMs).kcal!! > paused.kcal!!)
    }

    @Test fun restAndFinishedSessionFreezeTheEstimate() {
        val coach = CoachSession(CoachSession.Plan(2, 1, 5000, 0))
        coach.start(0); coach.observe(pose(), 0)
        val rest = basis().estimate(coach.observe(pose(1), 1000).activeMs)
        assertEquals(rest.kcal, basis().estimate(coach.tick(5000).activeMs).kcal)
        coach.tick(6000); coach.observe(pose(1), 6000)
        val finished = basis().estimate(coach.observe(pose(2), 7000).activeMs)
        assertTrue(finished.kcal!! > rest.kcal!!)
        assertEquals(finished.kcal, basis().estimate(coach.tick(50_000).activeMs).kcal)
    }

    @Test fun historyKeepsTheRecordedWeightReferenceAndUnroundedValue() {
        val original = basis().estimate(18_000)
        val recorded = TrainingCalories.fromStored(TrainingCalories.Source.CAMERA, original.toFields())
        val nextSession = basis(90.0).estimate(18_000)
        assertEquals(original, recorded)
        assertNotEquals(nextSession.kcal, recorded.kcal)
        val olderReference = original.toFields() + mapOf("met" to 4.0, "referenceCode" to "archived", "referenceLabel" to "本次保存的参考类别")
        val restored = TrainingCalories.fromStored(TrainingCalories.Source.CAMERA, olderReference)
        assertEquals(4.0, restored.reference!!.met, 0.0)
        assertEquals("archived", restored.reference!!.code)
        assertEquals(original.kcal, restored.kcal)
    }

    @Test fun oldIncompleteAndInvalidHistoryAreUnavailableInsteadOfInventedEstimates() {
        val fields = basis().estimate(18_000).toFields()
        listOf(null, emptyMap(), fields - "kcal", fields + ("kcal" to Double.NaN), fields + ("method" to "future"), fields + ("version" to 2)).forEach {
            val restored = TrainingCalories.fromStored(TrainingCalories.Source.CAMERA, it)
            assertEquals(TrainingCalories.Status.LEGACY, restored.status)
            assertNull(restored.kcal)
        }
        val missing = basis(null).estimate(18_000)
        assertEquals(TrainingCalories.Status.MISSING_WEIGHT,
            TrainingCalories.fromStored(TrainingCalories.Source.CAMERA, missing.toFields()).status)
    }
}
