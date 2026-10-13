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
    val watch by lazy { com.lrh.healthvivo.watch.WatchSyncBridge(this, repository, advice) }
    val orbitBridge by lazy { com.lrh.healthvivo.watch.OrbitLocalBridge(this, repository, advice) }
}
