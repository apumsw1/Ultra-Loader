package com.apumsw1.ultraloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter

object ActivationManager {

    private const val API_URL =
        "https://ultra-loader.ultraloader.workers.dev/api/activate"

    private const val CONTACT_URL =
        "https://t.me/ultraloader"

    private const val PREFS = "ultra_loader_activation_v1"
    private const val PREF_LICENSE = "encrypted_license"
    private const val PREF_DEVICE_ID = "stable_device_id"
    private const val KEY_ALIAS = "UltraLoaderActivationKeyV1"

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

    private data class License(
        val key: String,
        val deviceId: String,
        val expiresAt: Long
    )

    @Volatile
    private var lastStatus = "NOT ACTIVATED"

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
        background: android.graphics.drawable.Drawable,
        color: Int
    ): TextView {
        return TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            gravity = Gravity.CENTER
            background = background
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

        // Animated logo
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

        // Activation panel
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
            singleLine = true
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
            .setCancelable(false)
            .create()

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

            if (context is Activity) {
                context.runOnUiThread {
                    callback(result.status)
                }
            } else {
                callback(result.status)
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
