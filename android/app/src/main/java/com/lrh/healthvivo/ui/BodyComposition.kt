package com.lrh.healthvivo.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.fitness.*
import java.time.LocalDate

@Composable fun MetricStatus(status: BodyMetricStatus) {
    val colors = MaterialTheme.colorScheme
    val background = when(status.normal) { true -> colors.secondaryContainer; false -> colors.errorContainer; null -> colors.surfaceVariant }
    val foreground = when(status.normal) { true -> colors.onSecondaryContainer; false -> colors.onErrorContainer; null -> colors.onSurfaceVariant }
    Surface(shape = MaterialTheme.shapes.small, color = background) {
        Text(status.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = foreground, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable fun WeightBmi(weight: Double?, profile: Profile?, record: () -> Unit) {
    val bmi = BodyMetrics.bmi(weight, profile?.heightCm)
    val status = BodyMetrics.bmiStatus(bmi, profile)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("体重 · kg", Modifier.weight(1f).alignByBaseline(), fontSize = 12.sp, lineHeight = 16.sp)
                Text("BMI", Modifier.weight(1f).alignByBaseline(), fontSize = 12.sp, lineHeight = 16.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(weight?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f).alignByBaseline(), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text(bmi?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f).alignByBaseline(), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("成人 BMI 参考", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MetricStatus(status)
        }
        val scale = BodyMetrics.bmiScale(profile)
        if(scale != null) ReferenceScale(bmi, scale, "BMI")
        else Text(status.explanation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BodyMetrics.bmiWeightAdjustment(weight, profile)?.let { adjustment ->
            Text("至少${adjustment.direction} ${"%.1f".format(adjustment.kilograms)} kg", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("按 BMI ${adjustment.boundaryBmi} 参考边界估算", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = record) { Text("记录 / 更新体重") }
    }
}

@Composable private fun ReferenceScale(value: Double?, scale: BodyReferenceScale, markerPrefix: String) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    fun number(value: Double) = if(value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    val marker = value?.let { measurer.measure("$markerPrefix ${"%.1f".format(it)}${scale.unit}", TextStyle(color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)) }
    val boundaries = listOf(scale.min) + scale.breaks + scale.max
    val ticks = boundaries.mapIndexed { index, point ->
        val label = (if(index == 0) "≤" else if(index == boundaries.lastIndex) "≥" else "") + number(point) + scale.unit
        point to measurer.measure(label, TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp))
    }
    val names = scale.labels.map { measurer.measure(it, TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)) }
    val markerHeight = marker?.size?.height ?: with(density) { 16.sp.toPx() }.toInt()
    val barTop = markerHeight + with(density) { 18.dp.toPx() }
    val barHeight = with(density) { 12.dp.toPx() }
    val tickTop = barTop + barHeight + with(density) { 6.dp.toPx() }
    val namesTop = tickTop + ticks.maxOf { it.second.size.height } + with(density) { 6.dp.toPx() }
    val height = with(density) { (namesTop + names.maxOf { it.size.height }).toDp() }
    Canvas(Modifier.fillMaxWidth().height(height).semantics {
        contentDescription = "$markerPrefix 参考数轴：" + scale.labels.indices.joinToString("；") { "${scale.labels[it]} ${number(boundaries[it])}至${number(boundaries[it + 1])}${scale.unit}" } +
            (value?.let { "。当前${"%.1f".format(it)}${scale.unit}，箭头指示对应位置。" } ?: "。尚无当前数值。")
    }) {
        val left = 12.dp.toPx(); val width = (size.width - left * 2).coerceAtLeast(1f)
        fun x(value: Double) = left + ((value - scale.min) / (scale.max - scale.min)).coerceIn(0.0, 1.0).toFloat() * width
        val outline = Path().apply { addRoundRect(RoundRect(Rect(left, barTop, left + width, barTop + barHeight), CornerRadius(barHeight / 2))) }
        clipPath(outline) {
            scale.colors.forEachIndexed { index, color ->
                drawRect(Color(android.graphics.Color.parseColor(color)), Offset(x(boundaries[index]), barTop), androidx.compose.ui.geometry.Size(x(boundaries[index + 1]) - x(boundaries[index]), barHeight))
            }
        }
        if(value != null && marker != null) {
            val point = x(value)
            drawText(marker, topLeft = Offset((point - marker.size.width / 2f).coerceIn(0f, (size.width - marker.size.width).coerceAtLeast(0f)), 0f))
            drawPath(Path().apply {
                moveTo(point, barTop - 2.dp.toPx()); lineTo(point - 4.dp.toPx(), barTop - 10.dp.toPx()); lineTo(point + 4.dp.toPx(), barTop - 10.dp.toPx()); close()
            }, colors.primary)
        }
        ticks.forEach { (value, text) -> drawText(text, topLeft = Offset((x(value) - text.size.width / 2f).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f)), tickTop)) }
        names.forEachIndexed { index, text -> drawText(text, topLeft = Offset(x((boundaries[index] + boundaries[index + 1]) / 2) - text.size.width / 2f, namesTop)) }
    }
}

@Composable fun BodyFatContent(entry: BodyFatEntry?, profile: Profile?, record: () -> Unit) {
    val status = BodyMetrics.fatStatus(entry?.percent, profile)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(entry?.let { "%.1f%%".format(it.percent) } ?: "—", fontWeight = FontWeight.Bold, fontSize = 30.sp, modifier = Modifier.weight(1f))
            MetricStatus(status)
        }
        Text(entry?.let { "手动记录 · ${it.day}" } ?: "上传你的体脂秤或体测结果", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BodyMetrics.fatScale(profile)?.let { ReferenceScale(entry?.percent, it, "体脂率") }
        Text(status.explanation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = record) { Text(if(entry == null) "记录体脂率" else "记录 / 更新体脂率") }
    }
}

@Composable fun BodyMetricReferences() {
    val context = LocalContext.current
    Text("BMI 与体脂率为参考指标，范围正常不代表整体健康；体脂率参考表来自 Gallagher 等（2000），不同人群及测量设备会有差异。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BodyMetrics.BMI_SOURCE))) }) { Text("BMI 来源") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BodyMetrics.FAT_SOURCE))) }) { Text("体脂率参考来源") }
    }
}

@Composable fun BodyFatTrend(entries: List<BodyFatEntry>, profile: Profile?, record: () -> Unit, delete: (String) -> Unit) {
    Button(onClick = record) { Text("记录 / 更新体脂率") }
    if(entries.isEmpty()) { Text("手动记录体脂率后显示趋势。", color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(144.dp)) {
        val min = entries.minOf { it.percent } - .5; val max = entries.maxOf { it.percent } + .5
        val first = LocalDate.parse(entries.first().day).toEpochDay(); val span = LocalDate.parse(entries.last().day).toEpochDay() - first
        for(i in 0..2) { val y = 8f + i * (size.height - 16) / 2; drawLine(line, Offset(8f, y), Offset(size.width - 8f, y), 1.dp.toPx()) }
        val points = entries.map { Offset(if(span == 0L) size.width / 2 else 8f + (LocalDate.parse(it.day).toEpochDay() - first).toFloat() / span * (size.width - 16), (size.height - 8 - (it.percent - min) / (max - min) * (size.height - 16)).toFloat()) }
        points.zipWithNext().forEach { (a, b) -> drawLine(accent, a, b, 3.dp.toPx()) }
        points.forEach { drawCircle(accent, 4.dp.toPx(), it) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(entries.first().day.substring(5), fontSize = 11.sp); Text(entries.last().day.substring(5), fontSize = 11.sp) }
    val difference = entries.last().percent - entries.first().percent
    Text("最近 ${entries.size} 次记录 · 变化 ${if(difference > 0) "+" else ""}${"%.1f".format(difference)} 个百分点", fontSize = 13.sp)
    entries.takeLast(7).reversed().forEach { entry ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${entry.day.substring(5)} · ${"%.1f".format(entry.percent)}%", fontSize = 14.sp)
                MetricStatus(BodyMetrics.fatStatus(entry.percent, profile))
            }
            TextButton(onClick = { delete(entry.day) }) { Text("删除") }
        }
    }
}
