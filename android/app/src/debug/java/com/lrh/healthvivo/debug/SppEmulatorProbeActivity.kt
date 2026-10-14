package com.lrh.healthvivo.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView

/** Virtual Bluetooth diagnostic. It cannot be started on a physical phone. */
class SppEmulatorProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { textSize = 18f; setPadding(32, 64, 32, 32) }
        setContentView(text)
        if (Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") {
            text.text = "此诊断只允许在 Android 模拟器运行。"
            return
        }
        val service = Intent(this, SppEmulatorProbeService::class.java)
        if (intent.getBooleanExtra("stop", false)) {
            stopService(service)
            text.text = "虚拟 SPP 诊断已停止。"
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            text.text = "请先为模拟器诊断授予附近设备权限。"
            return
        }
        service.putExtra("duration_seconds", intent.getIntExtra("duration_seconds", 60).coerceIn(15, 300))
        service.putExtra("peer_address", intent.getStringExtra("peer_address") ?: "F0:F1:F2:F3:F4:F5")
        service.putExtra("run_id", intent.getStringExtra("run_id") ?: java.util.UUID.randomUUID().toString())
        startForegroundService(service)
        text.text = "独立 App 虚拟 SPP 诊断运行中\n\n可退到桌面检查后台收发。\n只发送合成 PING/PONG，不使用 OrbitV、扩展程序或健康记录。\n\n对端是 Bumble 虚拟设备，不是 GT2 固件。"
    }
}
