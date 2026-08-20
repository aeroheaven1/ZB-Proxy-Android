package com.zbproxy.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.zbproxy.android.proxy.ConfigManager
import com.zbproxy.android.util.LogCollector

class App : Application() {

    lateinit var configManager: ConfigManager
        private set
    lateinit var logCollector: LogCollector
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager(this)
        logCollector = LogCollector()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(serviceChannel)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val logChannel = NotificationChannel(
                CHANNEL_LOGS,
                getString(R.string.notification_channel_logs),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Proxy log notifications"
                setShowBadge(false)
            }
            nm.createNotificationChannel(logChannel)
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "zbproxy_service"
        const val CHANNEL_LOGS = "zbproxy_logs"

        lateinit var instance: App
            private set
    }
}