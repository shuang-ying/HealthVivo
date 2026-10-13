package com.lrh.healthvivo.watch

import android.content.*
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun OrbitSyncPanel(bridge: OrbitLocalBridge) {
    val context = LocalContext.current
    val state by bridge.status.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("通过 OrbitV 同步手表", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("iQOO WATCH GT2 · 本机传输", color = muted, fontSize = 12.sp)
            Text(state.message, fontSize = 13.sp)
            Text("本机接口：${if(state.active) "已开启" else "已关闭"}\nOrbitV 扩展：${if(state.extensionConnected) "已连接" else "等待连接"}\n手表蓝牙通道：${if(state.transportReady) "就绪" else "未就绪"}\n手表应用：${if(state.watchAppConnected) "已连接 ${state.watchVersion}" else "等待打开"}", fontSize = 12.sp, color = muted)
            if(state.lastSavedAt > 0) Text("手表最近确认：${state.lastSavedDay}", fontSize = 12.sp, color = muted)
            Button(onClick = { ContextCompat.startForegroundService(context, Intent(context, OrbitSyncService::class.java)) }) {
                Text(if(state.active) "持续连接已开启" else "开启持续连接")
            }
            Button(onClick = {
                ContextCompat.startForegroundService(context, Intent(context, OrbitSyncService::class.java).setAction("request_update"))
                context.packageManager.getLaunchIntentForPackage("com.yyh.orbitv")?.let(context::startActivity)
            }) { Text("更新到手表") }
            OutlinedButton(onClick = {
                val clip = ClipData.newPlainText("活力记录同步配对", bridge.pairingConfiguration())
                if(Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); copied = true
            }) { Text(if(copied) "已复制配对信息" else "复制首次配对信息") }
            TextButton(onClick = {
                val launch = context.packageManager.getLaunchIntentForPackage("com.yyh.orbitv")
                if(launch != null) context.startActivity(launch)
                else android.widget.Toast.makeText(context, "请先安装 OrbitV，并导入活力记录同步扩展包", android.widget.Toast.LENGTH_LONG).show()
            }) { Text("打开 OrbitV") }
            Text("配对保存后自动检查连接，断线后重新检测；不再每 15 分钟关闭接口。请保持 OrbitV 扩展程序与手表应用打开。点击更新才同步新增和修改的日期；未连接时请求会等待。系统暂停后台服务后，回到本 App 即恢复连接。", color = muted, fontSize = 12.sp)
            Text("只同步显示摘要，包含体重、BMI、体脂率、步数、运动、热量、三餐和建议。摘要交给手机上的 OrbitV 与手表，不含照片或 API 密钥。配对信息包含访问令牌，仅粘贴到此扩展程序。", color = muted, fontSize = 12.sp)
            if(state.active || bridge.connectionEnabled()) TextButton(onClick = { bridge.setConnectionEnabled(false); context.stopService(Intent(context, OrbitSyncService::class.java)) }) { Text("关闭持续连接") }
        }
    }
}
