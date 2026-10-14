package com.lrh.healthvivo.watch

import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** A display-only daily snapshot. Credentials, photographs and profile details never enter it. */
object WatchSnapshot {
    const val CLIENT = "com.lrh.healthvivo.watch"
    /** Days of history, and measurement records, that travel with each day's snapshot. */
    const val HISTORY_LIMIT = 500
    const val MEASUREMENT_LIMIT = 500
    fun create(data: FitnessData, day: String, revision: Long, saved: SavedAdvice?,
        today: LocalDate = LocalDate.now()): JSONObject {
        val meals = data.meals.filter { it.day == day }.sortedWith(compareBy<MealEntry> { it.recordedAt }.thenBy { it.id })
        val workouts = data.exercises.filter { it.day == day }.sortedWith(compareBy<ExerciseEntry> { it.recordedAt }.thenBy { it.id })
        val steps = data.steps.firstOrNull { it.day == day }
        val weight = CalorieCalculator.weightAt(data, day)
        val bodyFat = BodyMetrics.bodyFatAt(data, day)
        val bmi = BodyMetrics.bmi(weight, data.profile?.heightCm)
        val activity = CalorieCalculator.activities(steps, workouts, weight)
        val plan = data.profile?.takeIf { weight != null }?.copy(weightKg = weight!!)?.let { CalorieCalculator.plan(it, activity.kcal ?: 0.0) }
        fun n(value: Any?) = value ?: JSONObject.NULL
        // The watch draws the same calendar charts as the phone. It gets the sparse per-day figures with
        // every energy rule already applied, plus the measurement records, so it only has to add up the
        // days of whichever period is on screen - the rules are never implemented twice.
        //
        // The history is the whole series, not part of any one day, so it travels with the newest day's
        // snapshot alone. Three things follow, and all three matter:
        //   - a period crossing into days after the day being viewed still has its readings, which is
        //     what makes the 14th's weight appear while the 12th is on screen;
        //   - an edit anywhere changes one snapshot rather than every day in the index, because the days
        //     that no longer carry it cannot be invalidated by it;
        //   - the transfer does not repeat the series once per cached day.
        val history = if(day == today.toString()) Trends.history(data, HISTORY_LIMIT, today) else null
        fun measurements(rows: List<Pair<String, Double>>) = JSONArray(rows.takeLast(MEASUREMENT_LIMIT).map { (day, value) ->
            JSONObject().put("day", day).put("value", value) })
        fun scale(definition: BodyReferenceScale?, value: Double?): Any = definition?.let {
            JSONObject().put("min", it.min).put("max", it.max).put("breaks", JSONArray(it.breaks)).put("labels", JSONArray(it.labels))
                .put("colors", JSONArray(it.colors)).put("unit", it.unit).put("value", n(value))
        } ?: JSONObject.NULL
        return JSONObject().put("schema", 3).put("client", CLIENT).put("day", day).put("revision", revision)
            .put("today", today.toString())
            .put("generatedAt", System.currentTimeMillis()).put("steps", n(steps?.steps))
            .put("workoutSteps", activity.workoutSteps).put("recordedSteps", n(activity.recordedSteps))
            .put("weightKg", n(weight)).put("targetWeightKg", n(data.profile?.targetWeightKg))
            .put("body", JSONObject().put("bmi", n(bmi)).put("bmiStatus", BodyMetrics.bmiStatus(bmi, data.profile).label)
                .put("fatPercent", n(bodyFat?.percent)).put("fatDay", n(bodyFat?.day)).put("fatStatus", BodyMetrics.fatStatus(bodyFat?.percent, data.profile).label)
                .put("bmiScale", scale(BodyMetrics.bmiScale(data.profile), bmi)).put("fatScale", scale(BodyMetrics.fatScale(data.profile), bodyFat?.percent)))
            .put("energy", JSONObject().put("intakeKcal", meals.sumOf { it.kcal })
                .put("restingKcal", n(plan?.restingKcal)).put("activityKcal", n(activity.kcal))
                .put("stepsKcal", n(activity.stepsKcal)).put("workoutKcal", n(activity.workoutKcal))
                .put("mealKcal", JSONArray(listOf("breakfast", "lunch", "dinner", "snack").map { kind -> meals.filter { it.meal == kind }.sumOf { it.kcal } }))
                .put("referenceKcal", n(plan?.maintenanceKcal)).put("pendingCount", activity.pendingCount)
                .put("overlapHandled", activity.overlapHandled))
            .put("history", history?.let { days -> JSONObject()
                .put("days", JSONArray(days.map { point ->
                    JSONObject().put("day", point.day).put("intakeKcal", point.intakeKcal)
                        .put("activityKcal", point.activityKcal).put("referenceKcal", n(point.referenceKcal)) }))
                .put("weights", measurements(data.weights.sortedBy { it.day }.map { it.day to it.kg }))
                .put("fats", measurements(data.bodyFats.sortedBy { it.day }.map { it.day to it.percent })) } ?: JSONObject.NULL)
            .put("meals", JSONArray(listOf("breakfast", "lunch", "dinner", "snack").map { kind ->
                val rows = meals.filter { it.meal == kind }
                JSONObject().put("kind", kind).put("count", rows.size)
                    .put("kcal", n(rows.takeIf { it.isNotEmpty() }?.sumOf { it.kcal }))
                    .put("omitted", (rows.size - 20).coerceAtLeast(0))
                    .put("foods", JSONArray(rows.take(20).map { row -> JSONObject().put("name", row.name.take(120))
                        .put("grams", row.grams).put("kcal", row.kcal).put("estimated", row.source == "ai") }))
            }))
            .put("exerciseCount", workouts.size).put("omittedExercises", (workouts.size - 20).coerceAtLeast(0))
            .put("exercises", JSONArray(workouts.take(20).map { row -> JSONObject().put("name", row.name.take(120))
                .put("category", row.category).put("minutes", n(row.minutes)).put("distanceKm", n(row.distanceKm))
                .put("sets", n(row.sets)).put("reps", n(row.reps)).put("loadKg", n(row.loadKg))
                .put("kcal", n(row.kcal)).put("net", row.netCalories).put("estimated", row.method == "ai")
                .put("includedInSteps", row.includedInSteps).put("steps", n(row.steps)) }))
            .put("advice", n(saved?.let { JSONObject().put("generatedAt", it.generatedAt)
                .put("stale", it.fingerprint != AdviceSummary.create(data, day).fingerprint)
                .put("content", JSONObject().put("food", it.advice.food).put("exercise", it.advice.exercise)) }))
    }
    /**
     * Fields that belong to the session rather than to the day's content, and so must not take part in
     * the revision the watch stores.
     *
     * - `revision` and `generatedAt` are assigned by the synchroniser itself, so hashing them would be
     *   circular and would make every check look like a change.
     * - `today` is what the watch compares a column against to draw it as not-yet-reached. It advances
     *   for every day at midnight at once; hashing it would report the whole index as edited once a day,
     *   and all a stale value can do is colour one stub differently until something real moves.
     */
    private val VOLATILE_FIELDS = listOf("revision", "generatedAt", "today")
    /** The text a day's revision is derived from: the snapshot without its session fields. */
    fun revisionMaterial(snapshot: JSONObject): String {
        val copy = JSONObject(snapshot.toString())
        VOLATILE_FIELDS.forEach { copy.remove(it) }
        return copy.toString()
    }
    // Retained for historical prototype tools; production synchronization uses the direct link.
    fun checksum(text: String): String {        var hash = 5381
        text.forEach { hash = hash * 31 + it.code }
        return Integer.toUnsignedString(hash, 16)
    }
    fun parts(text: String): List<String> {
        require(text.length <= 128 * 1024) { "当日显示摘要过大" }
        val result = mutableListOf<String>()
        var start = 0
        while(start < text.length) {
            var end = minOf(start + 512, text.length)
            if(end < text.length && text[end - 1].isHighSurrogate()) end--
            result.add(text.substring(start, end)); start = end
        }
        require(result.size <= 256) { "当日显示摘要过大" }
        return result
    }
}
