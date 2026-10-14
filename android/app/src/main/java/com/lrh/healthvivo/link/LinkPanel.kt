package com.lrh.healthvivo.link

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The connection card in "我的 → 手表直连". */
@Composable fun LinkPanel(manager: LinkManager) {
    val state by manager.status.collectAsStateWithLifecycle()
    var afterPermission by remember { mutableStateOf("connect") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (manager.hasPermissions()) {
            when (afterPermission) { "scan" -> manager.scan(); "sync" -> manager.sync(); else -> manager.enable(true) }
        } else manager.permissionDenied()
    }
    fun allowed(action: String, work: () -> Unit) {
        if (manager.hasPermissions()) work() else { afterPermission = action; permission.launch(manager.permissions()) }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("手表直连", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("iQOO WATCH GT2 · BlueOS 3 · 连接持久化", color = muted, fontSize = 12.sp)
                }
                Switch(checked = state.enabled, onCheckedChange = { enabled ->
                    if (enabled) allowed("connect") { manager.enable(true) } else manager.enable(false)
                })
            }
            Surface(color = if (state.ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (state.ready) "已连接" else if (state.enabled) "等待连接" else "连接已关闭", fontWeight = FontWeight.SemiBold)
                    if (state.selectedName.isNotBlank()) Text(state.selectedName, fontSize = 12.sp, color = muted)
                    Text(state.message, fontSize = 13.sp)
                    if (state.pending) Text("有手动更新请求等待完成", fontSize = 12.sp, color = muted)
                    if (state.stage == "reconnecting" && state.attempts > 0) Text("已重连尝试 ${state.attempts} 次", fontSize = 12.sp, color = muted)
                }
            }
            Text("连接由前台服务持有，断线按 1、2、4、8 秒递增重试，最高 30 秒；手表端有广播看门狗，" +
                "手机随时可以重新连上。连接本身不传输记录。", fontSize = 13.sp, color = muted)
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (!state.busy) state.devices.forEach { device ->
                OutlinedButton(onClick = { manager.connect(device.address) }, modifier = Modifier.fillMaxWidth()) { Text(device.name) }
            }
            OutlinedButton(onClick = { allowed("scan") { manager.scan() } }, enabled = !state.scanning && !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.scanning) "正在搜索…" else "搜索手表") }
            Button(onClick = { allowed("sync") { manager.sync() } }, enabled = state.selectedName.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在更新…" else "更新到手表") }
            if (state.selectedName.isNotBlank()) {
                TextButton(onClick = { manager.probe() }, enabled = state.ready && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("连接自检（向手表发送一次加密往返）")
                }
            }
            if (state.probeResult.isNotBlank()) Text(state.probeResult, fontSize = 12.sp, color = muted)
            if (state.lastSavedAt > 0) Text("最近保存确认：${DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.lastSavedAt))}", fontSize = 12.sp, color = muted)
            Text("打开手表活力记录，搜索并点击手表即可连接，无需输入代码。连接信息以 Keystore 包装保存在手机，" +
                "首次连接时自动发送给手表。", fontSize = 12.sp, color = muted)
            Text("同步不需要扩展程序或 vivo 健康。手表保存后可离线查询，照片和 API 密钥不会传到手表。", fontSize = 12.sp, color = muted)
        }
    }
}
