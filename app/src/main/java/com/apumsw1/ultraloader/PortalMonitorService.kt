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

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"
    }

    private lateinit var connectivityManager: ConnectivityManager

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var callbackRegistered = false

    private val captiveNetworks = mutableSetOf<Network>()
    private val wifiNetworks = mutableSetOf<Network>()
    private val validatedNetworks = mutableSetOf<Network>()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Ultra Loader Monitoring",
                "Wi-Fi monitoring is active",
                true
            )
        )

        registerWifiMonitor()
        refreshCurrentNetworks()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Loader Monitoring",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Wi-Fi and captive portal monitoring alerts"
                enableVibration(true)
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        message: String,
        ongoing: Boolean
    ): Notification {

        val openIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            action = ACTION_OPEN_PORTAL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val pendingIntent = PendingIntent.getActivity(
            this,
            WIFI_NOTIFICATION_ID,
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
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
    }

    private fun registerWifiMonitor() {
        if (callbackRegistered) return

        val request = NetworkRequest.Builder()
            .addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI
            )
            .build()

        val callback =
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    wifiNetworks.add(network)

                    evaluateNetwork(
                        network,
                        connectivityManager.getNetworkCapabilities(network)
                    )
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    evaluateNetwork(network, capabilities)
                }

                override fun onLost(network: Network) {
                    removeNetwork(network)
                }
            }

        try {
            connectivityManager.registerNetworkCallback(
                request,
                callback
            )

            wifiCallback = callback
            callbackRegistered = true

        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: RuntimeException) {
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshCurrentNetworks() {
        try {
            for (network in connectivityManager.allNetworks) {

                val capabilities =
                    connectivityManager.getNetworkCapabilities(network)

                if (
                    capabilities?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true
                ) {
                    wifiNetworks.add(network)
                    evaluateNetwork(network, capabilities)
                }
            }

            updateMonitorNotification()

        } catch (_: Exception) {
            // Network callback continues monitoring.
        }
    }

    private fun evaluateNetwork(
        network: Network,
        capabilities: NetworkCapabilities?
    ) {
        if (capabilities == null) return

        val isWifi = capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_WIFI
        )

        if (!isWifi) {
            removeNetwork(network)
            return
        }

        wifiNetworks.add(network)

        val captive = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
        )

        val validated = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )

        if (captive) {

            validatedNetworks.remove(network)

            if (captiveNetworks.add(network)) {
                showCaptivePortalNotification()
            }

        } else {

            captiveNetworks.remove(network)

            if (validated) {
                validatedNetworks.add(network)
            } else {
                validatedNetworks.remove(network)
            }

            if (captiveNetworks.isEmpty()) {
                notificationManager().cancel(
                    WIFI_NOTIFICATION_ID
                )
            }
        }

        updateMonitorNotification()
    }

    private fun removeNetwork(network: Network) {

        wifiNetworks.remove(network)
        captiveNetworks.remove(network)
        validatedNetworks.remove(network)

        if (captiveNetworks.isEmpty()) {
            notificationManager().cancel(
                WIFI_NOTIFICATION_ID
            )
        }

        updateMonitorNotification()
    }

    private fun updateMonitorNotification() {

        val message = when {

            captiveNetworks.isNotEmpty() ->
                "Captive Portal detected — login required"

            validatedNetworks.isNotEmpty() ->
                "Wi-Fi connected — internet available"

            wifiNetworks.isNotEmpty() ->
                "Wi-Fi connected — checking internet"

            else ->
                "Waiting for Wi-Fi connection"
        }

        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification(
                "Ultra Loader Monitoring",
                message,
                true
            )
        )
    }

    private fun showCaptivePortalNotification() {

        notificationManager().notify(
            WIFI_NOTIFICATION_ID,
            buildNotification(
                "Ultra Loader — Login Required",
                "Wi-Fi login page detected. Tap to open.",
                false
            )
        )
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        return START_STICKY
    }

    override fun onDestroy() {

        wifiCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(
                    callback
                )
            } catch (_: Exception) {
            }
        }

        callbackRegistered = false

        captiveNetworks.clear()
        wifiNetworks.clear()
        validatedNetworks.clear()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
