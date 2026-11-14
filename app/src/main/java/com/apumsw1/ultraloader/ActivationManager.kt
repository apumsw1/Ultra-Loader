package com.apumsw1.ultraloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ActivationManager {

    private const val PREFS = "ultra_loader_server_activation"
    private const val API_URL =
        "https://ultra-loader.ultraloader.workers.dev/api/activate"

    private const val KEY_DEVICE = "device_id"
    private const val KEY_EXPIRY = "expiry_time"
    private const val KEY_MASTER = "master_access"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    fun hasAccess(context: Context): Boolean {
        val p = prefs(context)

        if (p.getString(KEY_DEVICE, null) != deviceId(context)) {
            return false
        }

        if (p.getBoolean(KEY_MASTER, false)) return true

        val expiry = p.getLong(KEY_EXPIRY, 0L)
        return expiry > System.currentTimeMillis()
    }

    fun statusText(context: Context): String {
        if (!hasAccess(context)) {
            return "ACTIVATION: NOT ACTIVATED"
        }

        val p = prefs(context)

        if (p.getBoolean(KEY_MASTER, false)) {
            return "ACTIVATION: MASTER ACCESS"
        }

        val remaining =
            p.getLong(KEY_EXPIRY, 0L) - System.currentTimeMillis()

        val days = remaining / 86400000L
        val hours = (remaining % 86400000L) / 3600000L
        val minutes = (remaining % 3600000L) / 60000L

        return "ACTIVATION: ACTIVE • ${days}D ${hours}H ${minutes}M LEFT"
    }

    fun requireActivation(
        activity: Activity,
        onGranted: () -> Unit
    ) {
        if (hasAccess(activity)) {
            onGranted()
            return
        }

        showActivationDialog(activity, onGranted)
    }

    private fun parseExpiry(value: String): Long {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )

        for (pattern in formats) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.US)
                formatter.timeZone = TimeZone.getTimeZone("UTC")
                formatter.isLenient = false
                val date = formatter.parse(value)
                if (date != null) return date.time
            } catch (_: Exception) {
            }
        }

        return 0L
    }

    private fun verifyOnline(
        context: Context,
        code: String
    ): String {
        var connection: HttpURLConnection? = null

        return try {
            connection = URL(API_URL).openConnection()
                as HttpURLConnection

            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )

            val body = JSONObject()
                .put("key", code)
                .put("deviceId", deviceId(context))
                .toString()

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader()
                    .use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()
                    ?.use { it.readText() } ?: "{}"
            }

            val response = JSONObject(responseText)

            if (responseCode !in 200..299 ||
                !response.optBoolean("ok", false) ||
                !response.optBoolean("active", false)
            ) {
                return response.optString(
                    "error",
                    "Activation failed. Please try again."
                )
            }

            val master =
                response.optString("licenseType") == "MASTER"

            val expiryText =
                response.optString("expiresAt", "")

            val expiry = if (master) {
                0L
            } else {
                parseExpiry(expiryText)
            }

            if (!master && expiry <= System.currentTimeMillis()) {
                return "Invalid expiry time received from server."
            }

            prefs(context).edit()
                .putString(KEY_DEVICE, deviceId(context))
                .putBoolean(KEY_MASTER, master)
                .putLong(KEY_EXPIRY, expiry)
                .apply()

            if (master) "MASTER" else "SUCCESS"

        } catch (e: Exception) {
            "Connection error: ${e.message ?: "Check your internet"}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun showActivationDialog(
        activity: Activity,
        onGranted: () -> Unit
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }

        val message = TextView(activity).apply {
            text = "Enter your ULTRA LOADER Activation Key."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val input = EditText(activity).apply {
            hint = "UL-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        container.addView(message)
        container.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("ULTRA LOADER ACTIVATION")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("ACTIVATE", null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            )

            button.setOnClickListener {
                val entered = input.text.toString()
                    .trim()
                    .uppercase(Locale.US)

                if (entered.isBlank()) {
                    input.error = "Enter your activation key"
                    return@setOnClickListener
                }

                button.isEnabled = false
                button.text = "VERIFYING..."

                Thread {
                    val result = verifyOnline(activity, entered)

                    Handler(Looper.getMainLooper()).post {
                        if (activity.isFinishing ||
                            activity.isDestroyed
                        ) return@post

                        when (result) {
                            "SUCCESS", "MASTER" -> {
                                dialog.dismiss()

                                Toast.makeText(
                                    activity,
                                    if (result == "MASTER")
                                        "MASTER ACTIVATION SUCCESSFUL"
                                    else
                                        "ACTIVATION SUCCESSFUL",
                                    Toast.LENGTH_LONG
                                ).show()

                                onGranted()
                            }

                            else -> {
                                button.isEnabled = true
                                button.text = "ACTIVATE"
                                input.error = result
                            }
                        }
                    }
                }.start()
            }
        }

        dialog.show()
    }
}
