package com.lrh.healthvivo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelAndCatalogTest {
    @Test fun modelDiscoveryUsesOnlyGetAuthenticationWithoutHealthDataOrInference() = runBlocking {
        val settings = ApiSettings("https://example.com/gateway/v1", key = "test-secret")
        var calls = 0
        val client = ModelDiscovery(ModelsTransport { supplied, url ->
            calls++; assertEquals(settings, supplied)
            assertEquals("https://example.com/gateway/v1/models", url)
            """{"object":"list","data":[{"id":"vision-model"},{"id":"text-model"},{"id":"vision-model"}]}"""
        })
        assertEquals(listOf("text-model", "vision-model"), client.discover(settings)); assertEquals(1, calls)
    }
    @Test fun modelListRejectsEmptyResultsAndFiltersInvalidIdentifiers() {
        val client = ModelDiscovery()
        assertEquals(listOf("valid"), client.decode("""{"data":[{"id":"valid"},{"id":""},{"id":123},{"id":"bad\nmodel"}]}"""))
        try { client.decode("""{"data":[]}"""); fail("Empty models silently accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun expandedFoodLibraryPreservesPreciseAliasesNutritionSourcesAndCategories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = FoodCatalog(context)
        assertEquals(209, catalog.foods.size)
        assertEquals(209, catalog.foods.distinctBy { it.id }.size)
        assertTrue(catalog.foods.all { it.reference.contains("FDC") && it.kcalPer100g in 0.0..1000.0 })
        assertTrue(catalog.foods.all { food -> listOf(food.protein, food.fat, food.carbs).all { it == null || it in 0.0..100.0 } })
        assertTrue(catalog.categories.size >= 10)
        assertTrue(catalog.search("奶", "乳制品").all { it.category == "乳制品" })
        assertEquals(120.0, catalog.parseLocalText("熟藜麦100g").single().first.kcalPer100g, .001)
        assertEquals(195.0, CalorieCalculator.foodKcal(150.0, catalog.parseLocalText("熟米饭150g").single().first.kcalPer100g), .001)
        assertNotEquals(catalog.parseLocalText("生鸡胸肉100g").single().first.id, catalog.parseLocalText("熟鸡胸肉100g").single().first.id)
    }
    @Test fun assetExerciseFactsMatchCalculatorAndIncludeOriginalTypes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val array = org.json.JSONArray(context.assets.open("exercises.json").bufferedReader().use { it.readText() })
        assertEquals(ExerciseLibrary.items.size, array.length())
        for(index in 0 until array.length()) {
            val item = array.getJSONObject(index); val type = ExerciseLibrary.match(item.getString("name"))!!
            assertEquals(item.getString("code"), type.code); assertEquals(item.getDouble("met"), type.met, .001)
        }
    }
}
