package com.apumsw1.ultraloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

object ActivationManager {

    private const val API_URL =
        "https://ultra-loader.ultraloader.workers.dev/api/activate"

    private const val PREFS = "ultra_loader_activation_v1"
    private const val PREF_LICENSE = "encrypted_license"
    private const val PREF_DEVICE_ID = "stable_device_id"
    private const val KEY_ALIAS = "UltraLoaderActivationKeyV1"

    private const val CONNECT_TIMEOUT = 12000
    private const val READ_TIMEOUT = 15000

    private val executor = Executors.newSingleThreadExecutor()

    private data class License(
        val key: String,
        val deviceId: String,
        val expiresAt: Long
    )

    @Volatile
    private var lastStatus = "NOT ACTIVATED"

    fun hasAccess(context: Context): Boolean {
        val license = readLicense(context) ?: return false
        return license.expiresAt > System.currentTimeMillis()
    }

    fun statusText(context: Context): String {
        val license = readLicense(context)

        if (license == null) {
            lastStatus = "NOT ACTIVATED"
            return "LICENSE: NOT ACTIVATED"
        }

        if (license.expiresAt <= System.currentTimeMillis()) {
            lastStatus = "EXPIRED"
            return "LICENSE: EXPIRED"
        }

        if (license.expiresAt == Long.MAX_VALUE) {
            lastStatus = "ACTIVE (PERMANENT)"
            return "LICENSE: ACTIVE (PERMANENT)"
        }

        val formatted = SimpleDateFormat(
            "dd MMM yyyy, hh:mm a",
            Locale.getDefault()
        ).format(Date(license.expiresAt))

        lastStatus = "ACTIVE"
        return "LICENSE: ACTIVE\nEXPIRES: $formatted"
    }

    fun requireActivation(
        activity: Activity,
        onActivated: () -> Unit
    ) {
        if (hasAccess(activity)) {
            onActivated()
            return
        }

        if (activity.isFinishing ||
            (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)
        ) return

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 8)
        }

        val heading = TextView(activity).apply {
            text = "ULTRA LOADER ACTIVATION"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        val explanation = TextView(activity).apply {
            text = "Enter your activation key. Internet is required for the first activation."
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }

        val keyInput = EditText(activity).apply {
            hint = "Activation key"
            setSingleLine(true)
            textSize = 16f
            setSelectAllOnFocus(false)
        }

        container.addView(
            heading,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(explanation)

        container.addView(
            keyInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("EXIT", null)
            .setPositiveButton("ACTIVATE", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
                .setOnClickListener {
                    dialog.dismiss()
                    activity.finish()
                }

            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener {

                    val key = keyInput.text.toString().trim()

                    if (key.isEmpty()) {
                        keyInput.error = "Enter your activation key"
                        return@setOnClickListener
                    }

                    val button =
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE)

                    button.isEnabled = false
                    button.text = "CHECKING..."

                    executor.execute {
                        val result = sendActivation(
                            key,
                            getDeviceId(activity)
                        )

                        activity.runOnUiThread {

                            if (activity.isFinishing ||
                                (Build.VERSION.SDK_INT >= 17 &&
                                    activity.isDestroyed)
                            ) return@runOnUiThread

                            if (result.status == "VALID" &&
                                result.license != null
                            ) {
                                saveLicense(activity, result.license)
                                lastStatus = "ACTIVE"

                                dialog.dismiss()

                                Toast.makeText(
                                    activity,
                                    "Activation successful",
                                    Toast.LENGTH_LONG
                                ).show()

                                onActivated()

                            } else {
                                button.isEnabled = true
                                button.text = "ACTIVATE"

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
        }

        dialog.show()
    }

    fun verifySavedLicense(
        context: Context,
        callback: (String) -> Unit
    ) {
        val saved = readLicense(context)

        if (saved == null) {
            callback("NO_LICENSE")
            return
        }

        if (saved.expiresAt <= System.currentTimeMillis()) {
            removeLicense(context)
            lastStatus = "EXPIRED"
            callback("EXPIRED")
            return
        }

        executor.execute {
            val result = sendActivation(
                saved.key,
                saved.deviceId
            )

            if (result.status == "VALID" &&
                result.license != null
            ) {
                saveLicense(context, result.license)
                lastStatus = "ACTIVE"

            } else if (
                result.status == "INVALID" ||
                result.status == "REVOKED" ||
                result.status == "EXPIRED"
            ) {
                removeLicense(context)
                lastStatus = result.status
            }

            callback(result.status)
        }
    }

    private data class ActivationResult(
        val status: String,
        val message: String,
        val license: License? = null
    )

    private fun sendActivation(
        key: String,
        deviceId: String
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

            val body = JSONObject()
                .put("key", key)
                .put("deviceId", deviceId)
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
                            key,
                            deviceId,
                            expiry
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

    private fun saveLicense(
        context: Context,
        license: License
    ) {
        val json = JSONObject()
            .put("key", license.key)
            .put("deviceId", license.deviceId)
            .put("expiresAt", license.expiresAt)
            .toString()

        val encrypted = encrypt(
            json.toByteArray(Charsets.UTF_8)
        )

        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .putString(
                PREF_LICENSE,
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )
            )
            .apply()
    }

    private fun readLicense(context: Context): License? {
        return try {
            val prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            val encoded = prefs.getString(
                PREF_LICENSE,
                null
            ) ?: return null

            val encrypted = Base64.decode(
                encoded,
                Base64.NO_WRAP
            )

            val plain = decrypt(encrypted)

            val json = JSONObject(
                String(plain, Charsets.UTF_8)
            )

            License(
                key = json.getString("key"),
                deviceId = json.getString("deviceId"),
                expiresAt = json.getLong("expiresAt")
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun removeLicense(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(PREF_LICENSE)
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
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
    }

    private fun encrypt(plain: ByteArray): ByteArray {
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

    private fun decrypt(payload: ByteArray): ByteArray {
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
