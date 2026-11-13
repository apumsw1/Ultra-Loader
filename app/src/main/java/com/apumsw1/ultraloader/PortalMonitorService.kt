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
        private const val CHANNEL_ID = "ultra_loader_wifi_status_v2"
        private const val NOTIFICATION_ID = 7101
        private const val OLD_NOTIFICATION_ID = 7102

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"
    }

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager

    private var callbackRegistered = false
    private var currentStatus = WifiStatus.CHECKING

    private enum class WifiStatus {
        CHECKING,
        SIGN_IN_REQUIRED,
        CONNECTED,
        DISCONNECTED
    }

    private val wifiCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val caps =
                    connectivityManager.getNetworkCapabilities(network)

                if (caps?.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    ) == true
                ) {
                    updateStatusFromCapabilities(caps)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (!capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                ) return

                updateStatusFromCapabilities(capabilities)
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

        // Remove the previous separate portal alert.
        notificationManager.cancel(OLD_NOTIFICATION_ID)

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
                "Ultra Loader Wi-Fi Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Automatically displays Wi-Fi login and connection status"
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
                "Checking Wi-Fi connection…"

            WifiStatus.SIGN_IN_REQUIRED ->
                "Tap to sign in"

            WifiStatus.CONNECTED ->
                "✓ Already connected • Internet available"

            WifiStatus.DISCONNECTED ->
                "Wi-Fi not connected"
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
                (if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                })

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
            .setSmallIcon(android.R.drawable.stat_sys_wifi)
            .setContentTitle("ULTRA LOADER • WI-FI")
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
            val active = connectivityManager.activeNetwork

            val caps = active?.let {
                connectivityManager.getNetworkCapabilities(it)
            }

            if (
                caps?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) == true
            ) {
                updateStatusFromCapabilities(caps)
            } else {
                updateWifiStatus(WifiStatus.DISCONNECTED)
            }

        } catch (_: Exception) {
            updateWifiStatus(WifiStatus.CHECKING)
        }
    }

    private fun updateStatusFromCapabilities(
        capabilities: NetworkCapabilities
    ) {

        val validated = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )

        val captivePortal = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
        )

        when {
            validated ->
                updateWifiStatus(WifiStatus.CONNECTED)

            captivePortal ->
                updateWifiStatus(WifiStatus.SIGN_IN_REQUIRED)

            else ->
                updateWifiStatus(WifiStatus.SIGN_IN_REQUIRED)
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
