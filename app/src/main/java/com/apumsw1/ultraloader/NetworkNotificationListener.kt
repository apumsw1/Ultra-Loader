package com.apumsw1.ultraloader

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NetworkNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        const val ACTION_START_PORTAL =
            "com.apumsw1.ultraloader.START_PORTAL"

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

            if (
                packageName ==
                applicationContext.packageName.lowercase()
            ) return

            val originalText = listOf(
                title,
                text,
                bigText,
                subText,
                summary,
                lines,
                actionText
            ).joinToString(" ")

            val combined = originalText.lowercase()

            val normalized = combined
                .replace("•", " ")
                .replace("·", " ")
                .replace("/", " ")
                .replace("-", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val systemNetworkPackage =
                packageName == "android" ||
                packageName.contains("networkstack") ||
                packageName.contains("captiveportal") ||
                packageName.contains("networkmanager") ||
                packageName.contains("connectivity")

            val portalPhrases = listOf(
                "connected/tap to view website",
                "connected / tap to view website",
                "connected tap to view website",
                "tap to view website",
                "tap to login",
                "tap to log in",
                "tap to sign in",
                "tap to connect",
                "view website",
                "open website",
                "sign in to network",
                "sign in to wi-fi network",
                "sign in to wifi network",
                "network requires sign in",
                "network may require sign in",
                "captive portal",
                "login required",
                "log in required"
            )

            val hasPortalPhrase = portalPhrases.any { phrase ->
                val normalizedPhrase = phrase
                    .replace("/", " ")
                    .replace("-", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                combined.contains(phrase) ||
                    normalized.contains(normalizedPhrase)
            }

            val hasConnectedWebsiteText =
                combined.contains("connected/tap to view website") ||
                combined.contains("connected / tap to view website") ||
                normalized.contains("connected tap to view website")

            val networkNotification =
                hasConnectedWebsiteText ||
                (systemNetworkPackage && hasPortalPhrase) ||
                (
                    (
                        combined.contains("network manager") ||
                        title.contains("network manager", true)
                    ) && hasPortalPhrase
                ) ||
                (
                    combined.contains("captive portal") &&
                    hasPortalPhrase
                )

            if (!networkNotification) return

            val signature = "$packageName|$originalText"

            val prefs = getSharedPreferences(
                PREFS,
                MODE_PRIVATE
            )

            val previous = prefs.getString(LAST_KEY, "")
            val previousTime = prefs.getLong(LAST_TIME, 0L)
            val now = System.currentTimeMillis()

            if (
                signature == previous &&
                now - previousTime < DUPLICATE_WINDOW
            ) return

            prefs.edit()
                .putString(LAST_KEY, signature)
                .putLong(LAST_TIME, now)
                .apply()

            val portalUrl = extractUrl(originalText)

            // URL পাওয়া গেলে সেটি পাঠাবে।
            // URL না থাকলে MainActivity-কে বর্তমান portal খুঁজতে বলবে।
            val launchIntent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                action = if (portalUrl != null) {
                    ACTION_OPEN_PORTAL
                } else {
                    ACTION_START_PORTAL
                }

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
            // Ignore inaccessible or malformed notifications.
        }
    }

    private fun extractUrl(value: String): String? {
        val regex = Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(value)
            ?.value
            ?.trimEnd('.', ',', ')', ']', ';', ':')
            ?.takeIf {
                it.startsWith("https://", true) ||
                    it.startsWith("http://", true)
            }
    }
}
