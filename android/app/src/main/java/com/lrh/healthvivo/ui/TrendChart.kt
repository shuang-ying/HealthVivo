package com.lrh.healthvivo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.fitness.FitnessData
import com.lrh.healthvivo.fitness.TrendBucket
import com.lrh.healthvivo.fitness.TrendScale
import com.lrh.healthvivo.fitness.Trends
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val trendAccent = Color(0xFF5955E7)
private val intakeColor = Color(0xFFC76A3B)
private val negativeColor = Color(0xFFC0392B)
private const val BAR_FILL = 0.62f
private val plotHeight = 160.dp
private val measureHeight = 150.dp
private val gutter = 46.dp

/**
 * Where the charts are looking: a scale and a calendar position.
 *
 * The position is a date, not an index, so changing 周/月/年 keeps the calendar place: the week of
 * 10/05 and the month of October both come from an anchor inside them, and stepping back a month from
 * October lands in September whichever scale was showing before.
 */
private class PeriodState(val scale: TrendScale, val period: com.lrh.healthvivo.fitness.TrendPeriod,
    val canForward: Boolean, val atCurrent: Boolean,
    val setScale: (TrendScale) -> Unit, val move: (Long) -> Unit, val reset: () -> Unit)

@Composable private fun rememberPeriod(anchorDay: String): PeriodState {
    val selected = remember(anchorDay) { java.time.LocalDate.parse(anchorDay) }
    var scale by remember(anchorDay) { mutableStateOf(TrendScale.Week) }
    var anchor by remember(anchorDay) { mutableStateOf(selected) }
    val period = Trends.period(anchor.toString(), scale)
    val current = Trends.period(selected.toString(), scale)
    return PeriodState(scale, period, period.start < current.start, period.start == current.start,
        setScale = { scale = it }, move = { anchor = Trends.shift(anchor, scale, it) }, reset = { anchor = selected })
}

/**
 * The three daily series of the phone, on the x axis the scale asks for: the seven days of a week, the
 * days of a month, or the twelve months of a year. Every column of the period is drawn, so the whole
 * span is on screen at once; tapping a column lifts its exact numbers.
 */
