package com.lrh.healthvivo.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.lrh.healthvivo.HealthApplication
import com.lrh.healthvivo.MainActivity

/**
 * A `connectedDevice` foreground service that holds the BLE client.
 *
 * The service exists because Android will not let a background app keep a GATT connection alive.
 * It owns nothing itself: it declares the foreground type, then hands control to [LinkManager],
 * whose coroutine is what actually persists across screen-off and app-switching.
 *
 * There is no dataSync job and no local HTTP server here — the link only moves bytes when the user
 * asked for an update, or when the watch has queued one.
 */
class LinkService : Service() {
    private val manager get() = (application as HealthApplication).link

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!manager.status.value.enabled || !manager.hasPermissions()) { stopSelf(); return START_NOT_STICKY }
        val channel = "vitality_link_watch"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "手表蓝牙连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("活力记录 · 手表直连").setContentText("保持蓝牙连接；仅手动更新时传输记录")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "关闭连接", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) { manager.stopPersistent(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) { manager.enable(false); stopSelf(); return START_NOT_STICKY }
        manager.startPersistent()
        // START_STICKY lets the system recreate the service after a low-memory kill; startPersistent
        // is idempotent, so a restart simply resumes the reconnect loop.
        return START_STICKY
    }

    override fun onDestroy() {
        manager.stopPersistent()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.lrh.healthvivo.link.STOP"
        const val NOTIFICATION_ID = 38
    }
}
