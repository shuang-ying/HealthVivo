package com.lrh.healthvivo.watch

import android.content.SharedPreferences
import com.lrh.healthvivo.ai.DailyAdviceCoordinator
import com.lrh.healthvivo.fitness.FitnessRepository
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/** Stable per-day revisions allow the watch to keep unchanged historical days. */
class WatchDeltaSource(private val repository: FitnessRepository, private val advice: DailyAdviceCoordinator,
    private val preferences: SharedPreferences) {
    suspend fun changed(known: JSONObject): List<JSONObject> {
        require(known.length() <= 5000) { "日期索引过大" }
        val inventory = known.keys().asSequence().associateWith { day ->
            LocalDate.parse(day); known.getLong(day).also { require(it > 0 && it <= 9007199254740991L) }
        }
        val data = repository.snapshot()
        val days = (listOf(LocalDate.now().toString()) + data.meals.map { it.day } + data.exercises.map { it.day } +
            data.steps.map { it.day } + data.weights.map { it.day } + data.bodyFats.map { it.day } + inventory.keys).distinct().sorted()
        require(days.size <= 5000) { "日期索引过大" }
        var sequence = preferences.getLong("revision", 0)
        val changes = mutableListOf<JSONObject>()
        val edit = preferences.edit()
        for(day in days) {
            val snapshot = WatchSnapshot.create(data, day, 1, advice.load(day))
            val hash = MessageDigest.getInstance("SHA-256").digest(WatchSnapshot.revisionMaterial(snapshot).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val same = preferences.getString("hash.$day", null) == hash
            val old = preferences.getLong("version.$day", 0)
            val revision = if(same && old > 0) old else maxOf(System.currentTimeMillis(), sequence + 1)
            sequence = maxOf(sequence, revision)
            val generatedAt = if(same && old > 0) preferences.getLong("time.$day", revision) else System.currentTimeMillis()
            edit.putString("hash.$day", hash).putLong("version.$day", revision).putLong("time.$day", generatedAt)
            snapshot.put("revision", revision).put("generatedAt", generatedAt)
            // A watch cannot claim a newer revision than the source's current version.
            require((inventory[day] ?: 0) <= revision) { "日期版本不一致，请重新连接" }
            if(inventory[day] != revision) changes.add(snapshot)
        }
        check(edit.putLong("revision", sequence).commit()) { "日期版本保存失败" }
        return changes
    }
}