@Composable fun EnergyTrendCard(data: FitnessData, anchorDay: String) {
    val state = rememberPeriod(anchorDay)
    var series by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val buckets = remember(data, state.period) { Trends.energy(data, state.period) }
    val values = buckets.map { energyValue(it, series) }
    val known = values.filterNotNull()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    // Bars grow from a real baseline: a bar measured from anything else would no longer be proportional
    // to its value. Only the top clears the tallest bar, by a fixed fraction of the range.
    val rawLow = if(series == 2) min(known.minOrNull() ?: 0.0, 0.0) else 0.0
    val rawHigh = if(series == 2) max(known.maxOrNull() ?: 0.0, 0.0) else (known.maxOrNull() ?: 0.0)
    val axis = axisFor(rawLow, rawHigh, minStep = 1.0, minSpan = 100.0, keepLow = true)
    val low = axis.low
    val top = axis.top
    val ticks = axis.ticks
    val index = selected?.coerceIn(0, max(buckets.size - 1, 0))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScaleChips(state.scale, state.setScale)
        PeriodBar(state)
        ChipRow(listOf("饮食", "运动", "热量剩余"), series) { series = it; selected = null }
        FitChart(buckets.size, plotHeight, index, low, top, ticks, { "%.0f".format(it) }, labelStride(state.scale), { selected = it },
            anchorIndex = buckets.indexOfFirst { it.startDay == anchorDay },
            tooltip = { position -> values.getOrNull(position)?.let { value ->
                (buckets[position].shortText + " · " + energyAmount(series, value)) to value } },
            axis = { buckets.map { it.label } },
            draw = { plot ->
                val zero = plot.y(0.0)
                drawLine(axisColor, Offset(plot.left, zero), Offset(plot.left + plot.width, zero), strokeWidth = 1.5f)
                values.forEachIndexed { position, value ->
                    val left = plot.barLeft(position)
                    if(value == null || buckets[position].future) {
                        // Nothing recorded, or nothing that has happened yet: a pale stub, never a zero bar.
                        drawRect(axisColor.copy(alpha = .55f), Offset(left, (zero - 4f).coerceAtLeast(0f)), Size(plot.barWidth, 4f))
                    } else {
                        val barTop = min(plot.y(value), zero)
                        val barBottom = max(plot.y(value), zero)
                        drawRect(if(series == 2 && value < 0) negativeColor else if(series == 0) intakeColor else trendAccent,
                            Offset(left, barTop), Size(plot.barWidth, max(barBottom - barTop, 1.5f)))
                        if(position == index) drawRect(Color.White.copy(alpha = .6f), Offset(left, barTop),
                            Size(plot.barWidth, max(barBottom - barTop, 1.5f)), style = Stroke(width = 3f))
                    }
                }
            })
        // Without a tap, the readout describes the column the arrow points at - the day being viewed -
        // whenever that column holds something. Falling back to the last recorded column would put the
        // arrow on one column and the value under it from another, which reads as a bug.
        val fallback = buckets.indexOfFirst { it.startDay == anchorDay }.takeIf { it >= 0 && buckets[it].recorded }
            ?: buckets.indexOfLast { it.recorded }
        val shown = buckets.getOrNull(index ?: fallback)
        Readout(shown?.rangeText, when {
            shown == null -> null
            shown.future -> "还没到这段日期"
            else -> energyAmount(series, energyValue(shown, series))
        }, shown?.let { "摄入 ${number(it.intakeKcal)} · 运动 ${number(it.activityKcal)} · 剩余 ${signed(energyValue(it, 2))} 千卡" },
            if(index == null) null else "已选中「${shown?.rangeText}」。")
        val recorded = buckets.filter { it.recorded }
        val meanValues = recorded.mapNotNull { energyValue(it, series) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TrendStat("合计", known.sum())
            TrendStat("均值", meanValues.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size })
            TrendStat("最高", known.maxByOrNull { abs(it) })
        }
        Text(when(series) {
            0 -> "每根柱子是一天（按年时是一个月）登记的所有餐次热量合计，加餐也计入。"
            1 -> "每根柱子是那一天（按年时是那个月）运动记录的活动净消耗合计，不含步数；步数消耗在运动页单独查看。"
            else -> "每根柱子是那一天（按年时是那个月）的消耗参考减登记摄入。紫色向上表示还有剩余，红色向下表示已超过参考。"
        }, fontSize = 12.sp, color = muted)
    }
}

