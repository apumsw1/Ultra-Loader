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
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class PortalMonitorService : Service() {

    companion object {
        private const val TAG = "PortalMonitorSvc"
        private const val CHANNEL_ID = "ultra_loader_wifi_v2"
        private const val WIFI_ALERT_CHANNEL_ID = "ultra_loader_wifi_alert_v1"
        private const val NOTIFICATION_ID = 7101
        private const val WIFI_NOTIFICATION_ID = 7102
        private const val VPN_RETURN_NOTIFICATION_ID = 7103

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        const val ACTION_REFRESH_NOTIFICATIONS =
            "com.apumsw1.ultraloader.REFRESH_NOTIFICATIONS"

        const val ACTION_PORTAL_NOTIFICATION_DETECTED =
            "com.apumsw1.ultraloader.PORTAL_NOTIFICATION_DETECTED"

        fun cancelVpnReturnNotification(context: Context) {
            try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(VPN_RETURN_NOTIFICATION_ID)
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var connectivityManager: ConnectivityManager

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null
    private var callbackRegistered = false
    private var vpnCallbackRegistered = false

    @Volatile
    private var lastMonitorMessage: String? = null

    @Volatile
    private var wifiNotificationVisible = false

    @Volatile
    private var lastDetectedUrl: String? = null

    @Volatile
    private var lastDetectionType: String? = null

    @Volatile
    private var sessionBroadcastSignature: String? = null

    @Volatile
    private var lastShownWifiNotifSignature: String? = null

    @Volatile
    private var activeWifiFingerprint: String? = null

    private val wifiNetworks: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())
    private val captiveNetworks: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())
    private val websiteNetworks: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())
    private val validatedNetworks: MutableSet<Network> = Collections.newSetFromMap(ConcurrentHashMap())

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        NetworkStatusManager.updateVpnConnectionState(PortalNetworkHelper.isVpnConnected(connectivityManager))

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Ultra Loader Monitoring",
                "Wi-Fi monitoring is active",
                true
            )
        )

        registerWifiMonitor()
        registerVpnMonitor()
        refreshCurrentNetworks(scanNotifications = true)
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
            putExtra("detected_at", System.currentTimeMillis())
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

    private fun registerVpnMonitor() {
        if (vpnCallbackRegistered) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                NetworkStatusManager.updateVpnConnectionState(true)
                handleConfirmedVpnWhileArmed()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                NetworkStatusManager.updateVpnConnectionState(isVpn)
                if (isVpn) {
                    handleConfirmedVpnWhileArmed()
                }
            }

            override fun onLost(network: Network) {
                val connected = PortalNetworkHelper.isVpnConnected(connectivityManager)
                NetworkStatusManager.updateVpnConnectionState(connected)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            vpnCallback = callback
            vpnCallbackRegistered = true
            NetworkStatusManager.updateVpnConnectionState(PortalNetworkHelper.isVpnConnected(connectivityManager))
        } catch (_: Exception) {
            vpnCallbackRegistered = false
        }
    }

    private fun handleConfirmedVpnWhileArmed() {
        if (!VpnAutomationBridge.isArmed() || VpnAutomationBridge.appInForeground) return
        if (!PortalNetworkHelper.isVpnConnected(connectivityManager)) return

        Log.i(TAG, "Confirmed OS VPN while armed; initiating return to Ultra Loader")
        val handledByAccessibility = VpnAutoConnectService.requestReturnToUltraLoader(this)
        if (!handledByAccessibility) {
            if (!VpnAutomationBridge.claimReturnToApp()) return
            VpnAutomationBridge.disarm()

            val fallbackIntent = Intent(this, MainActivity::class.java).apply {
                action = VpnAutomationBridge.ACTION_RETURN_FROM_VPN
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            try {
                startActivity(fallbackIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct return startActivity failed: ${e.message}")
            }
        }

        // Always post high-priority fallback notification while app is still backgrounded;
        // MainActivity.onResume cancels it immediately when foreground return succeeds.
        try {
            val returnIntent = Intent(this, MainActivity::class.java).apply {
                action = VpnAutomationBridge.ACTION_RETURN_FROM_VPN
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            val pi = PendingIntent.getActivity(
                this,
                VPN_RETURN_NOTIFICATION_ID,
                returnIntent,
                pendingFlags
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, WIFI_ALERT_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("VPN Connected")
                .setContentText("Returning to Ultra Loader")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
            notificationManager().notify(VPN_RETURN_NOTIFICATION_ID, notif)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshCurrentNetworks(scanNotifications: Boolean) {
        try {
            val wifiNet = PortalNetworkHelper.findWifiNetwork(connectivityManager)
            val fp = PortalNetworkHelper.getWifiSessionFingerprint(this, connectivityManager, wifiNet)
            if (!fp.isNullOrBlank()) {
                activeWifiFingerprint = fp
            }

            if (scanNotifications) {
                WifiNotificationListener.scanActiveNotificationsNow(notifyService = true)
            }

            val sessionCaptured = WifiNotificationListener.getSessionCapturedPortal(this, fp)
            if (sessionCaptured != null) {
                lastDetectedUrl = sessionCaptured.first
                lastDetectionType = sessionCaptured.second
            } else {
                val prefs = getSharedPreferences(WifiNotificationListener.PREFS, Context.MODE_PRIVATE)
                val recentType = prefs.getString("pending_detection_type", null)
                val recentAt = prefs.getLong("latest_notification_time", 0L)
                if (!recentType.isNullOrBlank() && System.currentTimeMillis() - recentAt <= 10 * 60_000L) {
                    lastDetectionType = recentType
                }
            }

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

        } catch (e: Exception) {
            Log.w(TAG, "refreshCurrentNetworks error: ${e.message}")
        }
    }

    private fun evaluateNetwork(
        network: Network,
        capabilities: NetworkCapabilities?
    ) {
        if (capabilities == null) return

        val isWifi = capabilities.hasTransport(
            NetworkCapabilities.TRANSPORT_WIFI
        ) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        if (!isWifi) {
            removeNetwork(network)
            return
        }

        wifiNetworks.add(network)

        val currentFp = PortalNetworkHelper.getWifiSessionFingerprint(this, connectivityManager, network)
        if (!currentFp.isNullOrBlank() &&
            !activeWifiFingerprint.isNullOrBlank() &&
            currentFp != activeWifiFingerprint
        ) {
            Log.i(TAG, "Wi-Fi changed ($activeWifiFingerprint -> $currentFp); purging stale portal state")
            lastDetectedUrl = null
            lastDetectionType = null
            sessionBroadcastSignature = null
            lastShownWifiNotifSignature = null
            captiveNetworks.clear()
            websiteNetworks.clear()
            validatedNetworks.clear()
            WifiNotificationListener.clearSessionState(this)
            NetworkStatusManager.resetPortalState()
            clearWifiNotification()
        }
        if (!currentFp.isNullOrBlank()) {
            activeWifiFingerprint = currentFp
        }

        val captive = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
        )

        val validated = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        )

        val (capPortalUrl, capVenueUrl) =
            PortalNetworkHelper.getCaptivePortalUrls(connectivityManager, network)

        if (captive) {
            val newlyCaptive = captiveNetworks.add(network)
            websiteNetworks.remove(network)
            validatedNetworks.remove(network)
            val resolved = capPortalUrl ?: capVenueUrl
            if (!resolved.isNullOrBlank()) {
                saveLinkIfFresh(resolved, WifiNotificationListener.TYPE_PORTAL_LOGIN)
            }
            NetworkStatusManager.updateDetectionType(
                WifiNotificationListener.TYPE_PORTAL_LOGIN,
                resolved ?: lastDetectedUrl
            )
            if (newlyCaptive || !resolved.isNullOrBlank()) {
                broadcastPortalOnce(resolved, WifiNotificationListener.TYPE_PORTAL_LOGIN)
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
                saveLinkIfFresh(capVenueUrl!!, WifiNotificationListener.TYPE_VIEW_WEBSITE)
                NetworkStatusManager.updateDetectionType(
                    WifiNotificationListener.TYPE_VIEW_WEBSITE,
                    capVenueUrl
                )
                if (newlyWebsite || sessionBroadcastSignature == null) {
                    broadcastPortalOnce(capVenueUrl, WifiNotificationListener.TYPE_VIEW_WEBSITE)
                }
                showWifiNotification(
                    captiveDetected = false,
                    forceRefresh = newlyWebsite,
                    websiteDetected = true
                )
            } else {
                websiteNetworks.remove(network)
                val stillDetected = captiveNetworks.isNotEmpty() || websiteNetworks.isNotEmpty() ||
                    lastDetectionType != null
                if (!stillDetected) {
                    NetworkStatusManager.updateCaptivePortalState(false, null)
                }
            }

            if (validated) {
                validatedNetworks.add(network)
            } else {
                validatedNetworks.remove(network)
            }

            if (!hasVenueWebsite) {
                showWifiNotification(
                    captiveDetected = lastDetectionType == WifiNotificationListener.TYPE_PORTAL_LOGIN,
                    forceRefresh = false,
                    websiteDetected = lastDetectionType == WifiNotificationListener.TYPE_VIEW_WEBSITE
                )
            }
        }

        updateMonitorNotification()
    }

    private fun saveLinkIfFresh(url: String, type: String) {
        if (!PortalNetworkHelper.isUsablePortalUrl(url)) return
        lastDetectedUrl = url
        lastDetectionType = type
        WifiNotificationListener.markSessionCaptured(this, url, type, activeWifiFingerprint)
    }

    @Synchronized
    private fun broadcastPortalOnce(url: String?, type: String) {
        val normalizedUrl = url?.trim().orEmpty()
        val sig = "$type|$normalizedUrl"

        // Do not rebroadcast identical type+URL, or overwrite a non-empty URL of the SAME type with empty.
        if (sessionBroadcastSignature == sig) return
        val prevParts = sessionBroadcastSignature?.split("|", limit = 2)
        if (prevParts != null && prevParts.size == 2) {
            val prevType = prevParts[0]
            val prevUrl = prevParts[1]
            if (prevType == type && prevUrl.isNotEmpty() && normalizedUrl.isEmpty()) return
            if (prevType == type && prevUrl == normalizedUrl && normalizedUrl.isNotEmpty()) return
        }

        sessionBroadcastSignature = sig
        try {
            val intent = Intent("com.apumsw1.ultraloader.WIFI_PORTAL_DETECTED").apply {
                setPackage(packageName)
                if (normalizedUrl.isNotEmpty()) {
                    putExtra("detected_url", normalizedUrl)
                }
                putExtra("detection_type", type)
                putExtra("detected_at", System.currentTimeMillis())
            }
            sendBroadcast(intent)
        } catch (_: Exception) {
        }
    }

    private fun removeNetwork(network: Network) {
        wifiNetworks.remove(network)
        captiveNetworks.remove(network)
        websiteNetworks.remove(network)
        validatedNetworks.remove(network)

        if (wifiNetworks.isEmpty()) {
            Log.i(TAG, "All Wi-Fi networks lost; clearing portal session state")
            lastDetectedUrl = null
            lastDetectionType = null
            activeWifiFingerprint = null
            sessionBroadcastSignature = null
            lastShownWifiNotifSignature = null
            WifiNotificationListener.clearSessionState(this)
            NetworkStatusManager.updateCaptivePortalState(false, null)
            clearWifiNotification()
        }

        updateMonitorNotification()
    }

    private fun updateMonitorNotification() {
        val message = when {
            captiveNetworks.isNotEmpty() || lastDetectionType == WifiNotificationListener.TYPE_PORTAL_LOGIN ->
                "PORTAL DETECTED"

            websiteNetworks.isNotEmpty() || lastDetectionType == WifiNotificationListener.TYPE_VIEW_WEBSITE ->
                "CONNECTED / TAP TO VIEW WEBSITE DETECTED"

            wifiNetworks.isNotEmpty() || validatedNetworks.isNotEmpty() ->
                "Monitoring Wi-Fi notifications"

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
        if (!captiveDetected && !websiteDetected) return
        val sig = "$websiteDetected|${lastDetectedUrl.orEmpty()}"
        if (wifiNotificationVisible && lastShownWifiNotifSignature == sig && !forceRefresh) return

        wifiNotificationVisible = true
        lastShownWifiNotifSignature = sig

        val title = if (websiteDetected) {
            "CONNECTED / TAP TO VIEW WEBSITE DETECTED"
        } else {
            "PORTAL DETECTED"
        }

        val message = "Tap to open Ultra Loader"

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
        lastShownWifiNotifSignature = null
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
                val alreadyCaptured = WifiNotificationListener.getSessionCapturedPortal(this) != null
                refreshCurrentNetworks(scanNotifications = !alreadyCaptured)
                if (wifiNotificationVisible) {
                    val isWebsite = lastDetectionType == WifiNotificationListener.TYPE_VIEW_WEBSITE
                    val isCaptive = lastDetectionType == WifiNotificationListener.TYPE_PORTAL_LOGIN ||
                        captiveNetworks.isNotEmpty()
                    if (isWebsite || isCaptive) {
                        showWifiNotification(
                            captiveDetected = isCaptive && !isWebsite,
                            forceRefresh = true,
                            websiteDetected = isWebsite
                        )
                    }
                }
            }

            ACTION_PORTAL_NOTIFICATION_DETECTED -> {
                val detectedUrl = intent.getStringExtra("detected_url")
                val detectionType = intent.getStringExtra("detection_type")
                if (!detectedUrl.isNullOrBlank() && PortalNetworkHelper.isUsablePortalUrl(detectedUrl)) {
                    lastDetectedUrl = detectedUrl
                }
                if (!detectionType.isNullOrBlank()) {
                    lastDetectionType = detectionType
                }
                val isWebsite = lastDetectionType == WifiNotificationListener.TYPE_VIEW_WEBSITE
                showWifiNotification(
                    captiveDetected = !isWebsite,
                    forceRefresh = false,
                    websiteDetected = isWebsite
                )
                updateMonitorNotification()
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

        vpnCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }

        callbackRegistered = false
        vpnCallbackRegistered = false

        wifiNetworks.clear()
        captiveNetworks.clear()
        websiteNetworks.clear()
        validatedNetworks.clear()

        lastDetectedUrl = null
        lastDetectionType = null
        sessionBroadcastSignature = null
        lastShownWifiNotifSignature = null
        lastMonitorMessage = null
        wifiNotificationVisible = false

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
