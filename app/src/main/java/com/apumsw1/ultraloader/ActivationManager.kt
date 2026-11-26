package com.apumsw1.ultraloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.animation.ObjectAnimator

object ActivationManager {

    private const val API_URL =
        "https://ultra-loader.ultraloader.workers.dev/api/activate"

    private const val CONTACT_URL =
        "https://t.me/ultraloader"

    private const val PREFS = "ultra_loader_activation_v1"
    private const val PREF_LICENSE = "encrypted_license"
    private const val PREF_LICENSES = "encrypted_licenses_v2"
    private const val PREF_DEVICE_ID = "stable_device_id"
    private const val KEY_ALIAS = "UltraLoaderActivationKeyV1"

    internal const val STATUS_ACTIVE = "ACTIVE"
    internal const val STATUS_EXPIRED = "EXPIRED"
    internal const val STATUS_REVOKED = "REVOKED"
    internal const val STATUS_INVALID = "INVALID"

    private const val CONNECT_TIMEOUT = 12000
    private const val READ_TIMEOUT = 15000

    private const val BG = 0xFF0B1220.toInt()
    private const val PANEL = 0xFF142238.toInt()
    private const val BLUE = 0xFF168DFF.toInt()
    private const val CYAN = 0xFF38D6FF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val MUTED = 0xFF9FB2CC.toInt()
    private const val GREEN = 0xFF21D49A.toInt()

    private val executor = Executors.newSingleThreadExecutor()

    internal data class License(
        val key: String,
        val deviceId: String,
        val expiresAt: Long,
        val status: String = STATUS_ACTIVE,
        val activatedAt: Long = 0L,
        val durationDays: Int = 0,
        val paidAmount: Int = 0
    )

    internal interface CipherDelegate {
        fun encrypt(plain: ByteArray): ByteArray
        fun decrypt(payload: ByteArray): ByteArray
    }

    private val keyStoreCipher = object : CipherDelegate {
        override fun encrypt(plain: ByteArray): ByteArray = aesGcmEncrypt(plain)
        override fun decrypt(payload: ByteArray): ByteArray = aesGcmDecrypt(payload)
    }

    @Volatile
    internal var cipherDelegate: CipherDelegate = keyStoreCipher

    @Volatile
    private var lastStatus = "NOT ACTIVATED"

    internal fun resetCipherForProduction() {
        cipherDelegate = keyStoreCipher
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun gradient(
        start: Int,
        end: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(start, end)
        ).apply {
            cornerRadius = radius.toFloat()
        }
    }

    private fun text(
        context: Context,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        gravity: Int = Gravity.START
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            this.gravity = gravity
            if (bold) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun addSpace(
        layout: LinearLayout,
        context: Context,
        height: Int
    ) {
        layout.addView(
            View(context),
            LinearLayout.LayoutParams(
                1,
                dp(context, height)
            )
        )
    }

    private fun makeButton(
        context: Context,
        label: String,
        buttonBackground: android.graphics.drawable.Drawable,
        color: Int
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            gravity = Gravity.CENTER
            setBackground(buttonBackground)
            setPadding(
                dp(context, 12),
                dp(context, 15),
                dp(context, 12),
                dp(context, 15)
            )
            isClickable = true
            isFocusable = true
        }
    }