/** The same navigation for a sparse measurement: weight or body fat, with tappable points. */
@Composable fun MeasureTrendCard(values: Map<String, Double>, anchorDay: String, unit: String, decimals: Int) {
    val state = rememberPeriod(anchorDay)
    var selected by remember { mutableStateOf<Int?>(null) }
    val points = remember(values, state.period) { Trends.measure(values, state.period) }
    val present = points.filter { it.value != null }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val index = selected?.coerceIn(0, max(points.size - 1, 0))
    if(present.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScaleChips(state.scale, state.setScale)
            PeriodBar(state)
            Text("这一段没有记录。", fontSize = 13.sp, color = muted)
        }
        return
    }
    // A measurement axis must not start at zero, or a two-kilogram change would vanish into the margin.
    // Both ends clear the readings by the same fraction, so the lowest point is not drawn on the frame
    // either. minSpan keeps a single reading from collapsing the axis to nothing.
    val axis = axisFor(present.minOf { it.value!! }, present.maxOf { it.value!! },
        minStep = 1.0 / 10.0.pow(decimals), minSpan = 10.0 / 10.0.pow(decimals), keepLow = false)
    val low = axis.low
    val top = axis.top
    val ticks = axis.ticks
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScaleChips(state.scale, state.setScale)
        PeriodBar(state)
        FitChart(points.size, measureHeight, index, low, top, ticks, { format(it, decimals) }, labelStride(state.scale),
            { selected = it },
            anchorIndex = points.indexOfFirst { it.startDay == anchorDay },
            tooltip = { position -> points.getOrNull(position)?.value?.let { value ->
                (points[position].shortText + " · " + format(value, decimals) + unit) to value } },
            axis = { points.map { it.label } },
            draw = { plot ->
                val positions = points.mapIndexedNotNull { position, point ->
                    point.value?.let { plot.centerX(position) to plot.y(it) } }
                if(positions.size > 1) {
                    val path = Path().apply { moveTo(positions.first().first, positions.first().second)
                        positions.drop(1).forEach { lineTo(it.first, it.second) } }
                    drawPath(path, trendAccent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }
                points.forEachIndexed { position, point ->
                    val value = point.value ?: return@forEachIndexed
                    val centre = Offset(plot.centerX(position), plot.y(value))
                    drawCircle(Color.White, 6.dp.toPx(), centre)
                    drawCircle(if(position == index) negativeColor else trendAccent, 4.dp.toPx(), centre)
                }
            })
        val fallback = points.indexOfFirst { it.startDay == anchorDay }.takeIf { it >= 0 && points[it].value != null }
            ?: points.indexOfLast { it.value != null }
        val shown = points.getOrNull(index ?: fallback)
        Readout(shown?.rangeText, shown?.value?.let { format(it, decimals) + unit }, null,
            if(index == null) null else "已选中「${shown?.rangeText}」。")
        val difference = present.last().value!! - present.first().value!!
        Text("这一段有 ${present.size} 次记录 · 变化 ${if(difference > 0) "+" else ""}${format(difference, decimals)}$unit",
            fontSize = 13.sp, color = muted)
    }
}

/** Where a column sits and how a value maps onto the vertical axis. */
private class Plot(val left: Float, val width: Float, val slot: Float, val barWidth: Float,
    val height: Float, val low: Double, val top: Double) {
    fun y(value: Double) = (height * (1 - (value - low) / (top - low))).toFloat().coerceIn(0f, height)
    fun centerX(index: Int) = left + index * slot + slot / 2f
    fun barLeft(index: Int) = left + index * slot + (slot - barWidth) / 2f
}

/**
 * A plot with a labelled vertical axis that always fits the whole period across the width.
 *
 * Nothing scrolls sideways: a month of thirty-one columns is drawn complete, however thin the columns
 * get, because seeing the shape of the whole period matters more than the width of one bar.
 */
