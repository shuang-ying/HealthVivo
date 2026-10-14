package com.lrh.healthvivo.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.ai.*
import com.lrh.healthvivo.fitness.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable fun StepsDialog(day: String, initial: DailySteps?, dismiss: () -> Unit, saving: Boolean, delete: (() -> Unit)? = null, save: (DailySteps) -> Unit) {
    var steps by remember { mutableStateOf(initial?.steps?.toString() ?: "") }
    var stride by remember { mutableStateOf(initial?.stepLengthCm?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    EntryDialog("每日步数 · $day", dismiss, {
        runCatching { DailySteps(day, steps.toIntOrNull() ?: error("请填写整数步数"),
            if(stride.isBlank()) null else stride.toDoubleOrNull() ?: error("请填写有效步长"), System.currentTimeMillis()).also { it.validate() } }
            .onSuccess(save).onFailure { error = it.message }
    }, saving, error) {
        FormSection("当天步行") {
        NumberField("当天总步数（步）", steps) { steps = it }
        NumberField("步长（cm，可选）", stride) { stride = it }
        }
        Text("一天只保存一条总步数，再次保存会更新。未填写与实际 0 步分开显示。", fontSize = 12.sp)
        Text("步长指每一步的距离，可实测一段步行距离÷步数。填写后按平地步行估算净消耗；若含跑步、坡度或不同步长，误差会增大。未填步长时仅记录步数。", fontSize = 12.sp)
        if(initial != null && delete != null) TextButton(onClick = delete, enabled = !saving, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除当天步数") }
    }
}

@Composable fun ExerciseDialog(day: String, weight: Double?, existing: ExerciseEntry? = null, api: ApiSettings,
    configureApi: () -> Unit, dismiss: () -> Unit, saving: Boolean, initialType: ExerciseType? = null, save: (ExerciseEntry) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: initialType?.name ?: "") }
    var category by remember { mutableStateOf(existing?.category?.takeUnless { it == "unspecified" } ?: initialType?.category ?: "aerobic") }
    var minutes by remember { mutableStateOf(existing?.minutes?.toString() ?: "") }
    var distance by remember { mutableStateOf(existing?.distanceKm?.toString() ?: "") }
    var sets by remember { mutableStateOf(existing?.sets?.toString() ?: "") }
    var reps by remember { mutableStateOf(existing?.reps?.toString() ?: "") }
    var load by remember { mutableStateOf(existing?.loadKg?.toString() ?: "") }
    var kg by remember { mutableStateOf((existing?.weightKg ?: weight)?.toString() ?: "") }
    var details by remember { mutableStateOf(existing?.details ?: "") }
    var overlap by remember { mutableStateOf(existing?.includedInSteps ?: initialType?.countsSteps ?: false) }
    // A name or library pick only suggests the default; once the user ticks the box themselves that choice wins.
    var overlapChosen by remember { mutableStateOf(existing != null) }
    var overlapSteps by remember { mutableStateOf(existing?.steps?.toString() ?: "") }
    var library by remember { mutableStateOf(false) }
    var estimate by remember { mutableStateOf(existing?.takeIf { it.netCalories }?.let { ExerciseEstimate(it.kcal, it.method, it.met, it.estimateNote) }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun invalidate() { estimate = null; error = null }
    fun draft(): ExerciseEntry {
        fun optional(text: String, label: String) = if (text.isBlank()) null else text.toDoubleOrNull() ?: error("请填写有效的$label")
        fun count(text: String, label: String) = if (text.isBlank()) null else text.toIntOrNull() ?: error("${label}需为整数")
        val minutesValue = optional(minutes, "时长")
        val distanceValue = optional(distance, "距离")
        val setsValue = count(sets, "组数")
        // The three inputs are independent now, so keep the legacy label in step with what was filled.
        val derived = when { distanceValue != null -> "distance"; minutesValue != null -> "time"; setsValue != null -> "sets"; else -> "time" }
        return ExerciseEntry(existing?.id ?: UUID.randomUUID().toString(), day, name.trim(),
            minutesValue, null, "pending", optional(kg, "体重"), recordedAt = existing?.recordedAt ?: System.currentTimeMillis(),
            category = category, inputMode = derived, distanceKm = distanceValue,
            sets = setsValue, reps = count(reps, "每组次数"), loadKg = optional(load, "负重"),
            includedInSteps = overlap, details = details.trim(), steps = count(overlapSteps, "本次运动步数")).also { it.validate() }
    }
    EntryDialog(if(existing == null) "记录运动 · $day" else "编辑运动", dismiss, {
        runCatching {
            val input = draft()
            val result = estimate
            input.copy(kcal = result?.kcal, method = result?.method?.takeIf { result.kcal != null } ?: "pending",
                met = result?.met, netCalories = result?.kcal != null, estimateNote = result?.note ?: "尚未估算热量").also { it.validate() }
        }.onSuccess(save).onFailure { error = it.message ?: "请检查运动输入" }
    }, saving || busy, error, saveLabel = if(estimate?.kcal != null) "确认并保存" else "保存运动（待估算）") {
        FormSection("运动项目") {
        OutlinedTextField(name, { name = it; if(!overlapChosen) overlap = CalorieCalculator.isStepExercise(it); invalidate() }, label = { Text("运动名称") }, placeholder = { Text("例如跑步、卧推") }, singleLine = true, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { library = true }, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth()) { AppIcon("book"); Spacer(Modifier.width(8.dp)); Text("从运动库选择") }
        ChoiceRow(listOf("aerobic" to "有氧", "anaerobic" to "无氧 / 力量"), category) { if(!busy && !saving) { category = it; invalidate() } }
        }
        FormSection("完成了多少（三项都可留空，按填写的内容计算）") {
        NumberField("时长（分钟，可选）", minutes) { if(!busy && !saving) { minutes = it; invalidate() } }
        NumberField("距离（km，可选）", distance) { if(!busy && !saving) { distance = it; invalidate() } }
        NumberField("组数（可选，力量训练）", sets) { if(!busy && !saving) { sets = it; invalidate() } }
        NumberField("每组次数（可选）", reps) { if(!busy && !saving) { reps = it; invalidate() } }
        NumberField("负重（kg，可选，徒手填 0）", load) { if(!busy && !saving) { load = it; invalidate() } }
        Text("同时填写时长和距离时，步行与跑步优先按距离公式计算。只填组数且没有时长、次数或负重时无法估算。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormSection("估算所需信息") {
        NumberField("当时体重（kg）", kg) { if(!busy && !saving) { kg = it; invalidate() } }
        OutlinedTextField(details, { if(!busy && !saving) { details = it; invalidate() } }, label = { Text("补充说明（可选：速度、休息、坡度等）") }, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth())
        Row { Checkbox(overlap, { if(!busy && !saving) { overlap = it; overlapChosen = true } }, enabled = !busy && !saving); Text("本次运动已包含在当天总步数中", modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp) }
        if(overlap) {
            NumberField("本次运动大约多少步（可选）", overlapSteps) { if(!busy && !saving) overlapSteps = it }
            Text("填写后会从当天总步数里减去这些步数，只对剩下的步数计算步行消耗，再加回这条运动自己的热量。留空时可点“发送并 AI 估算”由 AI 估算步数；本地公式不产生步数，未填步数时这条运动按未包含处理，不会重复计算。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        Text("本地按时长或平地步行 / 跑步距离估算；只有组数时先保存记录。速度、坡度和休息等说明供 AI 参考。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { runCatching { CalorieCalculator.localExercise(draft()) }.onSuccess { estimate = it; error = null }.onFailure { error = it.message } }, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth()) { Text("按本地公式计算") }
        val configured = runCatching { api.validate() }.isSuccess
        Text(if(configured) "点击 AI 仅发送本次运动、次数/负重/时长和填写的体重到 ${Uri.parse(api.endpoint).host}。与三餐共用 API。" else "AI 尚未配置，与三餐使用同一套 API。", fontSize = 12.sp)
        Button(onClick = {
            val input = runCatching { draft() }.onFailure { error = it.message }.getOrNull()
            if(input != null) { busy = true; error = null; estimate = null
                scope.launch {
                    try {
                        val result = ExerciseAiClient().estimate(api, input)
                        if(result.steps != null) overlapSteps = result.steps.toString()
                        val range = if(result.netKcal != null) "\nAI 范围：%.0f–%.0f 千卡。".format(result.lowKcal, result.highKcal) else ""
                        val stepsNote = result.steps?.let { "\nAI 估算本次约 $it 步，已填入“本次运动大约多少步”，可自行修改。" } ?: ""
                        estimate = ExerciseEstimate(result.netKcal, if(result.netKcal == null) "pending" else "ai", note = result.note + range + stepsNote + "\n服务：${Uri.parse(api.endpoint).host} / ${api.model}")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = when(failure) { is IllegalArgumentException -> failure.message; is java.io.IOException -> "运动估算连接失败或超时，请检查网络与 API"; else -> "运动估算响应格式不兼容，请重试或使用本地记录" } }
                    finally { busy = false }
                }
            }
        }, enabled = configured && !busy && !saving, modifier = Modifier.fillMaxWidth()) { Text(if(busy) "AI 估算中…" else "发送并 AI 估算") }
        TextButton(onClick = configureApi, enabled = !busy && !saving) { Text("配置三餐与运动 API") }
        estimate?.let { result -> FormSection("计算结果") {
            Text(if(result.kcal == null) "当前无法估算热量" else "%.0f 千卡 · 活动净消耗".format(result.kcal), fontSize = 21.sp, color = MaterialTheme.colorScheme.primary)
            Text(result.note, fontSize = 12.sp)
        } }
        if(existing != null && !existing.netCalories && existing.kcal != null) Text("旧记录保留 ${existing.kcal} 千卡，统计口径未确认。本次编辑需重新估算；取消会保留原记录。", fontSize = 12.sp)
        Text("热量均为估计。结果需确认后保存；修改名称或计算条件会清除旧估算。", fontSize = 12.sp)
    }
    if(library) ExercisePicker({ library = false }) { type -> name = type.name; category = type.category; overlap = type.countsSteps; overlapChosen = false; invalidate(); library = false }
}
