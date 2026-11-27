package com.apumsw1.ultraloader

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.Spanned
import android.text.style.URLSpan
import android.util.Log
import java.util.Collections
import java.util.Locale

class WifiNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WifiNotifListener"
        const val PREFS = "wifi_notification_links"
        const val TYPE_PORTAL_LOGIN = "PORTAL_LOGIN"
        const val TYPE_VIEW_WEBSITE = "VIEW_WEBSITE"

        private val URL_PATTERN = Regex(
            """https?://[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        )

        @Volatile
        private var activeInstance: WifiNotificationListener? = null

        @Volatile
        private var latestPortalContentIntent: PendingIntent? = null

        @Volatile
        private var sessionCapturedUrl: String? = null

        @Volatile
        private var sessionCapturedType: String? = null

        @Volatile
        private var sessionNetworkKey: String? = null

        private val processedSignatures: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf())

        fun isConnected(): Boolean = activeInstance != null

        fun hasSystemPortalIntent(): Boolean = latestPortalContentIntent != null

        fun openSystemPortalIntent(): Boolean {
            val pi = latestPortalContentIntent ?: return false
            return try {
                pi.send()
                true
            } catch (_: Exception) {
                false
            }
        }

        fun getSessionCapturedPortal(
            context: Context,
            currentFingerprint: String? = null
        ): Pair<String, String>? {
            if (!currentFingerprint.isNullOrBlank()) {
                val memKey = sessionNetworkKey
                if (!memKey.isNullOrBlank() && memKey != currentFingerprint) {
                    Log.i(TAG, "Purging stale session URL due to Wi-Fi fingerprint change ($memKey -> $currentFingerprint)")
                    clearSessionState(context)
                    return null
                }
            }
            val memUrl = sessionCapturedUrl
            val memType = sessionCapturedType
            if (!memUrl.isNullOrBlank() && PortalNetworkHelper.isUsablePortalUrl(memUrl)) {
                return Pair(memUrl, memType ?: TYPE_PORTAL_LOGIN)
            }
            if (!memUrl.isNullOrBlank() && !PortalNetworkHelper.isUsablePortalUrl(memUrl)) {
                sessionCapturedUrl = null
            }
            return try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val savedUrl = prefs.getString("session_captured_url", null)
                val savedType = prefs.getString("session_captured_type", null)
                val savedNetKey = prefs.getString("session_network_key", null)
                val savedAt = prefs.getLong("session_captured_at", 0L)
                val isStaleAge = savedAt > 0L && (System.currentTimeMillis() - savedAt) > 30 * 60_000L
                val isStaleNet = !currentFingerprint.isNullOrBlank() &&
                    !savedNetKey.isNullOrBlank() &&
                    savedNetKey != currentFingerprint
                if (isStaleAge || isStaleNet) {
                    Log.i(TAG, "Purging persisted stale session URL (staleAge=$isStaleAge, staleNet=$isStaleNet)")
                    clearSessionState(context)
                    return null
                }
                if (!savedUrl.isNullOrBlank() && PortalNetworkHelper.isUsablePortalUrl(savedUrl)) {
                    sessionCapturedUrl = savedUrl
                    sessionCapturedType = savedType ?: TYPE_PORTAL_LOGIN
                    sessionNetworkKey = savedNetKey
                    Pair(savedUrl, sessionCapturedType ?: TYPE_PORTAL_LOGIN)
                } else {
                    if (!savedUrl.isNullOrBlank()) {
                        prefs.edit().remove("session_captured_url").remove("latest_url").apply()
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        fun markSessionCaptured(
            context: Context,
            url: String,
            type: String,
            networkFingerprint: String? = null
        ) {
            if (!PortalNetworkHelper.isUsablePortalUrl(url)) {
                Log.d(TAG, "Ignored non-portal/probe URL in markSessionCaptured: $url")
                return
            }
            sessionCapturedUrl = url
            sessionCapturedType = type
            if (!networkFingerprint.isNullOrBlank()) {
                sessionNetworkKey = networkFingerprint
            }
            try {
                val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("session_captured_url", url)
                    .putString("session_captured_type", type)
                    .putLong("session_captured_at", System.currentTimeMillis())
                    .putString("latest_url", url)
                    .putLong("latest_url_time", System.currentTimeMillis())
                    .putString("pending_detection_type", type)
                if (!networkFingerprint.isNullOrBlank()) {
                    editor.putString("session_network_key", networkFingerprint)
                }
                editor.apply()
            } catch (_: Exception) {
            }
        }

        fun clearSessionState(context: Context? = null) {
            sessionCapturedUrl = null
            sessionCapturedType = null
            sessionNetworkKey = null
            latestPortalContentIntent = null
            processedSignatures.clear()
            activeInstance?.resetBroadcastDedup()
            if (context != null) {
                try {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .remove("session_captured_url")
                        .remove("session_captured_type")
                        .remove("session_captured_at")
                        .remove("session_network_key")
                        .remove("latest_url")
                        .remove("latest_url_time")
                        .remove("pending_detection_type")
                        .apply()
                } catch (_: Exception) {
                }
            }
        }

        fun scanActiveNotificationsNow(notifyService: Boolean = false): Boolean {
            val instance = activeInstance ?: return false
            return try {
                instance.scanAllActiveNotifications(notifyService)
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun isValidHttpUrlStatic(value: String): Boolean {
            return PortalNetworkHelper.isUsablePortalUrl(value)
        }
    }

    @Volatile
    private var lastServiceUpdateSignature: String = ""

    @Volatile
    private var lastServiceUpdateAt: Long = 0L

    private fun resetBroadcastDedup() {
        lastServiceUpdateSignature = ""
        lastServiceUpdateAt = 0L
    }

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
        // Process oldest to newest so the newest relevant notification wins.
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

    @Synchronized
    private fun processStatusBarNotification(
        sbn: StatusBarNotification,
        notifyMonitorService: Boolean
    ): Pair<String?, String>? {
        if (sbn.packageName == packageName) return null
        if (!isWifiConnected()) {
            clearSessionState(this)
            return null
        }

        val currentFingerprint = try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            PortalNetworkHelper.getWifiSessionFingerprint(this, cm)
        } catch (_: Exception) {
            null
        }
        if (!currentFingerprint.isNullOrBlank() &&
            !sessionNetworkKey.isNullOrBlank() &&
            sessionNetworkKey != currentFingerprint
        ) {
            Log.i(TAG, "Wi-Fi fingerprint changed ($sessionNetworkKey -> $currentFingerprint); clearing old session")
            clearSessionState(this)
        }

        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle.EMPTY
        val texts = mutableListOf<String>()
        val extraUrls = mutableListOf<String>()

        fun addText(value: CharSequence?) {
            if (!value.isNullOrBlank()) {
                val str = value.toString()
                texts.add(str)
                extractUrlsFromText(str, extraUrls)
                extractSpannedUrls(value, extraUrls)
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

        val textUrls = URL_PATTERN.findAll(combinedText)
            .map {
                it.value.trimEnd('.', ',', ';', ')', ']', '}', '!', '?')
            }
            .filter { isUsablePortalUrl(it) }
            .toList()

        val urls = (extraUrls.filter { isUsablePortalUrl(it) } + textUrls)
            .distinct()

        val venueUrlFromCapport = getCaptivePortalUrlFromNetwork(preferVenue = true)
        val userUrlFromCapport = getCaptivePortalUrlFromNetwork(preferVenue = false)

        val detectionType = classifyWifiNotification(
            pkg = sbn.packageName,
            combinedText = combinedText,
            hasCapportVenueUrl = !venueUrlFromCapport.isNullOrBlank()
        ) ?: return null

        val capportUrl = if (detectionType == TYPE_VIEW_WEBSITE) {
            venueUrlFromCapport ?: userUrlFromCapport
        } else {
            userUrlFromCapport ?: venueUrlFromCapport
        }

        val rankedCandidates = (urls + listOfNotNull(capportUrl))
        val resolvedUrl = PortalNetworkHelper.selectBestPortalUrl(rankedCandidates, detectionType)
            ?.takeIf { isUsablePortalUrl(it) }
        Log.i(
            TAG,
            "Notification matched pkg=${sbn.packageName} type=$detectionType candidates=${rankedCandidates.size} selected=${resolvedUrl ?: "none"}"
        )
        val eventTime = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()

        notification.contentIntent?.let { ci ->
            latestPortalContentIntent = ci
        }

        val notificationSignature = "${sbn.key}|${sbn.postTime}|$detectionType|${resolvedUrl.orEmpty()}"
        if (!processedSignatures.add(notificationSignature)) {
            return null
        }

        val alreadyCapturedUrl = sessionCapturedUrl
        val alreadyCapturedType = sessionCapturedType
        if (!alreadyCapturedUrl.isNullOrBlank() && alreadyCapturedType == detectionType) {
            if (resolvedUrl.isNullOrBlank() || resolvedUrl.equals(alreadyCapturedUrl, ignoreCase = false)) {
                return null
            }
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (!resolvedUrl.isNullOrBlank() && isUsablePortalUrl(resolvedUrl)) {
            val allDetected = rankedCandidates
                .filter { isUsablePortalUrl(it) }
                .distinct()
            val oldUrls = prefs.getString("url_history", "")
                .orEmpty()
                .split("\n")
                .filter { it.isNotBlank() && isUsablePortalUrl(it) }

            val history = (allDetected.reversed() + oldUrls)
                .distinct()
                .take(30)

            sessionCapturedUrl = resolvedUrl
            sessionCapturedType = detectionType
            if (!currentFingerprint.isNullOrBlank()) {
                sessionNetworkKey = currentFingerprint
                editor.putString("session_network_key", currentFingerprint)
            }

            editor
                .putString("session_captured_url", resolvedUrl)
                .putString("session_captured_type", detectionType)
                .putLong("session_captured_at", System.currentTimeMillis())
                .putString("latest_url", resolvedUrl)
                .putLong("latest_url_time", System.currentTimeMillis())
                .putString("url_history", history.joinToString("\n"))
        } else {
            sessionCapturedType = detectionType
            editor.putString("session_captured_type", detectionType)
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

        NetworkStatusManager.updateDetectionType(detectionType, resolvedUrl)

        if (notifyMonitorService) {
            notifyMonitorService(resolvedUrl, detectionType)
        }

        return Pair(resolvedUrl, detectionType)
    }

    internal fun classifyWifiNotification(
        pkg: String?,
        combinedText: String,
        hasCapportVenueUrl: Boolean
    ): String? {
        if (pkg == packageName || pkg == VpnAutomationBridge.VPN_PACKAGE) return null

        val lowerText = combinedText.lowercase(Locale.ROOT)
        val systemWifiPkg = isWifiSystemPackage(pkg)
        val hasStrongWifiContext =
            lowerText.contains("wi-fi") ||
                lowerText.contains("wifi") ||
                lowerText.contains("captive") ||
                lowerText.contains("portal") ||
                lowerText.contains("tap to view website") ||
                lowerText.contains("connected / tap to view") ||
                lowerText.contains("tap to sign in") ||
                lowerText.contains("sign in to network") ||
                lowerText.contains("sign in required") ||
                lowerText.contains("network may require") ||
                lowerText.contains("internet may not be available") ||
                lowerText.contains("limited connection")

        if (!systemWifiPkg && !hasStrongWifiContext) {
            return null
        }

        // Notification Type 2: CONNECTED / TAP TO VIEW WEBSITE
        val isViewWebsiteNotification =
            lowerText.contains("tap to view website") ||
                lowerText.contains("connected / tap to view") ||
                lowerText.contains("view website") ||
                ((systemWifiPkg || lowerText.contains("wi-fi") || lowerText.contains("wifi")) &&
                    (lowerText.contains("tap to view") ||
                        (lowerText.contains("connected") &&
                            (lowerText.contains("website") ||
                                lowerText.contains("view") ||
                                hasCapportVenueUrl))))

        // Notification Type 1: LOGIN / SIGN IN
        val isLoginPortalNotification =
            lowerText.contains("sign in to wi-fi") ||
                lowerText.contains("sign in to wifi") ||
                lowerText.contains("sign in to network") ||
                lowerText.contains("sign in required") ||
                lowerText.contains("wi-fi login") ||
                lowerText.contains("wifi login") ||
                lowerText.contains("login / sign in") ||
                lowerText.contains("login details") ||
                lowerText.contains("captive portal") ||
                lowerText.contains("network may require") ||
                lowerText.contains("authorization required") ||
                ((systemWifiPkg || hasStrongWifiContext) &&
                    (lowerText.contains("sign in") ||
                        lowerText.contains("log in") ||
                        lowerText.contains("login") ||
                        lowerText.contains("authenticate")))

        return when {
            isViewWebsiteNotification && !isLoginPortalNotification -> TYPE_VIEW_WEBSITE
            isLoginPortalNotification && !isViewWebsiteNotification -> TYPE_PORTAL_LOGIN
            isViewWebsiteNotification && lowerText.contains("tap to view website") -> TYPE_VIEW_WEBSITE
            isLoginPortalNotification -> TYPE_PORTAL_LOGIN
            isViewWebsiteNotification -> TYPE_VIEW_WEBSITE
            else -> null
        }
    }

    private fun isUsablePortalUrl(value: String): Boolean {
        return PortalNetworkHelper.isUsablePortalUrl(value)
    }

    private fun notifyMonitorService(resolvedUrl: String?, detectionType: String) {
        val now = System.currentTimeMillis()
        val signature = "$detectionType|${resolvedUrl.orEmpty()}"
        if (signature == lastServiceUpdateSignature && now - lastServiceUpdateAt < 30_000L) {
            return
        }
        lastServiceUpdateSignature = signature
        lastServiceUpdateAt = now

        try {
            val broadcastIntent = Intent("com.apumsw1.ultraloader.WIFI_PORTAL_DETECTED").apply {
                setPackage(packageName)
                if (!resolvedUrl.isNullOrBlank()) {
                    putExtra("detected_url", resolvedUrl)
                }
                putExtra("detection_type", detectionType)
                putExtra("detected_at", now)
            }
            sendBroadcast(broadcastIntent)
        } catch (_: Exception) {
        }

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

    private fun extractUrlsFromText(text: String, urls: MutableList<String>) {
        URL_PATTERN.findAll(text).forEach { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ')', ']', '}', '!', '?')
            if (isValidHttpUrl(candidate)) {
                urls.add(candidate)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun collectBundleValues(
        bundle: Bundle,
        texts: MutableList<String>,
        urls: MutableList<String>,
        depth: Int
    ) {
        if (depth > 4) return
        try {
            for (key in bundle.keySet()) {
                val value = bundle.get(key) ?: continue
                when (value) {
                    is String -> {
                        if (value.isNotBlank()) texts.add(value)
                        if (isValidHttpUrl(value)) urls.add(value)
                        extractUrlsFromText(value, urls)
                    }
                    is Uri -> {
                        val s = value.toString()
                        if (isValidHttpUrl(s)) urls.add(s)
                    }
                    is Intent -> {
                        val data = value.dataString ?: value.data?.toString()
                        if (!data.isNullOrBlank() && isValidHttpUrl(data)) {
                            urls.add(data)
                        }
                        value.extras?.let { collectBundleValues(it, texts, urls, depth + 1) }
                    }
                    is Bundle -> {
                        collectBundleValues(value, texts, urls, depth + 1)
                    }
                    is CharSequence -> {
                        if (value.isNotBlank()) {
                            val s = value.toString()
                            texts.add(s)
                            extractUrlsFromText(s, urls)
                            extractSpannedUrls(value, urls)
                        }
                    }
                    is Array<*> -> {
                        for (item in value) {
                            when (item) {
                                is CharSequence -> {
                                    val s = item.toString()
                                    if (s.isNotBlank()) {
                                        texts.add(s)
                                        extractUrlsFromText(s, urls)
                                        extractSpannedUrls(item, urls)
                                    }
                                }
                                is Bundle -> collectBundleValues(item, texts, urls, depth + 1)
                                is Uri -> {
                                    val s = item.toString()
                                    if (isValidHttpUrl(s)) urls.add(s)
                                }
                            }
                        }
                    }
                    is Collection<*> -> {
                        for (item in value) {
                            when (item) {
                                is CharSequence -> {
                                    val s = item.toString()
                                    if (s.isNotBlank()) {
                                        texts.add(s)
                                        extractUrlsFromText(s, urls)
                                        extractSpannedUrls(item, urls)
                                    }
                                }
                                is Bundle -> collectBundleValues(item, texts, urls, depth + 1)
                                is Uri -> {
                                    val s = item.toString()
                                    if (isValidHttpUrl(s)) urls.add(s)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun extractSpannedUrls(value: CharSequence, urls: MutableList<String>) {
        if (value is Spanned) {
            try {
                value.getSpans(0, value.length, URLSpan::class.java).forEach { span ->
                    val spanUrl = span?.url?.trim()
                    if (!spanUrl.isNullOrBlank() && isUsablePortalUrl(spanUrl)) {
                        urls.add(spanUrl)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun getCaptivePortalUrlFromNetwork(preferVenue: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            PortalNetworkHelper.getCaptivePortalUrl(manager, null, preferVenue)
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
        return isValidHttpUrlStatic(value)
    }
}