@Composable private fun FitChart(count: Int, height: Dp, selected: Int?, low: Double, top: Double, ticks: List<Double>,
    formatTick: (Double) -> String, labelStride: Int, onSelect: (Int) -> Unit, anchorIndex: Int,
    tooltip: (Int) -> Pair<String, Double>?, axis: () -> List<String>, draw: DrawScope.(Plot) -> Unit) {
    var width by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val activeColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = MaterialTheme.colorScheme.primary
    val gutterPx = with(density) { gutter.toPx() }
    val heightPx = with(density) { height.toPx() }
    val liftPx = with(density) { 30.dp.toPx() }
    val halfLinePx = with(density) { 7.dp.toPx() }
    val tooltipWidth = 132.dp
    val tooltipWidthPx = with(density) { tooltipWidth.toPx() }
    val labelWidth = 34.dp
    val labelWidthPx = with(density) { labelWidth.toPx() }
    val labelHalfPx = labelWidthPx / 2f
    val arrowHalf = with(density) { 5.dp.toPx() }
    val arrowHeight = with(density) { 9.dp.toPx() }
    val plotWidth = (width - gutterPx).coerceAtLeast(1f)
    val slot = if(count <= 0) plotWidth else plotWidth / count
    val labels = axis()
    val marker = anchorIndex
    fun yOf(value: Double) = (heightPx * (1 - (value - low) / (top - low))).toFloat().coerceIn(0f, heightPx)
    val stride = max(1, labelStride)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
            Row(Modifier.fillMaxWidth().height(height)) {
                // The tick labels are real text rather than glyphs drawn into the canvas: they line up
                // with the grid by construction, a screen reader can read them, and they cannot quietly
                // fail to appear.
                Box(Modifier.width(gutter).fillMaxHeight()) {
                    ticks.forEach { tick ->
                        Text(formatTick(tick), fontSize = 10.sp, color = tickColor, maxLines = 1,
                            modifier = Modifier.offset { IntOffset(0, (yOf(tick) - halfLinePx).coerceAtLeast(0f).roundToInt()) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()
                    .pointerInput(count) {
                        detectTapGestures { point ->
                            val each = size.width.toFloat() / max(count, 1)
                            if(count > 0) onSelect((point.x / each).toInt().coerceIn(0, count - 1))
                        }
                    }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val each = size.width / max(count, 1)
                        val plot = Plot(0f, size.width, each, each * BAR_FILL, size.height, low, top)
                        ticks.forEach { tick -> val y = plot.y(tick)
                            drawLine(activeColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f) }
                        draw(plot)
                        // The day being viewed is pointed at from above rather than marked on the axis:
                        // the axis labels move with the stride, the arrow always lands on its own column.
                        if(marker in 0 until count) {
                            val centre = each * marker + each / 2f
                            drawPath(Path().apply {
                                moveTo(centre - arrowHalf, 0f); lineTo(centre + arrowHalf, 0f); lineTo(centre, arrowHeight); close()
                            }, markerColor)
                        }
                    }
                }
            }
            selected?.let { position -> tooltip(position)?.let { (text, value) ->
                val each = plotWidth / max(count, 1)
                Surface(
                    modifier = Modifier.width(tooltipWidth).offset { IntOffset(
                        (gutterPx + position * each + each / 2f - tooltipWidthPx / 2f).coerceIn(0f, max(width - tooltipWidthPx, 0f)).roundToInt(),
                        (yOf(value) - liftPx).coerceAtLeast(0f).roundToInt()) },
                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                    Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1)
                }
            } }
        }
        // Labels are placed over the column they name instead of inside it: a month's column is only a
        // few dp wide, and a two-digit day would be truncated to its first digit if it had to fit.
        // They are centred on their column and never nudged inwards - shifting an edge label to keep it
        // inside would break the even spacing and push it onto its neighbour.
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            labels.forEachIndexed { position, label ->
                if(position % stride == 0) {
                    Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                        color = tickColor,
                        modifier = Modifier.width(labelWidth).offset { IntOffset(
                            (gutterPx + position * slot + slot / 2f - labelHalfPx).roundToInt(), 0) })
                }
            }
        }
    }
}

@Composable private fun PeriodBar(state: PeriodState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(onClick = { state.move(-1) }, modifier = Modifier.size(34.dp)) { AppIcon("left", "上一${state.scale.label}", modifier = Modifier.size(16.dp)) }
        Text(state.period.label, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
        FilledTonalIconButton(onClick = { state.move(1) }, enabled = state.canForward, modifier = Modifier.size(34.dp)) { AppIcon("right", "下一${state.scale.label}", modifier = Modifier.size(16.dp)) }
    }
    if(!state.atCurrent) TextButton(onClick = { state.reset() }, contentPadding = PaddingValues(0.dp)) { Text("回到当前${state.scale.label}", fontSize = 12.sp) }
}

