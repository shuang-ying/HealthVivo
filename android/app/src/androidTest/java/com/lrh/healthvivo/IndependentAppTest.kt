package com.lrh.healthvivo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IndependentAppTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun checkDatabase(check: suspend (FitnessRepository) -> Unit) = runBlocking {
        val name = "fitness-test-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, FitnessDatabase::class.java, name).build()
        try { check(FitnessRepository(db)) } finally { db.close(); context.deleteDatabase(name) }
    }
    private fun meal(id: String = "test", density: Double = 130.0) = MealEntry(id, "2026-10-12", "lunch", "熟米饭", 150.0, density, source = "manual", recordedAt = 1000)
    @Test fun profileWeightMealsAndExerciseSurviveReopening() = runBlocking {
        val name = "fitness-test-${UUID.randomUUID()}.db"
        try {
            val first = Room.databaseBuilder(context, FitnessDatabase::class.java, name).build()
            val repo = FitnessRepository(first)
            repo.saveProfile(Profile(age = 30, heightCm = 175.0, weightKg = 70.0, sex = "male", updatedAt = 1000))
            repo.saveMeals(listOf(meal()))
            repo.saveExercise(ExerciseEntry("run", "2026-10-12", "跑步", 30.0, 200.0, "manual", recordedAt = 1000))
            first.close()
            val second = Room.databaseBuilder(context, FitnessDatabase::class.java, name).build()
            try {
                val data = FitnessRepository(second).data.first()
                assertEquals(30, data.profile!!.age); assertEquals(70.0, data.weights.single().kg, 0.001)
                assertEquals(195.0, data.meals.single().kcal, 0.001); assertEquals(200.0, data.exercises.single().kcal!!, 0.001)
            } finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun updatingMealAndSameDayWeightDoNotDuplicateEntries() = checkDatabase { repo ->
        repo.saveMeals(listOf(meal())); repo.saveMeals(listOf(meal(density = 140.0)))
        repo.saveWeight(WeightEntry("2026-10-12", 70.0, 1)); repo.saveWeight(WeightEntry("2026-10-12", 69.5, 2))
        val data = repo.data.first(); assertEquals(1, data.meals.size); assertEquals(210.0, data.meals.single().kcal, 0.001); assertEquals(1, data.weights.size)
        repo.dao.deleteMeal("test"); assertTrue(repo.data.first().meals.isEmpty())
    }
    @Test fun invalidRecognitionBatchCannotPartiallySave() = checkDatabase { repo ->
        try { repo.saveMeals(listOf(meal(), meal("bad", Double.NaN))); fail("Invalid batch accepted") } catch (_: IllegalArgumentException) {}
        assertTrue(repo.data.first().meals.isEmpty())
        repo.saveMealAndFood(meal(), CustomFood("f", "自定义米饭", 130.0))
        assertEquals(1, repo.data.first().customFoods.size)
    }
    @Test fun offlineFoodCatalogRetainsSourceAndExactGramMatching() {
        val catalog = FoodCatalog(context)
        assertEquals(209, catalog.foods.size)
        assertTrue(catalog.foods.all { it.reference.contains("FDC") && it.kcalPer100g.isFinite() })
        val matches = catalog.parseLocalText("熟米饭150g，香蕉0.1kg")
        assertEquals(195.0, CalorieCalculator.foodKcal(matches[0].second, matches[0].first.kcalPer100g), 0.001)
        assertEquals(100.0, matches[1].second, 0.001)
        try { catalog.parseLocalText("一碗米饭"); fail("Unspecified grams silently accepted") } catch (_: IllegalArgumentException) {}
        try { catalog.parseLocalText("炸鸡100g"); fail("Unknown food silently matched") } catch (_: IllegalArgumentException) {}
    }
    @Test fun apiRequestSendsOnlyCurrentInputAndDoesNotPersistUnconfirmedResults() = checkDatabase { repo ->
        var requests = 0
        val client = MealAiClient(ApiTransport { settings, body ->
            requests++
            assertEquals("vision-model", settings.model)
            val root = JSONObject(body)
            assertFalse(root.has("profile")); assertFalse(root.has("apiKey")); assertFalse(root.has("weightKg"))
            val content = root.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals("image_url", content.getJSONObject(1).getString("type"))
            """{"choices":[{"finish_reason":"stop","message":{"content":"{\"items\":[{\"name\":\"米饭\",\"grams\":null,\"kcalPer100g\":130,\"note\":\"份量未知\"}]}"}}]}"""
        })
        val items = client.recognize(ApiSettings("https://example.com/v1/chat/completions", "vision-model", "test-secret"), "一碗米饭", "data:image/jpeg;base64,dGVzdA==")
        assertEquals(1, requests); assertNull(items.single().grams); assertTrue(repo.data.first().meals.isEmpty())
    }
    @Test fun invalidModelNumbersAndTruncationAreRejected() {
        val client = MealAiClient()
        val bad = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"items\":[{\"name\":\"米饭\",\"grams\":-1,\"kcalPer100g\":130}]}"}}]}"""
        try { client.decode(bad); fail("Negative grams accepted") } catch (_: IllegalArgumentException) {}
        try { client.decode(bad.replace("\"stop\"", "\"length\"")); fail("Truncated result accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun secretIsEncryptedAtRestAndCanBeCleared() {
        val name = "api-test-${UUID.randomUUID()}"; val alias = name
        val store = ApiSettingsStore(context, name, alias)
        try {
            store.save(ApiSettings("https://example.com/v1/chat/completions", "test-model", "verification-secret"))
            assertEquals("verification-secret", store.load().key)
            assertFalse(context.getSharedPreferences(name, 0).all.values.any { it.toString().contains("verification-secret") })
            store.clear(); assertTrue(store.load().key.isBlank())
        } finally {
            context.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    @Test fun appDoesNotRequestSelfTrackingOrVivoSdkPermissions() {
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
        assertTrue("android.permission.INTERNET" in info.requestedPermissions.orEmpty())
        assertTrue(info.requestedPermissions.orEmpty().none { it.contains("LOCATION") || it.contains("ACTIVITY_RECOGNITION") || it.contains("com.vivo") || it == "android.permission.CAMERA" })
        assertTrue(info.services.orEmpty().none { it.name.contains("Tracking") || it.name.contains("Rpc") })
    }
    @Test fun repeatedCsvImportsDoNotDuplicateExercises() = checkDatabase { repo ->
        val values = CsvImport.parse("date,name,minutes,kcal\n2026-10-11,步行,30,100")
        repo.importCsv(values); repo.importCsv(values)
        assertEquals(1, repo.data.first().exercises.size)
    }
    @Test fun selectedImageIsResizedAndReencodedBeforeApiUpload() = runBlocking {
        val file = java.io.File(context.cacheDir, "test-photo-${UUID.randomUUID()}.png")
        val bitmap = android.graphics.Bitmap.createBitmap(2000, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val data = MealImage.dataUrl(context, android.net.Uri.fromFile(file))
            assertTrue(data.startsWith("data:image/jpeg;base64,"))
            val bytes = android.util.Base64.decode(data.substringAfter(','), android.util.Base64.DEFAULT)
            val dimensions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
            assertEquals(1600, dimensions.outWidth); assertEquals(800, dimensions.outHeight)
        } finally { bitmap.recycle(); file.delete() }
    }
}
