package com.lrh.healthvivo.watch

import com.lrh.healthvivo.fitness.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Pins the trend arithmetic the watch performs against the phone's own answer.
 *
 * The watch aggregates the sparse history into week, month and year columns. That is the one piece of
 * the energy model implemented twice - once in Kotlin for the phone's charts, once in JavaScript for
 * the watch - so it is the one piece that can drift without either side failing to compile. Both sides
 * read this file: the phone writes the columns it computes with [Trends], and
 * `watch/test/trend-vectors.test.mjs` runs the watch's own aggregation over the same history and
 * demands the same numbers, down to the labels and the null references.
 */
class TrendVectorsTest {
    private val today = LocalDate.parse("2026-10-14")
    private fun fixture(): File {
        val candidates = listOf(File("../../watch/test/fixtures/trend-vectors.json"),
            File("../watch/test/fixtures/trend-vectors.json"), File("watch/test/fixtures/trend-vectors.json"))
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }
    private fun data() = FitnessData(
        profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1),
        weights = listOf(WeightEntry("2026-09-28", 72.0, 1), WeightEntry("2026-10-12", 70.0, 2),
            WeightEntry("2026-10-14", 69.5, 3)),
        meals = listOf(
            MealEntry("a", "2026-10-12", "lunch", "米饭", 200.0, 260.0, source = "manual", recordedAt = 1),
            MealEntry("b", "2026-10-13", "dinner", "面条", 250.0, 310.0, source = "manual", recordedAt = 1),
            MealEntry("c", "2026-10-14", "breakfast", "燕麦", 100.0, 120.0, source = "manual", recordedAt = 1),
            MealEntry("d", "2026-09-30", "lunch", "沙拉", 150.0, 180.0, source = "manual", recordedAt = 1)),
        exercises = listOf(ExerciseEntry("e1", "2026-10-14", "跑步", 30.0, 300.0, "met", weightKg = 70.0, met = 9.8,
            recordedAt = 1, category = "aerobic", inputMode = "time", includedInSteps = true, steps = 4000, netCalories = true)),
        steps = listOf(DailySteps("2026-10-12", 8000, 70.0, 1), DailySteps("2026-10-14", 12000, 69.5, 2)),
        bodyFats = listOf(BodyFatEntry("2026-10-08", 22.0, 1), BodyFatEntry("2026-10-14", 21.5, 2)))
    private fun bucket(value: TrendBucket) = JSONObject()
        .put("startDay", value.startDay).put("label", value.label).put("shortText", value.shortText)
        .put("rangeText", value.rangeText).put("intakeKcal", value.intakeKcal).put("activityKcal", value.activityKcal)
        .put("referenceKcal", value.referenceKcal ?: JSONObject.NULL).put("recorded", value.recorded).put("future", value.future)
    private fun point(value: TrendPoint) = JSONObject()
        .put("startDay", value.startDay).put("label", value.label).put("shortText", value.shortText)
        .put("rangeText", value.rangeText).put("value", value.value ?: JSONObject.NULL).put("future", value.future)
    private fun vectors(data: FitnessData): JSONObject {
        val cases = JSONArray()
        listOf(TrendScale.Week, TrendScale.Month, TrendScale.Year).forEach { scale ->
            listOf("2026-10-14", "2026-09-30").forEach { anchor ->
                val period = Trends.period(anchor, scale)
                val energy = Trends.energy(data, period, today)
                cases.put(JSONObject().put("scale", scale.name.lowercase()).put("anchor", anchor)
                    .put("start", period.start.toString()).put("endExclusive", period.endExclusive.toString())
                    .put("label", period.label).put("endInclusive", period.endInclusive)
                    .put("buckets", JSONArray(energy.map { bucket(it) }))
                    .put("weights", JSONArray(Trends.measure(data.weights.associate { it.day to it.kg }, period, today).map { point(it) }))
                    .put("fats", JSONArray(Trends.measure(data.bodyFats.associate { it.day to it.percent }, period, today).map { point(it) })))
            }
        }
        val history = Trends.history(data, 500, today)
        return JSONObject()
            .put("today", today.toString())
            .put("days", JSONArray(history.map { JSONObject().put("day", it.day).put("intakeKcal", it.intakeKcal)
                .put("activityKcal", it.activityKcal).put("referenceKcal", it.referenceKcal ?: JSONObject.NULL) }))
            .put("weights", JSONArray(data.weights.sortedBy { it.day }.map { JSONObject().put("day", it.day).put("value", it.kg) }))
            .put("fats", JSONArray(data.bodyFats.sortedBy { it.day }.map { JSONObject().put("day", it.day).put("value", it.percent) }))
            .put("cases", cases)
    }
    @Test fun theTrendVectorsMatchTheReviewedFixture() {
        val actual = vectors(data()).toString()
        val file = fixture()
        if (!file.exists()) {
            file.parentFile.mkdirs(); file.writeText(actual + "\n")
            println("Wrote the trend vector fixture: ${file.absolutePath}")
        }
        assertEquals("The trend arithmetic no longer matches watch/test/fixtures/trend-vectors.json.",
            canonical(JSONObject(file.readText())), canonical(JSONObject(actual)))
    }
    /** Key order must not matter: org.json orders keys by hash and a library bump must not read as a change. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString("\n") { "$it=${canonical(value.get(it))}" }.let { "{$it}" }
        is JSONArray -> (0 until value.length()).joinToString("\n") { canonical(value.get(it)) }.let { "[$it]" }
        else -> value.toString()
    }
}
