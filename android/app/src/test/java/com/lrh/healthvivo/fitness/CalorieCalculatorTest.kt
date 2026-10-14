package com.lrh.healthvivo.fitness

import com.lrh.healthvivo.ai.ApiSettings
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalorieCalculatorTest {
    private fun profile(sex: String = "male", age: Int = 30, goal: String = "maintain") =
        Profile(age = age, heightCm = 175.0, weightKg = 70.0, sex = sex, goal = goal, updatedAt = 1000)
    @Test fun restingEstimateUsesVerifiedSexSpecificFormula() {
        assertEquals(1648.75, CalorieCalculator.plan(profile())!!.restingKcal, 0.001)
        assertEquals(1482.75, CalorieCalculator.plan(profile("female"))!!.restingKcal, 0.001)
    }
    @Test fun referenceUsesRecordedActivityWithoutHiddenActivityMultiplier() {
        val value = CalorieCalculator.plan(profile(), 300.0)!!
        assertEquals(value.restingKcal + 300.0, value.maintenanceKcal, 0.001)
        assertEquals(value.maintenanceKcal, value.targetKcal, 0.001)
    }
    @Test fun unsupportedProfilesKeepRecordsWithoutAutomaticTargets() {
        assertNull(CalorieCalculator.plan(profile(age = 16)))
        assertNull(CalorieCalculator.plan(profile(age = 18)))
        assertNull(CalorieCalculator.plan(profile(age = 79)))
        assertNull(CalorieCalculator.plan(profile("unspecified")))
        assertNull(CalorieCalculator.plan(profile().copy(specialCondition = true)))
    }
    @Test fun incompleteExpenditureDoesNotBecomeADietPrescription() {
        val lose = CalorieCalculator.plan(profile(goal = "lose"))!!
        assertTrue(lose.adjustmentKcal >= -300 && lose.targetKcal >= lose.restingKcal)
        val gain = CalorieCalculator.plan(profile(goal = "gain"))!!
        assertEquals(0.0, gain.adjustmentKcal, 0.001)
        assertEquals(lose.restingKcal, lose.targetKcal, 0.001)
    }
    @Test fun foodScalesEdibleWeightWithoutAssumingAPortion() {
        assertEquals(195.0, CalorieCalculator.foodKcal(150.0, 130.0), 0.001)
        assertEquals(0.0, CalorieCalculator.foodKcal(150.0, 0.0), 0.001)
    }
    @Test fun workoutNetEstimateSubtractsRestingEnergy() {
        assertEquals(183.75, CalorieCalculator.netExerciseKcal(3.5, 70.0, 60.0), 0.001)
        assertEquals(0.0, CalorieCalculator.netExerciseKcal(1.0, 70.0, 60.0), 0.001)
    }
    @Test fun invalidAndInfiniteValuesAreRejected() {
        fun rejects(action: () -> Unit) { try { action(); fail("Invalid data accepted") } catch (_: IllegalArgumentException) {} }
        rejects { CalorieCalculator.foodKcal(Double.NaN, 130.0) }
        rejects { CalorieCalculator.foodKcal(-1.0, 130.0) }
        rejects { CalorieCalculator.foodKcal(100.0, Double.POSITIVE_INFINITY) }
        rejects { CalorieCalculator.netExerciseKcal(3.0, 70.0, 0.0) }
        rejects { profile().copy(weightKg = Double.NaN).validate() }
        rejects { profile().copy(sex = "unknown").validate() }
    }
    @Test fun historicalWeightDoesNotUseFutureMeasurements() {
        val data = FitnessData(profile(), weights = listOf(WeightEntry("2026-10-12", 70.0, 2), WeightEntry("2026-10-09", 72.0, 1)))
        assertEquals(72.0, CalorieCalculator.weightAt(data, "2026-10-10")!!, 0.001)
        assertNull(CalorieCalculator.weightAt(data, "2026-10-01"))
    }
    @Test fun historyListsOnlyTheDaysThatHoldARecordAndPricesThemByTheDay() {
        val data = FitnessData(profile(),
            weights = listOf(WeightEntry("2026-10-12", 70.0, 1)),
            meals = listOf(MealEntry("m", "2026-10-12", "lunch", "米饭", 100.0, 130.0, source = "manual", recordedAt = 1)),
            exercises = listOf(ExerciseEntry("e", "2026-10-12", "力量训练", 30.0, 91.875, "met", weightKg = 70.0, met = 3.5, recordedAt = 1, netCalories = true)),
            steps = listOf(DailySteps("2026-10-12", 10000, 70.0, 1)))
        val history = Trends.history(data, today = LocalDate.parse("2026-10-12"))
        // Only the recorded day travels: a blank day is not a row of zeros, it is simply absent, which is
        // what keeps a year of history small enough to send with every day's snapshot.
        assertEquals(1, history.size)
        val only = history.single()
        assertEquals("2026-10-12", only.day)
        assertEquals(130.0, only.intakeKcal, 0.001)
        // 245 kcal of walking plus the strength estimate, and the two are different bars' worth of data.
        assertEquals(336.875, only.activityKcal, 0.001)
        assertEquals(1648.75 + 336.875, only.referenceKcal!!, 0.001)
        // A day the phone has not reached yet is never sent, so a history asked for on the 11th is empty.
        assertTrue(Trends.history(data, today = LocalDate.parse("2026-10-11")).isEmpty())
    }
    @Test fun apiSettingsRejectUnencryptedTransportAndEmbeddedSecrets() {
        fun rejects(url: String) { try { ApiSettings(url, "model", "secret").validate(); fail("Invalid URL accepted") } catch (_: IllegalArgumentException) {} }
        rejects("http://example.com/v1/chat/completions")
        rejects("https://user:secret@example.com/v1/chat/completions")
        rejects("https://example.com/v1/chat/completions?api_key=secret")
        ApiSettings("https://example.com/v1/chat/completions", "vision-model", "secret").validate()
    }
    @Test fun mealAndExerciseRejectInvalidDatesAndWrongUnits() {
        try { MealEntry("id", "bad-date", "lunch", "米饭", 100.0, 130.0, source = "manual", recordedAt = 1).validate(); fail("Bad date accepted") } catch (_: java.time.format.DateTimeParseException) {}
        try { ExerciseEntry("id", "2026-10-12", "跑步", 30.0, 200.0, "met", met = 7.5, recordedAt = 1).validate(); fail("Missing weight accepted") } catch (_: IllegalArgumentException) {}
    }
}
