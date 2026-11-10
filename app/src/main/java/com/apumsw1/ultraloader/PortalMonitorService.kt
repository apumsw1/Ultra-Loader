package com.apumsw1.ultraloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder

class PortalMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "ultra_loader_wifi"
        private const val NOTIFICATION_ID = 7101
        private const val WIFI_NOTIFICATION_ID = 7102
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var callbackRegistered = false
    private var lastWifiNetwork: Network? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Ultra Loader is monitoring Wi-Fi",
                "Wi-Fi monitoring is active"
            )
        )

        registerWifiMonitor()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Loader Wi-Fi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wi-Fi connection alerts"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        message: String
    ): Notification {
        val openIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            action = "com.apumsw1.ultraloader.OPEN_PORTAL"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val pendingIntent = PendingIntent.getActivity(
            this,
            7102,
            openIntent,
            pendingFlags
        )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(title.contains("monitoring", true))
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
    }

    private fun registerWifiMonitor() {
        if (callbackRegistered) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val caps =
                    connectivityManager.getNetworkCapabilities(network)

                val isWifi =
                    caps?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true

                if (!isWifi || network == lastWifiNetwork) return

                lastWifiNetwork = network
                showWifiNotification()
            }

            override fun onLost(network: Network) {
                if (network == lastWifiNetwork) {
                    lastWifiNetwork = null
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(
                request,
                callback
            )
            callbackRegistered = true

            // Keep the callback alive for the service lifetime.
            wifiCallback = callback
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private var wifiCallback:
        ConnectivityManager.NetworkCallback? = null

    private fun showWifiNotification() {
        val notification = buildNotification(
            "Ultra Loader - Wi-Fi Connected",
            "TAP TO VIEW"
        )

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        manager.notify(WIFI_NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            wifiCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {
        }

        callbackRegistered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}