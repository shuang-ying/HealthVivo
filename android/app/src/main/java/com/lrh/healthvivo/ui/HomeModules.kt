package com.lrh.healthvivo.ui

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

val LocalHomeReordering = staticCompositionLocalOf { false }
data class HomeModule(val id: String, val title: String, val content: @Composable () -> Unit)
private data class ModulePosition(val top: Float, val height: Float)

@Composable fun HomeModules(modules: List<HomeModule>, modifier: Modifier = Modifier, persistOrder: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("home_layout", Context.MODE_PRIVATE) }
    val defaults = modules.map { it.id }
    var order by remember {
        val saved = (if(persistOrder) preferences.getString("order", "").orEmpty() else "").split(",").filter { it in defaults }.distinct().toMutableList()
        // The old steps module contained weight/BMI. Split it at the same position without resetting other cards.
        if("weight_bmi" in defaults && "weight_bmi" !in saved && "metrics" in saved) saved.add(saved.indexOf("metrics") + 1, "weight_bmi")
        mutableStateOf(saved + defaults.filterNot(saved::contains))
    }
    val scroll = rememberScrollState()
    val positions = remember { mutableStateMapOf<String, ModulePosition>() }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var movement by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var originalOrder by remember { mutableStateOf(order) }
    fun persist() { if(persistOrder) preferences.edit().putString("order", order.joinToString(",")).apply() }
    fun move(from: Int, to: Int) {
        if(from in order.indices && to in order.indices && from != to) order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun reorder() {
        val id = dragged ?: return
        val item = positions[id] ?: return
        val center = origin + movement + item.height / 2f
        val from = order.indexOf(id)
        val target = order.filter { other -> other != id && positions[other]?.let { position ->
            val targetCenter = position.top - viewportTop + position.height / 2f
            (order.indexOf(other) > from && center > targetCenter) || (order.indexOf(other) < from && center < targetCenter)
        } == true }.minByOrNull { other -> kotlin.math.abs(center - (positions.getValue(other).top - viewportTop + positions.getValue(other).height / 2f)) }
        target?.let { move(from, order.indexOf(it)) }
    }
    LaunchedEffect(dragged) {
        if(dragged == null) return@LaunchedEffect
        while(isActive && dragged != null) {
            val edge = 100f
            val speed = when {
                pointerY < edge -> -((edge - pointerY) / 8).coerceAtMost(30f)
                pointerY > viewportHeight - edge -> ((pointerY - viewportHeight + edge) / 8).coerceAtMost(30f)
                else -> 0f
            }
            if(speed != 0f) scroll.scrollBy(speed)
            reorder()
            delay(16)
        }
    }
        // Use the entire content viewport; controls are contained in each module.
        Column(modifier.fillMaxWidth().onGloballyPositioned { viewportTop = it.positionInWindow().y; viewportHeight = it.size.height.toFloat() }
            .verticalScroll(scroll).padding(top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            order.forEach { id -> key(id) {
                val module = modules.first { it.id == id }
                val active = dragged == id
                val offset = (positions[id]?.top ?: viewportTop) - viewportTop
                Box(Modifier.fillMaxWidth().zIndex(if(active) 1f else 0f).onGloballyPositioned { positions[id] = ModulePosition(it.positionInWindow().y, it.size.height.toFloat()) }) {
                Box(Modifier.fillMaxWidth().graphicsLayer {
                    translationY = if(active) origin + movement - offset else 0f
                    alpha = if(active) .92f else 1f
                }) {
                    CompositionLocalProvider(LocalHomeReordering provides true) { module.content() }
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(40.dp)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("上移${module.title}") { val i = order.indexOf(id); if(i > 0) { move(i, i - 1); persist(); true } else false },
                                CustomAccessibilityAction("下移${module.title}") { val i = order.indexOf(id); if(i < order.lastIndex) { move(i, i + 1); persist(); true } else false }
                            )
                        }.pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { point ->
                                    val item = positions[id]
                                    if(item != null) { originalOrder = order; origin = item.top - viewportTop; movement = 0f; pointerY = origin + point.y + 6.dp.toPx(); dragged = id }
                                },
                                onDrag = { change, amount -> change.consume(); movement += amount.y; pointerY += amount.y; reorder() },
                                onDragEnd = { dragged = null; movement = 0f; persist() },
                                onDragCancel = { order = originalOrder; dragged = null; movement = 0f }
                            )
                        }, contentAlignment = Alignment.Center) {
                        AppIcon("drag", "拖动${module.title}调整位置", modifier = Modifier.size(20.dp), tint = if(id == "intake") Color.White.copy(alpha = .75f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
            } }
        }
}
