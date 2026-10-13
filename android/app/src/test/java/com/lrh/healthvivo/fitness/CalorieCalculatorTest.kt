package com.lrh.healthvivo.fitness

import com.lrh.healthvivo.ai.ApiSettings
import org.junit.Assert.*
import org.junit.Test

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
