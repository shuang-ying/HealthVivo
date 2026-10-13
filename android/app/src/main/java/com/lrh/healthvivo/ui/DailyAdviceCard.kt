package com.lrh.healthvivo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lrh.healthvivo.ai.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf

@Composable fun DailyAdviceCard(snapshot: AdviceSnapshot, api: ApiSettings, coordinator: DailyAdviceCoordinator?, preview: SavedAdvice? = null, configure: () -> Unit) {
    val initial = remember(snapshot.day) { preview ?: coordinator?.load(snapshot.day) }
    val stateFlow = remember(coordinator) { coordinator?.states ?: flowOf(emptyMap<String, AdviceState>()) }
    val states by stateFlow.collectAsStateWithLifecycle(emptyMap())
    val state = states[snapshot.day] ?: AdviceState(initial)
    val saved = state.saved
    val busy = state.busy
    val error = state.error
    val configured = runCatching { api.validate() }.isSuccess
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(if(LocalHomeReordering.current) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(end = if(LocalHomeReordering.current) 36.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon("sparkles", tint = MaterialTheme.colorScheme.primary)
                Text("AI 每日建议", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
            saved?.let { result ->
                Text("吃饭建议", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(result.advice.food, fontSize = 14.sp)
                Text("锻炼建议", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(result.advice.exercise, fontSize = 14.sp)
                Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(result.generatedAt)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(result.fingerprint != snapshot.fingerprint && !busy) Text("记录已更新，可重新生成。", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            } ?: Text("结合身体数据、目标、已经吃的餐食和热量，建议下一餐吃什么、份量和运动安排。", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if(state.status.isNotBlank()) Text(state.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if(!configured) configure()
                else coordinator?.request(setOf(snapshot.day), force = true)
            }, enabled = !busy && coordinator != null, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Text(if(!configured) "配置 AI" else if(busy) "正在生成…" else if(saved == null) "生成每日建议" else "重新生成建议")
            }
        }
    }
}
