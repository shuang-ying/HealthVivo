package com.lrh.healthvivo.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The step count the AI may return alongside the calorie estimate, and the rules around it. */
class ExerciseAiClientTest {
    private val client = ExerciseAiClient()
    private fun response(content: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString()
    private fun estimate(steps: String) = client.decode(response(
        """{"netKcal":100,"lowKcal":70,"highKcal":130,"steps":$steps,"note":"按时长与配速假设"}"""))

    @Test fun stepCountIsCarriedAlongsideTheCalorieEstimate() {
        assertEquals(3000, estimate("3000").steps)
        assertEquals(100.0, estimate("3000").netKcal!!, 0.001)
    }
    @Test fun aMissingOrNullOrZeroStepCountMeansNoStepDeduction() {
        assertNull(client.decode(response("""{"netKcal":100,"lowKcal":70,"highKcal":130,"note":"骑行不产生步数"}""")).steps)
        assertNull(estimate("null").steps)
        assertNull(estimate("0").steps)
    }
    @Test fun anImplausibleStepCountIsRejectedRatherThanStored() {
        try { estimate("200001"); fail("Excessive step estimate accepted") } catch (_: IllegalArgumentException) {}
        try { estimate("-100"); fail("Negative step estimate accepted") } catch (_: IllegalArgumentException) {}
        try { estimate("\"3000\""); fail("Non-numeric step estimate accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun anEstimateWithoutAStepCountStillValidatesItsCalorieRange() {
        assertNull(client.decode(response("""{"netKcal":100,"lowKcal":70,"highKcal":130,"note":"骑行"}""")).steps)
        try { client.decode(response("""{"netKcal":100,"lowKcal":70,"note":"缺少上限"}""")); fail("Incomplete range accepted") } catch (_: IllegalArgumentException) {}
    }
}
