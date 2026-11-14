package com.apumsw1.ultraloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NetworkNotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        const val ACTION_URL_MISSING =
            "com.apumsw1.ultraloader.PORTAL_URL_MISSING"

        const val EXTRA_PORTAL_URL = "portal_url"

        private const val CHANNEL_ID =
            "ultra_loader_detected_portal"

        private const val ALERT_ID = 7102
        private const val DUPLICATE_WINDOW = 12000L
    }

    private var lastSignature = ""
    private var lastNotificationTime = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification?
    ) {
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
            )?.joinToString(" ") {
                it.toString()
            }.orEmpty()

            val actionText = notification.actions
                ?.joinToString(" ") {
                    it.title?.toString().orEmpty()
                }.orEmpty()

            val packageName = sbn.packageName.lowercase()

            // Ignore notifications created by Ultra Loader itself.
            if (
                packageName ==
                applicationContext.packageName.lowercase()
            ) return

            val originalText = listOf(
                title,
                text,
                bigText,
                subText,
                lines,
                actionText
            ).joinToString(" ")

            val normalized = originalText.lowercase()
                .replace("•", " ")
                .replace("·", " ")
                .replace("/", " ")
                .replace("-", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val systemPackage =
                packageName == "android" ||
                packageName.contains("networkstack") ||
                packageName.contains("captiveportal") ||
                packageName.contains("networkmanager") ||
                packageName.contains("connectivity") ||
                packageName.contains("systemui")

            val exactConnectedPhrase =
                normalized.contains(
                    "connected tap to view website"
                )

            val otherPortalPhrase = listOf(
                "tap to view website",
                "tap to login",
                "tap to log in",
                "tap to sign in",
                "sign in to network",
                "sign in to wi-fi network",
                "sign in to wifi network",
                "network requires sign in",
                "network may require sign in",
                "captive portal",
                "login required",
                "log in required"
            ).any {
                normalized.contains(it)
            }

            val isPortalNotification =
                exactConnectedPhrase ||
                (systemPackage && otherPortalPhrase)

            if (!isPortalNotification) return

            val now = System.currentTimeMillis()
            val signature = "$packageName|$originalText"

            // Duplicate protection stays in memory only.
            if (
                signature == lastSignature &&
                now - lastNotificationTime < DUPLICATE_WINDOW
            ) return

            lastSignature = signature
            lastNotificationTime = now

            val portalUrl = extractUrl(originalText)

            postPortalAlert(portalUrl)

        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Detected Wi-Fi Portal",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "Alerts when a current Wi-Fi portal notification is detected"

                setShowBadge(true)
            }

            manager.createNotificationChannel(channel)
        }
    }

    private fun postPortalAlert(url: String?) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        val action = if (url != null) {
            ACTION_OPEN_PORTAL
        } else {
            ACTION_URL_MISSING
        }

        val openIntent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            this.action = action

            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP

            if (url != null) {
                putExtra(EXTRA_PORTAL_URL, url)
            }
        }

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

        val pending = PendingIntent.getActivity(
            this,
            ALERT_ID,
            openIntent,
            pendingFlags
        )

        val hasUrl = url != null

        val detail = if (hasUrl) {
            "Current portal URL detected. Tap to open Ultra Loader."
        } else {
            "Portal detected, but this notification did not expose its URL. No old URL will be used."
        }

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(
                if (hasUrl) {
                    "ULTRA LOADER • PORTAL DETECTED"
                } else {
                    "ULTRA LOADER • PORTAL URL NOT AVAILABLE"
                }
            )
            .setContentText(detail)
            .setStyle(
                Notification.BigTextStyle().bigText(detail)
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        try {
            manager.notify(ALERT_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission may be disabled.
        }
    }

    private fun extractUrl(value: String): String? {
        val regex = Regex(
            """https?://[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        )

        val candidate = regex.find(value)
            ?.value
            ?.trimEnd('.', ',', ')', ']', ';', ':')
            ?: return null

        return try {
            val uri = Uri.parse(candidate)
            val scheme = uri.scheme?.lowercase()

            if (
                (scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrBlank()
            ) {
                candidate
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}