package com.apumsw1.ultraloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

    companion object {
        private const val VPN_PACKAGE =
            "com.fast.free.unblock.thunder.vpn"

        private const val FALLBACK_PORTAL =
            "https://guest7.ic.peplink.com/cp/login?"

        private const val PREFS = "ultra_loader_secure"
        private const val KEY_ALIAS = "UltraLoaderCredentialKey"

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"
    }

    private enum class Stage {
        IDLE,
        LOGIN,
        CHECK_LOGIN,
        WAIT_VPN,
        LOGOUT,
        WAIT_SECOND_LOGIN,
        FINISHED
    }

    private val cyan = Color.rgb(0, 220, 255)
    private val dark = Color.rgb(7, 13, 27)
    private val green = Color.rgb(0, 220, 130)
    private val red = Color.rgb(255, 75, 85)

    private lateinit var status: TextView
    private lateinit var internetStatus: TextView
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var webView: WebView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var stage = Stage.IDLE
    private var vpnOpened = false
    private var loginSubmitted = false
    private var portalUrl = FALLBACK_PORTAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createScreen()
        requestNotificationPermission()
        startMonitorService()

        if (intent?.action == ACTION_OPEN_PORTAL) {
            handler.postDelayed({
                startPortalProcess()
            }, 500)
        }

        updateInternetStatus()
    }

    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(dark)
        }

        val title = TextView(this).apply {
            text = "ULTRA LOADER"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(5, 18, 5, 5)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = "SMART WI-FI PORTAL ASSISTANT"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(5, 0, 5, 18)
        }

        status = TextView(this).apply {
            text = "● SYSTEM READY"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(10, 15, 10, 15)
        }

        internetStatus = TextView(this).apply {
            text = "INTERNET: CHECKING..."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(10, 12, 10, 12)
        }

        userInput = EditText(this).apply {
            hint = "Portal User ID"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        passwordInput = EditText(this).apply {
            hint = "Portal Password"
            setSingleLine(true)
            inputType = 129
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val saveButton = makeButton("SAVE LOGIN DETAILS") {
            val user = userInput.text.toString().trim()
            val pass = passwordInput.text.toString()

            if (user.isBlank() || pass.isBlank()) {
                toast("Please enter User ID and Password")
            } else if (saveCredentials(user, pass)) {
                toast("Login details encrypted and saved")
                statusText("● LOGIN DETAILS SAVED", green)
            } else {
                statusText("● SAVE FAILED", red)
            }
        }

        val startButton = makeButton("START ULTRA LOADER") {
            val saved = readCredentials()
            if (saved == null) {
                showCredentialDialog()
            } else {
                startPortalProcess()
            }
        }

        val vpnButton = makeButton("OPEN THUNDER VPN") {
            openThunderVpn()
        }

        val checkButton = makeButton("CHECK INTERNET") {
            updateInternetStatus()
            checkVpnStatus()
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)

            settings.userAgentString =
                WebSettings.getDefaultUserAgent(this@MainActivity)
                    .replace("; wv", "")

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance()
                .setAcceptThirdPartyCookies(this, true)

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    statusText("● PORTAL PAGE LOADING...", cyan)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    when (stage) {
                        Stage.LOGIN -> tryAutoLogin()
                        Stage.CHECK_LOGIN -> checkPortalLogin()
                        Stage.WAIT_SECOND_LOGIN -> checkSecondLoginForm()
                        else -> {
                            if (stage == Stage.IDLE) {
                                statusText("● PORTAL PAGE READY", green)
                            }
                        }
                    }
                }
            }
        }

        val webTitle = TextView(this).apply {
            text = "PORTAL LOGIN PAGE"
            textSize = 12f
            setTextColor(cyan)
            setPadding(5, 12, 5, 6)
        }

        val scroll = ScrollView(this)

        // FIXED: Use ViewGroup.LayoutParams instead of ScrollView.LayoutParams.
        scroll.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                850
            )
        )

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(internetStatus)
        root.addView(userInput)
        root.addView(passwordInput)
        root.addView(saveButton)
        root.addView(startButton)
        root.addView(vpnButton)
        root.addView(checkButton)
        root.addView(webTitle)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        readCredentials()?.let {
            userInput.setText(it.first)
            passwordInput.setText(it.second)
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.BLACK)
            setBackgroundColor(cyan)
            textSize = 14f
            setPadding(8, 8, 8, 8)
            setOnClickListener { action() }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 4, 0, 4)
            layoutParams = params
        }
    }

    private fun statusText(message: String, color: Int) {
        status.text = message
        status.setTextColor(color)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                501
            )
        }
    }

    private fun startMonitorService() {
        try {
            val serviceIntent =
                Intent(this, PortalMonitorService::class.java)

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            statusText("● WIFI MONITOR NOT STARTED", red)
        }
    }

    private fun showCredentialDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 5)
        }

        val user = EditText(this).apply {
            hint = "User ID"
            setSingleLine(true)
        }

        val pass = EditText(this).apply {
            hint = "Password"
            inputType = 129
            setSingleLine(true)
        }

        box.addView(user)
        box.addView(pass)

        AlertDialog.Builder(this)
            .setTitle("Portal Login")
            .setMessage("Enter your Portal User ID and Password.")
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                val u = user.text.toString().trim()
                val p = pass.text.toString()

                if (u.isNotBlank() && p.isNotBlank()) {
                    if (saveCredentials(u, p)) {
                        userInput.setText(u)
                        passwordInput.setText(p)
                        startPortalProcess()
                    } else {
                        toast("Unable to save credentials")
                    }
                } else {
                    toast("Both fields are required")
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun startPortalProcess() {
        if (readCredentials() == null) {
            showCredentialDialog()
            return
        }

        stage = Stage.LOGIN
        loginSubmitted = false
        vpnOpened = false

        statusText("● FINDING CURRENT PORTAL...", cyan)

        discoverPortalUrl()
    }

    private fun discoverPortalUrl() {
        executor.execute {
            var found: String? = null

            val manager =
                getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            val network = manager.activeNetwork

            val probes = listOf(
                "http://connectivitycheck.gstatic.com/generate_204",
                "http://www.google.com/generate_204",
                "http://clients3.google.com/generate_204"
            )

            for (probe in probes) {
                if (found != null) break

                var connection: HttpURLConnection? = null

                try {
                    connection = if (network != null) {
                        network.openConnection(URL(probe))
                    } else {
                        URL(probe).openConnection()
                    } as HttpURLConnection

                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 Android"
                    )

                    val code = connection.responseCode
                    val location =
                        connection.getHeaderField("Location")

                    if (code in 300..399 &&
                        !location.isNullOrBlank() &&
                        location.startsWith("http")
                    ) {
                        found = location
                    }
                } catch (_: Exception) {
                    // Try the next connectivity probe.
                } finally {
                    connection?.disconnect()
                }
            }

            runOnUiThread {
                portalUrl = found ?: FALLBACK_PORTAL

                statusText(
                    if (found != null)
                        "● CURRENT PORTAL FOUND"
                    else
                        "● USING PORTAL FALLBACK",
                    if (found != null) green else cyan
                )

                webView.visibility = View.VISIBLE
                webView.loadUrl(portalUrl)
            }
        }
    }

    private fun tryAutoLogin() {
        if (stage != Stage.LOGIN || loginSubmitted) return

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
                function dec(s) {
                    try {
                        return decodeURIComponent(escape(atob(s)));
                    } catch(e) {
                        return atob(s);
                    }
                }

                var u = dec('$user');
                var p = dec('$pass');
                var all = Array.from(document.querySelectorAll('input'));

                var pf = all.find(function(x) {
                    return (x.type || '').toLowerCase() === 'password';
                });

                var uf = all.find(function(x) {
                    if (x === pf) return false;
                    var n = ((x.name || '') + ' ' +
                             (x.id || '') + ' ' +
                             (x.placeholder || '')).toLowerCase();

                    return ['user','email','login','account']
                        .some(function(k) { return n.includes(k); }) ||
                        ['text','email'].includes(
                            (x.type || '').toLowerCase()
                        );
                });

                if (!uf || !pf) return 'FORM_NOT_FOUND';

                uf.focus();
                uf.value = u;
                pf.focus();
                pf.value = p;

                [uf,pf].forEach(function(x) {
                    x.dispatchEvent(new Event('input',{bubbles:true}));
                    x.dispatchEvent(new Event('change',{bubbles:true}));
                });

                Array.from(document.querySelectorAll(
                    'input[type=checkbox]'
                )).forEach(function(c) {
                    var label = (
                        (c.name || '') + ' ' +
                        (c.id || '') + ' ' +
                        (c.parentElement ? c.parentElement.innerText : '')
                    ).toLowerCase();

                    if (/remember|save|keep me/.test(label)) {
                        c.checked = true;
                        c.dispatchEvent(
                            new Event('change',{bubbles:true})
                        );
                    }
                });

                var form = pf.closest('form');

                if (form) {
                    var submit = form.querySelector(
                        'button[type=submit],input[type=submit]'
                    );

                    if (submit) submit.click();
                    else if (form.requestSubmit) form.requestSubmit();
                    else form.submit();

                    return 'SUBMITTED';
                }

                var buttons = Array.from(document.querySelectorAll(
                    'button,input[type=button],input[type=submit]'
                ));

                var login = buttons.find(function(b) {
                    return /log.?in|sign.?in|connect|continue/i
                        .test(b.innerText || b.value || '');
                });

                if (login) {
                    login.click();
                    return 'SUBMITTED';
                }

                return 'NO_SUBMIT_BUTTON';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result?.contains("SUBMITTED") == true) {
                loginSubmitted = true
                stage = Stage.CHECK_LOGIN
                statusText("● LOGIN SUBMITTED - VERIFYING...", cyan)

                handler.postDelayed({
                    checkPortalLogin()
                }, 3000)
            } else if (result?.contains("FORM_NOT_FOUND") == true) {
                statusText("● PORTAL LOGIN FORM NOT FOUND", red)
            } else {
                statusText("● CHECKING PORTAL FORM...", cyan)
                handler.postDelayed({
                    tryAutoLogin()
                }, 2000)
            }
        }
    }

    private fun checkPortalLogin() {
        if (stage != Stage.CHECK_LOGIN) return

        val script = """
            (function() {
                var text = (document.body ? document.body.innerText : '')
                    .toLowerCase();

                var hasPassword = !!document.querySelector(
                    'input[type=password]'
                );

                var logout = /log.?out|sign.?out/.test(text);
                var success = /welcome|successfully connected|connected successfully/
                    .test(text);

                if (!hasPassword && (logout || success)) return 'LOGIN_OK';
                if (hasPassword) return 'LOGIN_FORM_STILL_HERE';

                return 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            when {
                result?.contains("LOGIN_OK") == true -> {
                    statusText("● PORTAL LOGIN VERIFIED", green)
                    handler.postDelayed({
                        openThunderVpn()
                    }, 2500)
                }

                result?.contains("LOGIN_FORM_STILL_HERE") == true -> {
                    statusText("● LOGIN NOT CONFIRMED", red)
                    handler.postDelayed({
                        checkPortalLogin()
                    }, 3000)
                }

                else -> {
                    handler.postDelayed({
                        checkPortalLogin()
                    }, 2500)
                }
            }
        }
    }

    private fun openThunderVpn() {
        if (vpnOpened) return
        vpnOpened = true
        stage = Stage.WAIT_VPN

        val launch =
            packageManager.getLaunchIntentForPackage(VPN_PACKAGE)

        if (launch == null) {
            statusText("● THUNDER VPN NOT INSTALLED", red)
            showVpnDialog()
            return
        }

        try {
            statusText("● OPENING THUNDER VPN...", cyan)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)

            handler.postDelayed({
                checkVpnStatus()
            }, 2500)
        } catch (e: Exception) {
            statusText("● VPN OPEN FAILED", red)
            showVpnDialog()
        }
    }

    private fun isVpnConnected(): Boolean {
        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun checkVpnStatus() {
        if (isVpnConnected()) {
            statusText("● VPN CONNECTION DETECTED", green)

            if (stage == Stage.WAIT_VPN) {
                stage = Stage.LOGOUT
                handler.postDelayed({
                    clickPortalLogout()
                }, 1000)
            }
        } else {
            statusText("● PLEASE CONNECT VPN", red)
            showVpnDialog()
        }

        updateInternetStatus()
    }

    private fun showVpnDialog() {
        AlertDialog.Builder(this)
            .setTitle("Thunder VPN")
            .setMessage(
                "Tap Connect in Thunder VPN. " +
                "Then return to Ultra Loader and check again."
            )
            .setPositiveButton("OPEN THUNDER VPN") { _, _ ->
                vpnOpened = false
                openThunderVpn()
            }
            .setNeutralButton("I CONNECTED - CHECK") { _, _ ->
                checkVpnStatus()
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun clickPortalLogout() {
        if (stage != Stage.LOGOUT) return

        statusText("● LOOKING FOR PORTAL LOGOUT...", cyan)

        val script = """
            (function() {
                var nodes = Array.from(document.querySelectorAll(
                    'a,button,input[type=button],input[type=submit]'
                ));

                var item = nodes.find(function(x) {
                    return /log.?out|sign.?out/i.test(
                        (x.innerText || '') + ' ' + (x.value || '')
                    );
                });

                if (!item) return 'LOGOUT_NOT_FOUND';

                item.click();
                return 'LOGOUT_CLICKED';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result?.contains("LOGOUT_CLICKED") == true) {
                statusText("● LOGOUT CLICKED", green)

                handler.postDelayed({
                    clickPortalLoginAgent()
                }, 2500)
            } else {
                statusText(
                    "● PORTAL LOGOUT BUTTON NOT FOUND",
                    red
                )
            }
        }
    }

    private fun clickPortalLoginAgent() {
        if (stage != Stage.LOGOUT) return

        val script = """
            (function() {
                var nodes = Array.from(document.querySelectorAll(
                    'a,button,input[type=button],input[type=submit]'
                ));

                var item = nodes.find(function(x) {
                    var t = (x.innerText || '') + ' ' + (x.value || '');
                    return /log.?in|sign.?in/i.test(t);
                });

                if (!item) return 'LOGIN_AGENT_NOT_FOUND';

                item.click();
                return 'LOGIN_AGENT_CLICKED';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result?.contains("LOGIN_AGENT_CLICKED") == true) {
                stage = Stage.WAIT_SECOND_LOGIN
                statusText(
                    "● WAITING FOR NEXT LOGIN FORM...",
                    cyan
                )

                handler.postDelayed({
                    checkSecondLoginForm()
                }, 1500)
            } else {
                statusText(
                    "● PORTAL LOGIN ACTION NOT FOUND",
                    red
                )
            }
        }
    }

    private fun checkSecondLoginForm() {
        if (stage != Stage.WAIT_SECOND_LOGIN) return

        val script = """
            (function() {
                var user = document.querySelector(
                    'input[type=text],input[type=email],input[name*=user i]'
                );

                var pass = document.querySelector(
                    'input[type=password]'
                );

                return user && pass ? 'SECOND_FORM_READY' : 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result?.contains("SECOND_FORM_READY") == true) {
                stage = Stage.FINISHED
                statusText(
                    "● NEXT LOGIN PAGE READY - STOPPED",
                    green
                )
                toast("Next login page found. Second login was not submitted.")
            } else {
                handler.postDelayed({
                    checkSecondLoginForm()
                }, 2000)
            }
        }
    }

    private fun updateInternetStatus() {
        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val capabilities =
            manager.getNetworkCapabilities(manager.activeNetwork)

        val validated =
            capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true

        if (validated) {
            internetStatus.text = "INTERNET: NETWORK VALIDATED"
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

        val oldKey = keyStore.getKey(KEY_ALIAS, null)
        if (oldKey is SecretKey) return oldKey

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

            val encrypted = cipher.doFinal(
                "$username\n$password".toByteArray(
                    StandardCharsets.UTF_8
                )
            )

            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

            prefs.edit()
                .putString(
                    "iv",
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                )
                .putString(
                    "data",
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                )
                .apply()

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readCredentials(): Pair<String, String>? {
        return try {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

            val ivText = prefs.getString("iv", null) ?: return null
            val dataText = prefs.getString("data", null) ?: return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(
                    128,
                    Base64.decode(ivText, Base64.NO_WRAP)
                )
            )

            val plain = cipher.doFinal(
                Base64.decode(dataText, Base64.NO_WRAP)
            ).toString(StandardCharsets.UTF_8)

            val parts = plain.split("\n", limit = 2)
            if (parts.size != 2) null
            else Pair(parts[0], parts[1])
        } catch (_: Exception) {
            null
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent?.action == ACTION_OPEN_PORTAL) {
            handler.postDelayed({
                startPortalProcess()
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        updateInternetStatus()

        if (stage == Stage.WAIT_VPN) {
            handler.postDelayed({
                checkVpnStatus()
            }, 700)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
