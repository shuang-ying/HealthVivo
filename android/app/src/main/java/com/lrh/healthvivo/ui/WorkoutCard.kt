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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when(entry.inputMode) { "distance" -> entry.distanceKm?.let { "%.1f km".format(it) } ?: "—"; "sets" -> "${entry.sets ?: "—"} 组${entry.reps?.let { " × $it 次" } ?: ""}"; else -> entry.minutes?.let { "%.0f 分钟".format(it) } ?: "—" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(when(entry.inputMode) { "distance" -> "记录距离"; "sets" -> "训练组数"; else -> "运动时长" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.kcal?.let { "%.0f 千卡".format(it) } ?: "待估算", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(if(entry.kcal == null) "条件不足" else if(entry.netCalories) "活动净消耗" else "热量口径未知", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if(expanded) "收起详情" else "查看详情", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if(entry.includedInSteps) Text("已包含在当天总步数中", fontSize = 12.sp)
                if(entry.estimateNote.isNotBlank()) Text(entry.estimateNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(entry.details.isNotBlank()) Text(entry.details, fontSize = 13.sp)
                TextButton(onClick = delete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { AppIcon("trash"); Spacer(Modifier.width(6.dp)); Text("删除这条运动") }
            }
        }
    }
}
