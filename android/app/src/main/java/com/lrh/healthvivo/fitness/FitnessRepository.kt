package com.lrh.healthvivo.fitness

import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class FitnessRepository(private val database: FitnessDatabase) {
    val dao = database.dao()
    private val records = combine(dao.profile(), dao.weights(), dao.meals(), dao.exercises(), dao.foods()) { p, w, m, e, f -> FitnessData(p, w, m, e, f) }
    val data = combine(records, dao.steps(), dao.bodyFats()) { data, steps, bodyFats -> data.copy(steps = steps, bodyFats = bodyFats) }
    suspend fun snapshot(): FitnessData = database.withTransaction {
        FitnessData(profile = dao.profile().first(), weights = dao.weights().first(), meals = dao.meals().first(),
            exercises = dao.exercises().first(), steps = dao.steps().first(), bodyFats = dao.bodyFats().first())
    }
    suspend fun saveProfile(value: Profile, bodyFatPercent: Double? = null) {
        value.validate()
        val day = LocalDate.now().toString()
        val bodyFat = bodyFatPercent?.let { BodyFatEntry(day, it, value.updatedAt).also { entry -> entry.validate() } }
        database.withTransaction {
            dao.profile(value)
            // Only a changed figure is a new measurement. Each weight record starts a new step in the
            // carried-forward weight, so saving the same value again - or editing the target weight -
            // must not write one; the step would change nothing but would still show up in the history.
            if(CalorieCalculator.weightOn(dao.weights().first().sortedBy { it.day }, day) != value.weightKg)
                dao.weight(WeightEntry(day, value.weightKg, value.updatedAt))
            // Editing unrelated profile fields must not manufacture a new measurement.
            if(bodyFat != null && dao.bodyFats().first().firstOrNull { it.day <= bodyFat.day }?.percent != bodyFat.percent) dao.bodyFat(bodyFat)
        }
    }
    suspend fun saveBodyFat(value: BodyFatEntry) { value.validate(); dao.bodyFat(value) }
    suspend fun saveWeight(value: WeightEntry) { value.validate(); dao.weight(value) }
    suspend fun saveTargetWeight(target: Double?) {
        database.withTransaction {
            val profile = dao.profile().first() ?: error("请先填写个人资料")
            dao.profile(profile.copy(targetWeightKg = target).also { it.validate() })
        }
    }
    suspend fun saveSteps(value: DailySteps) { value.validate(); dao.steps(value) }
    suspend fun saveMeals(values: List<MealEntry>) {
        values.forEach { it.validate() }
        require(values.distinctBy { it.id }.size == values.size)
        database.withTransaction { values.forEach { dao.meal(it) } }
    }
    suspend fun saveExercise(value: ExerciseEntry) { value.validate(); dao.exercise(value) }
    suspend fun saveFood(value: CustomFood) { value.validate(); dao.food(value) }
    suspend fun saveMealAndFood(value: MealEntry, food: CustomFood?) {
        value.validate(); food?.validate()
        database.withTransaction { dao.meal(value); food?.let { dao.food(it) } }
    }
    suspend fun importCsv(records: CsvRecords) {
        records.weights.forEach { it.validate() }; records.exercises.forEach { it.validate() }; records.steps.forEach { it.validate() }
        database.withTransaction { records.weights.forEach { dao.weight(it) }; records.exercises.forEach { dao.exercise(it) }; records.steps.forEach { dao.steps(it) } }
    }
}