    private fun animatePress(view: View) {
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    private fun isEffectiveActive(license: License, nowMs: Long = System.currentTimeMillis()): Boolean {
        return license.status == STATUS_ACTIVE && license.expiresAt > nowMs
    }

    internal fun syncLocalExpiries(context: Context, nowMs: Long = System.currentTimeMillis()): List<License> {
        val current = readLicenses(context)
        var changed = false
        val updated = current.map { lic ->
            if (lic.status == STATUS_ACTIVE && lic.expiresAt <= nowMs) {
                changed = true
                lic.copy(status = STATUS_EXPIRED)
            } else {
                lic
            }
        }
        if (changed) {
            writeLicenses(context, updated)
        }
        return updated
    }

    internal fun getLicensesSnapshot(context: Context): List<License> {
        return syncLocalExpiries(context)
    }

    fun hasAccess(context: Context): Boolean {
        val nowMs = System.currentTimeMillis()
        val licenses = syncLocalExpiries(context, nowMs)
        return licenses.any { isEffectiveActive(it, nowMs) }
    }

    fun statusText(context: Context): String {
        val nowMs = System.currentTimeMillis()
        val licenses = syncLocalExpiries(context, nowMs)

        if (licenses.isEmpty()) {
            lastStatus = "NOT ACTIVATED"
            return "LICENSE: NOT ACTIVATED"
        }

        val active = licenses.filter { isEffectiveActive(it, nowMs) }
        if (active.isEmpty()) {
            val latestNonActive = licenses.last()
            lastStatus = latestNonActive.status
            return "LICENSE: ${latestNonActive.status}"
        }

        lastStatus = "ACTIVE"
        val permanent = active.any { it.expiresAt == Long.MAX_VALUE }
        val countTag = if (active.size > 1) " (${active.size} KEYS)" else ""
        if (permanent) {
            return "LICENSE: ACTIVE$countTag (PERMANENT)"
        }

        val primary = active.maxByOrNull { it.expiresAt } ?: active.first()
        val formatted = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date(primary.expiresAt))

        return "LICENSE: ACTIVE$countTag\nEXPIRES: $formatted"
    }

    fun detailedStatusText(context: Context): String {
        val nowMs = System.currentTimeMillis()
        val licenses = syncLocalExpiries(context, nowMs)
        if (licenses.isEmpty()) {
            return "LICENSE: NOT ACTIVATED"
        }

        val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return licenses.mapIndexed { index, lic ->
            val effectiveStatus = if (lic.status == STATUS_ACTIVE && lic.expiresAt <= nowMs) {
                STATUS_EXPIRED
            } else {
                lic.status
            }
            val masked = maskKey(lic.key)
            val expiryLabel = when {
                lic.expiresAt == Long.MAX_VALUE -> "PERMANENT"
                lic.expiresAt > 0L -> formatter.format(Date(lic.expiresAt))
                else -> "-"
            }
            val daysLabel = if (lic.durationDays > 0) " • ${lic.durationDays}d" else ""
            "#${index + 1} $masked [$effectiveStatus$daysLabel]\nEXP: $expiryLabel"
        }.joinToString("\n\n")
    }

    private fun maskKey(key: String): String {
        val clean = key.trim()
        if (clean.length <= 10) return clean
        return clean.take(6) + "••••" + clean.takeLast(4)
    }

    fun requireActivation(
        activity: Activity,
        onActivated: () -> Unit
    ) {
        if (hasAccess(activity)) {
            onActivated()
            return
        }

        showActivationDialog(activity, cancelable = false, onActivated = onActivated)
    }

    fun showAddKeyDialog(
        activity: Activity,
        onActivated: () -> Unit
    ) {
        showActivationDialog(activity, cancelable = true, onActivated = onActivated)
    }

