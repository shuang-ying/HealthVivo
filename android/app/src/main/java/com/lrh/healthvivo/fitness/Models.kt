package com.lrh.healthvivo.fitness

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "profile")
data class Profile(@PrimaryKey val id: Int = 1, val age: Int, val heightCm: Double,
    val weightKg: Double, val sex: String,
    val goal: String = "maintain", val specialCondition: Boolean = false, val updatedAt: Long, val targetWeightKg: Double? = null) {
    fun validate() {
        require(age in 1..120) { "年龄需在 1–120 岁" }
        require(heightCm.isFinite() && heightCm in 50.0..250.0) { "身高需在 50–250 cm" }
        require(weightKg.isFinite() && weightKg in 10.0..400.0) { "体重需在 10–400 kg" }
        require(sex in setOf("male", "female", "unspecified"))
        require(goal in setOf("maintain", "lose", "gain"))
        require(targetWeightKg == null || targetWeightKg.isFinite() && targetWeightKg in 10.0..400.0) { "目标体重需在 10–400 kg" }
    }
}
@Entity(tableName = "weights")
data class WeightEntry(@PrimaryKey val day: String, val kg: Double, val recordedAt: Long) {
    fun validate() { LocalDate.parse(day); require(kg.isFinite() && kg in 10.0..400.0) { "体重需在 10–400 kg" } }
}
@Entity(tableName = "meals")
data class MealEntry(@PrimaryKey val id: String, val day: String, val meal: String,
    val name: String, val grams: Double, val kcalPer100g: Double,
    val proteinPer100g: Double? = null, val fatPer100g: Double? = null, val carbsPer100g: Double? = null,
    val source: String, val sourceReference: String = "", val recordedAt: Long) {
    val kcal get() = grams * kcalPer100g / 100
    fun validate() {
        LocalDate.parse(day); require(id.isNotBlank() && name.isNotBlank())
        require(meal in setOf("breakfast", "lunch", "dinner", "snack"))
        require(grams.isFinite() && grams > 0 && grams <= 10000) { "食用重量需大于 0，最多 10000 g" }
        require(kcalPer100g.isFinite() && kcalPer100g in 0.0..1000.0) { "每 100 g 热量需在 0–1000 千卡" }
        listOf(proteinPer100g, fatPer100g, carbsPer100g).forEach { require(it == null || it.isFinite() && it in 0.0..100.0) }
        require(source in setOf("database", "manual", "ai"))
    }
}
@Entity(tableName = "exercises")
data class ExerciseEntry(@PrimaryKey val id: String, val day: String, val name: String,
    val minutes: Double?, val kcal: Double?, val method: String,
    val weightKg: Double? = null, val met: Double? = null, val recordedAt: Long,
    val category: String = "unspecified", val inputMode: String = "time",
    val distanceKm: Double? = null, val sets: Int? = null, val reps: Int? = null,
    val loadKg: Double? = null, val includedInSteps: Boolean = false,
    val netCalories: Boolean = false, val estimateNote: String = "", val details: String = "",
    /** Steps this workout contributed, when it is counted inside the daily total. */
    val steps: Int? = null) {
    /**
     * The daily-step total already contains this workout only when the user both ticked the box and
     * supplied a step count (their own or an AI estimate). A tick without a count is treated as not
     * ticked: the local formulas cannot produce a step count, so there would be nothing to subtract.
     */
    val countedInSteps get() = includedInSteps && steps != null
    fun validate() {
        LocalDate.parse(day); require(id.isNotBlank() && name.isNotBlank())
        require(name.length <= 120)
        require(minutes == null || minutes.isFinite() && minutes > 0 && minutes <= 1440) { "运动时长需大于 0，最多 1440 分钟" }
        require(kcal == null || kcal.isFinite() && kcal in 0.0..10000.0) { "运动热量需在 0–10000 千卡" }
        require(method in setOf("manual", "met", "distance", "ai", "pending"))
        require(category in setOf("aerobic", "anaerobic", "unspecified"))
        require(inputMode in setOf("time", "distance", "sets"))
        require(distanceKm == null || distanceKm.isFinite() && distanceKm > 0 && distanceKm <= 500) { "距离需大于 0，最多 500 km" }
        require(sets == null || sets in 1..1000) { "组数需在 1–1000 之间" }
        require(reps == null || reps in 1..1000) { "每组次数需在 1–1000 之间" }
        require(loadKg == null || loadKg.isFinite() && loadKg in 0.0..1000.0) { "负重需在 0–1000 kg" }
        require(weightKg == null || weightKg.isFinite() && weightKg in 10.0..400.0) { "体重需在 10–400 kg" }
        require(met == null || met.isFinite() && met in 1.0..30.0)
        require(estimateNote.length <= 2000)
        require(details.length <= 1000) { "运动说明最多 1000 字" }
        require(steps == null || steps in 1..200000) { "本次运动步数需在 1–200000 之间" }
        // Time, distance and sets are three independent optional fields, so only require that the
        // record says something about the effort.
        require(minutes != null || distanceKm != null || sets != null) { "请至少填写时长、距离或组数中的一项" }
        if (method == "pending") require(kcal == null)
        else require(kcal != null) { "请先计算或保存为待估算" }
        if (method == "met") require(weightKg != null && met != null && minutes != null)
        if (method == "distance") require(weightKg != null && distanceKm != null && netCalories)
        if (method == "ai") require(weightKg != null && netCalories && estimateNote.isNotBlank())
    }
}
@Entity(tableName = "daily_steps")
data class DailySteps(@PrimaryKey val day: String, val steps: Int, val stepLengthCm: Double? = null, val recordedAt: Long) {
    fun validate() {
        LocalDate.parse(day)
        require(steps in 0..200000) { "每日步数需在 0–200000 之间" }
        require(stepLengthCm == null || stepLengthCm.isFinite() && stepLengthCm in 10.0..200.0) { "步长需在 10–200 cm" }
    }
}
@Entity(tableName = "custom_foods")
data class CustomFood(@PrimaryKey val id: String, val name: String, val kcalPer100g: Double) {
    fun validate() { require(id.isNotBlank() && name.isNotBlank()); require(kcalPer100g.isFinite() && kcalPer100g in 0.0..1000.0) }
}
data class Food(val id: String, val name: String, val aliases: List<String>, val kcalPer100g: Double,
    val protein: Double?, val fat: Double?, val carbs: Double?, val reference: String, val description: String, val category: String = "其他")
data class FitnessData(val profile: Profile? = null, val weights: List<WeightEntry> = emptyList(),
    val meals: List<MealEntry> = emptyList(), val exercises: List<ExerciseEntry> = emptyList(), val customFoods: List<CustomFood> = emptyList(),
    val steps: List<DailySteps> = emptyList(), val bodyFats: List<BodyFatEntry> = emptyList())

@Entity(tableName = "body_fats")
data class BodyFatEntry(@PrimaryKey val day: String, val percent: Double, val recordedAt: Long) {
    fun validate() {
        LocalDate.parse(day)
        require(percent.isFinite() && percent > 0 && percent < 100) { "体脂率需大于 0、小于 100%，请填写测量值" }
    }
}
fun mealLabel(value: String) = when(value) { "breakfast" -> "早餐"; "lunch" -> "午餐"; "dinner" -> "晚餐"; else -> "加餐" }
