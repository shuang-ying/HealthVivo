package com.lrh.healthvivo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.fitness.TrendDay
import kotlin.math.max

private val positiveBar = Color(0xFF5955E7)
private val negativeBar = Color(0xFFC0392B)
private val intakeBar = Color(0xFFC76A3B)

/**
 * One card holding three histograms, switched by the chips above the plot: what was eaten, what was
 * recorded as activity, and what was left of the day's reference. Keeping them in a single card makes
 * the series comparable without scrolling, and every series shares the same day axis.
 */
@Composable fun TrendCard(points: List<TrendDay>) {
    var series by remember { mutableStateOf(0) }
    val labels = listOf("饮食", "运动", "热量剩余")
    val values = when(series) {
        0 -> points.map { it.intakeKcal }
        1 -> points.map { it.activityKcal }
        else -> points.map { point -> point.referenceKcal?.let { it - point.intakeKcal } }
    }
    val signed = series == 2
    val known = values.filterNotNull()
    val peak = max(known.maxOrNull() ?: 0.0, 1.0)
    val floor = if(signed) kotlin.math.min(known.minOrNull() ?: 0.0, 0.0) else 0.0
    val span = max(peak - floor, 1.0)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val barColor = if(series == 0) intakeBar else positiveBar
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                FilterChip(selected = series == index, onClick = { series = index }, label = { Text(label, fontSize = 13.sp) },
                    shape = RoundedCornerShape(50),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = series == index, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val slot = size.width / max(values.size, 1)
            val barWidth = slot * 0.58f
            fun y(value: Double) = (size.height * (1.0 - (value - floor) / span)).toFloat().coerceIn(0f, size.height)
            val zeroY = y(0.0)
            drawLine(axis, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.5f)
            values.forEachIndexed { index, value ->
                val left = index * slot + (slot - barWidth) / 2f
                if(value == null) {
                    // A missing day is drawn as a faint stub so it cannot be mistaken for a real zero.
                    drawRect(axis.copy(alpha = .45f), Offset(left, zeroY - 1.5f), Size(barWidth, 3f))
                } else {
                    val top = kotlin.math.min(y(value), zeroY)
                    val bottom = kotlin.math.max(y(value), zeroY)
                    drawRect(if(signed && value < 0) negativeBar else barColor, Offset(left, top), Size(barWidth, max(bottom - top, 1.5f)))
                }
            }
        }
        if(points.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(points.first(), points[points.size / 2], points.last()).forEach { Text(it.day.substring(5), fontSize = 11.sp, color = muted) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TrendStat("合计", known.sum())
            TrendStat("日均", if(known.isEmpty()) null else known.sum() / known.size)
            TrendStat(if(signed) "最大剩余" else "最高", known.maxOrNull())
        }
        Text(when(series) {
            0 -> "每天登记的所有餐次热量合计；加餐也计入。"
            1 -> "当天运动记录的活动净消耗合计，不含步数；步数消耗在运动页单独查看。"
            else -> "当天消耗参考减当天登记摄入。绿色向上表示还有剩余，红色向下表示已超过参考。"
        }, fontSize = 12.sp, color = muted)
        Text("横轴为最近 ${points.size} 天。未记录的日期画成浅色短线并按 0 统计，方便对齐日历，不代表当天真的没有摄入或消耗。", fontSize = 12.sp, color = muted)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            points.takeLast(7).reversed().forEachIndexed { index, point ->
                val value = when(series) {
                    0 -> point.intakeKcal
                    1 -> point.activityKcal
                    else -> point.referenceKcal?.let { it - point.intakeKcal }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if(index == 0) "${point.day.substring(5)} · 今天" else point.day.substring(5), fontSize = 13.sp)
                    Text(if(value == null) "未记录" else "%.0f 千卡".format(value), fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, color = if(signed && value != null && value < 0) negativeBar else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable private fun TrendStat(label: String, value: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(value == null) "—" else "%.0f".format(value), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
