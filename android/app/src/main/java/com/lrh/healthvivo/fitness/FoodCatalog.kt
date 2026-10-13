package com.lrh.healthvivo.fitness

import android.content.Context
import org.json.JSONArray

class FoodCatalog(context: Context) {
    val foods: List<Food> = context.assets.open("foods.json").bufferedReader().use { reader ->
        val array = JSONArray(reader.readText())
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val aliases = item.getJSONArray("aliases")
            fun number(key: String): Double? = if (item.isNull(key)) null else item.getDouble(key)
            Food(item.getString("id"), item.getString("name"), (0 until aliases.length()).map { aliases.getString(it) },
                item.getDouble("kcalPer100g"), number("protein"), number("fat"), number("carbs"),
                item.getString("reference"), item.getString("description"), item.optString("category", "其他"))
        }
    }
    val categories = listOf("主食", "肉蛋鱼", "乳制品", "豆类", "蔬菜", "水果", "坚果", "油脂调味", "饮品", "零食", "其他").filter { group -> foods.any { it.category == group } }
    fun search(query: String, category: String? = null): List<Food> = foods.filter { food ->
        (category == null || food.category == category) && (query.isBlank() || (food.aliases + food.name).any { it.contains(query.trim(), ignoreCase = true) }) }
    fun parseLocalText(text: String): List<Pair<Food, Double>> {
        return text.split(Regex("[，,；;\\n]+" )).map { part ->
            val match = Regex("^\\s*(.+?)\\s*(\\d+(?:\\.\\d+)?)\\s*(克|g|千克|kg)\\s*$", RegexOption.IGNORE_CASE).matchEntire(part)
                ?: throw IllegalArgumentException("请填写食物名与克数，例如：熟米饭150g，香蕉100g")
            val name = match.groupValues[1].trim()
            val matches = foods.filter { it.name == name || name in it.aliases }
            require(matches.size == 1) { "食物“$name”未精确匹配，请从食物库选择或使用 AI 识别。" }
            val grams = match.groupValues[2].toDouble() * if (match.groupValues[3].lowercase() in setOf("kg", "千克")) 1000 else 1
            CalorieCalculator.foodKcal(grams, matches.single().kcalPer100g)
            matches.single() to grams
        }
    }
}
