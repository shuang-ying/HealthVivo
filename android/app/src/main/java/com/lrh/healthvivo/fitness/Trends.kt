package com.lrh.healthvivo.fitness

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * What the x axis of a trend chart counts.
 *
 * The scale sets the axis granularity, not an aggregation window: a week shows its seven days, a
 * month its days, and a year its twelve months. Columns stay one calendar step wide whatever the
 * window, which is what makes "the same weekday last week" and "the same month last year" readable.
 */
enum class TrendScale(val label: String) { Week("周"), Month("月"), Year("年") }

/** One week, month or year, whichever [scale] says. Always half-open: [start, endExclusive). */
data class TrendPeriod(val scale: TrendScale, val start: LocalDate, val endExclusive: LocalDate) {
    val endInclusive: LocalDate get() = endExclusive.minusDays(1)
    fun shift(units: Long): TrendPeriod {
        val moved = when (scale) {
            TrendScale.Week -> start.plusWeeks(units)
            TrendScale.Month -> start.plusMonths(units)
            TrendScale.Year -> start.plusYears(units)
        }
        return copy(start = moved, endExclusive = when (scale) {
            TrendScale.Week -> moved.plusWeeks(1)
            TrendScale.Month -> moved.plusMonths(1)
            TrendScale.Year -> moved.plusYears(1)
        })
    }
    val label: String get() = when (scale) {
        TrendScale.Week -> "%d/%d–%d/%d".format(start.monthValue, start.dayOfMonth, endInclusive.monthValue, endInclusive.dayOfMonth)
        TrendScale.Month -> "%d年%d月".format(start.year, start.monthValue)
        TrendScale.Year -> "%d年".format(start.year)
    }
}

/** One column: a day for a week or month view, a whole month for a year view. */
data class TrendBucket(val startDay: String, val label: String, val shortText: String, val rangeText: String,
    val intakeKcal: Double, val activityKcal: Double, val referenceKcal: Double?,
    val recorded: Boolean, val future: Boolean)

/** One point of a measurement chart, which is sparse by nature: many columns hold no measurement. */
data class TrendPoint(val startDay: String, val label: String, val shortText: String, val rangeText: String,
    val value: Double?, val future: Boolean)

object Trends {
    private val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    /**
     * Move the position by [units] of the current scale. Navigation is expressed as a date rather than
     * as an index so that changing scale keeps the calendar position: the week of 10/05 and the month
     * of October both derive from an anchor inside them, and stepping a month back from October lands
     * in September whatever the scale used to be.
     */
    fun shift(anchor: LocalDate, scale: TrendScale, units: Long): LocalDate = when (scale) {
        TrendScale.Week -> anchor.plusWeeks(units)
        TrendScale.Month -> anchor.plusMonths(units)
        TrendScale.Year -> anchor.plusYears(units)
    }

    fun period(anchor: String, scale: TrendScale): TrendPeriod {
        val date = LocalDate.parse(anchor)
        return when (scale) {
            TrendScale.Week -> {
                val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                TrendPeriod(scale, start, start.plusWeeks(1))
            }
            TrendScale.Month -> {
                val start = date.withDayOfMonth(1)
                TrendPeriod(scale, start, start.plusMonths(1))
            }
            TrendScale.Year -> {
                val start = LocalDate.of(date.year, 1, 1)
                TrendPeriod(scale, start, start.plusYears(1))
            }
        }
    }

    /** The columns of [period], oldest first. */
    private fun columns(period: TrendPeriod): List<Pair<LocalDate, LocalDate>> = when (period.scale) {
        TrendScale.Week, TrendScale.Month -> generateSequence(period.start) { it.plusDays(1) }
            .takeWhile { it < period.endExclusive }.map { it to it }.toList()
        TrendScale.Year -> (0L until 12).map { offset ->
            val monthStart = period.start.plusMonths(offset)
            monthStart to minOf(monthStart.plusMonths(1).minusDays(1), period.endInclusive)
        }
    }

    private fun columnLabel(period: TrendPeriod, start: LocalDate): String = when (period.scale) {
        TrendScale.Week -> weekdayNames[start.dayOfWeek.value - 1]
        TrendScale.Month -> start.dayOfMonth.toString()
        TrendScale.Year -> "%d月".format(start.monthValue)
    }

