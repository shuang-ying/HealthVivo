package com.lrh.healthvivo.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.ai.ApiSettings
import com.lrh.healthvivo.fitness.*

@Composable fun MealCard(meal: String, entries: List<MealEntry>, open: () -> Unit) {
    val icon = when(meal) { "breakfast" -> "sun"; "lunch" -> "bowl"; else -> "cup" }
    Surface(onClick = open, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(icon, accent = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(mealLabel(meal), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(if(entries.isEmpty()) "尚未记录" else "${entries.size} 项食物", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(entries.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
                    Text("%.0f".format(entries.sumOf { it.kcal }), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Text("千卡", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppIcon("right", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            if(entries.isEmpty()) Text("点击进入，记录这一餐", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                entries.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.name, fontSize = 14.sp)
                            Text("%.0f g".format(entry.grams), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("%.0f 千卡".format(entry.kcal), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable fun MealWorkspace(day: String, meal: String, entries: List<MealEntry>, catalog: FoodCatalog, customFoods: List<CustomFood>,
    api: ApiSettings, recognizing: Boolean, description: String, changeDescription: (String) -> Unit, photo: Uri?,
    photoPreview: @Composable (Uri) -> Unit, takePhoto: () -> Unit, pickPhoto: () -> Unit, clearPhoto: () -> Unit,
    localMatch: () -> Unit, recognize: () -> Unit, configure: () -> Unit, chooseFood: (Food?) -> Unit,
    edit: (MealEntry) -> Unit, delete: (MealEntry) -> Unit, switchMeal: (String) -> Unit, dismiss: () -> Unit) {
    var mode by remember(meal) { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("全部") }
    fun back() { if(mode != null) mode = null else dismiss() }
    DetailPage(if(mode == null) mealLabel(meal) else "添加${mealLabel(meal)}", day, ::back,
        action = { if(mode == null) AddButton("添加食物") { mode = 0 } },
        footer = if(mode == null) ({ Button(onClick = { mode = 0 }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) { AppIcon("plus"); Spacer(Modifier.width(8.dp)); Text("添加食物") } }) else null) {
        if(mode == null) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本餐已记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if(entries.isEmpty()) "—" else "%.0f".format(entries.sumOf { it.kcal }), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("千卡", modifier = Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if(entries.isEmpty()) EmptyRecords("food", "这一餐还没有记录", "添加食物，慢慢建立你的饮食记录")
            else FormSection("${entries.size} 项食物") {
                entries.forEachIndexed { index, entry ->
                    if(index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(entry.name, fontWeight = FontWeight.Medium)
                            Text("%.0f g · %.0f 千卡".format(entry.grams, entry.kcal), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { edit(entry) }) { AppIcon("edit", "编辑食物", modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { delete(entry) }) { AppIcon("trash", "删除食物", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if(meal != "snack") TextButton(onClick = { switchMeal("snack") }) { Text("查看 / 记录加餐") }
        } else {
            ChoiceRow(listOf("0" to "食物库", "1" to "照片 / 文字"), mode.toString()) { mode = it.toInt() }
            if(mode == 0) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("搜索食物名称") }, leadingIcon = { AppIcon("search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                CategoryChips(listOf("全部") + catalog.categories + "自建", category) { category = it }
                val foods = (if(category == "自建") emptyList() else catalog.search(query, category.takeUnless { it == "全部" })) +
                    customFoods.filter { category in setOf("全部", "自建") && (query.isBlank() || it.name.contains(query.trim())) }
                        .map { Food(it.id, it.name, emptyList(), it.kcalPer100g, null, null, null, "用户自建食物", "自定义每100克营养标签", "自建") }
                Text("${foods.size} 项食物", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionBox(foods, key = { it.id }, resetKey = query to category, emptyText = "没有找到食物，可以手动录入或用照片和文字识别") { food ->
                    LibraryRow(food.name, "%.0f 千卡 / 100g · %s".format(food.kcalPer100g, food.category), foodIcon(food.category)) { chooseFood(food) }
                }
                OutlinedButton(onClick = { chooseFood(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("手动录入 / 新建食物") }
            } else {
                FormSection("这一餐吃了什么") {
                    OutlinedTextField(description, changeDescription, placeholder = { Text("例如：熟米饭150g，香蕉100g") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = takePhoto, enabled = !recognizing, modifier = Modifier.weight(1f)) { AppIcon("camera"); Spacer(Modifier.width(6.dp)); Text("拍照") }
                        OutlinedButton(onClick = pickPhoto, enabled = !recognizing, modifier = Modifier.weight(1f)) { AppIcon("image"); Spacer(Modifier.width(6.dp)); Text("相册") }
                    }
                    photo?.let { photoPreview(it); TextButton(onClick = clearPhoto, enabled = !recognizing) { Text("移除照片") } }
                }
                val configured = runCatching { api.validate() }.isSuccess
                Button(onClick = recognize, enabled = configured && !recognizing && (description.isNotBlank() || photo != null), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    AppIcon("sparkles"); Spacer(Modifier.width(8.dp)); Text(if(recognizing) "识别中…" else "AI 识别这一餐")
                }
                OutlinedButton(onClick = localMatch, enabled = !recognizing && description.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("按克数匹配本地食物库") }
                Text(if(configured) "仅发送本次文字和所选照片到 ${Uri.parse(api.endpoint).host}，核对结果后保存。" else "使用 AI 前请配置接口。也可以离线匹配食物库。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = configure) { Text(if(configured) "修改 AI 配置" else "配置 AI") }
            }
        }
    }
}
