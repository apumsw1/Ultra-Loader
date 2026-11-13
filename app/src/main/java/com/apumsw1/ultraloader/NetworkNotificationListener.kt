package com.apumsw1.ultraloader

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Bundle

class NetworkNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        const val EXTRA_PORTAL_URL = "portal_url"

        private const val PREFS = "network_notification_monitor"
        private const val LAST_KEY = "last_notification"
        private const val LAST_TIME = "last_notification_time"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: Bundle()

            val title = extras.getCharSequence(
                Notification.EXTRA_TITLE
            )?.toString().orEmpty()

            val text = extras.getCharSequence(
                Notification.EXTRA_TEXT
            )?.toString().orEmpty()

            val bigText = extras.getCharSequence(
                Notification.EXTRA_BIG_TEXT
            )?.toString().orEmpty()

            val subText = extras.getCharSequence(
                Notification.EXTRA_SUB_TEXT
            )?.toString().orEmpty()

            val lines = extras.getCharSequenceArray(
                Notification.EXTRA_TEXT_LINES
            )?.joinToString(" ") { it.toString() }.orEmpty()

            val actionText = notification.actions
                ?.joinToString(" ") {
                    it.title?.toString().orEmpty()
                }.orEmpty()

            val combined = listOf(
                title, text, bigText, subText, lines, actionText
            ).joinToString(" ").lowercase()

            val packageName = sbn.packageName.lowercase()

            // Ignore Ultra Loader's own notification.
            if (packageName == applicationContext.packageName.lowercase()) {
                return
            }

            val networkNotification =
                title.contains("network manager", true) ||
                packageName.contains("networkstack") ||
                packageName.contains("captiveportal") ||
                packageName.contains("networkmanager") ||
                packageName.contains("connectivity.resources") ||
                combined.contains("captive portal") ||
                combined.contains("network manager") ||
                combined.contains("tap to view website") ||
                combined.contains("connected/tap to view")

            val loginAction = listOf(
                "tap to login",
                "tap to sign in",
                "tap to connect",
                "connected/tap to view",
                "connected / tap to view",
                "tap to view website",
                "connect",
                "login",
                "sign in",
                "view website",
                "open website"
            ).any { combined.contains(it) }

            if (!networkNotification || !loginAction) return

            val signature = "$packageName|$title|$text|$bigText"

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val previous = prefs.getString(LAST_KEY, "")
            val previousTime = prefs.getLong(LAST_TIME, 0L)
            val now = System.currentTimeMillis()

            if (signature == previous && now - previousTime < 15000) {
                return
            }

            prefs.edit()
                .putString(LAST_KEY, signature)
                .putLong(LAST_TIME, now)
                .apply()

            val portalUrl = extractUrl(
                listOf(title, text, bigText, subText, lines)
                    .joinToString(" ")
            )

            // Open Ultra Loader for a detected Network Manager portal.
            // If no URL is included, MainActivity will discover the redirect.
            val intent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                action = ACTION_OPEN_PORTAL
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP

                if (portalUrl != null) {
                    putExtra(EXTRA_PORTAL_URL, portalUrl)
                }
            }

            startActivity(intent)

        } catch (_: Exception) {
            // Ignore inaccessible notifications.
        }
    }

    private fun extractUrl(value: String): String? {
        val regex = Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(value)
            ?.value
            ?.trimEnd('.', ',', ')', ']', ';')
            ?.takeIf {
                it.startsWith("https://") ||
                    it.startsWith("http://")
            }
    }
}
