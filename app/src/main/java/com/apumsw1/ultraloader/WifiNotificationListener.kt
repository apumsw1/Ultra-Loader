package com.apumsw1.ultraloader

import android.app.Notification
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.net.URI

class WifiNotificationListener : NotificationListenerService() {

    companion object {
        private const val PREFS = "wifi_notification_links"

        private val URL_PATTERN = Regex(
            """https?://[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (!isWifiConnected()) return

        val extras = sbn.notification?.extras ?: return
        val texts = mutableListOf<String>()

        fun addText(value: CharSequence?) {
            if (!value.isNullOrBlank()) {
                texts.add(value.toString())
            }
        }

        addText(extras.getCharSequence(Notification.EXTRA_TITLE))
        addText(extras.getCharSequence(Notification.EXTRA_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        addText(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        addText(sbn.notification.tickerText)

        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.forEach { addText(it) }

        val urls = URL_PATTERN.findAll(texts.joinToString("\n"))
            .map {
                it.value.trimEnd(
                    '.', ',', ';', ')', ']', '}', '!', '?'
                )
            }
            .filter { isValidHttpUrl(it) }
            .distinct()
            .toList()

        if (urls.isEmpty()) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Keep every detected URL, with the newest URL as the START target.
        val oldUrls = prefs.getString("url_history", "")
            .orEmpty()
            .split("\n")
            .filter { it.isNotBlank() }

        val history = (urls.reversed() + oldUrls)
            .distinct()
            .take(30)

        prefs.edit()
            .putString("latest_url", urls.last())
            .putLong("latest_url_time", System.currentTimeMillis())
            .putString("latest_package", sbn.packageName)
            .putString(
                "latest_title",
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            )
            .putString("url_history", history.joinToString("\n"))
            .apply()
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
