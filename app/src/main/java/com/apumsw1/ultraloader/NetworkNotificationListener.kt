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
        private const val DUPLICATE_WINDOW = 12000L
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

            val summary = extras.getCharSequence(
                Notification.EXTRA_SUMMARY_TEXT
            )?.toString().orEmpty()

            val lines = extras.getCharSequenceArray(
                Notification.EXTRA_TEXT_LINES
            )?.joinToString(" ") { it.toString() }.orEmpty()

            val actionText = notification.actions
                ?.joinToString(" ") {
                    it.title?.toString().orEmpty()
                }.orEmpty()

            val packageName = sbn.packageName.lowercase()

            // Ignore Ultra Loader's own notification.
            if (packageName == applicationContext.packageName.lowercase()) {
                return
            }

            val combined = listOf(
                title,
                text,
                bigText,
                subText,
                summary,
                lines,
                actionText
            ).joinToString(" ").lowercase()

            val normalized = combined
                .replace("•", " ")
                .replace("·", " ")
                .replace("/", " ")
                .replace("-", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            // Android system packages that commonly show captive portal notices.
            val systemNetworkPackage =
                packageName.contains("networkstack") ||
                packageName.contains("captiveportal") ||
                packageName.contains("connectivity.resources") ||
                packageName.contains("networkmanager") ||
                packageName.contains("connectivity")

            // Recognize different wording used by Android Network Manager.
            val portalPhrases = listOf(
                "tap to login",
                "tap to log in",
                "tap to sign in",
                "tap to connect",
                "tap to view website",
                "view website",
                "open website",
                "sign in to network",
                "sign in to wi-fi network",
                "sign in to wifi network",
                "network requires sign in",
                "network may require sign in",
                "captive portal",
                "connected tap to view",
                "connected tap to view website",
                "connected view website",
                "login required",
                "log in required"
            )

            val hasPortalPhrase = portalPhrases.any {
                normalized.contains(it)
            }

            // Accept known network-system notifications when they clearly
            // indicate a sign-in or website action.
            val networkNotification =
                (systemNetworkPackage && hasPortalPhrase) ||
                (
                    (
                        title.contains("network manager", true) ||
                        normalized.contains("network manager")
                    ) && hasPortalPhrase
                ) ||
                (
                    normalized.contains("tap to view website") &&
                    (
                        normalized.contains("connected") ||
                        normalized.contains("network") ||
                        normalized.contains("wi-fi") ||
                        normalized.contains("wifi")
                    )
                )

            if (!networkNotification) return

            // Do not repeatedly open the app for the same notification.
            val signature = "$packageName|$normalized"

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val previous = prefs.getString(LAST_KEY, "")
            val previousTime = prefs.getLong(LAST_TIME, 0L)
            val now = System.currentTimeMillis()

            if (
                signature == previous &&
                now - previousTime < DUPLICATE_WINDOW
            ) {
                return
            }

            prefs.edit()
                .putString(LAST_KEY, signature)
                .putLong(LAST_TIME, now)
                .apply()

            val portalUrl = extractUrl(
                listOf(
                    title,
                    text,
                    bigText,
                    subText,
                    summary,
                    lines
                ).joinToString(" ")
            )

            // Launch Ultra Loader. MainActivity will discover the portal
            // itself when the notification does not expose a URL.
            val launchIntent = Intent(
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

            startActivity(launchIntent)

        } catch (_: Exception) {
            // Ignore notifications that Android does not allow us to inspect.
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
