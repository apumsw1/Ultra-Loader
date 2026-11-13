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
        private const val CHANNEL_ID = "ultra_loader_wifi_status_v3"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"
    }

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager

    private var callbackRegistered = false

    private enum class WifiStatus {
        CHECKING,
        WIFI_AVAILABLE,
        DISCONNECTED
    }

    private var currentStatus = WifiStatus.CHECKING

    private val wifiCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                updateWifiStatus(WifiStatus.WIFI_AVAILABLE)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (
                    capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                ) {
                    // Connected/Validated মানেই Portal Login শেষ নয়।
                    updateWifiStatus(WifiStatus.WIFI_AVAILABLE)
                }
            }

            override fun onLost(network: Network) {
                refreshCurrentWifiStatus()
            }
        }

    override fun onCreate() {
        super.onCreate()

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            buildStatusNotification(WifiStatus.CHECKING)
        )

        registerWifiMonitor()
        refreshCurrentWifiStatus()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Loader Wi-Fi Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Wi-Fi detection and portal access"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(
        status: WifiStatus
    ): Notification {

        val message = when (status) {
            WifiStatus.CHECKING ->
                "Checking network • Tap to view"

            WifiStatus.WIFI_AVAILABLE ->
                "Wi-Fi detected • Tap to continue"

            WifiStatus.DISCONNECTED ->
                "Wi-Fi not detected • Waiting for network"
        }

        val openIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            action = ACTION_OPEN_PORTAL
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
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
            NOTIFICATION_ID,
            openIntent,
            pendingFlags
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ULTRA LOADER • NETWORK")
            .setContentText(message)
            .setStyle(
                Notification.BigTextStyle().bigText(message)
            )
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun registerWifiMonitor() {
        if (callbackRegistered) return

        val request = NetworkRequest.Builder()
            .addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI
            )
            .build()

        try {
            connectivityManager.registerNetworkCallback(
                request,
                wifiCallback
            )
            callbackRegistered = true
        } catch (_: Exception) {
            updateWifiStatus(WifiStatus.CHECKING)
        }
    }

    private fun refreshCurrentWifiStatus() {
        try {
            // VPN active থাকলেও Wi-Fi network আলাদাভাবে খুঁজবে।
            val wifiExists = connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true
            }

            updateWifiStatus(
                if (wifiExists) {
                    WifiStatus.WIFI_AVAILABLE
                } else {
                    WifiStatus.DISCONNECTED
                }
            )

        } catch (_: Exception) {
            updateWifiStatus(WifiStatus.CHECKING)
        }
    }

    private fun updateWifiStatus(status: WifiStatus) {
        if (currentStatus == status) return

        currentStatus = status

        notificationManager.notify(
            NOTIFICATION_ID,
            buildStatusNotification(status)
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        refreshCurrentWifiStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (callbackRegistered) {
                connectivityManager.unregisterNetworkCallback(
                    wifiCallback
                )
            }
        } catch (_: Exception) {
        }

        callbackRegistered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
