package com.apumsw1.ultraloader

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ActivationManager {

    private const val API_URL =
        "https://ultra-loader.ultraloader.workers.dev/api/activate"

    private const val TELEGRAM_URL =
        "https://t.me/ultraloader"

    private const val PREFS =
        "ultra_loader_server_activation"

    private const val KEY_ALIAS =
        "UltraLoaderActivationKey"

    private const val DEVICE_KEY = "device_id"
    private const val EXPIRY_KEY = "expiry_time"
    private const val MASTER_KEY = "master_access"
    private const val SAVED_KEY_IV = "activation_key_iv"
    private const val SAVED_KEY_DATA = "activation_key_data"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cyan = Color.rgb(0, 225, 255)
    private val green = Color.rgb(0, 230, 145)
    private val red = Color.rgb(255, 75, 95)
    private val dark = Color.rgb(4, 10, 26)
    private val panel = Color.rgb(12, 24, 43)

    @Volatile
    private var activationDialog: Dialog? = null

    @Volatile
    private var verificationRunning = false

    private var lastActivity: Activity? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        val p = prefs(context)

        val existing = p.getString(DEVICE_KEY, null)
        if (!existing.isNullOrBlank()) return existing

        val created = UUID.randomUUID().toString()

        p.edit()
            .putString(DEVICE_KEY, created)
            .apply()

        return created
    }

    fun hasAccess(context: Context): Boolean {
        val p = prefs(context)

        val storedDevice = p.getString(DEVICE_KEY, null)
        val currentDevice = deviceId(context)

        if (storedDevice != currentDevice) return false

        if (p.getBoolean(MASTER_KEY, false)) {
            return true
        }

        val expiry = p.getLong(EXPIRY_KEY, 0L)

        return expiry > System.currentTimeMillis()
    }

    fun statusText(context: Context): String {
        val p = prefs(context)

        if (!hasAccess(context)) {
            return "● ACTIVATION REQUIRED"
        }

        if (p.getBoolean(MASTER_KEY, false)) {
            return "● MASTER LICENSE ACTIVE"
        }

        val expiry = p.getLong(EXPIRY_KEY, 0L)
        val remaining = expiry - System.currentTimeMillis()

        if (remaining <= 0L) {
            return "● LICENSE EXPIRED"
        }

        val days = remaining / 86_400_000L
        val hours = (remaining % 86_400_000L) / 3_600_000L

        return "● LICENSE ACTIVE • ${days}D ${hours}H LEFT"
    }

    fun remainingTimeText(context: Context): String {
        val p = prefs(context)

        if (!hasAccess(context)) {
            return "লাইসেন্স সক্রিয় নয়"
        }

        if (p.getBoolean(MASTER_KEY, false)) {
            return "MASTER LICENSE • স্থায়ী অ্যাক্সেস"
        }

        val remaining =
            p.getLong(EXPIRY_KEY, 0L) - System.currentTimeMillis()

        if (remaining <= 0L) {
            return "লাইসেন্সের মেয়াদ শেষ"
        }

        val days = remaining / 86_400_000L
        val hours = (remaining % 86_400_000L) / 3_600_000L
        val minutes = (remaining % 3_600_000L) / 60_000L

        return "বাকি সময়: ${days} দিন ${hours} ঘণ্টা ${minutes} মিনিট"
    }

    fun requireActivation(
        activity: Activity,
        onGranted: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        lastActivity = activity

        // Always validate the saved key with the server.
        showActivationScreen(activity, onGranted)
    }

    private fun showActivationScreen(
        activity: Activity,
        onGranted: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (activationDialog?.isShowing == true) return

        val dialog = Dialog(activity)
        activationDialog = dialog

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(false)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 28, 24, 24)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(3, 9, 25),
                    Color.rgb(8, 22, 43),
                    Color.rgb(3, 9, 25)
                )
            ).apply {
                cornerRadius = 24f
                setStroke(2, cyan)
            }
        }

        val title = TextView(activity).apply {
            text = "⚡ ULTRA LOADER ⚡"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }

        val subtitle = TextView(activity).apply {
            text = "ACTIVATION & LICENSE CONTROL"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(4, 8, 4, 28)
            letterSpacing = 0.08f
        }

        val info = TextView(activity).apply {
            text = "SECURE ONLINE LICENSE VERIFICATION"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(8, 14, 8, 14)
            background = rounded(panel, green)
        }

        val keyInput = EditText(activity).apply {
            hint = "Enter your activation key"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(16, 12, 16, 12)
            background = rounded(panel, cyan)
        }

        val result = TextView(activity).apply {
            text = "Waiting for activation..."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(8, 16, 8, 16)
        }

        val activate = Button(activity).apply {
            text = "ACTIVATE"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(0, 85, 120), cyan)
        }

        val buy = Button(activity).apply {
            text = "BUY ACTIVATION KEY"
            isAllCaps = false
            textSize = 13f
            setTextColor(green)
            background = rounded(panel, green)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(info)
        root.addView(keyInput, matchWrap())
        root.addView(result)
        root.addView(activate, matchWrap())
        root.addView(buy, matchWrap())

        dialog.setContentView(root)

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        buy.setOnClickListener {
            try {
                activity.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(TELEGRAM_URL)
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(
                    activity,
                    "Unable to open Telegram",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        activate.setOnClickListener {
            val entered = keyInput.text.toString().trim()

            if (entered.isBlank()) {
                result.text = "Please enter your activation key"
                result.setTextColor(red)
                return@setOnClickListener
            }

            activate.isEnabled = false
            keyInput.isEnabled = false

            result.text = "CONNECTING TO LICENSE SERVER..."
            result.setTextColor(cyan)

            verifyOnline(activity, entered) { success, message ->
                if (activity.isFinishing || activity.isDestroyed) return@verifyOnline

                activate.isEnabled = true
                keyInput.isEnabled = true

                if (success) {
                    result.text = message
                    result.setTextColor(green)

                    dialog.dismiss()
                    activationDialog = null

                    onGranted()
                } else {
                    result.text = message
                    result.setTextColor(red)
                }
            }
        }

        dialog.setOnDismissListener {
            if (activationDialog === dialog) {
                activationDialog = null
            }
        }

        dialog.show()

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        // Automatically validate the previously saved key.
        val savedKey = decryptSavedKey(activity)

        if (!savedKey.isNullOrBlank()) {
            keyInput.setText(savedKey)

            activate.isEnabled = false
            keyInput.isEnabled = false
            result.text = "REVALIDATING SAVED LICENSE..."
            result.setTextColor(cyan)

            verifyOnline(activity, savedKey) { success, message ->
                if (activity.isFinishing || activity.isDestroyed) return@verifyOnline

                activate.isEnabled = true
                keyInput.isEnabled = true

                if (success) {
                    result.text = message
                    result.setTextColor(green)

                    dialog.dismiss()
                    activationDialog = null

                    onGranted()
                } else {
                    result.text = message
                    result.setTextColor(red)
                }
            }
        }
    }

    private fun verifyOnline(
        context: Context,
        key: String,
        callback: (Boolean, String) -> Unit
    ) {
        if (verificationRunning) return

        verificationRunning = true

        executor.execute {
            var success = false
            var message = "Unable to connect to license server"

            var connection: HttpURLConnection? = null

            try {
                val device = deviceId(context)

                connection = (
                    URL(API_URL).openConnection()
                    as HttpURLConnection
                )

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val body = JSONObject().apply {
                    put("key", key)
                    put("deviceId", device)
                }.toString()

                connection.outputStream.use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }

                val code = connection.responseCode

                val stream =
                    if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val responseText =
                    stream?.bufferedReader()?.use { it.readText() }
                        ?: "{}"

                val json = JSONObject(responseText)

                val active = json.optBoolean("active", false)
                val ok = json.optBoolean("ok", false)

                if (code in 200..299 && ok && active) {
                    val licenseType =
                        json.optString("licenseType", "STANDARD")

                    val expiryText =
                        json.optString("expiresAt", "")

                    val expiry =
                        parseExpiry(expiryText)

                    if (
                        licenseType != "MASTER" &&
                        expiry <= System.currentTimeMillis()
                    ) {
                        message = "LICENSE EXPIRED"
                    } else {
                        saveActivation(
                            context,
                            key,
                            expiry,
                            licenseType == "MASTER"
                        )

                        success = true

                        message =
                            if (licenseType == "MASTER") {
                                "MASTER LICENSE ACTIVATED"
                            } else {
                                "LICENSE VERIFIED SUCCESSFULLY"
                            }
                    }
                } else {
                    message = json.optString(
                        "error",
                        "INVALID, EXPIRED OR REVOKED KEY"
                    )
                }

            } catch (_: java.net.SocketTimeoutException) {
                message = "SERVER TIMEOUT — CHECK INTERNET"
            } catch (_: java.net.UnknownHostException) {
                message = "LICENSE SERVER UNAVAILABLE"
            } catch (_: Exception) {
                message = "NETWORK ERROR — PLEASE TRY AGAIN"
            } finally {
                connection?.disconnect()
                verificationRunning = false
            }

            mainHandler.post {
                callback(success, message)
            }
        }
    }

    private fun saveActivation(
        context: Context,
        key: String,
        expiry: Long,
        master: Boolean
    ) {
        val encrypted = encryptKey(key)

        prefs(context).edit()
            .putLong(EXPIRY_KEY, expiry)
            .putBoolean(MASTER_KEY, master)
            .putString(SAVED_KEY_IV, encrypted.first)
            .putString(SAVED_KEY_DATA, encrypted.second)
            .apply()
    }

    private fun encryptKey(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getSecretKey()
        )

        val encrypted = cipher.doFinal(
            value.toByteArray(StandardCharsets.UTF_8)
        )

        return Pair(
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        )
    }

    private fun decryptSavedKey(context: Context): String? {
        return try {
            val p = prefs(context)

            val ivText = p.getString(SAVED_KEY_IV, null)
                ?: return null

            val dataText = p.getString(SAVED_KEY_DATA, null)
                ?: return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(
                    128,
                    Base64.decode(ivText, Base64.NO_WRAP)
                )
            )

            String(
                cipher.doFinal(
                    Base64.decode(dataText, Base64.NO_WRAP)
                ),
                StandardCharsets.UTF_8
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun getSecretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)

        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

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
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)

        return generator.generateKey()
    }

    private fun parseExpiry(value: String): Long {
        if (value.isBlank()) return 0L

        return try {
            val normalized =
                if (value.endsWith("Z")) value
                else value

            java.time.Instant.parse(normalized).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(value)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    private fun rounded(
        color: Int,
        stroke: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f
            setStroke(2, stroke)
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }
    }
}
