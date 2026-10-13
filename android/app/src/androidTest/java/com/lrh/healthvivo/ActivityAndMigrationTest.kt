package com.lrh.healthvivo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ActivityAndMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun response(body: JSONObject, reason: String = "stop") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", reason).put("message", JSONObject().put("content", body.toString())))).toString()
    private fun estimate(kcal: Any = 100, low: Any = 70, high: Any = 130) = JSONObject().put("netKcal", kcal)
        .put("lowKcal", low).put("highKcal", high).put("note", "估算含强度与休息假设，净消耗范围")
    private fun strength(minutes: Double? = null, reps: Int? = null, load: Double? = null) = ExerciseEntry("test", "2026-10-12", "卧推", minutes, null, "pending",
        weightKg = 70.0, recordedAt = 1, category = "anaerobic", inputMode = "sets", sets = 4, reps = reps, loadKg = load, details = "组间休息90秒")
    @Test fun migrationPreservesAllExistingDataAndRemovesActivityFactor() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("CREATE TABLE profile (id INTEGER NOT NULL PRIMARY KEY, age INTEGER NOT NULL, heightCm REAL NOT NULL, weightKg REAL NOT NULL, sex TEXT NOT NULL, activityFactor REAL NOT NULL, goal TEXT NOT NULL, specialCondition INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE weights (day TEXT NOT NULL PRIMARY KEY, kg REAL NOT NULL, recordedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE meals (id TEXT NOT NULL PRIMARY KEY, day TEXT NOT NULL, meal TEXT NOT NULL, name TEXT NOT NULL, grams REAL NOT NULL, kcalPer100g REAL NOT NULL, proteinPer100g REAL, fatPer100g REAL, carbsPer100g REAL, source TEXT NOT NULL, sourceReference TEXT NOT NULL, recordedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE exercises (id TEXT NOT NULL PRIMARY KEY, day TEXT NOT NULL, name TEXT NOT NULL, minutes REAL NOT NULL, kcal REAL NOT NULL, method TEXT NOT NULL, weightKg REAL, met REAL, recordedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE custom_foods (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kcalPer100g REAL NOT NULL)")
                old.execSQL("INSERT INTO profile VALUES (1,30,175,70,'male',1.8,'lose',0,101)")
                old.execSQL("INSERT INTO weights VALUES ('2026-10-11',71,102)")
                old.execSQL("INSERT INTO meals VALUES ('meal','2026-10-11','lunch','米饭',150,130,2.5,0.3,28,'database','food-source',103)")
                old.execSQL("INSERT INTO exercises VALUES ('manual','2026-10-11','骑行',30,200,'manual',NULL,NULL,104)")
                old.execSQL("INSERT INTO exercises VALUES ('met','2026-10-11','步行（约 4.5–5.5 km/h）',30,102.9,'met',70,3.8,105)")
                old.execSQL("INSERT INTO custom_foods VALUES ('food','自建食物',140)")
                old.version = 1
            }
            val db = Room.databaseBuilder(context, FitnessDatabase::class.java, name).addMigrations(FitnessDatabase.MIGRATION_1_2, FitnessDatabase.MIGRATION_2_3).build()
            try {
                val data = FitnessRepository(db).data.first()
                assertEquals("lose", data.profile!!.goal); assertEquals(101L, data.profile!!.updatedAt)
                assertEquals(71.0, data.weights.single().kg, 0.001)
                assertEquals("food-source", data.meals.single().sourceReference); assertEquals(2.5, data.meals.single().proteinPer100g!!, 0.001)
                assertEquals("自建食物", data.customFoods.single().name)
                assertEquals(2, data.exercises.size)
                val manual = data.exercises.single { it.id == "manual" }
                assertEquals(200.0, manual.kcal!!, 0.001); assertFalse(manual.netCalories)
                val met = data.exercises.single { it.id == "met" }
                assertEquals(102.9, met.kcal!!, 0.001); assertTrue(met.netCalories); assertTrue(met.includedInSteps)
                assertTrue(data.steps.isEmpty())
                db.openHelper.writableDatabase.query("PRAGMA table_info(profile)").use { cursor ->
                    val columns = mutableListOf<String>(); while(cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    assertFalse("activityFactor" in columns)
                }
                FitnessRepository(db).saveSteps(DailySteps("2026-10-11", 6000, 70.0, 106))
                assertEquals(6000, FitnessRepository(db).data.first().steps.single().steps)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun stepsReplaceSameDayAndSetsAndDistanceSurviveReopening() = runBlocking {
        val name = "activity-test-${UUID.randomUUID()}.db"
        try {
            val db = Room.databaseBuilder(context, FitnessDatabase::class.java, name).build()
            try {
                val repo = FitnessRepository(db)
                repo.saveSteps(DailySteps("2026-10-12", 6000, recordedAt = 1))
                repo.saveSteps(DailySteps("2026-10-12", 7000, 70.0, 2))
                repo.saveSteps(DailySteps("2026-10-11", 0, recordedAt = 3))
                repo.saveExercise(strength())
                repo.saveExercise(ExerciseEntry("run", "2026-10-12", "跑步", null, 350.0, "distance", 70.0, recordedAt = 4,
                    category = "aerobic", inputMode = "distance", distanceKm = 5.0, netCalories = true, includedInSteps = true, estimateNote = "距离公式"))
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, FitnessDatabase::class.java, name).build()
            try {
                val repo = FitnessRepository(reopened); val data = repo.data.first()
                assertEquals(2, data.steps.size); assertEquals(7000, data.steps.first { it.day == "2026-10-12" }.steps)
                assertEquals(0, data.steps.first { it.day == "2026-10-11" }.steps)
                assertNull(data.exercises.first { it.id == "test" }.kcal); assertEquals(4, data.exercises.first { it.id == "test" }.sets)
                assertEquals(5.0, data.exercises.first { it.id == "run" }.distanceKm!!, 0.001)
                repo.dao.deleteSteps("2026-10-11"); assertEquals(1, repo.data.first().steps.size)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun exerciseUsesMealApiConfigurationAndOnlyCurrentExerciseAndWeight() = runBlocking {
        val settings = ApiSettings("https://example.com/v1/chat/completions", "shared-model", "test-secret")
        val client = ExerciseAiClient(ApiTransport { sentSettings, body ->
            assertEquals(settings, sentSettings)
            val root = JSONObject(body); assertEquals(settings.model, root.getString("model"))
            val current = JSONObject(root.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("卧推", current.getString("name")); assertEquals(70.0, current.getDouble("weightKg"), 0.001)
            assertEquals("组间休息90秒", current.getString("details")); assertEquals(4, current.getInt("sets"))
            assertFalse(current.has("age")); assertFalse(current.has("heightCm")); assertFalse(current.has("day")); assertFalse(current.has("history"))
            assertFalse(body.contains(settings.key))
            response(estimate())
        })
        val result = client.estimate(settings, strength(30.0, 10, 40.0))
        assertEquals(100.0, result.netKcal!!, 0.001); assertEquals(70.0, result.lowKcal!!, 0.001)
    }
    @Test fun setsOnlyCannotReceiveFabricatedPreciseCaloriesEvenFromNonCompliantAi() = runBlocking {
        val client = ExerciseAiClient(ApiTransport { _, _ -> response(estimate()) })
        val result = client.estimate(ApiSettings("https://example.com/v1/chat/completions", "model", "secret"), strength())
        assertNull(result.netKcal); assertNull(result.highKcal)
    }
    @Test fun exerciseAiRejectsBadRangesNumbersAndTruncatedOrMissingExplanations() {
        val client = ExerciseAiClient()
        val bad = listOf(response(estimate(-1)), response(estimate(100, 110, 130)), response(estimate(100, JSONObject.NULL, 130)),
            response(estimate("100")), response(estimate(), "length"), response(estimate().put("note", "")))
        bad.forEach { try { client.decode(it); fail("Bad exercise estimate accepted") } catch (_: IllegalArgumentException) {} }
        assertNull(client.decode(response(estimate(JSONObject.NULL, JSONObject.NULL, JSONObject.NULL))).netKcal)
    }
    @Test fun stepsCsvImportUpdatesOnlyMatchingDaysAndKeepsUnrelatedWeights() = runBlocking {
        val name = "steps-csv-test-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, FitnessDatabase::class.java, name).build()
        try {
            val repo = FitnessRepository(db)
            repo.saveSteps(DailySteps("2026-10-10", 3000, recordedAt = 1))
            repo.saveWeight(WeightEntry("2026-10-10", 70.0, 1))
            val records = CsvImport.parse("date,steps\n2026-10-11,0\n2026-10-12,6000", today = java.time.LocalDate.of(2026, 10, 12))
            repo.importCsv(records); repo.importCsv(records)
            val data = repo.data.first()
            assertEquals(3, data.steps.size); assertEquals(70.0, data.weights.single().kg, 0.001)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
