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
    @Test fun overlappingRunIsSubtractedFromTheDailyStepsBeforePricingThem() {
        val run = pending("跑步").copy(kcal = 350.0, method = "ai", netCalories = true, includedInSteps = true, steps = 3000, estimateNote = "估算")
        val strength = pending("力量训练").copy(id = "strength", kcal = 100.0, method = "met", met = 3.5, netCalories = true)
        val result = CalorieCalculator.activities(DailySteps(day, 10000, 70.0, 1), listOf(run, strength), 70.0)
        // 10000 - 3000 = 7000 steps at 0.7 m each is 4.9 km, priced as level walking: 70 * 4.9 * 0.5.
        assertEquals(171.5, result.stepsKcal!!, 0.001)
        assertEquals(10000, result.totalSteps)
        assertEquals(3000, result.workoutSteps)
        assertEquals(7000, result.recordedSteps)
        // The remainder plus both workouts, with no double count of the run.
        assertEquals(621.5, result.kcal!!, 0.001)
        assertEquals(450.0, result.workoutKcal!!, 0.001)
        assertTrue(result.overlapHandled)
        assertFalse(result.stepsExceeded)
    }
    @Test fun aTickWithoutAStepCountIsTreatedAsUnticked() {
        // The local formulas cannot produce a step count, so there is nothing to subtract.
        val walk = pending("步行", "distance", null, 5.0).copy(kcal = 175.0, method = "distance", netCalories = true, includedInSteps = true)
        val result = CalorieCalculator.activities(DailySteps(day, 10000, 70.0, 1), listOf(walk), 70.0)
        assertEquals(0, result.workoutSteps)
        assertEquals(10000, result.recordedSteps)
        assertEquals(245.0, result.stepsKcal!!, 0.001)
        assertEquals(420.0, result.kcal!!, 0.001)
        assertFalse(result.overlapHandled)
    }
    @Test fun workoutStepsLargerThanTheDailyTotalAreClampedAndFlagged() {
        val run = pending("跑步").copy(kcal = 350.0, method = "ai", netCalories = true, includedInSteps = true, steps = 12000, estimateNote = "估算")
        val result = CalorieCalculator.activities(DailySteps(day, 10000, 70.0, 1), listOf(run), 70.0)
        assertEquals(10000, result.workoutSteps)
        assertEquals(0, result.recordedSteps)
        assertEquals(0.0, result.stepsKcal!!, 0.001)
        assertEquals(350.0, result.kcal!!, 0.001)
        assertTrue(result.stepsExceeded)
    }
    @Test fun stepLengthIsStillRequiredToPriceStepsEvenWhenWorkoutStepsAreKnown() {
        val run = pending("跑步").copy(kcal = 350.0, method = "ai", netCalories = true, includedInSteps = true, steps = 3000, estimateNote = "估算")
        val result = CalorieCalculator.activities(DailySteps(day, 10000, recordedAt = 1), listOf(run), 70.0)
        assertNull(result.stepsKcal)
        assertEquals(350.0, result.kcal!!, 0.001)
        assertEquals(7000, result.recordedSteps)
    }
    @Test fun anExerciseRecordMustDescribeAtLeastOneOfTimeDistanceOrSets() {
        try { pending("跑步", "time", null).validate(); fail("A record with no duration, distance or sets was accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun distanceWinsOverDurationForWalkingAndRunningWhenBothAreGiven() {
        // The three inputs are independent now, so the formula must not be gated on a chosen mode.
        val both = pending("跑步", "time", 30.0, 5.0)
        val estimate = CalorieCalculator.localExercise(both)
        assertEquals("distance", estimate.method)
        assertEquals(350.0, estimate.kcal!!, 0.001)
    }
    @Test fun anExerciseWithWorkoutStepsOutsideTheDailyTotalRangeIsRejected() {
        try { pending("跑步").copy(steps = 0).validate(); fail("Zero workout steps accepted") } catch (_: IllegalArgumentException) {}
        try { pending("跑步").copy(steps = 200001).validate(); fail("Excessive workout steps accepted") } catch (_: IllegalArgumentException) {}
        pending("跑步").copy(steps = 200000).validate()
    }
    @Test fun rowsMigratedFromVersionFourStillValidateAndAreNotTreatedAsCountedInSteps() {
        // The 4 -> 5 migration only adds a nullable column, so every existing row arrives with
        // steps == null. None of them may now fail validation or silently start subtracting steps.
        val legacyRun = ExerciseEntry("legacy-run", day, "跑步", 30.0, 350.0, "ai", weightKg = 70.0, recordedAt = 1,
            category = "aerobic", inputMode = "time", includedInSteps = true, netCalories = true, estimateNote = "旧 AI 估算")
        legacyRun.validate()
        assertNull(legacyRun.steps)
        assertFalse(legacyRun.countedInSteps)
        val result = CalorieCalculator.activities(DailySteps(day, 10000, 70.0, 1), listOf(legacyRun), 70.0)
        assertEquals(0, result.workoutSteps)
        assertFalse(result.overlapHandled)
        assertEquals(245.0, result.stepsKcal!!, 0.001)
        assertEquals(595.0, result.kcal!!, 0.001)
        // A row from the version 1 migration keeps its unknown-scope calories out of the total.
        val legacyManual = ExerciseEntry("legacy-manual", day, "旧记录", 30.0, 300.0, "manual", recordedAt = 1)
        legacyManual.validate()
        assertEquals(1, CalorieCalculator.activities(null, listOf(legacyManual), 70.0).pendingCount)
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
