package com.lrh.healthvivo.watch

import com.lrh.healthvivo.fitness.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The snapshot is the contract with the watch, so the split step count, the energy breakdown and the
 * trend series have to be asserted here rather than discovered on the device.
 */
class WatchSnapshotTest {
    private val day = "2026-10-12"
    private val run = ExerciseEntry("run", day, "跑步", 30.0, 350.0, "met", weightKg = 70.0, met = 9.8, recordedAt = 1,
        category = "aerobic", inputMode = "time", includedInSteps = true, netCalories = true, steps = 3000)
    private fun data() = FitnessData(
        profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1),
        weights = listOf(WeightEntry(day, 70.0, 1)),
        meals = listOf(MealEntry("m1", day, "breakfast", "燕麦", 50.0, 380.0, source = "manual", recordedAt = 1)),
        exercises = listOf(run),
        steps = listOf(DailySteps(day, 10000, 70.0, 1)))
    private fun snapshot() = WatchSnapshot.create(data(), day, 7, null)

    @Test fun dailyStepsCarryBothTheirTotalAndTheirSplit() {
        val value = snapshot()
        assertEquals(10000, value.getInt("steps"))
        assertEquals(3000, value.getInt("workoutSteps"))
        assertEquals(7000, value.getInt("recordedSteps"))
    }
    @Test fun energyBreaksDownIntoMetabolismWorkoutsAndSteps() {
        val energy = snapshot().getJSONObject("energy")
        // 7000 remaining steps at 0.7 m each, priced as level walking.
        assertEquals(171.5, energy.getDouble("stepsKcal"), 0.001)
        assertEquals(350.0, energy.getDouble("workoutKcal"), 0.001)
        assertEquals(521.5, energy.getDouble("activityKcal"), 0.001)
        // Mifflin-St Jeor for a 30-year-old man at 175 cm and 70 kg: 700 + 1093.75 - 150 + 5.
        assertEquals(1648.75, energy.getDouble("restingKcal"), 0.001)
        assertEquals(2170.25, energy.getDouble("referenceKcal"), 0.001)
        assertTrue(energy.getBoolean("overlapHandled"))
    }
    @Test fun perMealTotalsFollowTheFixedMealOrder() {
        val meals = snapshot().getJSONObject("energy").getJSONArray("mealKcal")
        assertEquals(4, meals.length())
        assertEquals(190.0, meals.getDouble(0), 0.001)
        assertEquals(0.0, meals.getDouble(1), 0.001)
        assertEquals(0.0, meals.getDouble(2), 0.001)
        assertEquals(0.0, meals.getDouble(3), 0.001)
    }
    @Test fun trendCoversAFortnightOldestFirstAndEndsOnTheSnapshotDay() {
        val trend = snapshot().getJSONArray("trend")
        assertEquals(14, trend.length())
        assertEquals("2026-09-29", trend.getJSONObject(0).getString("day"))
        val last = trend.getJSONObject(13)
        assertEquals(day, last.getString("day"))
        assertEquals(190.0, last.getDouble("intakeKcal"), 0.001)
        assertEquals(521.5, last.getDouble("activityKcal"), 0.001)
        assertFalse(last.isNull("referenceKcal"))
        // Days before any record still occupy their slot and carry no reference.
        assertTrue(trend.getJSONObject(0).isNull("referenceKcal"))
    }
    @Test fun aDayWithoutStepsOrWorkoutsStillProducesAValidSnapshot() {
        val bare = WatchSnapshot.create(FitnessData(
            profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1)),
            day, 8, null)
        assertTrue(bare.isNull("steps"))
        assertTrue(bare.isNull("recordedSteps"))
        assertEquals(0, bare.getInt("workoutSteps"))
        assertFalse(bare.getJSONObject("energy").getBoolean("overlapHandled"))
        assertEquals(14, bare.getJSONArray("trend").length())
    }
}
