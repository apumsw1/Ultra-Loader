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
        private const val CHANNEL_ID = "ultra_loader_wifi_v2"
        private const val WIFI_ALERT_CHANNEL_ID = "ultra_loader_wifi_alert_v1"
        private const val NOTIFICATION_ID = 7101
        private const val WIFI_NOTIFICATION_ID = 7102

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        const val ACTION_REFRESH_NOTIFICATIONS =
            "com.apumsw1.ultraloader.REFRESH_NOTIFICATIONS"

        const val ACTION_PORTAL_NOTIFICATION_DETECTED =
            "com.apumsw1.ultraloader.PORTAL_NOTIFICATION_DETECTED"
    }

    private lateinit var connectivityManager: ConnectivityManager

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var callbackRegistered = false
    private var lastMonitorMessage: String? = null
    private var wifiNotificationVisible = false
    private var lastDetectedUrl: String? = null
    private var lastDetectionType: String? = null

    private val wifiNetworks = mutableSetOf<Network>()
    private val captiveNetworks = mutableSetOf<Network>()
    private val websiteNetworks = mutableSetOf<Network>()
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
            val monitorChannel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Loader Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Wi-Fi and portal monitoring"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                WIFI_ALERT_CHANNEL_ID,
                "Ultra Loader Wi-Fi Detection",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tap to open Ultra Loader and start automation when Wi-Fi connects"
                enableVibration(false)
                setSound(null, null)
            }

            notificationManager().createNotificationChannel(monitorChannel)
            notificationManager().createNotificationChannel(alertChannel)
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
            if (!lastDetectedUrl.isNullOrBlank()) {
                putExtra("detected_url", lastDetectedUrl)
            }
            if (!lastDetectionType.isNullOrBlank()) {
                putExtra("detection_type", lastDetectionType)
            }
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

        val requestCode = if (ongoing) NOTIFICATION_ID else WIFI_NOTIFICATION_ID
        val channelId = if (ongoing) CHANNEL_ID else WIFI_ALERT_CHANNEL_ID

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            pendingFlags
        )

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOnlyAlertOnce(ongoing)
            .setDefaults(0)
            .setSound(null)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(
                if (ongoing) Notification.PRIORITY_LOW
                else Notification.PRIORITY_DEFAULT
            )
            .build()
    }

    private fun registerWifiMonitor() {
        if (callbackRegistered) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback =
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {
                    wifiNetworks.add(network)
                    showWifiNotification()

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

        } catch (_: Exception) {
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
                    showWifiNotification()
                    evaluateNetwork(network, capabilities)
                }
            }

            updateMonitorNotification()

        } catch (_: Exception) {
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

        var capPortalUrl: String? = null
        var capVenueUrl: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val linkProps = connectivityManager.getLinkProperties(network)
                val capData = linkProps?.javaClass?.getMethod("getCaptivePortalData")?.invoke(linkProps)
                capPortalUrl = capData?.javaClass?.getMethod("getUserPortalUrl")?.invoke(capData)?.toString()
                capVenueUrl = capData?.javaClass?.getMethod("getVenueInfoUrl")?.invoke(capData)?.toString()
            } catch (_: Exception) {
            }
        }

        if (captive) {
            val newlyCaptive = captiveNetworks.add(network)
            websiteNetworks.remove(network)
            validatedNetworks.remove(network)
            val resolved = capPortalUrl ?: capVenueUrl
            if (!resolved.isNullOrBlank()) {
                saveLinkIfFresh(resolved, "PORTAL_LOGIN")
            }
            showWifiNotification(
                captiveDetected = true,
                forceRefresh = newlyCaptive,
                websiteDetected = false
            )
        } else {
            captiveNetworks.remove(network)

            val hasVenueWebsite = !capVenueUrl.isNullOrBlank()
            if (hasVenueWebsite) {
                val newlyWebsite = websiteNetworks.add(network)
                saveLinkIfFresh(capVenueUrl!!, "VIEW_WEBSITE")
                showWifiNotification(
                    captiveDetected = false,
                    forceRefresh = newlyWebsite,
                    websiteDetected = true
                )
            } else {
                websiteNetworks.remove(network)
            }

            if (validated) {
                validatedNetworks.add(network)
            } else {
                validatedNetworks.remove(network)
            }

            if (!hasVenueWebsite) {
                showWifiNotification(
                    captiveDetected = false,
                    forceRefresh = false,
                    websiteDetected = false
                )
            }
        }

        updateMonitorNotification()
    }

    private fun saveLinkIfFresh(url: String, type: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        lastDetectedUrl = url
        lastDetectionType = type
        try {
            val prefs = getSharedPreferences("wifi_notification_links", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastTime = prefs.getLong("latest_url_time", 0L)
            if (now - lastTime > 10_000L || prefs.getString("latest_url", null).isNullOrBlank()) {
                prefs.edit()
                    .putString("latest_url", url)
                    .putLong("latest_url_time", now)
                    .putString("pending_detection_type", type)
                    .apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun removeNetwork(network: Network) {
        wifiNetworks.remove(network)
        captiveNetworks.remove(network)
        websiteNetworks.remove(network)
        validatedNetworks.remove(network)

        if (wifiNetworks.isEmpty()) {
            lastDetectedUrl = null
            lastDetectionType = null
            clearWifiNotification()
        }

        updateMonitorNotification()
    }

    private fun updateMonitorNotification() {
        val message = when {
            captiveNetworks.isNotEmpty() ->
                "Captive Portal detected"

            websiteNetworks.isNotEmpty() ->
                "Connected website / portal detected"

            validatedNetworks.isNotEmpty() ->
                "Wi-Fi connected — internet available"

            wifiNetworks.isNotEmpty() ->
                "Wi-Fi connected — monitoring notifications"

            else ->
                "Waiting for Wi-Fi connection"
        }

        if (message == lastMonitorMessage) return

        lastMonitorMessage = message

        notificationManager().notify(
            NOTIFICATION_ID,
            buildNotification(
                "Ultra Loader Monitoring",
                message,
                true
            )
        )
    }

    private fun showWifiNotification(
        captiveDetected: Boolean = false,
        forceRefresh: Boolean = false,
        websiteDetected: Boolean = false
    ) {
        if (wifiNotificationVisible && !forceRefresh) return

        wifiNotificationVisible = true

        val title = when {
            websiteDetected -> "Ultra Loader — Connected Website Detected"
            captiveDetected -> "Ultra Loader — Wi-Fi Login Detected"
            else -> "Ultra Loader — Wi-Fi Detected"
        }

        val message = when {
            websiteDetected -> "Tap to view website detected. Opening Ultra Loader."
            captiveDetected -> "Wi-Fi login detected. Tap to open Ultra Loader & run."
            else -> "Wi-Fi connected. Tap to open Ultra Loader & run."
        }

        notificationManager().notify(
            WIFI_NOTIFICATION_ID,
            buildNotification(
                title,
                message,
                false
            )
        )
    }

    private fun clearWifiNotification() {
        if (!wifiNotificationVisible) return

        wifiNotificationVisible = false
        notificationManager().cancel(WIFI_NOTIFICATION_ID)
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
        when (intent?.action) {
            ACTION_REFRESH_NOTIFICATIONS -> {
                wifiNotificationVisible = false
                lastMonitorMessage = null
                refreshCurrentNetworks()
            }

            ACTION_PORTAL_NOTIFICATION_DETECTED -> {
                val detectedUrl = intent.getStringExtra("detected_url")
                val detectionType = intent.getStringExtra("detection_type")
                if (!detectedUrl.isNullOrBlank()) {
                    lastDetectedUrl = detectedUrl
                }
                if (!detectionType.isNullOrBlank()) {
                    lastDetectionType = detectionType
                }
                val isWebsite = detectionType == "VIEW_WEBSITE"
                showWifiNotification(
                    captiveDetected = !isWebsite,
                    forceRefresh = true,
                    websiteDetected = isWebsite
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wifiCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }

        callbackRegistered = false

        wifiNetworks.clear()
        captiveNetworks.clear()
        websiteNetworks.clear()
        validatedNetworks.clear()

        lastDetectedUrl = null
        lastDetectionType = null
        lastMonitorMessage = null
        wifiNotificationVisible = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
