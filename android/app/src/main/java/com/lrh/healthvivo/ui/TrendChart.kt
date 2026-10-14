package com.lrh.healthvivo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.fitness.CalorieCalculator
import com.lrh.healthvivo.fitness.FitnessData
import com.lrh.healthvivo.fitness.TrendBucket
import com.lrh.healthvivo.fitness.TrendRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val positiveBar = Color(0xFF5955E7)
private val negativeBar = Color(0xFFC0392B)
private val intakeBar = Color(0xFFC76A3B)
private const val BAR_FILL = 0.58f
private val chartHeight = 150.dp
private val tooltipWidth = 92.dp
private val tooltipLift = 30.dp

/**
 * One card holding three histograms, switched by the chips above the plot: what was eaten, what was
 * recorded as activity, and what was left of the period's reference. Each series can also be read per
 * week, month or year, because one day's number answers a different question from one month's.
 *
 * Tapping a column lifts its exact value over the plot: a bar chart alone cannot be read precisely.
 */
@Composable fun TrendCard(data: FitnessData, day: String) {
    var series by remember { mutableStateOf(0) }
    var range by remember { mutableStateOf(TrendRange.Week) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val buckets = remember(data, day, range) { CalorieCalculator.trendBuckets(data, day, range) }
    val labels = listOf("饮食", "运动", "热量剩余")
    val values = buckets.map { valueOf(it, series) }
    val known = values.filterNotNull()
    val peak = max(known.maxOfOrNull { abs(it) } ?: 0.0, 1.0)
    val floor = if(series == 2) min(known.minOrNull() ?: 0.0, 0.0) else 0.0
    val span = max(peak - floor, 1.0)
    val barColor = if(series == 0) intakeBar else positiveBar
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axis = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val heightPx = with(density) { chartHeight.toPx() }
    val tooltipWidthPx = with(density) { tooltipWidth.toPx() }
    val tooltipLiftPx = with(density) { tooltipLift.toPx() }
    var chartWidth by remember { mutableStateOf(0f) }
    fun slot() = if(buckets.isEmpty()) 0f else chartWidth / buckets.size
    fun barWidth() = slot() * BAR_FILL
    fun barLeft(index: Int) = index * slot() + (slot() - barWidth()) / 2f
    fun yOf(value: Double) = (heightPx * (1.0 - (value - floor) / span)).toFloat().coerceIn(0f, heightPx)
    val zeroY = yOf(0.0)
    val index = selected?.coerceIn(0, max(buckets.size - 1, 0))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipRow(labels, series) { series = it }
        ChipRow(TrendRange.entries.map { it.label }, TrendRange.entries.indexOf(range)) { range = TrendRange.entries[it]; selected = null }
        Text("${range.window} · 每根柱子是一${range.label}的合计", fontSize = 12.sp, color = muted)
        Box(Modifier.fillMaxWidth().height(chartHeight)
            .onSizeChanged { chartWidth = it.width.toFloat() }
            .pointerInput(buckets.size, range) {
                detectTapGestures { point ->
                    val width = slot()
                    if(width > 0f && buckets.isNotEmpty()) selected = (point.x / width).toInt().coerceIn(0, buckets.size - 1)
                }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(axis, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.5f)
                values.forEachIndexed { position, value ->
                    val left = barLeft(position)
                    if(value == null) {
                        // A period with no records is drawn as a faint stub, never as a real zero.
                        drawRect(axis.copy(alpha = .45f), Offset(left, zeroY - 1.5f), Size(barWidth(), 3f))
                    } else {
                        val top = min(yOf(value), zeroY)
                        val bottom = max(yOf(value), zeroY)
                        drawRect(if(series == 2 && value < 0) negativeBar else barColor, Offset(left, top), Size(barWidth(), max(bottom - top, 1.5f)))
                        if(position == index) drawRect(Color.White.copy(alpha = .6f), Offset(left, top),
                            Size(barWidth(), max(bottom - top, 1.5f)), style = Stroke(width = 3f))
                    }
                }
            }
            index?.let { position ->
                val value = values.getOrNull(position)
                if(value != null) {
                    val center = barLeft(position) + barWidth() / 2f
                    val top = min(yOf(value), zeroY)
                    Surface(
                        modifier = Modifier
                            .width(tooltipWidth)
                            .offset { IntOffset(
                                (center - tooltipWidthPx / 2f).coerceIn(0f, max(chartWidth - tooltipWidthPx, 0f)).roundToInt(),
                                (top - tooltipLiftPx).coerceAtLeast(0f).roundToInt()) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 4.dp) {
                        Text(trendAmount(series, value), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.inverseOnSurface,
                            maxLines = 1)
                    }
                }
            }
        }
        if(buckets.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(buckets.first(), buckets[buckets.size / 2], buckets.last()).forEach { Text(it.label, fontSize = 11.sp, color = muted) }
            }
        }
        val shown = buckets.getOrNull(index ?: buckets.lastIndex)
        if(shown != null) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if(index == null) "${shown.rangeText}（最新一${range.label}）" else shown.rangeText, fontSize = 12.sp, color = muted)
                    Text(trendAmount(series, valueOf(shown, series)), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("摄入 ${number(valueOf(shown, 0))} · 运动 ${number(valueOf(shown, 1))} · 剩余 ${signed(valueOf(shown, 2))} 千卡", fontSize = 12.sp, color = muted)
                }
            }
        }
        Text(if(index == null) "点击任意柱子可查看该${range.label}的准确数值。" else "已选中第 ${index + 1} 根柱子，点击其他柱子即可切换。", fontSize = 12.sp, color = muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TrendStat("合计/一${range.label}", known.sum())
            TrendStat("均值/一${range.label}", known.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size })
            TrendStat("最高/一${range.label}", known.maxByOrNull { abs(it) })
        }
        Text(when(series) {
            0 -> "每${range.label}登记的所有餐次热量合计；加餐也计入。"
            1 -> "每${range.label}运动记录的活动净消耗合计，不含步数；步数消耗在运动页单独查看。"
            else -> "每${range.label}的消耗参考减登记摄入之和。紫色向上表示还有剩余，红色向下表示已超过参考。"
        }, fontSize = 12.sp, color = muted)
        Text("横轴为${range.window}。完全没有记录的时间段画成浅色短线并按 0 统计，不代表那段时期真的没有摄入或消耗；当前${range.label}只统计到今天。", fontSize = 12.sp, color = muted)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            buckets.takeLast(6).reversed().forEachIndexed { position, bucket ->
                val value = valueOf(bucket, series)
                Row(Modifier.fillMaxWidth().clickable { selected = buckets.lastIndex - position }) {
                    Text(if(position == 0) "${bucket.rangeText} · 最新" else bucket.rangeText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if(value == null) "未记录" else "${number(value)} 千卡", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if(series == 2 && value != null && value < 0) negativeBar else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private fun valueOf(bucket: TrendBucket, series: Int): Double? = when(series) {
    0 -> bucket.intakeKcal
    1 -> bucket.activityKcal
    else -> bucket.referenceKcal?.let { it - bucket.intakeKcal }
}
private fun number(value: Double?) = if(value == null) "—" else "%.0f".format(value)
private fun signed(value: Double?) = if(value == null) "—" else (if(value >= 0) "+" else "−") + "%.0f".format(abs(value))
private fun trendAmount(series: Int, value: Double?) = when {
    value == null -> "未记录"
    series == 2 -> signed(value) + " 千卡"
    else -> number(value) + " 千卡"
}

@Composable private fun ChipRow(labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, onClick = { choose(index) }, label = { Text(label, fontSize = 13.sp) },
                shape = RoundedCornerShape(50),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == index, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable private fun TrendStat(label: String, value: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(value == null) "—" else "%.0f".format(value), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
