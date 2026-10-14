package com.lrh.healthvivo

import android.app.Application
import com.lrh.healthvivo.fitness.FitnessDatabase
import com.lrh.healthvivo.fitness.FitnessRepository
import com.lrh.healthvivo.fitness.FoodCatalog
import com.lrh.healthvivo.ai.ApiSettingsStore
import com.lrh.healthvivo.ai.MealAiClient
import com.lrh.healthvivo.ai.DailyAdviceCoordinator
import com.lrh.healthvivo.ai.DailyAdviceStore

class HealthApplication : Application() {
    val repository by lazy { FitnessRepository(FitnessDatabase.create(this)) }
    val catalog by lazy { FoodCatalog(this) }
    val apiSettings by lazy { ApiSettingsStore(this) }
    val recognition by lazy { MealAiClient() }
    val advice by lazy { DailyAdviceCoordinator(repository, apiSettings, DailyAdviceStore(this)) }
    /** The persistent BLE link to the watch. One instance for the whole process, like the service. */
    val link by lazy { com.lrh.healthvivo.link.LinkManager(this, repository, advice) }
}
