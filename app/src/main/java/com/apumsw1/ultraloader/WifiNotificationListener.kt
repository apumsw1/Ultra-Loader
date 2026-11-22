package com.apumsw1.ultraloader

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.URI
import java.util.Locale

class WifiNotificationListener : NotificationListenerService() {

    companion object {
        private const val PREFS = "wifi_notification_links"

        private val URL_PATTERN = Regex(
            """https?://[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        )

        @Volatile
        private var activeInstance: WifiNotificationListener? = null

        fun isConnected(): Boolean = activeInstance != null

        fun scanActiveNotificationsNow(notifyService: Boolean = false): Boolean {
            val instance = activeInstance ?: return false
            return try {
                instance.scanAllActiveNotifications(notifyService)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private var lastServiceUpdateSignature: String = ""
    private var lastServiceUpdateAt: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        try {
            scanAllActiveNotifications(notifyService = true)
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    fun scanAllActiveNotifications(notifyService: Boolean = false) {
        if (!isWifiConnected()) return
        val active = try {
            activeNotifications
        } catch (_: Exception) {
            null
        } ?: return

        var latestDetection: Pair<String?, String>? = null
        // Process oldest to newest so the newest notification becomes latest_url
        active.sortedBy { it.postTime }.forEach { sbn ->
            val matched = processStatusBarNotification(sbn, notifyMonitorService = false)
            if (matched != null) {
                latestDetection = matched
            }
        }

        if (notifyService && latestDetection != null) {
            notifyMonitorService(latestDetection!!.first, latestDetection!!.second)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        processStatusBarNotification(sbn, notifyMonitorService = true)
    }

    private fun processStatusBarNotification(
        sbn: StatusBarNotification,
        notifyMonitorService: Boolean
    ): Pair<String?, String>? {
        if (sbn.packageName == packageName) return null
        if (!isWifiConnected()) return null

        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle.EMPTY
        val texts = mutableListOf<String>()
        val extraUrls = mutableListOf<String>()

        fun addText(value: CharSequence?) {
            if (!value.isNullOrBlank()) {
                texts.add(value.toString())
            }
        }

        addText(extras.getCharSequence(Notification.EXTRA_TITLE))
        addText(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
        addText(extras.getCharSequence(Notification.EXTRA_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        addText(notification.tickerText)

        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.forEach { addText(it) }

        notification.publicVersion?.extras?.let { pubExtras ->
            addText(pubExtras.getCharSequence(Notification.EXTRA_TITLE))
            addText(pubExtras.getCharSequence(Notification.EXTRA_TEXT))
            collectBundleValues(pubExtras, texts, extraUrls, depth = 0)
        }

        notification.actions?.forEach { action ->
            addText(action?.title)
            action?.extras?.let { collectBundleValues(it, texts, extraUrls, depth = 0) }
        }

        collectBundleValues(extras, texts, extraUrls, depth = 0)

        val combinedText = texts.joinToString("\n")
        val lowerText = combinedText.lowercase(Locale.ROOT)

        val textUrls = URL_PATTERN.findAll(combinedText)
            .map {
                it.value.trimEnd('.', ',', ';', ')', ']', '}', '!', '?')
            }
            .filter { isValidHttpUrl(it) }
            .toList()

        val urls = (textUrls + extraUrls.filter { isValidHttpUrl(it) })
            .distinct()

        val systemWifiPkg = isWifiSystemPackage(sbn.packageName)
        val hasWifiKeywords =
            lowerText.contains("wi-fi") ||
                lowerText.contains("wifi") ||
                lowerText.contains("captive") ||
                lowerText.contains("portal") ||
                lowerText.contains("network")

        val isViewWebsiteNotification =
            lowerText.contains("tap to view website") ||
                lowerText.contains("view website") ||
                lowerText.contains("connected / tap to view") ||
                ((systemWifiPkg || hasWifiKeywords) &&
                    (lowerText.contains("tap to view") ||
                        (lowerText.contains("connected") && lowerText.contains("website"))))

        val isLoginPortalNotification =
            lowerText.contains("sign in to wi-fi") ||
                lowerText.contains("sign in to wifi") ||
                lowerText.contains("sign in to network") ||
                lowerText.contains("captive portal") ||
                lowerText.contains("network may require") ||
                lowerText.contains("authorization required") ||
                ((systemWifiPkg || hasWifiKeywords) &&
                    (lowerText.contains("sign in") ||
                        lowerText.contains("log in") ||
                        lowerText.contains("login") ||
                        lowerText.contains("portal")))

        if (!isViewWebsiteNotification && !isLoginPortalNotification && urls.isEmpty()) {
            return null
        }

        if (!systemWifiPkg && !hasWifiKeywords && !isViewWebsiteNotification && !isLoginPortalNotification) {
            return null
        }

        val detectionType = if (isViewWebsiteNotification) "VIEW_WEBSITE" else "PORTAL_LOGIN"
        val capportUrl = getCaptivePortalUrlFromNetwork(preferVenue = (detectionType == "VIEW_WEBSITE"))
        val resolvedUrl = urls.lastOrNull() ?: capportUrl
        val eventTime = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (!resolvedUrl.isNullOrBlank()) {
            val allDetected = (urls + listOfNotNull(capportUrl)).distinct()
            val oldUrls = prefs.getString("url_history", "")
                .orEmpty()
                .split("\n")
                .filter { it.isNotBlank() }

            val history = (allDetected.reversed() + oldUrls)
                .distinct()
                .take(30)

            editor
                .putString("latest_url", resolvedUrl)
                .putLong("latest_url_time", System.currentTimeMillis())
                .putString("url_history", history.joinToString("\n"))
        }

        editor
            .putString("latest_package", sbn.packageName)
            .putString(
                "latest_title",
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            )
            .putString("pending_detection_type", detectionType)
            .putLong("latest_notification_time", eventTime)
            .apply()

        if (notifyMonitorService) {
            notifyMonitorService(resolvedUrl, detectionType)
        }

        return Pair(resolvedUrl, detectionType)
    }

    private fun notifyMonitorService(resolvedUrl: String?, detectionType: String) {
        val now = System.currentTimeMillis()
        val signature = "$detectionType|${resolvedUrl.orEmpty()}"
        if (signature == lastServiceUpdateSignature && now - lastServiceUpdateAt < 10_000L) {
            return
        }
        lastServiceUpdateSignature = signature
        lastServiceUpdateAt = now

        try {
            val serviceIntent = Intent(this, PortalMonitorService::class.java).apply {
                action = PortalMonitorService.ACTION_PORTAL_NOTIFICATION_DETECTED
                putExtra("detected_url", resolvedUrl)
                putExtra("detection_type", detectionType)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun collectBundleValues(
        bundle: Bundle,
        texts: MutableList<String>,
        urls: MutableList<String>,
        depth: Int
    ) {
        if (depth > 3) return
        try {
            for (key in bundle.keySet()) {
                val value = bundle.get(key) ?: continue
                when (value) {
                    is String -> {
                        if (isValidHttpUrl(value)) urls.add(value)
                    }
                    is Uri -> {
                        val s = value.toString()
                        if (isValidHttpUrl(s)) urls.add(s)
                    }
                    is Bundle -> {
                        collectBundleValues(value, texts, urls, depth + 1)
                    }
                    is CharSequence -> {
                        if (value.isNotBlank()) texts.add(value.toString())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun getCaptivePortalUrlFromNetwork(preferVenue: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = manager.allNetworks.firstOrNull { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null

            val linkProps = manager.getLinkProperties(wifi) ?: return null
            val capData = linkProps.javaClass.getMethod("getCaptivePortalData").invoke(linkProps)
                ?: return null
            val userUrl = capData.javaClass.getMethod("getUserPortalUrl").invoke(capData)?.toString()
            val venueUrl = capData.javaClass.getMethod("getVenueInfoUrl").invoke(capData)?.toString()

            val candidate = if (preferVenue) {
                venueUrl ?: userUrl
            } else {
                userUrl ?: venueUrl
            }

            if (!candidate.isNullOrBlank() && isValidHttpUrl(candidate)) candidate else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isWifiSystemPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase(Locale.ROOT)
        return lower == "android" ||
            lower == "com.android.systemui" ||
            lower.contains("networkstack") ||
            lower.contains("captiveportallogin") ||
            lower.contains("wifi") ||
            lower.contains("wireless") ||
            lower.contains("connectivity")
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val manager =
                getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidHttpUrl(value: String): Boolean {
        return try {
            val uri = URI(value)

            (uri.scheme.equals("http", true) ||
                uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
