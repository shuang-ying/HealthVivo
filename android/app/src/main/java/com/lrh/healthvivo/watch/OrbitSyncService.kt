package com.lrh.healthvivo.watch

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.lrh.healthvivo.HealthApplication
import com.lrh.healthvivo.MainActivity
import kotlinx.coroutines.*

class OrbitSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge get() = (application as HealthApplication).orbitBridge
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent == null && !bridge.connectionEnabled()) { stopSelf(); return START_NOT_STICKY }
        val channel = "orbitv_local_sync"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "手表本机同步", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, OrbitSyncService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(37, Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("活力记录 · 持续连接").setContentText("等待 OrbitV 与手表；仅手动更新时传输记录")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "结束同步", stop).build()).build())
        if(intent?.action == "stop") { bridge.setConnectionEnabled(false); stopSelf() } else {
            bridge.setConnectionEnabled(true)
            bridge.start(scope) { stopSelf() }
            if(intent?.action == "request_update" && bridge.status.value.active) bridge.requestUpdate()
            if(!bridge.status.value.active) stopSelf()
        }
        return if(intent?.action != "stop" && bridge.status.value.active) START_STICKY else START_NOT_STICKY
    }
    // Keep the preference so returning to the visible phone app can restore the connection.
    override fun onTimeout(startId: Int, fgsType: Int) { bridge.stop("系统暂停后台连接，打开活力记录即可恢复。"); stopSelf() }
    override fun onDestroy() { bridge.stop(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
