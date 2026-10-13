package com.lrh.healthvivo.watch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun WatchSyncPanel(bridge: WatchSyncBridge) {
    val state by bridge.status.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if(bridge.hasPermissions()) bridge.enable(true) else bridge.permissionDenied()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("手表同步", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("iQOO WATCH GT2 · BlueOS 3", color = muted, fontSize = 12.sp)
                }
                Switch(checked = state.enabled, onCheckedChange = { enabled ->
                    if(!enabled || bridge.hasPermissions()) bridge.enable(enabled) else permission.launch(bridge.permissions())
                }, enabled = state.configured)
            }
            Text("蓝牙直连手表应用，同步今日步数、运动、热量、三餐与 AI 建议。手机保存记录或更新建议后自动发送，手表保留离线缓存。", fontSize = 13.sp, color = muted)
            Text(state.message, fontSize = 13.sp)
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.pairingCode.isNotBlank()) {
                Text("确认码 " + state.pairingCode, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("两端代码一致时，在手表允许连接。", fontSize = 12.sp, color = muted)
            }
            if(!state.busy && !state.ready) state.devices.forEach { device ->
                OutlinedButton(onClick = { bridge.connect(device.address) }, modifier = Modifier.fillMaxWidth()) { Text(device.name) }
            }
            if(state.lastSavedAt > 0) Text("最近确认：${state.lastSavedDay} · ${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.lastSavedAt))}", fontSize = 12.sp, color = muted)
            OutlinedButton(onClick = {
                if(bridge.hasPermissions()) bridge.sync() else permission.launch(bridge.permissions())
            }, enabled = state.enabled && !state.busy && !state.scanning) { Text(if(state.ready) "同步今日数据" else if(state.scanning) "正在搜索…" else "搜索附近手表") }
            if(state.ready || state.busy) TextButton(onClick = bridge::disconnect) { Text("断开连接") }
            Text("手机和手表均需更新到配套直连版本，并保持手表应用在前台。应用数据不经过 vivo 健康；安装 RPK 仍可使用其安装入口。仅收到手表保存确认后显示完成，不传输照片或 API 密钥。", fontSize = 12.sp, color = muted)
        }
    }
}
