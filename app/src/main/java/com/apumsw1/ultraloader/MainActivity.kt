
package com.apumsw1.ultraloader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

    companion object {
        private const val PORTAL_URL =
            "https://guest7.ic.peplink.com/cp/"

        private const val VPN_PACKAGE =
            "com.fast.free.unblock.thunder.vpn"

        private const val KEY_ALIAS =
            "UltraLoaderCredentialKey"

        private const val PREFS =
            "ultra_loader_secure"
    }

    private val cyan = Color.rgb(0, 220, 255)
    private val dark = Color.rgb(7, 13, 27)
    private val panel = Color.rgb(17, 29, 49)
    private val green = Color.rgb(0, 230, 140)
    private val red = Color.rgb(255, 75, 85)

    private lateinit var status: TextView
    private lateinit var internetStatus: TextView
    private lateinit var startButton: Button
    private lateinit var webView: WebView

    private val handler = Handler(Looper.getMainLooper())

    private var startInProgress = false
    private var vpnLaunchRequested = false
    private var loginAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUI()
        updateInternetStatus()
    }

    private fun createUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
            setPadding(18, 18, 18, 12)
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "ULTRA LOADER"
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(5, 20, 5, 4)
        }

        val subtitle = TextView(this).apply {
            text = "SMART NETWORK ACCESS"
            textSize = 11f
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(5, 0, 5, 20)
        }

        val statusPanel = createPanel()

        status = TextView(this).apply {
            text = "● SYSTEM READY"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16)
        }

        statusPanel.addView(status)

        val internetPanel = createPanel()

        internetStatus = TextView(this).apply {
            text = "INTERNET: CHECKING..."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(10, 12, 10, 12)
        }

        internetPanel.addView(internetStatus)

        startButton = Button(this).apply {
            text = "START FREE NET"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setBackgroundColor(cyan)
            isAllCaps = false
            setPadding(10, 15, 10, 15)

            setOnClickListener {
                startFreeNet()
            }
        }

        val portalTitle = TextView(this).apply {
            text = "PEPLINK PORTAL"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            setPadding(5, 18, 5, 8)
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    status.text = "● PORTAL LOADING..."
                    status.setTextColor(cyan)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    status.text = "● PORTAL PAGE LOADED"
                    status.setTextColor(green)

                    if (!loginAttempted) {
                        loginAttempted = true
                        attemptPortalLogin()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        status.text = "● PORTAL LOAD ERROR"
                        status.setTextColor(red)
                    }
                }
            }
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(statusPanel)
        content.addView(internetPanel)
        content.addView(startButton)
        content.addView(portalTitle)

        scroll.addView(webView)

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun createPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(5, 5, 5, 5)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.setMargins(0, 5, 0, 7)
            layoutParams = params
        }
    }

    private fun startFreeNet() {
        if (startInProgress) {
            updateInternetStatus()
            return
        }

        val credentials = readCredentials()

        if (credentials == null) {
            showCredentialDialog()
            return
        }

        startInProgress = true
        vpnLaunchRequested = false
        loginAttempted = false

        startButton.isEnabled = false
        startButton.text = "STARTING..."

        status.text = "● OPENING PEPLINK PORTAL..."
        status.setTextColor(cyan)

        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl(PORTAL_URL)
    }

    private fun showCredentialDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 5)
        }

        val userInput = EditText(this).apply {
            hint = "User ID"
            setSingleLine(true)
        }

        val passwordInput = EditText(this).apply {
            hint = "Password"
            setSingleLine(true)
            inputType = 129
        }

        box.addView(userInput)
        box.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Save Portal Login")
            .setMessage(
                "Enter your Peplink Portal credentials. " +
                    "They will be encrypted on this phone."
            )
            .setView(box)
            .setPositiveButton("SAVE AND START") { _, _ ->

                val user = userInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (user.isBlank() || password.isBlank()) {
                    Toast.makeText(
                        this,
                        "Enter both User ID and Password",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }

                if (saveCredentials(user, password)) {
                    startFreeNet()
                } else {
                    status.text = "● CREDENTIAL SAVE FAILED"
                    status.setTextColor(red)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun attemptPortalLogin() {
        val credentials = readCredentials() ?: return

        val user = Base64.encodeToString(
            credentials.first.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )

        val pass = Base64.encodeToString(
            credentials.second.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )

        val script = """
            (function() {
                function decode(v) {
                    try {
                        return decodeURIComponent(escape(atob(v)));
                    } catch(e) {
                        return atob(v);
                    }
                }

                var username = decode('$user');
                var password = decode('$pass');

                var inputs = Array.from(
                    document.querySelectorAll('input')
                );

                var userField = inputs.find(function(el) {
                    var t = (el.type || '').toLowerCase();
                    var n = (el.name || '').toLowerCase();
                    var p = (el.placeholder || '').toLowerCase();

                    return t === 'email' ||
                           t === 'text' ||
                           n.includes('user') ||
                           n.includes('email') ||
                           p.includes('user') ||
                           p.includes('email');
                });

                var passField = inputs.find(function(el) {
                    return (el.type || '').toLowerCase() === 'password';
                });

                if (userField && passField) {
                    userField.value = username;
                    passField.value = password;

                    userField.dispatchEvent(
                        new Event('input', {bubbles:true})
                    );

                    passField.dispatchEvent(
                        new Event('input', {bubbles:true})
                    );

                    var form = passField.closest('form');

                    if (form) {
                        form.requestSubmit();
                    } else {
                        var button = document.querySelector(
                            'button[type=submit], input[type=submit]'
                        );

                        if (button) button.click();
                    }

                    return 'LOGIN_FORM_SUBMITTED';
                }

                return 'LOGIN_FORM_NOT_FOUND';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->

            if (result?.contains("LOGIN_FORM_SUBMITTED") == true) {
                status.text = "● LOGIN SUBMITTED"
                status.setTextColor(green)

                handler.postDelayed({
                    openThunderVpn()
                }, 3500)

            } else {
                status.text = "● PORTAL READY - CHECK PAGE"
                status.setTextColor(cyan)

                handler.postDelayed({
                    openThunderVpn()
                }, 5000)
            }
        }
    }

    private fun openThunderVpn() {
        if (vpnLaunchRequested) return

        vpnLaunchRequested = true

        val launchIntent =
            packageManager.getLaunchIntentForPackage(VPN_PACKAGE)

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)

            status.text = "● THUNDER VPN OPENED"
            status.setTextColor(cyan)

        } else {
            status.text = "● THUNDER VPN NOT INSTALLED"
            status.setTextColor(red)

            Toast.makeText(
                this,
                "Install Thunder VPN first",
                Toast.LENGTH_LONG
            ).show()
        }

        startInProgress = false
        startButton.isEnabled = true
        startButton.text = "START FREE NET"
    }

    private fun updateInternetStatus() {
        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val network = manager.activeNetwork
        val capabilities =
            manager.getNetworkCapabilities(network)

        val hasInternet =
            capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true

        val validated =
            capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true

        if (hasInternet && validated) {
            internetStatus.text = "INTERNET: CONNECTED"
            internetStatus.setTextColor(green)
        } else {
            internetStatus.text = "INTERNET: NOT VERIFIED"
            internetStatus.setTextColor(red)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance(
            "AndroidKeyStore"
        )

        keyStore.load(null)

        val existing = keyStore.getKey(KEY_ALIAS, null)

        if (existing is SecretKey) {
            return existing
        }

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

    private fun saveCredentials(
        username: String,
        password: String
    ): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

            val plain = "$username\n$password"
                .toByteArray(StandardCharsets.UTF_8)

            val encrypted = cipher.doFinal(plain)

            val iv = Base64.encodeToString(
                cipher.iv,
                Base64.NO_WRAP
            )

            val data = Base64.encodeToString(
                encrypted,
                Base64.NO_WRAP
            )

            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString("iv", iv)
                .putString("data", data)
                .apply()

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readCredentials(): Pair<String, String>? {
        return try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

            val ivText = prefs.getString("iv", null) ?: return null
            val dataText = prefs.getString("data", null) ?: return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val data = Base64.decode(dataText, Base64.NO_WRAP)

            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(128, iv)
            )

            val plain = cipher.doFinal(data)
                .toString(StandardCharsets.UTF_8)

            val parts = plain.split("\n", limit = 2)

            if (parts.size != 2) return null

            Pair(parts[0], parts[1])
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        updateInternetStatus()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
