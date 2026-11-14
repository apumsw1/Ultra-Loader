package com.apumsw1.ultraloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.View
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
import org.json.JSONObject
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

        private const val PREFS = "ultra_loader_secure"
        private const val KEY_ALIAS = "UltraLoaderCredentialKey"

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        private const val ACTION_START_PORTAL =
            "com.apumsw1.ultraloader.START_PORTAL"

        private const val ACTION_URL_MISSING =
            "com.apumsw1.ultraloader.PORTAL_URL_MISSING"

        private const val EXTRA_PORTAL_URL = "portal_url"

        private const val MAX_SECOND_LOGIN_ATTEMPTS = 10
        private const val SECOND_LOGIN_RETRY_DELAY = 2000L
    }

    private enum class Stage {
        IDLE,
        LOGIN,
        CHECK_LOGIN,
        WAIT_VPN,
        LOGOUT,
        WAIT_SECOND_LOGIN,
        SECOND_LOGIN,
        CHECK_SECOND_LOGIN,
        FINISHED
    }

    private val cyan = Color.rgb(0, 225, 255)
    private val green = Color.rgb(0, 230, 145)
    private val red = Color.rgb(255, 75, 95)
    private val panel = Color.rgb(12, 24, 43)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var internetStatus: TextView
    private lateinit var activationStatus: TextView
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var webView: WebView
    private lateinit var webTitle: TextView

    private var stage = Stage.IDLE
    private var vpnOpened = false
    private var loginSubmitted = false

    // URL stays in memory only. It is never persisted.
    private var portalUrl = ""
    private var portalNetwork: Network? = null

    private var dashboardShown = false
    private var appInitialized = false
    private var secondLoginAttempts = 0
    private var internetCheckAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(3, 8, 20)
        window.navigationBarColor = Color.rgb(3, 8, 20)

        ActivationManager.requireActivation(this) {
            initializeApplication()
        }
    }

    private fun initializeApplication() {
        if (appInitialized || isFinishing) return

        if (!ActivationManager.hasAccess(this)) {
            ActivationManager.requireActivation(this) {
                initializeApplication()
            }
            return
        }

        appInitialized = true

        createScreen()
        requestNotificationPermission()
        startMonitorService()

        updateInternetStatus()
        updateActivationStatus()

        handleIncomingIntent(intent)
    }

    private fun createScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 8)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(4, 10, 26),
                    Color.rgb(8, 18, 36),
                    Color.rgb(3, 8, 20)
                )
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        scroll.addView(content)

        val title = TextView(this).apply {
            text = "⚡ ULTRA LOADER ⚡"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(5, 18, 5, 5)
        }

        val subtitle = TextView(this).apply {
            text = "SMART WI-FI PORTAL ASSISTANT"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(5, 5, 5, 14)
        }

        status = TextView(this).apply {
            text = "● SYSTEM READY"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(10, 13, 10, 13)
            background = rounded(panel, cyan, 1)
        }

        activationStatus = TextView(this).apply {
            text = ActivationManager.statusText(this@MainActivity)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(8, 10, 8, 10)
        }

        internetStatus = TextView(this).apply {
            text = "INTERNET: CHECKING..."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(8, 10, 8, 10)
        }

        userInput = makeEditText("Portal User ID", false)
        passwordInput = makeEditText("Portal Password", true)

        val saveButton = makeButton("SAVE LOGIN DETAILS") {
            val user = userInput.text.toString().trim()
            val pass = passwordInput.text.toString()

            if (user.isBlank() || pass.isBlank()) {
                toast("Please enter User ID and Password")
            } else if (saveCredentials(user, pass)) {
                statusText("● LOGIN DETAILS SAVED", green)
                toast("Encrypted login details saved")
            } else {
                statusText("● SAVE FAILED", red)
            }
        }

        val startButton = makeButton("▶ START ULTRA LOADER") {
            if (!checkActivationBeforeAction()) return@makeButton

            if (readCredentials() == null) {
                showCredentialDialog()
            } else {
                // This is a fresh discovery attempt, not an old saved URL.
                startPortalProcess()
            }
        }

        val vpnButton = makeButton("CONNECT VPN") {
            if (!checkActivationBeforeAction()) return@makeButton
            openThunderVpn()
        }

        val checkButton = makeButton("CHECK INTERNET") {
            updateInternetStatus()
            checkVpnStatus()
        }

        val notificationButton =
            makeButton("ENABLE NOTIFICATION ACCESS") {
                try {
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                } catch (_: Exception) {
                    toast("Open Android Notification Access settings")
                }
            }

        webTitle = TextView(this).apply {
            text = "▣ PORTAL LOGIN PAGE"
            textSize = 12f
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(5, 13, 5, 7)
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
                    if (!dashboardShown) {
                        statusText("● PORTAL PAGE LOADING...", cyan)
                    }
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    when (stage) {
                        Stage.LOGIN -> tryAutoLogin(false)
                        Stage.CHECK_LOGIN -> checkPortalLogin(false)

                        Stage.WAIT_SECOND_LOGIN ->
                            checkSecondLoginForm()

                        Stage.SECOND_LOGIN ->
                            tryAutoLogin(true)

                        Stage.CHECK_SECOND_LOGIN ->
                            checkPortalLogin(true)

                        else -> {
                            if (stage == Stage.IDLE && !dashboardShown) {
                                statusText("● PORTAL PAGE READY", green)
                            }
                        }
                    }
                }
            }
        }

        content.addView(title, matchWrap())
        content.addView(subtitle, matchWrap())
        content.addView(status, matchWrap())
        content.addView(activationStatus, matchWrap())
        content.addView(internetStatus, matchWrap())
        content.addView(userInput, matchWrap())
        content.addView(passwordInput, matchWrap())
        content.addView(saveButton, matchWrap())
        content.addView(startButton, matchWrap())
        content.addView(vpnButton, matchWrap())
        content.addView(checkButton, matchWrap())
        content.addView(notificationButton, matchWrap())
        content.addView(webTitle, matchWrap())

        content.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                620
            )
        )

        val footer = TextView(this).apply {
            text = "✦ M'r ApurbA Creation ✦"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(6, 12, 6, 8)
        }

        content.addView(footer, matchWrap())

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

    private fun handleIncomingIntent(source: Intent?) {
        if (source == null) return

        when (source.action) {
            ACTION_OPEN_PORTAL -> {
                val url = source.getStringExtra(EXTRA_PORTAL_URL)

                if (isValidPortalUrl(url)) {
                    openDetectedPortal(url!!)
                } else {
                    showMissingPortalUrl()
                }
            }

            ACTION_URL_MISSING -> showMissingPortalUrl()

            ACTION_START_PORTAL -> {
                if (readCredentials() == null) {
                    showCredentialDialog()
                } else {
                    startPortalProcess()
                }
            }
        }
    }

    private fun showMissingPortalUrl() {
        statusText("● CURRENT NOTIFICATION HAS NO PORTAL URL", red)
        webTitle.text = "▣ PORTAL URL NOT PROVIDED"

        toast(
            "The current notification did not expose a portal URL. " +
                "No old URL will be used."
        )
    }

    private fun isValidPortalUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false

        return try {
            val uri = Uri.parse(value.trim())
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun openDetectedPortal(url: String) {
        if (!checkActivationBeforeAction()) return

        if (!isValidPortalUrl(url)) {
            showMissingPortalUrl()
            return
        }

        if (readCredentials() == null) {
            showCredentialDialog()
            return
        }

        dashboardShown = false
        stage = Stage.LOGIN
        loginSubmitted = false
        vpnOpened = false
        secondLoginAttempts = 0
        internetCheckAttempts = 0

        portalUrl = url.trim()

        statusText("● CURRENT NOTIFICATION PORTAL DETECTED", cyan)
        webTitle.text = "▣ CURRENT PORTAL LOGIN PAGE"

        webView.visibility = View.VISIBLE
        webView.loadUrl(portalUrl)
    }

    private fun checkActivationBeforeAction(): Boolean {
        if (ActivationManager.hasAccess(this)) {
            updateActivationStatus()
            return true
        }

        ActivationManager.requireActivation(this) {
            updateActivationStatus()
        }

        return false
    }

    private fun updateActivationStatus() {
        if (!::activationStatus.isInitialized) return

        activationStatus.text = ActivationManager.statusText(this)
        activationStatus.setTextColor(
            if (ActivationManager.hasAccess(this)) green else red
        )
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun rounded(
        color: Int,
        stroke: Int,
        width: Int
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 18f
        setStroke(width, stroke)
    }

    private fun makeEditText(
        hintText: String,
        password: Boolean
    ) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
        textSize = 14f
        setPadding(14, 5, 14, 5)

        if (password) inputType = 129

        background = rounded(panel, Color.rgb(30, 125, 160), 1)

        layoutParams = matchWrap().apply {
            setMargins(0, 5, 0, 5)
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(10, 50, 75), cyan, 1)
        setPadding(8, 9, 8, 9)

        layoutParams = matchWrap().apply {
            setMargins(0, 5, 0, 5)
        }

        setOnClickListener { action() }
    }

    private fun statusText(message: String, color: Int) {
        if (::status.isInitialized) {
            status.text = message
            status.setTextColor(color)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
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
        } catch (_: Exception) {
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

    // Manual Start performs a fresh redirect probe.
    // It never reads or writes a saved portal URL.
    private fun startPortalProcess() {
        if (!checkActivationBeforeAction()) return

        if (readCredentials() == null) {
            showCredentialDialog()
            return
        }

        dashboardShown = false
        stage = Stage.LOGIN
        loginSubmitted = false
        vpnOpened = false
        secondLoginAttempts = 0

        statusText("● FINDING CURRENT PORTAL...", cyan)
        webTitle.text = "▣ FRESH PORTAL DETECTION"

        discoverPortalUrl()
    }

    private fun discoverPortalUrl() {
        executor.execute {
            var found: String? = null
            var selectedNetwork: Network? = null

            try {
                val manager =
                    getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

                val wifi = manager.allNetworks.firstOrNull { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }

                val network = wifi ?: manager.activeNetwork
                selectedNetwork = wifi ?: network

                val probes = listOf(
                    "http://www.google.in/generate_204",
                    "http://connectivitycheck.gstatic.com/generate_204",
                    "http://www.google.com/generate_204",
                    "http://clients3.google.com/generate_204"
                )

                for (probe in probes) {
                    if (found != null) break

                    var connection: HttpURLConnection? = null

                    try {
                        connection = (
                            if (network != null) {
                                network.openConnection(URL(probe))
                            } else {
                                URL(probe).openConnection()
                            }
                        ) as HttpURLConnection

                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 7000
                        connection.readTimeout = 7000
                        connection.requestMethod = "GET"

                        connection.setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                        )

                        connection.setRequestProperty(
                            "Cache-Control",
                            "no-cache"
                        )

                        val code = connection.responseCode
                        val location =
                            connection.getHeaderField("Location")

                        if (code in 300..399 && !location.isNullOrBlank()) {
                            val resolved = try {
                                URL(URL(probe), location).toString()
                            } catch (_: Exception) {
                                null
                            }

                            if (isValidPortalUrl(resolved)) {
                                val originalHost = URL(probe).host
                                val targetHost = URL(resolved!!).host

                                if (!targetHost.equals(originalHost, true)) {
                                    found = resolved
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        connection?.disconnect()
                    }
                }
            } catch (_: Exception) {
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (!ActivationManager.hasAccess(this)) {
                    statusText("● ACTIVATION EXPIRED", red)
                    updateActivationStatus()
                    return@runOnUiThread
                }

                if (found.isNullOrBlank()) {
                    statusText("● FRESH PORTAL URL NOT FOUND", red)
                    webTitle.text = "▣ PORTAL URL NOT FOUND"

                    toast(
                        "No fresh portal redirect was detected. " +
                            "Wait for the network notification or use its current link."
                    )
                    return@runOnUiThread
                }

                portalNetwork = selectedNetwork
                portalUrl = found

                try {
                    portalNetwork?.let {
                        (getSystemService(Context.CONNECTIVITY_SERVICE)
                            as ConnectivityManager).bindProcessToNetwork(it)
                    }
                } catch (_: Exception) {
                }

                dashboardShown = false
                stage = Stage.LOGIN
                loginSubmitted = false

                statusText("● FRESH PORTAL FOUND", green)
                webTitle.text = "▣ PORTAL LOGIN PAGE"

                webView.visibility = View.VISIBLE
                webView.loadUrl(portalUrl)
            }
        }
    }

    // Used for both the first login and the second login.
    private fun tryAutoLogin(secondLogin: Boolean) {
        val expectedStage =
            if (secondLogin) Stage.SECOND_LOGIN else Stage.LOGIN

        if (stage != expectedStage || loginSubmitted) return
        if (!ActivationManager.hasAccess(this)) return

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

              var inputs = Array.from(
                document.querySelectorAll('input')
              );

              var pf = inputs.find(function(x) {
                return (x.type || '').toLowerCase() === 'password';
              });

              var uf = inputs.find(function(x) {
                if (x === pf) return false;
                if (x.disabled || x.type === 'hidden') return false;

                var n = (
                  (x.name || '') + ' ' +
                  (x.id || '') + ' ' +
                  (x.placeholder || '') +
                  ' ' + (x.autocomplete || '')
                ).toLowerCase();

                return (
                  ['user','email','login','account'].some(
                    k => n.includes(k)
                  ) ||
                  ['text','email'].includes(
                    (x.type || '').toLowerCase()
                  )
                );
              });

              if (!uf || !pf) return 'FORM_NOT_FOUND';

              function setValue(el, value) {
                var proto = el.tagName === 'TEXTAREA'
                  ? HTMLTextAreaElement.prototype
                  : HTMLInputElement.prototype;

                var setter = Object.getOwnPropertyDescriptor(
                  proto, 'value'
                );

                if (setter && setter.set) {
                  setter.set.call(el, value);
                } else {
                  el.value = value;
                }

                el.dispatchEvent(
                  new Event('input', {bubbles:true})
                );

                el.dispatchEvent(
                  new Event('change', {bubbles:true})
                );
              }

              setValue(uf, u);
              setValue(pf, p);

              // Accept Terms & Conditions when the page provides it.
              var checks = Array.from(
                document.querySelectorAll('input[type=checkbox]')
              );

              var terms = checks.filter(function(c) {
                var label = '';

                if (c.id) {
                  var associated = document.querySelector(
                    'label[for="' + CSS.escape(c.id) + '"]'
                  );
                  if (associated) label += ' ' + associated.innerText;
                }

                if (c.parentElement) {
                  label += ' ' + c.parentElement.innerText;
                }

                label += ' ' + (c.name || '') + ' ' + (c.value || '');

                return /terms|conditions|agree|accept|policy/i.test(label);
              });

              if (terms.length === 0 && checks.length === 1) {
                terms = checks;
              }

              terms.forEach(function(c) {
                if (!c.checked) {
                  c.click();
                  c.dispatchEvent(
                    new Event('change', {bubbles:true})
                  );
                }
              });

              var form = pf.closest('form');

              if (form) {
                var submit = form.querySelector(
                  'button[type=submit],input[type=submit]'
                );

                if (submit) {
                  submit.click();
                } else if (form.requestSubmit) {
                  form.requestSubmit();
                } else {
                  form.submit();
                }

                return 'SUBMITTED';
              }

              var buttons = Array.from(
                document.querySelectorAll(
                  'button,input[type=button],input[type=submit]'
                )
              );

              var login = buttons.find(function(b) {
                return /log.?in|sign.?in|connect|continue/i.test(
                  (b.innerText || '') + ' ' + (b.value || '')
                );
              });

              if (login) {
                login.click();
                return 'SUBMITTED';
              }

              return 'NO_SUBMIT_BUTTON';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (stage != expectedStage) return@evaluateJavascript

            if (result?.contains("SUBMITTED") == true) {
                loginSubmitted = true

                stage = if (secondLogin) {
                    Stage.CHECK_SECOND_LOGIN
                } else {
                    Stage.CHECK_LOGIN
                }

                statusText(
                    if (secondLogin) {
                        "● SECOND LOGIN SUBMITTED - VERIFYING..."
                    } else {
                        "● FIRST LOGIN SUBMITTED - VERIFYING..."
                    },
                    cyan
                )

                handler.postDelayed({
                    checkPortalLogin(secondLogin)
                }, 3000)

            } else if (result?.contains("FORM_NOT_FOUND") == true) {
                statusText("● LOGIN FORM NOT FOUND", red)

                handler.postDelayed({
                    if (stage == expectedStage) {
                        tryAutoLogin(secondLogin)
                    }
                }, 2500)

            } else {
                handler.postDelayed({
                    if (stage == expectedStage) {
                        tryAutoLogin(secondLogin)
                    }
                }, 2000)
            }
        }
    }

    private fun checkPortalLogin(secondLogin: Boolean) {
        val expectedStage =
            if (secondLogin) {
                Stage.CHECK_SECOND_LOGIN
            } else {
                Stage.CHECK_LOGIN
            }

        if (stage != expectedStage) return

        val script = """
            (function() {
              var t = (
                document.body ? document.body.innerText : ''
              ).toLowerCase();

              var passwordField = !!document.querySelector(
                'input[type=password]'
              );

              var logout = /log.?out|sign.?out/.test(t);

              var success = /welcome|successfully connected|connected successfully|internet access granted/.test(t);

              if (!passwordField && (logout || success)) {
                return 'LOGIN_OK';
              }

              if (passwordField) return 'LOGIN_FORM_STILL_HERE';

              return 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (stage != expectedStage) return@evaluateJavascript

            when {
                result?.contains("LOGIN_OK") == true -> {
                    if (secondLogin) {
                        finishSecondLogin()
                    } else {
                        statusText("● FIRST PORTAL LOGIN VERIFIED", green)

                        try {
                            (getSystemService(Context.CONNECTIVITY_SERVICE)
                                as ConnectivityManager)
                                .bindProcessToNetwork(null)
                        } catch (_: Exception) {
                        }

                        handler.postDelayed({
                            openThunderVpn()
                        }, 2500)
                    }
                }

                result?.contains("LOGIN_FORM_STILL_HERE") == true -> {
                    statusText("● LOGIN NOT CONFIRMED - CHECKING...", red)

                    handler.postDelayed({
                        checkPortalLogin(secondLogin)
                    }, 3000)
                }

                else -> {
                    handler.postDelayed({
                        checkPortalLogin(secondLogin)
                    }, 2500)
                }
            }
        }
    }

    private fun openThunderVpn() {
        if (!checkActivationBeforeAction()) return

        if (vpnOpened) {
            checkVpnStatus()
            return
        }

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
            statusText("● OPENING THUNDER VPN — CONNECT MANUALLY", cyan)

            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)

            handler.postDelayed({
                checkVpnStatus()
            }, 2500)
        } catch (_: Exception) {
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
                secondLoginAttempts = 0

                handler.postDelayed({
                    clickPortalLogout()
                }, 1000)
            }

            updateInternetStatus()
        } else {
            statusText("● PLEASE CONNECT VPN", red)

            if (stage == Stage.WAIT_VPN) {
                showVpnDialog()
            }
        }
    }

    private fun showVpnDialog() {
        AlertDialog.Builder(this)
            .setTitle("Thunder VPN")
            .setMessage(
                "Tap Connect in Thunder VPN, then return here. " +
                    "Automatic control of another VPN app is not enabled."
            )
            .setPositiveButton("CONNECT VPN") { _, _ ->
                vpnOpened = false
                openThunderVpn()
            }
            .setNeutralButton("I CONNECTED — CHECK") { _, _ ->
                checkVpnStatus()
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun clickPortalLogout() {
        if (stage != Stage.LOGOUT) return

        statusText("● LOOKING FOR PORTAL LOGOUT...", cyan)

        portalNetwork?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager).bindProcessToNetwork(it)
            } catch (_: Exception) {
            }
        }

        val script = """
            (function() {
              var elements = Array.from(
                document.querySelectorAll(
                  'a,button,input[type=button],input[type=submit]'
                )
              );

              var button = elements.find(function(n) {
                return /log.?out|sign.?out/i.test(
                  (n.innerText || '') + ' ' + (n.value || '')
                );
              });

              if (!button) return 'LOGOUT_NOT_FOUND';

              button.click();
              return 'LOGOUT_CLICKED';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (stage != Stage.LOGOUT) return@evaluateJavascript

            if (result?.contains("LOGOUT_CLICKED") == true) {
                statusText("● LOGOUT CLICKED", green)

                secondLoginAttempts = 0

                handler.postDelayed({
                    clickPortalLoginAgent()
                }, 2500)
            } else {
                statusText("● PORTAL LOGOUT BUTTON NOT FOUND", red)
            }
        }
    }

    private fun clickPortalLoginAgent() {
        if (stage != Stage.LOGOUT) return

        secondLoginAttempts++

        statusText(
            "● SEARCHING LOGIN PAGE: $secondLoginAttempts/$MAX_SECOND_LOGIN_ATTEMPTS",
            cyan
        )

        val script = """
            (function() {
              var elements = Array.from(
                document.querySelectorAll(
                  'a,button,input[type=button],input[type=submit]'
                )
              );

              var button = elements.find(function(n) {
                return /log.?in|sign.?in/i.test(
                  (n.innerText || '') + ' ' + (n.value || '')
                );
              });

              if (!button) return 'LOGIN_AGENT_NOT_FOUND';

              button.click();
              return 'LOGIN_AGENT_CLICKED';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (stage != Stage.LOGOUT) return@evaluateJavascript

            if (result?.contains("LOGIN_AGENT_CLICKED") == true) {
                stage = Stage.WAIT_SECOND_LOGIN
                loginSubmitted = false

                statusText("● WAITING FOR NEXT LOGIN FORM...", cyan)

                handler.postDelayed({
                    checkSecondLoginForm()
                }, 1500)

            } else if (
                secondLoginAttempts < MAX_SECOND_LOGIN_ATTEMPTS
            ) {
                handler.postDelayed({
                    clickPortalLoginAgent()
                }, SECOND_LOGIN_RETRY_DELAY)

            } else {
                statusText("● SECOND LOGIN PAGE NOT FOUND", red)
                stage = Stage.IDLE

                toast("Login page not found after 10 attempts")
            }
        }
    }

    private fun checkSecondLoginForm() {
        if (stage != Stage.WAIT_SECOND_LOGIN) return

        val script = """
            (function() {
              var inputs = Array.from(
                document.querySelectorAll('input')
              );

              var p = inputs.some(function(x) {
                return (x.type || '').toLowerCase() === 'password';
              });

              var u = inputs.some(function(x) {
                var type = (x.type || '').toLowerCase();
                return type === 'text' || type === 'email';
              });

              return u && p ? 'SECOND_FORM_READY' : 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (stage != Stage.WAIT_SECOND_LOGIN) return@evaluateJavascript

            if (result?.contains("SECOND_FORM_READY") == true) {
                stage = Stage.SECOND_LOGIN
                loginSubmitted = false

                statusText("● SECOND LOGIN FORM FOUND", green)

                tryAutoLogin(true)
            } else {
                handler.postDelayed({
                    checkSecondLoginForm()
                }, 2000)
            }
        }
    }

    private fun finishSecondLogin() {
        stage = Stage.FINISHED

        statusText("● SECOND PORTAL LOGIN VERIFIED", green)

        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager).bindProcessToNetwork(null)
        } catch (_: Exception) {
        }

        internetCheckAttempts = 0

        handler.postDelayed({
            verifyFinalInternet()
        }, 2000)
    }

    private fun verifyFinalInternet() {
        if (stage != Stage.FINISHED || dashboardShown) return

        internetCheckAttempts++

        updateInternetStatus()

        if (isVpnConnected() && hasValidatedInternet()) {
            statusText("● VPN + INTERNET VERIFIED", green)
            loadIpDashboard()
            return
        }

        if (internetCheckAttempts < 10) {
            handler.postDelayed({
                verifyFinalInternet()
            }, 2000)
        } else {
            statusText("● SECOND LOGIN DONE - INTERNET NOT VERIFIED", red)
            toast("Portal login completed, but Android did not verify internet.")
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val cm =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            == true
    }

    private fun updateInternetStatus() {
        if (!::internetStatus.isInitialized) return

        val cm =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val validated =
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                == true

        if (validated) {
            internetStatus.text = "INTERNET: VERIFIED CONNECTION"
            internetStatus.setTextColor(green)

            if (
                stage == Stage.FINISHED &&
                isVpnConnected() &&
                !dashboardShown
            ) {
                handler.post {
                    loadIpDashboard()
                }
            }
        } else {
            internetStatus.text = "INTERNET: NOT VERIFIED"
            internetStatus.setTextColor(red)
        }
    }

    private fun loadIpDashboard() {
        if (
            dashboardShown ||
            !isVpnConnected() ||
            !hasValidatedInternet()
        ) return

        dashboardShown = true

        statusText("● VPN + INTERNET VERIFIED", green)
        webTitle.text = "▣ LIVE PUBLIC IP DASHBOARD"

        executor.execute {
            var data: JSONObject? = null

            try {
                val connection =
                    URL("https://ipwho.is/").openConnection()
                        as HttpURLConnection

                connection.connectTimeout = 9000
                connection.readTimeout = 9000
                connection.setRequestProperty(
                    "User-Agent",
                    "UltraLoader/1.0"
                )

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()
                data = JSONObject(body)
            } catch (_: Exception) {
            }

            val html = buildDashboardHtml(data)

            runOnUiThread {
                if (::webView.isInitialized && !isFinishing) {
                    webView.loadDataWithBaseURL(
                        "https://ipwho.is/",
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        }
    }

    private fun buildDashboardHtml(data: JSONObject?): String {
        val success = data?.optBoolean("success", false) == true

        val ip =
            if (success) data?.optString("ip", "Unavailable")
            else "Unavailable"

        val city =
            if (success) data?.optString("city", "Unavailable")
            else "Unavailable"

        val region =
            if (success) data?.optString("region", "") else ""

        val country =
            if (success) data?.optString("country", "Unavailable")
            else "Unavailable"

        val flag =
            if (success) data?.optJSONObject("flag")
                ?.optString("emoji", "🌐")
            else "🌐"

        val isp =
            if (success) data?.optJSONObject("connection")
                ?.optString("isp", "Unavailable")
            else "Unavailable"

        return """
            <!doctype html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            body{margin:0;padding:18px;background:#f2f6fa;color:#15243a;font-family:Arial}
            h2{color:#087e9c}
            .card{background:white;border:1px solid #dce6ef;border-radius:16px;padding:16px;margin:12px 0}
            .key{font-size:11px;color:#718096;text-transform:uppercase}
            .val{font-size:18px;font-weight:bold;margin-top:5px;overflow-wrap:anywhere}
            </style>
            </head>
            <body>
            <h2>🌐 Internet Connection Dashboard</h2>
            <div class="card"><div class="key">Public IP Address</div><div class="val">$ip</div></div>
            <div class="card"><div class="key">Country</div><div class="val">$flag $country</div></div>
            <div class="card"><div class="key">Approximate IP Location</div><div class="val">$city $region</div></div>
            <div class="card"><div class="key">Internet Provider / ISP</div><div class="val">$isp</div></div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getSecretKey(): SecretKey {
        val keyStore =
            java.security.KeyStore.getInstance("AndroidKeyStore")

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
    ): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val encrypted = cipher.doFinal(
            "$username\n$password".toByteArray(
                StandardCharsets.UTF_8
            )
        )

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
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

        if (appInitialized) {
            handler.postDelayed({
                handleIncomingIntent(intent)
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!appInitialized) return

        updateActivationStatus()
        updateInternetStatus()

        if (!ActivationManager.hasAccess(this)) {
            statusText("● ACTIVATION EXPIRED", red)

            ActivationManager.requireActivation(this) {
                updateActivationStatus()
            }

            return
        }

        if (stage == Stage.WAIT_VPN) {
            handler.postDelayed({
                checkVpnStatus()
            }, 700)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()

        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager).bindProcessToNetwork(null)
        } catch (_: Exception) {
        }

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
