package com.lrh.healthvivo.fitness

import org.junit.Assert.*
import org.junit.Test

class ActivityEstimateTest {
    private val day = "2026-10-12"
    private fun pending(name: String, mode: String = "time", minutes: Double? = 30.0, km: Double? = null, sets: Int? = null) =
        ExerciseEntry("test", day, name, minutes, null, "pending", weightKg = 70.0, recordedAt = 1,
            category = if(mode == "sets") "anaerobic" else "aerobic", inputMode = mode, distanceKm = km, sets = sets)
    @Test fun runningDistanceAndWalkingDistanceUseDistinctNetCosts() {
        assertEquals(350.0, CalorieCalculator.localExercise(pending("跑步", "distance", null, 5.0)).kcal!!, 0.001)
        assertEquals(175.0, CalorieCalculator.localExercise(pending("步行", "distance", null, 5.0)).kcal!!, 0.001)
    }
    @Test fun familiarTimeExerciseUsesMetAndRetainsFormulaSource() {
        val value = CalorieCalculator.localExercise(pending("力量训练"))
        assertEquals(91.875, value.kcal!!, 0.001)
        assertTrue(value.note.contains("02054"))
    }
    @Test fun strengthSetsCanBeRecordedWithoutInventingDurationOrCalories() {
        val record = pending("卧推", "sets", null, sets = 4)
        record.validate()
        assertNull(CalorieCalculator.localExercise(record).kcal)
        assertNull(record.minutes)
        assertNull(record.kcal)
    }
    @Test fun unknownExerciseIsNotSilentlyMatchedToAnotherActivity() {
        assertNull(CalorieCalculator.localExercise(pending("跳舞" )).kcal)
        assertNull(CalorieCalculator.localExercise(pending("骑行", "distance", null, 10.0)).kcal)
    }
    @Test fun stepsMissingStrideAreRecordedWithoutGuessedEnergy() {
        val result = CalorieCalculator.activities(DailySteps(day, 6000, recordedAt = 1), emptyList(), 70.0)
        assertNull(result.stepsKcal); assertNull(result.kcal)
        assertNull(CalorieCalculator.activities(DailySteps(day, 6000, 70.0, 1), emptyList(), null).kcal)
    }
    @Test fun zeroStepsAndMissingStepsStayDistinct() {
        assertNull(CalorieCalculator.activities(null, emptyList(), 70.0).kcal)
        assertEquals(0.0, CalorieCalculator.activities(DailySteps(day, 0, recordedAt = 1), emptyList(), null).kcal!!, 0.001)
    }
    @Test fun overlappingRunIsNotAddedOnTopOfItsDailySteps() {
        val run = pending("跑步").copy(kcal = 350.0, method = "ai", netCalories = true, includedInSteps = true, estimateNote = "估算")
        val strength = pending("力量训练").copy(id = "strength", kcal = 100.0, method = "met", met = 3.5, netCalories = true)
        val result = CalorieCalculator.activities(DailySteps(day, 10000, 70.0, 1), listOf(run, strength), 70.0)
        assertEquals(245.0, result.stepsKcal!!, 0.001)
        assertEquals(450.0, result.kcal!!, 0.001)
        assertTrue(result.overlapHandled)
    }
    @Test fun largerStepEstimateIsRetainedWhenWorkoutIsAlreadyCovered() {
        val walk = pending("步行").copy(kcal = 100.0, method = "met", met = 3.8, netCalories = true, includedInSteps = true)
        assertEquals(245.0, CalorieCalculator.activities(DailySteps(day, 10000, 70.0, 1), listOf(walk), 70.0).kcal!!, 0.001)
    }
    @Test fun pendingAndLegacyUnknownScopeCaloriesAreExcludedFromNetSum() {
        val legacy = ExerciseEntry("old", day, "旧记录", 30.0, 300.0, "manual", recordedAt = 1)
        val result = CalorieCalculator.activities(null, listOf(legacy, pending("卧推", "sets", null, sets = 3)), 70.0)
        assertNull(result.kcal); assertEquals(2, result.pendingCount)
    }
    @Test fun setsCanUseMetWhenUserSuppliesActualSessionDuration() {
        assertEquals(91.875, CalorieCalculator.localExercise(pending("力量训练", "sets", 30.0, sets = 4)).kcal!!, 0.001)
    }
    @Test fun invalidInputUnitsAreRejected() {
        val invalid = listOf(pending("跑步", "distance", null, 0.0), pending("卧推", "sets", null, sets = 0), pending("跑步", minutes = Double.NaN))
        invalid.forEach { try { it.validate(); fail("Invalid record accepted") } catch (_: IllegalArgumentException) {} }
        try { DailySteps(day, -1, recordedAt = 1).validate(); fail("Negative steps accepted") } catch (_: IllegalArgumentException) {}
        try { DailySteps(day, 100, Double.NaN, 1).validate(); fail("NaN stride accepted") } catch (_: IllegalArgumentException) {}
    }
}
