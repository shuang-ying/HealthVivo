package com.lrh.healthvivo.fitness

import java.time.LocalDate

data class EnergyPlan(val restingKcal: Double, val maintenanceKcal: Double,
    val targetKcal: Double, val adjustmentKcal: Double)
data class ExerciseType(val name: String, val met: Double, val code: String, val aliases: List<String> = emptyList(),
    val category: String = "aerobic", val group: String = "其他", val reference: String = "https://pacompendium.com/",
    val distanceKind: String = "", val countsSteps: Boolean = false)
data class ActivityEstimate(val kcal: Double?, val stepsKcal: Double?, val workoutKcal: Double?,
    val overlapHandled: Boolean, val pendingCount: Int)
data class ExerciseEstimate(val kcal: Double?, val method: String, val met: Double? = null, val note: String)

object CalorieCalculator {
    // Resting + recorded net activity is a partial expenditure reference, not a full TDEE or diet target.
    fun plan(profile: Profile, activityKcal: Double = 0.0): EnergyPlan? {
        profile.validate()
        require(activityKcal.isFinite() && activityKcal >= 0)
        if (profile.age !in 19..78 || profile.specialCondition || profile.sex == "unspecified") return null
        val resting = 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * profile.age + if (profile.sex == "male") 5 else -161
        if (resting <= 0) return null
        val recordedReference = resting + activityKcal
        return EnergyPlan(resting, recordedReference, recordedReference, 0.0)
    }
    fun foodKcal(grams: Double, kcalPer100g: Double): Double {
        require(grams.isFinite() && grams > 0 && grams <= 10000)
        require(kcalPer100g.isFinite() && kcalPer100g in 0.0..1000.0)
        return grams * kcalPer100g / 100
    }
    fun netExerciseKcal(met: Double, kg: Double, minutes: Double): Double {
        require(met.isFinite() && met in 1.0..30.0 && kg.isFinite() && kg in 10.0..400.0 && minutes.isFinite() && minutes > 0 && minutes <= 1440)
        // Subtract resting 1 MET before combining with the resting daily reference.
        return (met - 1) * 3.5 * kg / 200 * minutes
    }
    fun distanceKcal(kg: Double, km: Double, running: Boolean): Double {
        require(kg.isFinite() && kg in 10.0..400.0 && km.isFinite() && km > 0 && km <= 500)
        // Level ACSM horizontal oxygen term, 5 kcal/L O2: walk .5, run 1 kcal/kg/km net.
        return kg * km * if (running) 1.0 else 0.5
    }
    fun isStepExercise(name: String) = ExerciseLibrary.match(name)?.countsSteps == true || name.contains("步行") || name.contains("快走") || name.contains("散步") || name.contains("走路") || name.contains("跑步") || name.contains("慢跑") || name.contains("跑走")
    fun localExercise(value: ExerciseEntry): ExerciseEstimate {
        value.validate()
        val kg = value.weightKg ?: return ExerciseEstimate(null, "pending", note = "缺少当时体重，已保留运动记录。")
        val type = ExerciseLibrary.match(value.name)
        val running = type?.distanceKind == "run"
        val walking = type?.distanceKind == "walk"
        if (value.inputMode == "distance" && (running || walking)) {
            return ExerciseEstimate(distanceKcal(kg, value.distanceKm!!, running), "distance", note = "ACSM 平地${if(running) "跑步" else "步行"}距离近似；不含静息。假定平地、匀速，无坡度/负重修正；距离单独输入无法核对速度适用范围。")
        }
        if (value.minutes != null && type != null) return ExerciseEstimate(netExerciseKcal(type.met, kg, value.minutes), "met", type.met,
            "2024 Compendium ${type.code}，${type.name}，${type.met} MET；(MET−1)×3.5×体重÷200×分钟。时长与强度需对应该条目，不套用其他强度；${type.reference}")
        return ExerciseEstimate(null, "pending", note = if(value.inputMode == "sets") "仅凭组数无法确定消耗。可补充总时长并选择一般力量训练，或让 AI 根据次数、负重和休息说明估算。" else "本地未匹配可用公式。可使用 AI 或保存为待估算。")
    }
    fun activities(steps: DailySteps?, workouts: List<ExerciseEntry>, kg: Double?): ActivityEstimate {
        steps?.validate(); workouts.forEach { it.validate() }
        val stepKcal = when {
            steps == null -> null
            steps.steps == 0 -> 0.0
            steps.stepLengthCm == null || kg == null -> null
            else -> distanceKcal(kg, steps.steps * steps.stepLengthCm / 100000, false)
        }
        val eligible = workouts.filter { it.netCalories && it.kcal != null }
        val workoutKcal = eligible.takeIf { it.isNotEmpty() }?.sumOf { it.kcal!! }
        val covered = eligible.filter { it.includedInSteps }
        val separate = eligible.filterNot { it.includedInSteps }.sumOf { it.kcal!! }
        val overlap = stepKcal != null && covered.isNotEmpty()
        // With no exact overlap distance, retain the larger estimate of the same step activity.
        // This avoids adding both; it can undercount walking outside the recorded workout.
        val union = if (stepKcal != null || covered.isNotEmpty()) maxOf(stepKcal ?: 0.0, covered.sumOf { it.kcal!! }) else 0.0
        val known = if (stepKcal == null && eligible.isEmpty()) null else separate + union
        return ActivityEstimate(known, stepKcal, workoutKcal, overlap, workouts.size - eligible.size)
    }
    fun weightAt(data: FitnessData, day: String): Double? = data.weights.filter { it.day <= day }.maxByOrNull { it.day }?.kg
        ?: data.profile?.weightKg?.takeIf { day >= LocalDate.now().toString() }
    val exercises get() = ExerciseLibrary.items
}
