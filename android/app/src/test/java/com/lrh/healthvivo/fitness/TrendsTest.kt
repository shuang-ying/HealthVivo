package com.lrh.healthvivo.fitness

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * The calendar model behind the trend charts: which period is shown, how the columns divide it, and
 * how switching scale keeps the place. 2026-10-12 is a Monday and October 2026 has 31 days.
 */
class TrendsTest {
    private val today = LocalDate.parse("2026-10-14")
    private fun profile() = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1)
    private fun meal(id: String, day: String, kcal: Double) = MealEntry(id, day, "lunch", "食物", 100.0, kcal, source = "manual", recordedAt = 1)

    @Test fun aWeekRunsFromMondayToSunday() {
        val period = Trends.period("2026-10-14", TrendScale.Week)
        assertEquals(LocalDate.parse("2026-10-12"), period.start)
        assertEquals(LocalDate.parse("2026-10-19"), period.endExclusive)
        assertEquals("10/12–10/18", period.label)
    }

    @Test fun aMonthAndAYearCoverTheirWholeCalendarSpan() {
        val month = Trends.period("2026-10-14", TrendScale.Month)
        assertEquals(LocalDate.parse("2026-10-01"), month.start)
        assertEquals(LocalDate.parse("2026-11-01"), month.endExclusive)
        assertEquals("2026年10月", month.label)
        val year = Trends.period("2026-10-14", TrendScale.Year)
        assertEquals(LocalDate.parse("2026-01-01"), year.start)
        assertEquals(LocalDate.parse("2027-01-01"), year.endExclusive)
        assertEquals("2026年", year.label)
    }

    @Test fun changingScaleKeepsTheCalendarPlaceInsteadOfJumpingToToday() {
        // Standing in the week of 10/05 and switching to 月 must show October, not the current month.
        val inThatWeek = LocalDate.parse("2026-10-08")
        assertEquals(LocalDate.parse("2026-10-05"), Trends.period(inThatWeek.toString(), TrendScale.Week).start)
        assertEquals(LocalDate.parse("2026-10-01"), Trends.period(inThatWeek.toString(), TrendScale.Month).start)
        assertEquals(LocalDate.parse("2026-01-01"), Trends.period(inThatWeek.toString(), TrendScale.Year).start)
        // A year view of 2025 opens the month and week that contain the same day.
        val lastYear = LocalDate.parse("2025-06-15")
        assertEquals(LocalDate.parse("2025-01-01"), Trends.period(lastYear.toString(), TrendScale.Year).start)
        assertEquals(LocalDate.parse("2025-06-01"), Trends.period(lastYear.toString(), TrendScale.Month).start)
        assertEquals(LocalDate.parse("2025-06-09"), Trends.period(lastYear.toString(), TrendScale.Week).start)
    }

    @Test fun navigationStepsByTheUnitOfTheCurrentScale() {
        val anchor = LocalDate.parse("2026-10-14")
        assertEquals(LocalDate.parse("2026-10-07"), Trends.shift(anchor, TrendScale.Week, -1))
        assertEquals(LocalDate.parse("2026-09-14"), Trends.shift(anchor, TrendScale.Month, -1))
        assertEquals(LocalDate.parse("2025-10-14"), Trends.shift(anchor, TrendScale.Year, -1))
        // Stepping a month back from a month view lands in the previous calendar month.
        val monthBack = Trends.shift(anchor, TrendScale.Month, -1)
        assertEquals("2026年9月", Trends.period(monthBack.toString(), TrendScale.Month).label)
        val yearBack = Trends.shift(anchor, TrendScale.Year, -2)
        assertEquals("2024年", Trends.period(yearBack.toString(), TrendScale.Year).label)
    }

    @Test fun aWeekViewHasOneColumnPerDayNamedAfterTheWeekday() {
        val buckets = Trends.energy(FitnessData(profile()), Trends.period("2026-10-14", TrendScale.Week), today)
        assertEquals(7, buckets.size)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), buckets.map { it.label })
        assertEquals("10/12 周一", buckets.first().rangeText)
        assertEquals("10/18 周日", buckets.last().rangeText)
    }

    @Test fun aMonthViewHasOneColumnPerDayAndAYearViewOnePerMonth() {
        val month = Trends.energy(FitnessData(profile()), Trends.period("2026-10-14", TrendScale.Month), today)
        assertEquals(31, month.size)
        assertEquals("1", month.first().label)
        assertEquals("31", month.last().label)
        assertEquals("10/1", month.first().rangeText)
        val year = Trends.energy(FitnessData(profile()), Trends.period("2026-10-14", TrendScale.Year), today)
        assertEquals(12, year.size)
        assertEquals("1月", year.first().label)
        assertEquals("12月", year.last().label)
        assertEquals("2026年1月", year.first().rangeText)
    }

    @Test fun aRecordLandsInItsOwnColumnAndNotInTheNeighbouringPeriod() {
        // Every date here is on or before the selected day: a future date would be excluded outright.
        val data = FitnessData(profile(), meals = listOf(
            meal("a", "2026-10-12", 500.0),
            meal("b", "2026-10-13", 300.0),
            meal("c", "2026-10-11", 900.0)))
        val week = Trends.energy(data, Trends.period("2026-10-14", TrendScale.Week), today)
        assertEquals(500.0, week[0].intakeKcal, 0.001)
        assertEquals(300.0, week[1].intakeKcal, 0.001)
        assertEquals(0.0, week[3].intakeKcal, 0.001)
        // The previous Sunday belongs to the previous week, not this one.
        assertEquals(week.sumOf { it.intakeKcal }, 800.0, 0.001)
    }

    @Test fun aYearColumnSumsTheWholeMonthItStandsFor() {
        val data = FitnessData(profile(), meals = listOf(
            meal("a", "2026-01-05", 400.0), meal("b", "2026-01-20", 600.0), meal("c", "2026-03-01", 250.0)))
        val year = Trends.energy(data, Trends.period("2026-10-14", TrendScale.Year), today)
        assertEquals(1000.0, year[0].intakeKcal, 0.001)
        assertEquals(250.0, year[2].intakeKcal, 0.001)
        assertEquals(0.0, year[1].intakeKcal, 0.001)
    }

    @Test fun daysThatHaveNotHappenedAreFlaggedRatherThanCountedAsMeasuredZeros() {
        val data = FitnessData(profile(), meals = listOf(meal("a", "2026-10-12", 500.0)))
        val month = Trends.energy(data, Trends.period("2026-10-14", TrendScale.Month), today)
        // Index 0 is the 1st, so the meal on the 12th lands at index 11.
        assertFalse(month[0].recorded); assertFalse(month[0].future)
        assertTrue(month[11].recorded); assertFalse(month[11].future)
        // The 13th has passed with nothing recorded; the 15th has not arrived.
        assertFalse(month[12].recorded); assertFalse(month[12].future)
        assertFalse(month[14].recorded); assertTrue(month[14].future)
    }

    @Test fun axisLabelsAndTooltipNamesMatchTheScale() {
        val week = Trends.energy(FitnessData(profile()), Trends.period("2026-10-14", TrendScale.Week), today)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), week.map { it.label })
        assertEquals("10/12", week.first().shortText)
        assertEquals("10/12 周一", week.first().rangeText)
        val month = Trends.energy(FitnessData(profile()), Trends.period("2026-10-14", TrendScale.Month), today)
        assertEquals("1", month.first().label)
        assertEquals("31", month.last().label)
        // The tooltip names the day it belongs to, not just the number.
        assertEquals("10/31", month.last().shortText)
        val year = Trends.energy(FitnessData(profile()), Trends.period("2026-10-14", TrendScale.Year), today)
        assertEquals("1月", year.first().label)
        assertEquals("12月", year.last().label)
        assertEquals("12月", year.last().shortText)
        assertEquals("2026年12月", year.last().rangeText)
    }

    @Test fun onlyDaysThatHappenedAndHoldARecordContributeAReference() {
        // The weight from 10-12 is carried forward, so any later day would be credited with a whole day
        // of resting metabolism - including the seventeen days of October that have not happened, and
        // the blank days that hold no record at all.
        val data = FitnessData(profile(),
            weights = listOf(WeightEntry("2026-10-12", 70.0, 1)),
            meals = listOf(meal("a", "2026-10-13", 100.0), meal("b", "2026-10-14", 100.0)),
            steps = listOf(DailySteps("2026-10-12", 10000, 70.0, 1)))
        val october = Trends.energy(data, Trends.period("2026-10-14", TrendScale.Month), today)
        assertEquals(1648.75 + 245.0, october[11].referenceKcal!!, 0.001)   // 10-12: steps, and a weight
        assertEquals(1648.75, october[12].referenceKcal!!, 0.001)           // 10-13: a meal
        assertEquals(1648.75, october[13].referenceKcal!!, 0.001)           // 10-14: a meal, today
        assertNull(october[10].referenceKcal)                               // 10-11: nothing recorded
        assertFalse(october[10].recorded)
        assertNull(october[14].referenceKcal)                               // 10-15 has not happened
        assertTrue(october[14].future)
        assertEquals(1648.75 * 3 + 245.0, october.mapNotNull { it.referenceKcal }.sum(), 0.001)
    }

    @Test fun theProfileWeightIsNotAMeasurementSoItProducesNoReference() {
        // The profile carries a weight from setup. Using it would invent a burn for a day the user never
        // weighed themselves on, so a day whose weight is only the default contributes no reference.
        val data = FitnessData(profile(), meals = listOf(meal("a", "2026-10-14", 500.0)))
        val week = Trends.energy(data, Trends.period("2026-10-14", TrendScale.Week), today)
        val todayBucket = week[2]
        assertTrue(todayBucket.recorded)
        assertEquals(500.0, todayBucket.intakeKcal, 0.001)
        assertNull(todayBucket.referenceKcal)
        // A weight recorded on the day itself is a measurement, and then the reference appears.
        val weighed = FitnessData(profile(), weights = listOf(WeightEntry("2026-10-14", 70.0, 1)),
            meals = listOf(meal("a", "2026-10-14", 500.0)))
        assertEquals(1648.75, Trends.energy(weighed, Trends.period("2026-10-14", TrendScale.Week), today)[2].referenceKcal!!, 0.001)
    }

    @Test fun aWeightCarriesForwardUntilTheNextRecordAndStartsOnItsOwnDay() {
        // The three examples: a lone record covers everything after it; a second record takes over from
        // its own day; a third narrows the middle step. Days before the first record have no weight.
        val data = FitnessData(profile(), weights = listOf(
            WeightEntry("2026-10-10", 100.0, 1), WeightEntry("2026-10-20", 99.0, 2), WeightEntry("2026-10-25", 98.0, 3)))
        assertNull(CalorieCalculator.weightAt(data, "2026-10-09"))
        assertEquals(100.0, CalorieCalculator.weightAt(data, "2026-10-10")!!, 0.001)
        assertEquals(100.0, CalorieCalculator.weightAt(data, "2026-10-19")!!, 0.001)
        assertEquals(99.0, CalorieCalculator.weightAt(data, "2026-10-20")!!, 0.001)
        assertEquals(99.0, CalorieCalculator.weightAt(data, "2026-10-24")!!, 0.001)
        assertEquals(98.0, CalorieCalculator.weightAt(data, "2026-10-25")!!, 0.001)
        assertEquals(98.0, CalorieCalculator.weightAt(data, "2026-12-31")!!, 0.001)
        // The profile's own figure is what was typed at setup, never a measurement of a day.
        assertNull(CalorieCalculator.weightAt(FitnessData(profile()), "2026-10-14"))
    }

    @Test fun aLaterWeightRecordOnlyAffectsItsOwnDayAndThoseAfterIt() {
        val meals = listOf(meal("a", "2026-10-10", 100.0), meal("b", "2026-10-13", 100.0))
        val before = FitnessData(profile(), weights = listOf(WeightEntry("2026-10-10", 100.0, 1)), meals = meals)
        val after = FitnessData(profile(), weights = listOf(
            WeightEntry("2026-10-10", 100.0, 1), WeightEntry("2026-10-13", 99.0, 2)), meals = meals)
        val period = Trends.period("2026-10-14", TrendScale.Month)
        val old = Trends.energy(before, period, today)
        val new = Trends.energy(after, period, today)
        // Mifflin-St Jeor at 175 cm and 30 years: 1948.75 kg at 100 kg, 1938.75 at 99.
        assertEquals(1948.75, old[9].referenceKcal!!, 0.001)
        assertEquals(1948.75, new[9].referenceKcal!!, 0.001)     // 10-10 keeps the weight it recorded
        assertEquals(1948.75, old[12].referenceKcal!!, 0.001)
        assertEquals(1938.75, new[12].referenceKcal!!, 0.001)    // 10-13 was updated, so it is recomputed
    }

    @Test fun theAnchorOnlyPicksThePeriodAndNeverHidesLaterReadings() {
        // Selecting the 12th must not drop the reading made on the 14th: the anchor decides which period
        // the chart opens on, nothing more.
        val values = mapOf("2026-10-12" to 100.0, "2026-10-14" to 99.0)
        val week = Trends.measure(values, Trends.period("2026-10-12", TrendScale.Week), today)
        assertEquals(100.0, week[0].value!!, 0.001)
        assertEquals(99.0, week[2].value!!, 0.001)
        assertEquals("一", week[0].label)
        assertEquals("三", week[2].label)
    }

    @Test fun theWeightOnAnyDayIsTheLatestRecordOnOrBeforeIt() {
        // The three cases above are examples of one rule rather than a table, so this sweeps every day
        // of a year against the rule itself: any number of records, at any dates, with any gaps.
        val records = listOf(
            WeightEntry("2026-01-15", 102.0, 1), WeightEntry("2026-02-28", 101.0, 2),
            WeightEntry("2026-03-01", 100.5, 3), WeightEntry("2026-04-01", 100.5, 4),
            WeightEntry("2026-06-30", 95.0, 5), WeightEntry("2026-07-01", 94.0, 6),
            WeightEntry("2026-12-31", 90.0, 7))
        val data = FitnessData(profile(), weights = records)
        fun expected(day: String) = records.filter { it.day <= day }.maxByOrNull { it.day }?.kg
        var day = LocalDate.parse("2026-01-01")
        while (day <= LocalDate.parse("2027-01-31")) {
            val key = day.toString()
            assertEquals("weight on $key", expected(key), CalorieCalculator.weightAt(data, key))
            // The record's own day already uses the new value, and the day before it does not.
            records.firstOrNull { it.day == key }?.let { record ->
                assertEquals(record.kg, CalorieCalculator.weightAt(data, key)!!, 0.001)
                assertEquals(expected(day.minusDays(1).toString()), CalorieCalculator.weightAt(data, day.minusDays(1).toString()))
            }
            day = day.plusDays(1)
        }
    }

    @Test fun backfillingOrDeletingARecordReshapesOnlyTheStepsAroundIt() {
        val records = listOf(WeightEntry("2026-10-10", 100.0, 1), WeightEntry("2026-10-20", 98.0, 2))
        fun weightOf(weights: List<WeightEntry>, day: String) =
            CalorieCalculator.weightAt(FitnessData(profile(), weights = weights), day)
        assertEquals(100.0, weightOf(records, "2026-10-17")!!, 0.001)
        assertEquals(98.0, weightOf(records, "2026-10-20")!!, 0.001)
        // Backfilling the 15th carves a middle step out of the first one, and the 20th still takes over.
        val backfilled = records + WeightEntry("2026-10-15", 99.0, 3)
        assertEquals(100.0, weightOf(backfilled, "2026-10-14")!!, 0.001)
        assertEquals(99.0, weightOf(backfilled, "2026-10-15")!!, 0.001)
        assertEquals(99.0, weightOf(backfilled, "2026-10-19")!!, 0.001)
        assertEquals(98.0, weightOf(backfilled, "2026-10-20")!!, 0.001)
        // Deleting the 20th's record leaves the 15th's value in force from then on.
        val deleted = backfilled.filterNot { it.day == "2026-10-20" }
        assertEquals(99.0, weightOf(deleted, "2026-10-25")!!, 0.001)
        // Deleting the earliest record leaves the days before the next one with no weight at all.
        assertNull(weightOf(listOf(WeightEntry("2026-10-20", 98.0, 1)), "2026-10-19"))
    }

    @Test fun aSparseMeasurementKeepsItsGapsAndAYearTakesTheMonthEnd() {
        val values = mapOf("2026-01-05" to 80.0, "2026-01-20" to 78.0, "2026-03-02" to 77.0, "2025-12-31" to 81.0)
        val year = Trends.measure(values, Trends.period("2026-10-14", TrendScale.Year), today)
        assertEquals(78.0, year[0].value!!, 0.001)
        assertNull(year[1].value)
        assertEquals(77.0, year[2].value!!, 0.001)
        val week = Trends.measure(values, Trends.period("2026-01-05", TrendScale.Week), today)
        assertEquals(80.0, week[0].value!!, 0.001)
        assertNull(week[1].value)
        assertEquals(7, week.size)
    }
}