    private fun columnText(period: TrendPeriod, start: LocalDate, end: LocalDate): String = when (period.scale) {
        TrendScale.Week -> "%d/%d 周%s".format(start.monthValue, start.dayOfMonth, weekdayNames[start.dayOfWeek.value - 1])
        TrendScale.Month -> "%d/%d".format(start.monthValue, start.dayOfMonth)
        TrendScale.Year -> "%d年%d月".format(start.year, start.monthValue)
    }

    /** The column named as briefly as possible, for a tooltip that also has to fit a number. */
    private fun columnShort(period: TrendPeriod, start: LocalDate): String = when (period.scale) {
        TrendScale.Week, TrendScale.Month -> "%d/%d".format(start.monthValue, start.dayOfMonth)
        TrendScale.Year -> "%d月".format(start.monthValue)
    }

    /**
     * The intake, recorded activity and remaining reference of every column of [period].
     *
     * A column sums the days it covers, so a month view answers "which day" and a year view answers
     * "which month". A day with no record contributes zeros rather than being dropped, which keeps the
     * calendar spacing; [TrendBucket.recorded] and [TrendBucket.future] keep that from being read as a
     * measured zero.
     */
    fun energy(data: FitnessData, period: TrendPeriod, today: LocalDate = LocalDate.now()): List<TrendBucket> {
        val mealsByDay = data.meals.groupBy { it.day }
        val workoutsByDay = data.exercises.groupBy { it.day }
        val stepsByDay = data.steps.associateBy { it.day }
        val weights = data.weights.sortedBy { it.day }
        val profile = data.profile
        return columns(period).map { (columnStart, columnEnd) ->
            var intake = 0.0; var activity = 0.0; var reference = 0.0; var referenceKnown = false; var recorded = false
            var cursor = columnStart
            // Only days that have happened and that actually hold a record. A blank day is not evidence
            // that nothing was eaten, so it may not contribute a day of resting metabolism either: that
            // is how a month-to-date balance came out tens of thousands of kcal in credit.
            while (cursor <= columnEnd && cursor <= today) {
                val key = cursor.toString()
                val hasRecord = mealsByDay[key] != null || workoutsByDay[key] != null || stepsByDay[key] != null
                if (hasRecord) {
                    recorded = true
                    // The weight has to be one the user recorded, carried forward from its own day; the
                    // figure kept in the profile is a default filled in at setup, not a measurement.
                    val weight = CalorieCalculator.weightOn(weights, key)
                    val dayActivity = CalorieCalculator.activities(stepsByDay[key], workoutsByDay[key] ?: emptyList(), weight)
                    intake += mealsByDay[key]?.sumOf { it.kcal } ?: 0.0
                    activity += dayActivity.kcal ?: 0.0
                    val dayPlan = profile?.takeIf { weight != null }?.copy(weightKg = weight!!)
                        ?.let { CalorieCalculator.plan(it, dayActivity.kcal ?: 0.0) }
                    if (dayPlan != null) { reference += dayPlan.maintenanceKcal; referenceKnown = true }
                }
                cursor = cursor.plusDays(1)
            }
            TrendBucket(columnStart.toString(), columnLabel(period, columnStart), columnShort(period, columnStart),
                columnText(period, columnStart, columnEnd),
                intake, activity, reference.takeIf { referenceKnown }, recorded, columnStart > today)
        }
    }

    /**
     * A sparse measurement per column: the weight or body-fat reading of that day, or of that month.
     *
     * For a year view a month has many readings and only one column, so the last reading of the month
     * is used - it is the state the month ended in, which is what a weight trend is read for. Gaps stay
     * gaps rather than becoming zeros, because "not measured" is not "measured as nothing".
     */
    fun measure(values: Map<String, Double>, period: TrendPeriod, today: LocalDate = LocalDate.now()): List<TrendPoint> =
        columns(period).map { (columnStart, columnEnd) ->
            var value: Double? = null
            var cursor = columnStart
            while (cursor <= columnEnd) {
                values[cursor.toString()]?.let { value = it }
                cursor = cursor.plusDays(1)
            }
            TrendPoint(columnStart.toString(), columnLabel(period, columnStart), columnShort(period, columnStart),
                columnText(period, columnStart, columnEnd), value, columnStart > today)
        }
}
