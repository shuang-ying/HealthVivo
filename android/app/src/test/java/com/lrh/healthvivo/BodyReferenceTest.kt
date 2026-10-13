package com.lrh.healthvivo

import com.lrh.healthvivo.fitness.*
import org.junit.Assert.*
import org.junit.Test

class BodyReferenceTest {
    private fun profile(age: Int = 30, sex: String = "male", special: Boolean = false) =
        Profile(age = age, heightCm = 175.0, weightKg = 70.0, sex = sex, goal = "lose", updatedAt = 1, specialCondition = special)

    @Test fun normalBmiHasNoWeightChangePrompt() {
        assertNull(BodyMetrics.bmiWeightAdjustment(70.0, profile()))
        assertNull(BodyMetrics.bmiWeightAdjustment(18.5 * 1.75 * 1.75, profile()))
        assertNull(BodyMetrics.bmiWeightAdjustment(23.9 * 1.75 * 1.75, profile()))
    }
    @Test fun changeIsRoundedUpAndSufficientToReachDisplayedBoundary() {
        val gain = BodyMetrics.bmiWeightAdjustment(50.0, profile())!!
        assertEquals("增重", gain.direction); assertEquals(6.7, gain.kilograms, 0.00001)
        assertTrue(BodyMetrics.bmi(50.0 + gain.kilograms, 175.0)!! >= 18.5)
        val loss = BodyMetrics.bmiWeightAdjustment(80.0, profile())!!
        assertEquals("减重", loss.direction); assertEquals(6.9, loss.kilograms, 0.00001)
        assertTrue(BodyMetrics.bmi(80.0 - loss.kilograms, 175.0)!! < 24.0)
    }
    @Test fun bodyFatAxisUsesTheSameAgeAndSexRangeAsClassification() {
        for(sex in listOf("male", "female")) for(age in listOf(20, 39, 40, 59, 60, 79)) {
            val p = profile(age, sex); val range = BodyMetrics.fatReference(p)!!; val scale = BodyMetrics.fatScale(p)!!
            assertEquals(listOf(range.low, range.high), scale.breaks)
            assertTrue(BodyMetrics.fatStatus(range.low, p).normal!!)
            assertFalse(BodyMetrics.fatStatus(range.high, p).normal!!)
        }
    }
    @Test fun unsuitableOrMissingProfilesDoNotProduceAdultAxesOrWeightTargets() {
        assertNull(BodyMetrics.bmiScale(profile(17))); assertNull(BodyMetrics.bmiWeightAdjustment(80.0, profile(17)))
        assertNull(BodyMetrics.bmiWeightAdjustment(80.0, profile(special = true)))
        assertNull(BodyMetrics.fatScale(profile(sex = "unspecified"))); assertNull(BodyMetrics.fatScale(profile(80)))
        assertNull(BodyMetrics.fatScale(profile(special = true))); assertNull(BodyMetrics.bmiWeightAdjustment(null, profile()))
    }
}
