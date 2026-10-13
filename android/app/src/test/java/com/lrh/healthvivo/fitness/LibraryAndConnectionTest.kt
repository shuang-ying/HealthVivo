package com.lrh.healthvivo.fitness

import com.lrh.healthvivo.ai.ApiSettings
import org.junit.Assert.*
import org.junit.Test

class LibraryAndConnectionTest {
    @Test fun baseUrlsFullUrlsAndProxyPrefixesUseCorrectChatAndModelsPaths() {
        listOf("https://example.com", "https://example.com/", "https://example.com/v1", "https://example.com/v1/", "https://example.com/v1/chat/completions/").forEach { url ->
            val settings = ApiSettings(url, "model", "secret").normalized()
            assertEquals("https://example.com/v1/chat/completions", settings.endpoint)
            assertEquals("https://example.com/v1/models", settings.modelsUrl())
        }
        assertEquals("https://example.com/proxy/api/v3/models", ApiSettings("https://example.com/proxy/api/v3/chat/completions").modelsUrl())
    }
    @Test fun discoveryNeedsConnectionCredentialsButDoesNotRequireAModel() {
        ApiSettings("https://example.com/v1", key = "secret").validateConnection()
        try { ApiSettings("https://example.com/v1", key = "secret").validate(); fail("Missing model accepted for inference") } catch (_: IllegalArgumentException) {}
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com?key=secret", "https://example.com/#secret").forEach { url ->
            try { ApiSettings(url, key = "secret").modelsUrl(); fail("Unsafe URL accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun exerciseLibraryKeepsUniqueSourcesAndDisambiguatesIntensity() {
        assertEquals(74, ExerciseLibrary.items.size)
        assertEquals(74, ExerciseLibrary.items.distinctBy { it.code }.size)
        assertTrue(ExerciseLibrary.items.all { it.reference.startsWith("https://pacompendium.com/") && it.met in 1.0..30.0 })
        assertEquals(4.0, ExerciseLibrary.match("乒乓球")!!.met, .001)
        assertEquals(11.8, ExerciseLibrary.match("跳绳")!!.met, .001)
        assertNotEquals(ExerciseLibrary.match("自由泳")!!.met, ExerciseLibrary.match("自由泳（快速高强度）")!!.met)
        assertTrue(ExerciseLibrary.search("泳", "游泳").all { it.group == "游泳" })
    }
    @Test fun supportedSportsCanCalculateFromTimeButDoNotInventDistanceCosts() {
        val badminton = ExerciseEntry("id", "2026-10-12", "羽毛球", 30.0, null, "pending", 70.0, recordedAt = 1)
        assertEquals(165.375, CalorieCalculator.localExercise(badminton).kcal!!, .001)
        val cycle = badminton.copy(name = "骑行", minutes = null, inputMode = "distance", distanceKm = 10.0)
        assertNull(CalorieCalculator.localExercise(cycle).kcal)
        assertFalse(CalorieCalculator.isStepExercise("骑行")); assertTrue(CalorieCalculator.isStepExercise("徒步"))
    }
}