    private fun showActivationDialog(
        activity: Activity,
        cancelable: Boolean,
        onActivated: () -> Unit
    ) {
        if (activity.isFinishing ||
            (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)
        ) return

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, 22),
                dp(activity, 22),
                dp(activity, 22),
                dp(activity, 18)
            )
            background = rounded(
                BG,
                dp(activity, 22)
            )
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root)
        }

        val logo = TextView(activity).apply {
            text = "UL"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            background = gradient(
                0xFF087BEA.toInt(),
                0xFF22C8F0.toInt(),
                dp(activity, 25)
            )
            elevation = dp(activity, 8).toFloat()
        }

        val logoContainer = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addView(
                logo,
                LinearLayout.LayoutParams(
                    dp(activity, 82),
                    dp(activity, 82)
                )
            )
        }

        root.addView(
            logoContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 92)
            )
        )

        val pulse = ObjectAnimator.ofFloat(
            logo,
            "alpha",
            0.72f,
            1f
        ).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulse.start()

        addSpace(root, activity, 12)

        root.addView(
            text(
                activity,
                "ULTRA LOADER",
                23f,
                WHITE,
                true,
                Gravity.CENTER
            )
        )

        addSpace(root, activity, 5)

        root.addView(
            text(
                activity,
                "PREMIUM ACTIVATION CENTER",
                11f,
                CYAN,
                true,
                Gravity.CENTER
            )
        )

        addSpace(root, activity, 12)

        root.addView(
            text(
                activity,
                "Unlock your experience with a valid license.",
                13f,
                MUTED,
                false,
                Gravity.CENTER
            )
        )

        addSpace(root, activity, 22)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(activity, 16),
                dp(activity, 18),
                dp(activity, 16),
                dp(activity, 18)
            )
            background = rounded(
                PANEL,
                dp(activity, 16),
                0xFF263F5E.toInt(),
                dp(activity, 1)
            )
        }

        root.addView(
            panel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        panel.addView(
            text(
                activity,
                "ENTER ACTIVATION KEY",
                13f,
                WHITE,
                true
            )
        )

        addSpace(panel, activity, 12)

        val keyInput = EditText(activity).apply {
            hint = "Enter your activation key"
            setHintTextColor(0xFF7287A4.toInt())
            setTextColor(WHITE)
            textSize = 15f
            setSingleLine(true)
            setPadding(
                dp(activity, 14),
                dp(activity, 13),
                dp(activity, 14),
                dp(activity, 13)
            )
            background = rounded(
                0xFF0B1729.toInt(),
                dp(activity, 11),
                0xFF385778.toInt(),
                dp(activity, 1)
            )
            setSelectAllOnFocus(false)
        }

        panel.addView(
            keyInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 52)
            )
        )

        addSpace(panel, activity, 16)

        val activateButton = makeButton(
            activity,
            "ACTIVATE NOW",
            gradient(
                0xFF087BEA.toInt(),
                0xFF20B9F0.toInt(),
                dp(activity, 12)
            ),
            WHITE
        )

        panel.addView(
            activateButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 52)
            )
        )

        addSpace(root, activity, 12)

        val buyButton = makeButton(
            activity,
            "BUY ACTIVATION KEY",
            rounded(
                0xFF142D4A.toInt(),
                dp(activity, 12),
                0xFF299FE9.toInt(),
                dp(activity, 1)
            ),
            0xFF75D5FF.toInt()
        )

        root.addView(
            buyButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 50)
            )
        )

        addSpace(root, activity, 9)

        val contactButton = makeButton(
            activity,
            "CONTACT SUPPORT",
            rounded(
                0xFF1C2A40.toInt(),
                dp(activity, 12)
            ),
            WHITE
        )

        root.addView(
            contactButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 48)
            )
        )

        if (cancelable) {
            addSpace(root, activity, 9)

            val closeButton = makeButton(
                activity,
                "CLOSE",
                rounded(
                    0xFF142134.toInt(),
                    dp(activity, 12),
                    0xFF334E73.toInt(),
                    dp(activity, 1)
                ),
                MUTED
            )

            root.addView(
                closeButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(activity, 46)
                )
            )
        }

        addSpace(root, activity, 16)

        root.addView(
            text(
                activity,
                "SECURE LICENSE VERIFICATION",
                10f,
                MUTED,
                true,
                Gravity.CENTER
            )
        )

        addSpace(root, activity, 5)

        root.addView(
            text(
                activity,
                "ULTRA LOADER • LICENSE PROTECTED",
                9f,
                0xFF71839D.toInt(),
                false,
                Gravity.CENTER
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .setCancelable(cancelable)
            .create()

        dialog.setCanceledOnTouchOutside(cancelable)

        if (cancelable) {
            val closeBtn = root.getChildAt(root.childCount - 5)
            closeBtn?.setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(
                android.R.color.transparent
            )

            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels *
                    0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            scroll.alpha = 0f
            scroll.translationY = dp(activity, 25).toFloat()

            scroll.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setInterpolator(
                    AccelerateDecelerateInterpolator()
                )
                .start()
        }

        dialog.setOnDismissListener {
            pulse.cancel()
        }

        buyButton.setOnClickListener {
            animatePress(buyButton)
            openContact(activity)
        }

        contactButton.setOnClickListener {
            animatePress(contactButton)
            openContact(activity)
        }

        activateButton.setOnClickListener {
            animatePress(activateButton)

            val key = keyInput.text.toString().trim()

            if (key.isEmpty()) {
                keyInput.error = "Enter your activation key"
                return@setOnClickListener
            }

            activateButton.isEnabled = false
            buyButton.isEnabled = false
            contactButton.isEnabled = false
            keyInput.isEnabled = false

            activateButton.text = "CHECKING LICENSE..."

            executor.execute {
                val result = sendActivation(
                    key,
                    getDeviceId(activity),
                    activity
                )

                activity.runOnUiThread {

                    if (activity.isFinishing ||
                        (Build.VERSION.SDK_INT >= 17 &&
                            activity.isDestroyed)
                    ) return@runOnUiThread

                    if (result.status == "VALID" &&
                        result.license != null
                    ) {
                        upsertLicense(activity, result.license)
                        lastStatus = "ACTIVE"

                        activateButton.text = "ACTIVATION SUCCESSFUL"
                        activateButton.setTextColor(WHITE)
                        activateButton.background = rounded(
                            GREEN,
                            dp(activity, 12)
                        )

                        Toast.makeText(
                            activity,
                            "Activation successful!",
                            Toast.LENGTH_LONG
                        ).show()

                        activateButton.postDelayed({
                            if (dialog.isShowing) {
                                dialog.dismiss()
                                onActivated()
                            }
                        }, 700)

                    } else {
                        activateButton.isEnabled = true
                        buyButton.isEnabled = true
                        contactButton.isEnabled = true
                        keyInput.isEnabled = true
                        activateButton.text = "ACTIVATE NOW"

                        Toast.makeText(
                            activity,
                            activationMessage(
                                result.status,
                                result.message
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun openContact(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(CONTACT_URL)
                )
            )
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                "Unable to open contact page",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun verifySavedLicense(
        context: Context,
        callback: (String) -> Unit
    ) {
        val nowMs = System.currentTimeMillis()
        val licenses = syncLocalExpiries(context, nowMs)

        if (licenses.isEmpty()) {
            lastStatus = "NOT ACTIVATED"
            callback("NO_LICENSE")
            return
        }

        val activeKeys = licenses.filter { isEffectiveActive(it, nowMs) }
        if (activeKeys.isEmpty()) {
            val fallbackStatus = licenses.last().status
            lastStatus = fallbackStatus
            callback(fallbackStatus)
            return
        }

        executor.execute {
            var anyValid = false
            var lastFailureStatus = "ERROR"

            for (saved in activeKeys) {
                val result = sendActivation(
                    saved.key,
                    saved.deviceId,
                    context
                )

                if (result.status == "VALID" && result.license != null) {
                    upsertLicense(context, result.license)
                    anyValid = true
                } else if (
                    result.status == STATUS_INVALID ||
                    result.status == STATUS_REVOKED ||
                    result.status == STATUS_EXPIRED
                ) {
                    markLicenseStatus(context, saved.key, result.status)
                    lastFailureStatus = result.status
                } else {
                    lastFailureStatus = result.status
                }
            }

            val finalStatus = if (anyValid) {
                lastStatus = "ACTIVE"
                "VALID"
            } else {
                val stillHasLocalActive = hasAccess(context)
                if (stillHasLocalActive && lastFailureStatus == "ERROR") {
                    lastStatus = "ACTIVE"
                    "VALID"
                } else {
                    lastStatus = lastFailureStatus
                    lastFailureStatus
                }
            }

            if (context is Activity) {
                context.runOnUiThread {
                    callback(finalStatus)
                }
            } else {
                callback(finalStatus)
            }
        }
    }

    private data class ActivationResult(
        val status: String,
        val message: String,
        val license: License? = null
    )

    private fun sendActivation(
        key: String,
        deviceId: String,
        context: Context? = null
    ): ActivationResult {

        var connection: HttpURLConnection? = null

        return try {
            connection =
                URL(API_URL).openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                useCaches = false

                setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )

                setRequestProperty(
                    "Accept",
                    "application/json"
                )
            }

            val appVersion = context?.let {
                try {
                    it.packageManager.getPackageInfo(it.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
            }.orEmpty()

            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val body = JSONObject()
                .put("key", key)
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
                .put("deviceModel", Build.MODEL ?: "")
                .put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                .put("appVersion", appVersion)
                .toString()

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.use { input ->
                BufferedReader(
                    InputStreamReader(input, Charsets.UTF_8)
                ).use {
                    it.readText()
                }
            }.orEmpty()

            val json = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                JSONObject()
            }

            val status = json.optString(
                "status",
                "ERROR"
            ).uppercase(Locale.US)

            val message = json.optString(
                "error",
                json.optString("message", "")
            )

            if (status == "VALID" &&
                json.optBoolean("ok", false)
            ) {
                val expiryString = json.optString(
                    "expiresAt",
                    ""
                )

                val expiry = if (
                    expiryString.isBlank() ||
                    expiryString == "null"
                ) {
                    Long.MAX_VALUE
                } else {
                    parseServerDate(expiryString)
                }

                val activatedAtString = json.optString("activatedAt", "")
                val activatedAtMs = if (activatedAtString.isNotBlank() && activatedAtString != "null") {
                    parseServerDate(activatedAtString)
                } else {
                    System.currentTimeMillis()
                }
                val durationDays = json.optInt("durationDays", 0)
                val paidAmount = json.optInt("paidAmount", 0)

                if (expiry <= System.currentTimeMillis()) {
                    ActivationResult(
                        "EXPIRED",
                        "Activation key expired"
                    )
                } else {
                    ActivationResult(
                        status = "VALID",
                        message = "Activation successful",
                        license = License(
                            key = key,
                            deviceId = deviceId,
                            expiresAt = expiry,
                            status = STATUS_ACTIVE,
                            activatedAt = activatedAtMs,
                            durationDays = durationDays,
                            paidAmount = paidAmount
                        )
                    )
                }

            } else {
                ActivationResult(
                    status = if (status.isBlank()) {
                        "ERROR"
                    } else {
                        status
                    },
                    message = message.ifBlank {
                        "Server returned HTTP $code"
                    }
                )
            }

        } catch (error: Exception) {
            ActivationResult(
                status = "ERROR",
                message = error.localizedMessage
                    ?: "Network or server error"
            )

        } finally {
            connection?.disconnect()
        }
    }

    private fun parseServerDate(value: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )

        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(
                    pattern,
                    Locale.US
                ).apply {
                    isLenient = false
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }

                val parsed = format.parse(value)

                if (parsed != null) return parsed.time

            } catch (_: Exception) {
                // Try next date format.
            }
        }

        return 0L
    }

    private fun activationMessage(
        status: String,
        detail: String
    ): String {
        return when (status) {
            "INVALID" ->
                "Invalid activation key"

            "REVOKED" ->
                "This activation key has been revoked"

            "EXPIRED" ->
                "This activation key has expired"

            "DEVICE_LIMIT" ->
                "Device limit reached for this key"

            "DEVICE_CONFLICT" ->
                "This device is linked to another key"

            else ->
                detail.ifBlank {
                    "Unable to contact activation server"
                }
        }
    }

    private fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val saved = prefs.getString(
            PREF_DEVICE_ID,
            null
        )

        if (!saved.isNullOrBlank()) return saved

        val androidId = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
        } catch (_: Exception) {
            null
        }

        val id = androidId?.takeIf {
            it.isNotBlank()
        } ?: UUID.randomUUID().toString()

        prefs.edit()
            .putString(PREF_DEVICE_ID, id)
            .apply()

        return id
    }

    internal fun upsertLicense(
        context: Context,
        license: License
    ) {
        val current = readLicenses(context).toMutableList()
        val idx = current.indexOfFirst { it.key == license.key }
        if (idx >= 0) {
            current[idx] = license
        } else {
            current.add(license)
        }
        writeLicenses(context, current)
    }

    internal fun markLicenseStatus(
        context: Context,
        key: String,
        newStatus: String
    ) {
        val current = readLicenses(context)
        var changed = false
        val updated = current.map { lic ->
            if (lic.key == key && lic.status != newStatus) {
                changed = true
                lic.copy(status = newStatus)
            } else {
                lic
            }
        }
        if (changed) {
            writeLicenses(context, updated)
        }
    }

    private fun writeLicenses(
        context: Context,
        licenses: List<License>
    ) {
        val arr = JSONArray()
        for (lic in licenses) {
            arr.put(
                JSONObject()
                    .put("key", lic.key)
                    .put("deviceId", lic.deviceId)
                    .put("expiresAt", lic.expiresAt)
                    .put("status", lic.status)
                    .put("activatedAt", lic.activatedAt)
                    .put("durationDays", lic.durationDays)
                    .put("paidAmount", lic.paidAmount)
            )
        }

        val encrypted = cipherDelegate.encrypt(
            arr.toString().toByteArray(Charsets.UTF_8)
        )

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                PREF_LICENSES,
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )
            )
            .apply()
    }

    private fun readLicenses(context: Context): List<License> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = mutableListOf<License>()

        val multiEncoded = prefs.getString(PREF_LICENSES, null)
        if (!multiEncoded.isNullOrBlank()) {
            try {
                val encrypted = Base64.decode(multiEncoded, Base64.NO_WRAP)
                val plain = cipherDelegate.decrypt(encrypted)
                val arr = JSONArray(String(plain, Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "")
                    val deviceId = obj.optString("deviceId", "")
                    if (key.isBlank() || deviceId.isBlank()) continue
                    result.add(
                        License(
                            key = key,
                            deviceId = deviceId,
                            expiresAt = obj.optLong("expiresAt", 0L),
                            status = obj.optString("status", STATUS_ACTIVE).ifBlank { STATUS_ACTIVE },
                            activatedAt = obj.optLong("activatedAt", 0L),
                            durationDays = obj.optInt("durationDays", 0),
                            paidAmount = obj.optInt("paidAmount", 0)
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }

        // Migrate any legacy single-key PREF_LICENSE record without dropping existing users.
        val legacyEncoded = prefs.getString(PREF_LICENSE, null)
        if (!legacyEncoded.isNullOrBlank()) {
            try {
                val encrypted = Base64.decode(legacyEncoded, Base64.NO_WRAP)
                val plain = cipherDelegate.decrypt(encrypted)
                val obj = JSONObject(String(plain, Charsets.UTF_8))
                val key = obj.optString("key", "")
                val deviceId = obj.optString("deviceId", "")
                val expiresAt = obj.optLong("expiresAt", 0L)
                if (key.isNotBlank() && deviceId.isNotBlank() && result.none { it.key == key }) {
                    val migrated = License(
                        key = key,
                        deviceId = deviceId,
                        expiresAt = expiresAt,
                        status = if (expiresAt <= System.currentTimeMillis()) STATUS_EXPIRED else STATUS_ACTIVE
                    )
                    result.add(migrated)
                    writeLicenses(context, result)
                }
            } catch (_: Exception) {
            }
        }

        return result
    }

    internal fun clearAllLicensesForTesting(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_LICENSE)
            .remove(PREF_LICENSES)
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        try {
            val keyStore = java.security.KeyStore
                .getInstance("AndroidKeyStore")
                .apply {
                    load(null)
                }

            val existing = keyStore.getKey(
                KEY_ALIAS,
                null
            ) as? SecretKey

            if (existing != null) return existing

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()

            generator.init(spec)

            return generator.generateKey()
        } catch (e: Exception) {
            try {
                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {}

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()

            generator.init(spec)
            return generator.generateKey()
        }
    }

    private fun aesGcmEncrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(
            "AES/GCM/NoPadding"
        )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )

        val encrypted = cipher.doFinal(plain)

        return cipher.iv + encrypted
    }

    private fun aesGcmDecrypt(payload: ByteArray): ByteArray {
        require(payload.size > 12) {
            "Invalid encrypted license"
        }

        val iv = payload.copyOfRange(0, 12)

        val encrypted = payload.copyOfRange(
            12,
            payload.size
        )

        val cipher = Cipher.getInstance(
            "AES/GCM/NoPadding"
        )

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, iv)
        )

        return cipher.doFinal(encrypted)
    }
}
