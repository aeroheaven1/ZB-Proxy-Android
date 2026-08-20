package com.zbproxy.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.zbproxy.android.App
import com.zbproxy.android.MainActivity
import com.zbproxy.android.R
import com.zbproxy.android.proxy.ProxyServer
import kotlinx.coroutines.*

class ProxyForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var proxyServer: ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        proxyServer = ProxyServer.getInstance(App.instance.configManager, App.instance.logCollector)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> serviceScope.launch { stopProxy() }
            else -> startProxy()
        }
        return START_STICKY
    }

    private fun startProxy() {
        startForeground(NOTIFICATION_ID, createNotification(false))

        // Acquire wake lock
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ZBProxy:WakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes timeout
        }

        serviceScope.launch {
            proxyServer?.start()
            updateNotification()
        }
    }

    private suspend fun stopProxy() {
        proxyServer?.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val status = proxyServer?.status
        val notification = createNotification(
            status?.isRunning == true,
            status?.activeConnections ?: 0
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(running: Boolean, connections: Int = 0): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle(
                if (running) getString(R.string.notif_running) else getString(R.string.notif_starting)
            )
            .setContentText(
                if (running && connections > 0)
                    getString(R.string.notif_active_conns, connections)
                else
                    getString(R.string.notif_service_active)
            )
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentIntent)
            .setOngoing(running)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_service),
                stopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch {
            proxyServer?.stop()
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.zbproxy.android.START_PROXY"
        const val ACTION_STOP = "com.zbproxy.android.STOP_PROXY"
        private const val NOTIFICATION_ID = 1001
    }
}