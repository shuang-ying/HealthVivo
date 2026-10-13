package com.lrh.healthvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import java.util.UUID

@Composable fun EntryDialog(title: String, dismiss: () -> Unit, save: () -> Unit, saving: Boolean = false,
    error: String? = null, saveLabel: String = "保存", content: @Composable ColumnScope.() -> Unit) {
    DetailPage(title.substringBefore(" · "), title.substringAfter(" · ", ""), dismiss, canDismiss = !saving, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
            Text(it, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
        } }
        Button(onClick = save, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
            Text(if(saving) "处理中…" else saveLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        }
    }) {
        content()
    }
}
@Composable fun NumberField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ChoiceRow(options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (id, label) -> FilterChip(selected == id, { select(id) }, label = { Text(label, fontSize = 13.sp) }, shape = MaterialTheme.shapes.small,
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == id, borderColor = androidx.compose.ui.graphics.Color.Transparent, selectedBorderColor = androidx.compose.ui.graphics.Color.Transparent),
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary)) }
    }
}

@Composable fun ProfileDialog(initial: Profile?, currentWeight: Double?, currentBodyFat: BodyFatEntry?, dismiss: () -> Unit, saving: Boolean,
    save: (Profile, Double?) -> Unit) {
    var age by remember { mutableStateOf(initial?.age?.toString() ?: "") }
    var height by remember { mutableStateOf(initial?.heightCm?.toString() ?: "") }
    var weight by remember { mutableStateOf((currentWeight ?: initial?.weightKg)?.toString() ?: "") }
    var sex by remember { mutableStateOf(initial?.sex ?: "unspecified") }
    var goal by remember { mutableStateOf(initial?.goal ?: "maintain") }
    var target by remember { mutableStateOf(initial?.targetWeightKg?.toString() ?: "") }
    var bodyFat by remember { mutableStateOf(currentBodyFat?.percent?.toString() ?: "") }
    var special by remember { mutableStateOf(initial?.specialCondition ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog("个人资料", dismiss, save = {
        runCatching {
            val value = Profile(age = age.toIntOrNull() ?: error("请填写整数年龄"), heightCm = height.toDoubleOrNull() ?: error("请填写身高"),
                weightKg = weight.toDoubleOrNull() ?: error("请填写体重"), sex = sex,
                goal = goal, specialCondition = special, updatedAt = System.currentTimeMillis(),
                targetWeightKg = if(target.isBlank()) null else target.toDoubleOrNull() ?: error("请填写有效的目标体重")).also { it.validate() }
            val fat = if(bodyFat.isBlank()) null else (bodyFat.toDoubleOrNull() ?: error("请填写有效的体脂率")).also {
                BodyFatEntry(java.time.LocalDate.now().toString(), it, System.currentTimeMillis()).validate()
            }
            value to fat
        }.onSuccess { (value, fat) -> save(value, fat) }.onFailure { error = it.message }
    }, saving = saving, error = error) {
        FormSection("基本信息") {
        NumberField("年龄（岁）", age) { age = it }; NumberField("身高（cm）", height) { height = it }; NumberField("当前体重（kg）", weight) { weight = it }
        Text("公式使用的性别参数", fontSize = 13.sp)
        ChoiceRow(listOf("male" to "男性", "female" to "女性", "unspecified" to "不选择"), sex) { sex = it }
        NumberField("体脂率（%，手动录入，可选）", bodyFat) { bodyFat = it }
        Text(currentBodyFat?.let { "最近记录 ${it.day}；修改数值会保存为今天的记录。留空不删除历史记录。" } ?: "填写体脂秤 / 体测结果，不会根据体重或 BMI 自动推算。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormSection("我的目标") {
        ChoiceRow(listOf("maintain" to "保持", "lose" to "减脂", "gain" to "增重"), goal) { goal = it }
        NumberField("目标体重（kg，可选）", target) { target = it }
        }
        Row { Checkbox(special, { special = it }); Text("妊娠、哺乳或需要专业饮食管理", modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp) }
        Text("参考估算采用成人公式；年龄不在研究范围、未选性别参数或存在特殊情况时，保留记录并暂停自动热量目标。", fontSize = 12.sp)
    }
}

@Composable fun BodyFatDialog(day: String, initial: BodyFatEntry?, dismiss: () -> Unit, saving: Boolean, save: (BodyFatEntry) -> Unit) {
    var value by remember { mutableStateOf(initial?.percent?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog("记录体脂率 · $day", dismiss, {
        runCatching { BodyFatEntry(day, value.toDoubleOrNull() ?: error("请填写测量的体脂率"), System.currentTimeMillis()).also { it.validate() } }
            .onSuccess(save).onFailure { error = it.message }
    }, saving, error) {
        FormSection("我的测量结果") { NumberField("体脂率（%）", value) { value = it } }
        Text("手动录入体脂秤或体测数据。同一天再次保存会更新该日记录，未测量的日期保持空白。", fontSize = 13.sp)
        Text("尽量使用同一设备，在相近时间和状态测量，便于比较趋势。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun TargetWeightDialog(initial: Double?, current: Double?, dismiss: () -> Unit, saving: Boolean, save: (Double?) -> Unit) {
    var value by remember { mutableStateOf(initial?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog("目标体重", dismiss, {
        runCatching {
            if(value.isBlank()) null else (value.toDoubleOrNull() ?: error("请填写有效的目标体重"))
                .also { require(it.isFinite() && it in 10.0..400.0) { "目标体重需在 10–400 kg" } }
        }.onSuccess(save).onFailure { error = it.message }
    }, saving, error) {
        FormSection("我的体重目标") {
            current?.let { Text("当前体重：%.1f kg".format(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            NumberField("目标体重（kg）", value) { value = it }
        }
        Text("留空可清除目标。设置目标不会修改已记录体重，也不会自动生成每日摄入额度。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun WeightDialog(day: String, initial: WeightEntry?, dismiss: () -> Unit, saving: Boolean, save: (WeightEntry) -> Unit) {
    var value by remember { mutableStateOf(initial?.kg?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog("记录体重 · $day", dismiss, {
        runCatching { WeightEntry(day, value.toDoubleOrNull() ?: error("请填写体重"), System.currentTimeMillis()).also { it.validate() } }
            .onSuccess(save).onFailure { error = it.message }
    }, saving, error) { NumberField("体重（kg）", value) { value = it }; Text("同一天再次保存会更新该日体重。", fontSize = 12.sp) }
}

@Composable fun FoodEntryDialog(day: String, initialMeal: String, food: Food?, existing: MealEntry? = null,
    dismiss: () -> Unit, saving: Boolean, save: (MealEntry, Boolean) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: food?.name ?: "") }
    var grams by remember { mutableStateOf(existing?.grams?.toString() ?: "") }
    var density by remember { mutableStateOf((existing?.kcalPer100g ?: food?.kcalPer100g)?.toString() ?: "") }
    var meal by remember { mutableStateOf(existing?.meal ?: initialMeal) }
    var keepFood by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog(if (existing != null) "编辑食物" else "记录食物 · $day", dismiss, {
        runCatching {
            val originalDensity = existing?.kcalPer100g ?: food?.kcalPer100g
            val currentDensity = density.toDoubleOrNull() ?: error("请填写每100g热量")
            val unchanged = currentDensity == originalDensity && name.trim() == (existing?.name ?: food?.name)
            MealEntry(existing?.id ?: UUID.randomUUID().toString(), day, meal, name.trim(), grams.toDoubleOrNull() ?: error("请填写克数"), currentDensity,
                if (unchanged) existing?.proteinPer100g ?: food?.protein else null,
                if (unchanged) existing?.fatPer100g ?: food?.fat else null,
                if (unchanged) existing?.carbsPer100g ?: food?.carbs else null,
                if (unchanged) existing?.source ?: if (food != null) "database" else "manual" else "manual",
                if (unchanged) existing?.sourceReference ?: food?.reference ?: "用户录入" else "用户修改热量",
                existing?.recordedAt ?: System.currentTimeMillis()).also { it.validate() }
        }.onSuccess { save(it, keepFood) }.onFailure { error = it.message }
    }, saving, error) {
        FormSection("食物信息") {
        ChoiceRow(listOf("breakfast" to "早餐", "lunch" to "午餐", "dinner" to "晚餐", "snack" to "加餐"), meal) { meal = it }
        OutlinedTextField(name, { name = it }, label = { Text("食物名称与烹调方式") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        FormSection("份量与热量") {
        NumberField("可食部重量（g）", grams) { grams = it }; NumberField("每 100g 热量（千卡）", density) { density = it }
        val energy = runCatching { CalorieCalculator.foodKcal(grams.toDouble(), density.toDouble()) }.getOrNull()
        Text("${energy?.let { "%.0f".format(it) } ?: "—"} 千卡 · 本份热量", fontSize = 23.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        food?.let { Text(it.reference, fontSize = 11.sp); Text(it.description, fontSize = 11.sp) }
        if (food == null && existing == null) Row { Checkbox(keepFood, { keepFood = it }); Text("同时加入我的食物库", modifier = Modifier.padding(top = 11.dp), fontSize = 12.sp) }
        Text("注意生熟重量；汤汁、烹调油和酱料需要单独记录。", fontSize = 12.sp)
    }
}

data class ReviewDraft(val day: String, val meal: String, val items: List<MealCandidate>, val reference: String)
private data class EditableCandidate(val id: String, val name: String, val grams: String, val density: String, val note: String)
@Composable fun ReviewDialog(draft: ReviewDraft, dismiss: () -> Unit, saving: Boolean, save: (List<MealEntry>) -> Unit) {
    var rows by remember { mutableStateOf(draft.items.map { EditableCandidate(it.id, it.name, it.grams?.toString() ?: "", it.kcalPer100g?.toString() ?: "", it.note) }) }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog("确认${mealLabel(draft.meal)} · ${draft.day}", dismiss, {
        runCatching {
            require(rows.isNotEmpty()) { "至少保留一条食物" }
            rows.map { MealEntry(it.id, draft.day, draft.meal, it.name.trim(), it.grams.toDoubleOrNull() ?: error("请确认“${it.name}”克数"),
                it.density.toDoubleOrNull() ?: error("请确认“${it.name}”每100g热量"), source = if (draft.reference.startsWith("USDA")) "database" else "ai",
                sourceReference = if (draft.reference.startsWith("USDA")) it.note else draft.reference, recordedAt = System.currentTimeMillis()).also { value -> value.validate() } }
        }.onSuccess(save).onFailure { error = it.message }
    }, saving, error, saveLabel = "确认并保存") {
        Text("识别未自动入账。请检查食物、生熟状态、份量、油和酱料；模型估计的热量也可以修改。", fontSize = 12.sp)
        rows.forEachIndexed { index, row ->
            fun update(value: EditableCandidate) { rows = rows.toMutableList().also { it[index] = value } }
            FormSection("食物 ${index + 1}") {
            OutlinedTextField(row.name, { update(row.copy(name = it)) }, label = { Text("食物 ${index + 1}") }, modifier = Modifier.fillMaxWidth())
            NumberField("可食部（g）", row.grams) { update(row.copy(grams = it)) }
            NumberField("每100g（千卡）", row.density) { update(row.copy(density = it)) }
            Text(row.note, fontSize = 12.sp)
            TextButton(onClick = { rows = rows.filterNot { it.id == row.id } }) { Text("移除这一项") }
            }
        }
    }
}
