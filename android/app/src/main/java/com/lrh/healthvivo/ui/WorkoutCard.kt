package com.lrh.healthvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.fitness.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun WorkoutCard(entry: ExerciseEntry, edit: () -> Unit, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val kind = if(entry.category == "anaerobic") "力量训练" else if(entry.category == "aerobic") "有氧运动" else "未分类"
    Surface(onClick = { expanded = !expanded }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(if(entry.category == "anaerobic") "dumbbell" else "route", accent = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(kind, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = edit, modifier = Modifier.size(40.dp)) { AppIcon("edit", "编辑运动", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Time, distance and sets are independent now, so show every value that was filled.
                entry.minutes?.let { value -> Metric("%.0f 分钟".format(value), if(entry.inputMode == "sets") "总时长" else "运动时长") }
                entry.distanceKm?.let { value -> Metric("%.1f km".format(value), "记录距离") }
                entry.sets?.let { value -> Metric("$value 组${entry.reps?.let { " × $it 次" } ?: ""}", "训练组数") }
                entry.loadKg?.takeIf { it > 0 }?.let { value -> Metric("%.0f kg".format(value), "负重") }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.kcal?.let { "%.0f 千卡".format(it) } ?: "待估算", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(if(entry.kcal == null) "条件不足" else if(entry.netCalories) "活动净消耗" else "热量口径未知", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if(expanded) "收起详情" else "查看详情", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if(entry.countedInSteps) Text("本次约 ${entry.steps} 步已包含在当天总步数中，计算步数消耗时已扣除", fontSize = 12.sp)
                else if(entry.includedInSteps) Text("已勾选包含在总步数中，但未填写步数，因此按未包含处理（不会重复计算）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(entry.estimateNote.isNotBlank()) Text(entry.estimateNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(entry.details.isNotBlank()) Text(entry.details, fontSize = 13.sp)
                TextButton(onClick = delete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { AppIcon("trash"); Spacer(Modifier.width(6.dp)); Text("删除这条运动") }
            }
        }
    }
}

@Composable private fun Metric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The day's step total, shown at the top of the exercise page like a workout but never counted as one.
 * Its calorie figure is the step part of the recorded activity, already net of the steps the user
 * attributed to workouts, so it is only shown here and not on the home page's step card.
 */
@Composable fun StepsCard(activity: ActivityEstimate, steps: DailySteps?, edit: () -> Unit) {
    Surface(onClick = edit, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge("steps", accent = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("每日步数", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("不计入运动记录条数", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = edit, modifier = Modifier.size(40.dp)) { AppIcon("edit", "编辑每日步数", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(if(steps == null) "—" else "${steps.steps} 步", "当天总步数")
                Metric(activity.stepsKcal?.let { "%.0f 千卡".format(it) } ?: "待估算", "步数净消耗")
            }
            Text(stepSplitCaption(activity), fontSize = 13.sp)
            Text(when {
                steps == null -> "点击录入当天的总步数。"
                steps.stepLengthCm == null -> "填写步长（一步的距离，cm）后即可按平地步行估算步数消耗。"
                activity.stepsKcal == null -> "缺少当天体重，暂时无法估算步数消耗。"
                activity.workoutSteps > 0 -> "已从总步数中减去运动步数，只对剩下的记录步数计算消耗，运动本身的热量不重复计入。"
                else -> "按平地步行估算；若含跑步、坡度或不同步长，实际值会有偏差。"
            }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The total followed by its split, phrased the same way on the phone and the watch. */
fun stepSplitCaption(activity: ActivityEstimate): String = when {
    activity.totalSteps == null -> "当天步数未录入"
    activity.workoutSteps > 0 -> "总 ${activity.totalSteps} 步（运动 ${activity.workoutSteps} + 记录 ${activity.recordedSteps ?: 0}）"
    else -> "总 ${activity.totalSteps} 步（全部为记录步数）"
}
