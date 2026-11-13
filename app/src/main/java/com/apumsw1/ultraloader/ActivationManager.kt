package com.apumsw1.ultraloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.Locale

object ActivationManager {

    private const val PREFS = "ultra_loader_activation"
    private const val KEY_DEVICE = "activation_device"
    private const val KEY_TIME = "activation_time"
    private const val KEY_MASTER = "master_activation"
    private const val KEY_USED = "used_activation_codes"

    private const val VALIDITY_MS =
        30L * 24L * 60L * 60L * 1000L

    private const val MASTER_HASH =
        "0daba0afd7c14e53f5c14c5c438454bd8a10fe0c9d55819b998a8ed7d6aec553"

    // Original 50 phone activation code hashes.
    private val activationHashes = setOf(
        "d9d28611418785fd2abb5a69fbadd943e334d4fc1cebb602470792f5e9c13e20",
        "1384a2eceee6a4e19652e017e04ffda4c77ffeb79514e0fd9bea1aa574f73c97",
        "10033b60a4187cfbc1f550dc6ba8c653c1e756c86a71c5c4356281721d6f5689",
        "ad75b18862ea3ab916e56f8efebc0917b84c9300d4eff4ff187cf730f406ca70",
        "0a7f15ba80fbf855c98c9149ed72d4527be04a3de6b43412f5690ec663f28b7a",
        "fc530115723459f6109ef465d5179fedcf6cba8742e292c6f6a9fc960c30d1b9",
        "a0375d063a47ffc87a90f56ddf5fbe6832a3078fab8cec94f42522a87c2bc9f9",
        "82e9c4aca25cee0a3f7f41f5f6d3d6ff312d15fe721f4714a7c617953ade0ce8",
        "f446704525bd3b729cad89a767c80d6ea9cf4a345e7e21c8932386e6c58ad426",
        "ad263c3ecd5e9b528533e00bf1e168b4ce077af7a1e3481b3d2d59a35a226188",
        "1e2cc2b59f6ccbc4ec88920ff619ff32f2715765b91f10d0e1fe50c1088646b5",
        "e591a28acebe2be49ab0f165ed232e4285c558ec9afec4584786e382d13e",
        "56dd93132aa5c43c13fc598faba0862fb6c03b261948c3551e6bbb8d1691a163",
        "c506162e4526b34f4e49466fe7213dd523a63e81656b72a6febc33661b929026",
        "4233d8230b4291f91f883f576b568ef4e1bac6b03cb9bcac8f2587c5e3b545ff",
        "f4c8b0e8a35f56eb8cae715bf58206ee30d48bbd4f2e33d6efbfff4a35923803",
        "7142072f6a19d5b5dfe149b3c2b65aa40f3ed15f95521c558cde568337fe6de7",
        "acea1c9b2bb7622703c0d94b836de037d62c475aba4791b0a203770b94849ba2",
        "bf1c38836848fd639a00401d27bf2091f331ee8462a57f8376c68607cfdbe00b",
        "3464fc9377c5e873d3f57e37e6ab04ea3c607c5a57691e5018ff9fcc5cd12b1d",
        "38c852729eceb79fe8c4ba5317bb8b2042fc23c2313a00b212704979b213e9b3",
        "1ec8838eb3c3b37b42aa82c6c0b4d53b1d084104958ed3a868a55c1cef74524f",
        "e4e6ce2f09dd458a2b6fa4732b3a5f0a18fc6402ff095afd32ab95af04370e31",
        "fecc29c6ae6026be1da44d48181b9a6cacc8a7c764e55b93ce5614876dc563dd",
        "9c2b3442aae14c78bcd1b847bc798e5ef9aeef88ea3fb760ef33d5dd9059ff84",
        "a823bb72966858159c664d850d1a52595a0846e8fdb8be12306d8310a7fd4e9b",
        "1e63b6dcec08881edc03e640ea6392d83c71108bcf0f01a497e59dc30239ab7e",
        "17fbef8a05c267c875f92055f06981ddf3628a5af2eee60dd22183644543f300",
        "c7267c190e1e35b5a4d797a50a948d5d113d2f716131726364b1a908fee77749",
        "2ef4f9ec7dc32627e4d0de50d6122657d00ef1c0a27061dd6bc35bcbd2efeb32",
        "ceb3432fb30aaf4ed3ec909e8aa943ecd2c329dc95bf2687aa05ed34f650cdd3",
        "546afff9fc66c7e3e4dd6b014aff889555e4615589a911bfafdea3469cdf7d93",
        "2ea5d489b5e0a5cf5bf292d2d7254bcb74d9f740c4f966dc845b11c2014c8f4c",
        "7e37a527758a3d1ff397add6a4538475011c24156a235010afd74cf1c3c85e15",
        "60c8fd848229dbc5da0c10a8c9de728a2147ebed0d6cbbc43af174ef06b423be",
        "6fa738322d451107c2bfc62ca01f3d7516a19c1a197627de9cdeb58d6453ada4",
        "ffb63d03bc27e7ab24c1f6ed8bf1ea8a5d3f418dddd76c2c0e8ad4be56ed9f78",
        "4b2f29168a407c6ff8529cbaaa3d1019461ca339e248c7535c5e536ea6169c37",
        "6636bc46e376b68900aca3989bef0e87619fa239e78e9018c97e65f319f7b445",
        "f19801bc6f57fcfc674916c9c83f18b5001e4728c2cd2835a8952317ccf46711",
        "5fa1d32f3d4920eb2a78fe1cb2816514181dcf8bd04c9889b23d9468ad948c85",
        "35ef43be7bc2c4986bf2a27bec1c6599359fe40c6e76f1949889031e037bba51",
        "e9fb68041e1f3d018c94e6f6b1f50d47522083ba8887482260a9fa94e6e24d35",
        "2fe6ceed26ecec6d3f05c6a674dc09c32bdc973fee9241cc83e9e99ada131173",
        "f2ea156063dec17f8be4d80e4b01fff424fd86eae1eebfa005292289ff8dff14",
        "ce3f56b6e8e0e2bfec42f58dc20125c24dbf3c9985e228d76385e402374f18bd",
        "b6d35d894f39122a36359fa1f742e3d5a0f425866fdf440a86da2b71be253c2b",
        "13a3a63519d7cfeee092eee86687da2641c7940e4bb187d0957804fac9e6f8c2",
        "54648e2b72f234a44c09c588fbab538e206dc25b216ca05c7fc066e7ea807228",
        "8ca0eb0a4df19ea4832562fb67a287c764cd390188f89bd42f9abd87f1a356d4"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-device"
    }

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(
                value.trim()
                    .uppercase(Locale.US)
                    .toByteArray(Charsets.UTF_8)
            )

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun sameDevice(context: Context): Boolean {
        val saved = prefs(context).getString(KEY_DEVICE, null)
            ?: return true

        return saved == deviceId(context)
    }

