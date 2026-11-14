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
        private const val CHANNEL_ID = "ultra_loader_portal_process"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"
        private const val EXTRA_PORTAL_URL = "portal_url"
        private const val PREFS = "ultra_loader_secure"
    }

    private lateinit var cm: ConnectivityManager
    private lateinit var nm: NotificationManager
    private var registered = false

    private enum class WifiStatus {
        CHECKING, DISCONNECTED, WIFI_AVAILABLE,
        CAPTIVE_PORTAL, INTERNET_AVAILABLE
    }

    private var currentStatus = WifiStatus.CHECKING

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                )
            ) return

            val state = when {
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                ) -> WifiStatus.CAPTIVE_PORTAL

                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) -> WifiStatus.INTERNET_AVAILABLE

                else -> WifiStatus.WIFI_AVAILABLE
            }

            update(state)
        }

        override fun onLost(network: Network) {
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()

        cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        nm = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        createChannel()

        startForeground(
            NOTIFICATION_ID,
            buildNotification(WifiStatus.CHECKING)
        )

        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                    .build(),
                callback
            )
            registered = true
        } catch (_: Exception) {
        }

        refresh()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ultra Loader Portal Process",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "Wi-Fi portal login, VPN and second login process"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun savedPortalUrl(): String? {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("last_portal_url", null)
            ?.takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            }
    }

    private fun buildNotification(state: WifiStatus): Notification {
        val detail = when (state) {
            WifiStatus.CHECKING ->
                "Checking Wi-Fi connection. Tap to open Ultra Loader."

            WifiStatus.DISCONNECTED ->
                "Wi-Fi disconnected. Waiting for a Wi-Fi network."

            WifiStatus.WIFI_AVAILABLE ->
                "Wi-Fi detected. Tap to open the portal page and start Login → VPN → Logout → Login."

            WifiStatus.CAPTIVE_PORTAL ->
                "Portal login may be required. Tap to open Ultra Loader and start the complete login process."

            WifiStatus.INTERNET_AVAILABLE ->
                "Wi-Fi is already connected. Tap to open the saved portal page and run the same complete process."
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PORTAL
            savedPortalUrl()?.let {
                putExtra(EXTRA_PORTAL_URL, it)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_IMMUTABLE
                } else 0

        val pending = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            flags
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ULTRA LOADER • FULL PORTAL PROCESS")
            .setContentText(detail)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "$detail\n\n" +
                    "1. Open the Peplink portal page.\n" +
                    "2. Complete the first login and Terms checkbox.\n" +
                    "3. Open Thunder VPN and connect.\n" +
                    "4. Return to the portal and sign out.\n" +
                    "5. Wait for the next login page.\n" +
                    "6. Complete the second login.\n" +
                    "7. Verify internet connectivity."
                )
            )
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun refresh() {
        try {
            val wifi = cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }

            if (wifi == null) {
                update(WifiStatus.DISCONNECTED)
                return
            }

            val caps = cm.getNetworkCapabilities(wifi)

            val state = when {
                caps == null -> WifiStatus.CHECKING

                caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                ) -> WifiStatus.CAPTIVE_PORTAL

                caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) -> WifiStatus.INTERNET_AVAILABLE

                else -> WifiStatus.WIFI_AVAILABLE
            }

            update(state)
        } catch (_: Exception) {
            update(WifiStatus.CHECKING)
        }
    }

    private fun update(state: WifiStatus) {
        if (currentStatus == state) return
        currentStatus = state
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            if (registered) cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        registered = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
