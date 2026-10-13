package com.lrh.healthvivo.fitness

import java.security.MessageDigest
import java.time.LocalDate

data class CsvRecords(val weights: List<WeightEntry> = emptyList(), val exercises: List<ExerciseEntry> = emptyList(), val steps: List<DailySteps> = emptyList())
object CsvImport {
    fun parse(text: String, now: Long = System.currentTimeMillis(), today: LocalDate = LocalDate.now()): CsvRecords {
        require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "CSV 文件最多 1 MB" }
        val lines = text.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        require(lines.size in 2..1001) { "CSV 应包含表头及 1–1000 条记录" }
        val header = cells(lines.first()).map { it.trim().lowercase() }
        val weightFile = header == listOf("date", "weight_kg")
        val stepsFile = header == listOf("date", "steps") || header == listOf("date", "steps", "step_length_cm")
        require(weightFile || stepsFile || header == listOf("date", "name", "minutes", "kcal")) { "体重：date,weight_kg；步数：date,steps（可加 step_length_cm）；运动：date,name,minutes,kcal" }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val weights = mutableListOf<WeightEntry>(); val exercises = mutableListOf<ExerciseEntry>(); val steps = mutableListOf<DailySteps>()
        lines.drop(1).forEachIndexed { index, line ->
            try {
                val values = cells(line).map { it.trim() }
                require(values.size == header.size) { "列数不匹配" }
                val day = LocalDate.parse(values[0]); require(day <= today) { "不能导入未来日期" }
                if (weightFile) weights += WeightEntry(day.toString(), values[1].toDouble(), now).also { it.validate() }
                else if(stepsFile) steps += DailySteps(day.toString(), values[1].toInt(), values.getOrNull(2)?.takeIf { it.isNotBlank() }?.toDouble(), now).also { it.validate() }
                else exercises += ExerciseEntry("csv-$hash-$index", day.toString(), values[1], values[2].toDouble(), values[3].toDouble(), "manual", recordedAt = now).also { it.validate() }
            } catch (_: Exception) { throw IllegalArgumentException("CSV 第 ${index + 2} 行格式或数值无效，请检查日期、单位与范围") }
        }
        require(weights.distinctBy { it.day }.size == weights.size) { "体重 CSV 中同一天只能出现一条记录" }
        require(steps.distinctBy { it.day }.size == steps.size) { "步数 CSV 中同一天只能出现一条记录" }
        return CsvRecords(weights, exercises, steps)
    }
    private fun cells(line: String): List<String> {
        val result = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' -> if (quoted && index + 1 < line.length && line[index + 1] == '"') { cell.append('"'); index++ } else quoted = !quoted
                char == ',' && !quoted -> { result += cell.toString(); cell.clear() }
                else -> cell.append(char)
            }
            index++
        }
        require(!quoted) { "未闭合的 CSV 引号" }; result += cell.toString(); return result
    }
}