    fun hasAccess(context: Context): Boolean {
        val p = prefs(context)

        if (!sameDevice(context)) return false

        if (p.getBoolean(KEY_MASTER, false)) return true

        val started = p.getLong(KEY_TIME, 0L)
        if (started <= 0L) return false

        val now = System.currentTimeMillis()

        return now >= started &&
            now < started + VALIDITY_MS
    }

    fun statusText(context: Context): String {
        val p = prefs(context)

        if (!sameDevice(context)) {
            return "ACTIVATION: DEVICE VERIFICATION FAILED"
        }

        if (p.getBoolean(KEY_MASTER, false)) {
            return "ACTIVATION: MASTER ACCESS • PERMANENT"
        }

        val started = p.getLong(KEY_TIME, 0L)

        if (started <= 0L) {
            return "ACTIVATION: NOT ACTIVATED"
        }

        val remaining =
            started + VALIDITY_MS - System.currentTimeMillis()

        if (remaining <= 0L) {
            return "ACTIVATION: EXPIRED"
        }

        val days = remaining / 86_400_000L
        val hours = (remaining % 86_400_000L) / 3_600_000L
        val minutes = (remaining % 3_600_000L) / 60_000L

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

    private fun showActivationDialog(
        activity: Activity,
        onGranted: () -> Unit
    ) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }

        val message = TextView(activity).apply {
            text = "Enter your Phone Activation Code or Master Activation Code."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val input = EditText(activity).apply {
            hint = "XXXX-XXXX-XXXX"
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {

                    val entered = input.text.toString()
                        .trim()
                        .uppercase(Locale.US)

                    val result = activate(activity, entered)

                    when (result) {
                        "MASTER" -> {
                            dialog.dismiss()
                            Toast.makeText(
                                activity,
                                "MASTER ACTIVATION SUCCESSFUL",
                                Toast.LENGTH_LONG
                            ).show()
                            onGranted()
                        }

                        "PHONE" -> {
                            dialog.dismiss()
                            Toast.makeText(
                                activity,
                                "ACTIVATED FOR 30 DAYS",
                                Toast.LENGTH_LONG
                            ).show()
                            onGranted()
                        }

                        "USED" -> {
                            input.error =
                                "This activation code has already been used on this device."
                        }

                        "DEVICE" -> {
                            input.error =
                                "This activation belongs to another device."
                        }

                        else -> {
                            input.error = "Invalid activation code."
                        }
                    }
                }
        }

        dialog.show()
    }

    private fun activate(
        context: Context,
        code: String
    ): String {

        if (code.isBlank()) return "INVALID"

        val p = prefs(context)
        val currentDevice = deviceId(context)
        val savedDevice = p.getString(KEY_DEVICE, null)

        if (savedDevice != null && savedDevice != currentDevice) {
            return "DEVICE"
        }

        val enteredHash = hash(code)

        // Master code: permanent access.
        if (enteredHash == MASTER_HASH) {
            p.edit()
                .putString(KEY_DEVICE, currentDevice)
                .putBoolean(KEY_MASTER, true)
                .putLong(KEY_TIME, 0L)
                .apply()

            return "MASTER"
        }

        if (enteredHash !in activationHashes) {
            return "INVALID"
        }

        val used = p.getStringSet(KEY_USED, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()

        if (enteredHash in used) {
            return "USED"
        }

        used.add(enteredHash)

        p.edit()
            .putString(KEY_DEVICE, currentDevice)
            .putStringSet(KEY_USED, used)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putBoolean(KEY_MASTER, false)
            .apply()

        return "PHONE"
    }
}