@Composable private fun ScaleChips(scale: TrendScale, choose: (TrendScale) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrendScale.entries.forEach { option ->
            FilterChip(selected = scale == option, onClick = { choose(option) },
                label = { Text(option.label, fontSize = 13.sp) }, shape = RoundedCornerShape(50),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = scale == option, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable private fun ChipRow(labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, onClick = { choose(index) },
                label = { Text(label, fontSize = 13.sp) }, shape = RoundedCornerShape(50),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == index, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable private fun Readout(title: String?, value: String?, detail: String?, hint: String?) {
    if(title != null) Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: "未记录", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            detail?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    hint?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun TrendStat(label: String, value: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(value == null) "—" else "%.0f".format(value), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun energyValue(bucket: TrendBucket, series: Int): Double? = when(series) {
    0 -> bucket.intakeKcal
    1 -> bucket.activityKcal
    else -> bucket.referenceKcal?.let { it - bucket.intakeKcal }
}
/**
 * How many columns apart the axis labels sit, chosen from what each scale has to show rather than from
 * the pixel width: a week labels every day, a month every fifth day (1, 6, 11 …) and a year every other
 * month (1月, 3月 … 11月), which is what stays legible at the compressed widths.
 */
private fun labelStride(scale: TrendScale) = when(scale) {
    TrendScale.Week -> 1
    TrendScale.Month -> 5
    TrendScale.Year -> 2
}
private fun ticksAt(low: Double, top: Double, step: Double): List<Double> {
    val result = mutableListOf<Double>()
    var value = ceil(low / step) * step
    while(value <= top + step * 1e-6 && result.size < 8) { result += value; value += step }
    return result
}
private fun niceStep(raw: Double): Double {
    if(raw <= 0 || !raw.isFinite()) return 1.0
    val exponent = floor(log10(raw)); val base = 10.0.pow(exponent); val normalized = raw / base
    val factor = when { normalized <= 1 -> 1.0; normalized <= 2 -> 2.0; normalized <= 2.5 -> 2.5; normalized <= 5 -> 5.0; else -> 10.0 }
    return factor * base
}

/** A vertical axis: the range the plot shows and the grid lines worth labelling. */
private class Axis(val low: Double, val top: Double, val ticks: List<Double>)

/**
 * An axis that clears the data instead of touching it.
 *
 * The bounds are the data's own range plus [PAD] of headroom at the top, so the tallest bar or the
 * highest reading always sits inside the frame with a visible gap. The bounds are deliberately *not*
 * snapped to the tick grid: snapping is what forces a maximum of 1973 either to graze the top line or
 * to jump a whole step to 2500. The grid lines are then placed on readable numbers inside the frame.
 *
 * [keepLow] pins the floor to the data's own low value, which a bar chart needs - a bar measured from
 * anywhere but its zero baseline is no longer proportional to its value - while a line chart is
 * allowed to start below its lowest reading, or a two-kilogram change would be flattened into nothing.
 */
private fun axisFor(rawLow: Double, rawHigh: Double, minStep: Double, minSpan: Double, keepLow: Boolean): Axis {
    val span = (rawHigh - rawLow).takeIf { it > 1e-9 } ?: minSpan
    val low = if(keepLow) rawLow else rawLow - span * PAD
    val top = rawHigh + span * PAD
    var step = niceStep((top - low) / 4)
    if(step < minStep) step = minStep
    val ticks = ticksAt(low, top, step).ifEmpty { listOf(rawHigh) }
    return Axis(low, top, ticks)
}
private const val PAD = 0.08
private fun number(value: Double?) = if(value == null) "—" else "%.0f".format(value)
private fun signed(value: Double?) = if(value == null) "—" else (if(value >= 0) "+" else "−") + "%.0f".format(abs(value))
private fun format(value: Double, decimals: Int) = if(decimals <= 0) "%.0f".format(value) else "%.${decimals}f".format(value)
private fun energyAmount(series: Int, value: Double?) = when {
    value == null -> "未记录"
    series == 2 -> signed(value) + " 千卡"
    else -> number(value) + " 千卡"
}
