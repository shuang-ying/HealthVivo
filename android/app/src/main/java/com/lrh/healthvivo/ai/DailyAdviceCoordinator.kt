package com.lrh.healthvivo.ai

import android.net.Uri
import com.lrh.healthvivo.fitness.FitnessRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AdviceState(val saved: SavedAdvice? = null, val busy: Boolean = false, val status: String = "", val error: String? = null)

// Owned by the application: switching pages does not cancel a saved record's advice request.
class DailyAdviceCoordinator(private val repository: FitnessRepository, private val settings: ApiSettingsStore,
    private val store: DailyAdviceStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val pending = linkedSetOf<String>().apply { addAll(store.pendingDays()) }
    private val forced = mutableSetOf<String>()
    private val revisions = pending.associateWith { 0L }.toMutableMap()
    private var activeDay: String? = null
    private val mutableStates = MutableStateFlow<Map<String, AdviceState>>(emptyMap())
    val states = mutableStates.asStateFlow()
    fun load(day: String) = store.load(day)
    private fun persistQueue() { store.savePendingDays(pending + listOfNotNull(activeDay)) }
    private fun update(day: String, value: AdviceState) { mutableStates.value = mutableStates.value + (day to value) }
    fun request(days: Set<String>, force: Boolean = false) {
        days.forEach { day ->
            revisions[day] = (revisions[day] ?: 0L) + 1L
            pending.add(day)
            if(force) forced.add(day)
            update(day, AdviceState(mutableStates.value[day]?.saved ?: load(day), busy = true, status = "准备更新建议…"))
        }
        persistQueue(); signals.trySend(Unit)
    }
    fun configurationChanged() {
        pending.clear(); forced.clear(); activeDay = null; persistQueue()
        revisions.keys.toList().forEach { revisions[it] = revisions.getValue(it) + 1L }
        mutableStates.value = mutableStates.value.mapValues { (_, value) -> value.copy(busy = false, status = "", error = null) }
    }
    init {
        scope.launch {
            for(signal in signals) while(pending.isNotEmpty()) {
                delay(1500) // Merge rapid saves before reading a consistent database snapshot.
                if(pending.isEmpty()) continue
                val day = pending.first(); pending.remove(day)
                activeDay = day
                val force = forced.remove(day)
                val revision = revisions.getValue(day)
                try {
                    val api = withContext(Dispatchers.IO) { settings.load() }
                    if(runCatching { api.validate() }.isFailure) {
                        update(day, AdviceState(mutableStates.value[day]?.saved, status = "配置 API 后，保存记录会自动更新建议。"))
                        continue
                    }
                    val snapshot = AdviceSummary.create(repository.snapshot(), day)
                    if(revisions[day] != revision) continue
                    val old = mutableStates.value[day]?.saved ?: load(day)
                    val provider = "${Uri.parse(api.endpoint).host} / ${api.model}"
                    if(!force && old?.fingerprint == snapshot.fingerprint && old.provider == provider) {
                        update(day, AdviceState(old)); continue
                    }
                    update(day, AdviceState(old, busy = true, status = "正在更新 AI 建议…"))
                    val advice = DailyAdviceClient().generate(api, snapshot)
                    // An older response must not overwrite advice for a newer save/configuration.
                    if(revisions[day] != revision) continue
                    val result = SavedAdvice(snapshot.fingerprint, System.currentTimeMillis(), provider, advice)
                    withContext(Dispatchers.IO) { store.save(day, result) }
                    if(revisions[day] == revision) update(day, AdviceState(result))
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) {
                    if(revisions[day] == revision) update(day, AdviceState(mutableStates.value[day]?.saved, error = when(failure) {
                        is IllegalArgumentException -> failure.message ?: "请检查 API 配置"
                        is java.io.IOException -> "建议请求连接失败或超时，可手动重试。记录已保存在本机。"
                        else -> "建议响应不兼容或保存失败，可手动重试。记录已保存在本机。"
                    }))
                } finally { activeDay = null; persistQueue() }
            }
        }
        if(pending.isNotEmpty()) signals.trySend(Unit)
    }
}
