package com.lrh.healthvivo.fitness

import org.junit.Assert.*
import org.junit.Test

/**
 * The weekly / monthly / yearly histograms. Bucket boundaries are the part that is easy to get subtly
 * wrong, so each test pins a record to the one bucket that may contain it and checks its neighbours
 * stay empty.
 */
class TrendBucketTest {
    // 2026-10-12 is a Monday, so the current week is 10-12..10-18 and the previous one 10-05..10-11.
    private val day = "2026-10-12"
    private fun profile() = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1)
    private fun meal(id: String, day: String, kcal: Double) = MealEntry(id, day, "lunch", "食物", 100.0, kcal, source = "manual", recordedAt = 1)

    @Test fun weeklyBucketsPutEachDayInItsOwnMondayToSundayWeek() {
        val data = FitnessData(profile(), meals = listOf(
            meal("a", "2026-10-12", 500.0),
            meal("b", "2026-10-11", 300.0),
            meal("c", "2026-09-30", 200.0)))
        val buckets = CalorieCalculator.trendBuckets(data, day, TrendRange.Week)
        assertEquals(12, buckets.size)
        assertEquals("7/27", buckets.first().label)
        // The newest week is cut at the selected day, so it cannot claim days that have not happened.
        assertEquals("10/12–10/12", buckets.last().rangeText)
        assertEquals("10/5–10/11", buckets[10].rangeText)
        assertEquals(500.0, buckets[11].intakeKcal, 0.001)
        assertEquals(300.0, buckets[10].intakeKcal, 0.001)
        assertEquals("9/28–10/4", buckets[9].rangeText)
        assertEquals(200.0, buckets[9].intakeKcal, 0.001)
        assertEquals(0.0, buckets[8].intakeKcal, 0.001)
    }

    @Test fun monthlyBucketsFollowCalendarMonthsAndIgnoreLaterDays() {
        val data = FitnessData(profile(), meals = listOf(
            meal("a", "2026-10-01", 400.0),
            meal("b", "2026-10-31", 900.0),
            meal("c", "2026-09-30", 250.0)))
        val buckets = CalorieCalculator.trendBuckets(data, day, TrendRange.Month)
        assertEquals(12, buckets.size)
        assertEquals("10月", buckets.last().label)
        assertEquals("2026-10", buckets.last().rangeText)
        // 10-31 is later than the selected day, so it is not part of a month-to-date figure yet.
        assertEquals(400.0, buckets.last().intakeKcal, 0.001)
        assertEquals("2026-09", buckets[10].rangeText)
        assertEquals(250.0, buckets[10].intakeKcal, 0.001)
        assertEquals("2025-11", buckets.first().rangeText)
    }

    @Test fun yearlyBucketsGroupWholeCalendarYears() {
        val data = FitnessData(profile(), meals = listOf(
            meal("a", "2026-01-05", 100.0), meal("b", "2025-06-01", 200.0), meal("c", "2022-12-31", 300.0)))
        val buckets = CalorieCalculator.trendBuckets(data, day, TrendRange.Year)
        assertEquals(5, buckets.size)
        assertEquals("2022", buckets.first().rangeText)
        assertEquals("2026", buckets.last().rangeText)
        assertEquals(300.0, buckets[0].intakeKcal, 0.001)
        assertEquals(200.0, buckets[3].intakeKcal, 0.001)
        assertEquals(100.0, buckets[4].intakeKcal, 0.001)
    }

    @Test fun eachBucketSumsOnlyTheDaysItCovers() {
        val run = ExerciseEntry("r", "2026-10-12", "跑步", 30.0, 350.0, "met", weightKg = 70.0, met = 9.8, recordedAt = 1,
            category = "aerobic", inputMode = "time", includedInSteps = true, netCalories = true, steps = 3000)
        val data = FitnessData(profile(),
            weights = listOf(WeightEntry("2026-10-11", 70.0, 1), WeightEntry("2026-10-12", 70.0, 1)),
            exercises = listOf(run),
            steps = listOf(DailySteps("2026-10-11", 5000, 70.0, 1), DailySteps("2026-10-12", 10000, 70.0, 1)))
        val buckets = CalorieCalculator.trendBuckets(data, day, TrendRange.Week)
        // 10-12: the remaining 7000 steps priced as walking, plus the run itself.
        assertEquals(171.5 + 350.0, buckets[11].activityKcal, 0.001)
        assertEquals(1648.75 + 521.5, buckets[11].referenceKcal!!, 0.001)
        // 10-11 sits in the previous Monday-to-Sunday week and is only 5000 steps of walking.
        assertEquals(122.5, buckets[10].activityKcal, 0.001)
        assertEquals(1648.75 + 122.5, buckets[10].referenceKcal!!, 0.001)
        // Weeks before the first weight have activity but no reference to subtract from.
        assertEquals(0.0, buckets[9].activityKcal, 0.001)
        assertNull(buckets[9].referenceKcal)
    }

    @Test fun bucketsReportNoReferenceWhenThereIsNoProfileToEstimateFrom() {
        val buckets = CalorieCalculator.trendBuckets(FitnessData(meals = listOf(meal("a", day, 100.0))), day, TrendRange.Month)
        assertEquals(100.0, buckets.last().intakeKcal, 0.001)
        assertNull(buckets.last().referenceKcal)
        assertEquals(0.0, buckets.last().activityKcal, 0.001)
    }
}
