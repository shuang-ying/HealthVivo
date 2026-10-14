package com.lrh.healthvivo.watch

import com.lrh.healthvivo.fitness.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

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
    private fun snapshot() = WatchSnapshot.create(data(), day, 7, null, LocalDate.parse(day))

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
    @Test fun theRevisionIgnoresSessionFieldsButNotContent() {
        val snapshot = snapshot()
        val material = WatchSnapshot.revisionMaterial(snapshot)
        // The synchroniser's own bookkeeping must not look like a change, and neither must tomorrow.
        val touched = org.json.JSONObject(snapshot.toString()).put("revision", 999L).put("generatedAt", 1L)
            .put("today", "2026-11-01")
        assertEquals(material, WatchSnapshot.revisionMaterial(touched))
        // A real change still moves it, so the field list cannot be widened until nothing is compared.
        assertNotEquals(material, WatchSnapshot.revisionMaterial(
            org.json.JSONObject(snapshot.toString()).put("steps", 20000)))
    }
    @Test fun aLaterRecordNeverChangesAnEarlierDaysSnapshot() {
        // The synchroniser hashes a whole day's snapshot to decide whether to re-send it. Only the newest
        // day carries the series, so a past day cannot be invalidated by a record made after it: this is
        // the property that keeps one edit from re-sending the index.
        val dayBefore = "2026-10-11"
        val today = LocalDate.parse(day)
        val base = FitnessData(profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1),
            weights = listOf(WeightEntry(dayBefore, 70.0, 1)),
            meals = listOf(MealEntry("m0", dayBefore, "lunch", "米饭", 100.0, 130.0, source = "manual", recordedAt = 1)))
        val later = base.copy(meals = base.meals + MealEntry("m1", day, "dinner", "面条", 200.0, 260.0, source = "manual", recordedAt = 2))
        fun earlier(data: FitnessData) = WatchSnapshot.revisionMaterial(WatchSnapshot.create(data, dayBefore, 1, null, today))
        assertEquals(earlier(base), earlier(later))
    }
    @Test fun historyCarriesTheRecordedDaysAndTheMeasurements() {
        val history = snapshot().getJSONObject("history")
        val days = history.getJSONArray("days")
        assertTrue(days.length() > 0)
        val last = days.getJSONObject(days.length() - 1)
        assertEquals(day, last.getString("day"))
        assertEquals(190.0, last.getDouble("intakeKcal"), 0.001)
        assertEquals(521.5, last.getDouble("activityKcal"), 0.001)
        assertFalse(last.isNull("referenceKcal"))
        // Only days with a record travel; the watch adds up a period from them.
        for(position in 0 until days.length()) assertTrue(days.getJSONObject(position).getString("day") <= day)
        val weights = history.getJSONArray("weights")
        assertEquals(1, weights.length())
        assertEquals(70.0, weights.getJSONObject(0).getDouble("value"), 0.001)
        assertEquals("2026-10-12", weights.getJSONObject(0).getString("day"))
    }
    @Test fun onlyTheNewestDayCarriesTheSeriesAndItIsTheWholeSeries() {
        // The history is the series itself, not part of a day: it travels with today's snapshot, and a
        // day whose snapshot is drawn from it still sees readings recorded after that day.
        val base = FitnessData(profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1),
            weights = listOf(WeightEntry("2026-10-12", 70.0, 1), WeightEntry("2026-10-14", 69.0, 2)),
            meals = listOf(MealEntry("m0", "2026-10-12", "lunch", "米饭", 100.0, 130.0, source = "manual", recordedAt = 1)))
        val today = java.time.LocalDate.parse("2026-10-14")
        val newest = WatchSnapshot.create(base, "2026-10-14", 1, null, today)
        val older = WatchSnapshot.create(base, "2026-10-12", 2, null, today)
        assertFalse(newest.isNull("history"))
        assertTrue(older.isNull("history"))
        // The series reaching past the older day is exactly what stops the 14th's reading disappearing.
        val weights = newest.getJSONObject("history").getJSONArray("weights")
        assertEquals("2026-10-14", weights.getJSONObject(weights.length() - 1).getString("day"))
    }
    @Test fun aDayWithoutStepsOrWorkoutsStillProducesAValidSnapshot() {
        val bare = WatchSnapshot.create(FitnessData(
            profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1)),
            day, 8, null, LocalDate.parse(day))
        assertTrue(bare.isNull("steps"))
        assertTrue(bare.isNull("recordedSteps"))
        assertEquals(0, bare.getInt("workoutSteps"))
        assertFalse(bare.getJSONObject("energy").getBoolean("overlapHandled"))
        // No records at all: an empty history rather than a run of zero days.
        assertEquals(0, bare.getJSONObject("history").getJSONArray("days").length())
    }
}
