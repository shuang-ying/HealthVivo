package com.lrh.healthvivo.watch

import com.lrh.healthvivo.fitness.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the JSON the phone sends to the file the watch validates.
 *
 * The watch is a separate program in another language, so the failure mode is not a compile error but
 * a silent disagreement about field names or types. `watch/test/snapshot-contract.test.mjs` runs this
 * exact file through the watch's own validator and row builders, so renaming a field on either side
 * turns one of the two tests red instead of shipping a day the watch quietly refuses.
 *
 * The file is written if it is missing and asserted afterwards. It is meant to be read and reviewed:
 * regenerating it to silence a failure would defeat its purpose.
 */
class WatchSnapshotContractTest {
    private val day = "2026-10-12"
    private val fixedGeneratedAt = 1_760_000_000_000L
    private fun fixture(): File {
        val candidates = listOf(File("../../watch/test/fixtures/snapshot-v1.json"),
            File("../watch/test/fixtures/snapshot-v1.json"), File("watch/test/fixtures/snapshot-v1.json"))
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }
    private fun snapshot(): JSONObject {
        val run = ExerciseEntry("run-1", day, "跑步", 32.0, 350.0, "met", weightKg = 70.0, met = 9.8, recordedAt = 1,
            category = "aerobic", inputMode = "time", includedInSteps = true, netCalories = true, steps = 3000,
            estimateNote = "2024 Compendium 12020；示例数据")
        val strength = ExerciseEntry("strength-1", day, "力量训练", 30.0, 91.875, "met", weightKg = 70.0, met = 3.5, recordedAt = 2,
            category = "anaerobic", inputMode = "sets", sets = 4, reps = 10, loadKg = 40.0, netCalories = true)
        val data = FitnessData(
            profile = Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", goal = "lose", updatedAt = 1, targetWeightKg = 65.0),
            weights = listOf(WeightEntry(day, 70.0, 1)),
            meals = listOf(
                MealEntry("meal-1", day, "breakfast", "燕麦粥", 250.0, 70.0, source = "database", sourceReference = "USDA", recordedAt = 1),
                MealEntry("meal-2", day, "lunch", "鸡胸肉", 150.0, 165.0, source = "manual", recordedAt = 2)),
            exercises = listOf(run, strength),
            steps = listOf(DailySteps(day, 10000, 70.0, 1)),
            bodyFats = listOf(BodyFatEntry(day, 21.5, 1)))
        // generatedAt cannot be reproduced, so it is pinned before comparing.
        return WatchSnapshot.create(data, day, 7, null).also { it.put("generatedAt", fixedGeneratedAt) }
    }
    /**
     * A key-order-independent rendering of the snapshot. org.json orders keys by hash, so comparing
     * the pretty-printed text directly would fail on a library bump that changed nothing meaningful.
     */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString("\n") { "$it=${canonical(value.get(it))}" }.let { "{$it}" }
        is JSONArray -> (0 until value.length()).joinToString("\n") { canonical(value.get(it)) }.let { "[$it]" }
        else -> value.toString()
    }
    @Test fun theSnapshotMatchesTheReviewedContractFixture() {
        // Both sides go through JSON text first: writing a Double as text drops the ".0", so comparing
        // a live object against a parsed file would report differences that do not exist on the wire.
        val actual = JSONObject(snapshot().toString()).toString(2)
        val file = fixture()
        if (!file.exists()) {
            file.parentFile.mkdirs(); file.writeText(actual + "\n")
            println("Wrote the watch snapshot contract fixture: ${file.absolutePath}")
        }
        assertEquals("The phone's snapshot no longer matches watch/test/fixtures/snapshot-v1.json. " +
            "Either the change is intended (update the fixture and the watch together) or it is a regression.",
            canonical(JSONObject(file.readText())), canonical(JSONObject(actual)))
    }
    @Test fun theFixtureCoversTheFieldsTheWatchDrawsFrom() {
        val value = snapshot()
        assertEquals(day, value.getString("day"))
        assertEquals(10000, value.getInt("steps"))
        assertEquals(3000, value.getInt("workoutSteps"))
        assertEquals(7000, value.getInt("recordedSteps"))
        assertEquals(14, value.getJSONArray("trend").length())
        assertEquals(4, value.getJSONObject("energy").getJSONArray("mealKcal").length())
        assertEquals(2, value.getInt("exerciseCount"))
        assertEquals(2, value.getJSONArray("exercises").length())
    }
}
