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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
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

        private const val FALLBACK_PORTAL =
            "https://guest7.ic.peplink.com/cp/login?"

        private const val PREFS = "ultra_loader_secure"

        private const val KEY_ALIAS =
            "UltraLoaderCredentialKey"

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

    private val cyan = Color.rgb(0, 225, 255)
    private val green = Color.rgb(0, 230, 145)
    private val red = Color.rgb(255, 75, 95)
    private val dark = Color.rgb(5, 10, 24)
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
    private var portalUrl = FALLBACK_PORTAL
    private var portalNetwork: Network? = null
    private var animationStep = 0
    private var dashboardShown = false
    private var appInitialized = false

    private val backgroundPulse = object : Runnable {
        override fun run() {
            if (!::root.isInitialized) return

            animationStep = (animationStep + 1) % 360
            val blend = (animationStep % 120) / 120f

            val c1 = blendColor(
                Color.rgb(4, 10, 26),
                Color.rgb(10, 20, 42),
                blend
            )

            val c2 = blendColor(
                Color.rgb(8, 18, 36),
                Color.rgb(4, 12, 28),
                blend
            )

            root.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c1, c2, Color.rgb(3, 8, 20))
            )

            handler.postDelayed(this, 90)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(3, 8, 20)
        window.navigationBarColor = Color.rgb(3, 8, 20)

        // Activation is required before opening the application.
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
        handler.post(backgroundPulse)

        requestNotificationPermission()
        startMonitorService()

        if (intent?.action == ACTION_OPEN_PORTAL) {
            handler.postDelayed({
                startPortalProcess()
            }, 500)
        }

        updateInternetStatus()
        updateActivationStatus()
    }

    private fun blendColor(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)

        fun channel(x: Int, y: Int) =
            (Color.red(x) +
                (Color.red(y) - Color.red(x)) * t).toInt()

        fun greenChannel(x: Int, y: Int) =
            (Color.green(x) +
                (Color.green(y) - Color.green(x)) * t).toInt()

        fun blueChannel(x: Int, y: Int) =
            (Color.blue(x) +
                (Color.blue(y) - Color.blue(x)) * t).toInt()

        return Color.rgb(
            channel(a, b),
            greenChannel(a, b),
            blueChannel(a, b)
        )
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

        val scrollPage = ScrollView(this).apply {
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        scrollPage.addView(content)

        val title = TextView(this).apply {
            text = "⚡ ULTRA LOADER ⚡"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(5, 18, 5, 2)
        }

        val subtitle = TextView(this).apply {
            text = "SMART WI-FI PORTAL ASSISTANT  •  SECURE ACCESS"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            letterSpacing = 0.08f
            setPadding(5, 2, 5, 14)
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
                toast("Login details encrypted and saved")
                statusText("● LOGIN DETAILS SAVED", green)
            } else {
                statusText("● SAVE FAILED", red)
            }
        }

        val startButton = makeButton("▶  START ULTRA LOADER") {
            if (!checkActivationBeforeAction()) return@makeButton

            if (readCredentials() == null) {
                showCredentialDialog()
            } else {
                startPortalProcess()
            }
        }.apply {
            textSize = 16f
            setPadding(10, 15, 10, 15)
            background = rounded(
                Color.rgb(0, 95, 125),
                cyan,
                2
            )
        }

        val vpnButton = makeButton("CONNECT VPN") {
            if (!checkActivationBeforeAction()) return@makeButton
            openThunderVpn()
        }

        val checkButton = makeButton("CHECK INTERNET") {
            if (!checkActivationBeforeAction()) return@makeButton
            updateInternetStatus()
            checkVpnStatus()
        }

        webTitle = TextView(this).apply {
            text = "▣  PORTAL LOGIN PAGE"
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
                        statusText(
                            "● PORTAL PAGE LOADING...",
                            cyan
                        )
                    }
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
                            if (stage == Stage.IDLE && !dashboardShown) {
                                statusText(
                                    "● PORTAL PAGE READY",
                                    green
                                )
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
        content.addView(webTitle, matchWrap())

        content.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                620
            )
        )

        val footer = TextView(this).apply {
            text = "✦  M'r ApurbA Creation  ✦"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(6, 12, 6, 8)
        }

        content.addView(footer, matchWrap())

        root.addView(
            scrollPage,
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

        activationStatus.text =
            ActivationManager.statusText(this)

        activationStatus.setTextColor(
            if (ActivationManager.hasAccess(this)) green else red
        )
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rounded(
        color: Int,
        stroke: Int,
        width: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 18f
            setStroke(width, stroke)
        }
    }

    private fun makeEditText(
        hintText: String,
        password: Boolean
    ): EditText {
        return EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(14, 5, 14, 5)

            if (password) inputType = 129

            background = rounded(panel, Color.rgb(30, 125, 160), 1)

            val lp = matchWrap()
            lp.setMargins(0, 5, 0, 5)
            layoutParams = lp
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(
                Color.rgb(10, 50, 75),
                cyan,
                1
            )
            setPadding(8, 9, 8, 9)

            val lp = matchWrap()
            lp.setMargins(0, 5, 0, 5)
            layoutParams = lp

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(80)
                            .start()

                        background = rounded(
                            Color.rgb(0, 115, 140),
                            green,
                            2
                        )
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(110)
                            .start()

                        background = rounded(
                            Color.rgb(10, 50, 75),
                            cyan,
                            1
                        )
                    }
                }
                false
            }

            setOnClickListener {
                action()
            }
        }
    }

    private fun statusText(
        message: String,
        color: Int
    ) {
        if (::status.isInitialized) {
            status.text = message
            status.setTextColor(color)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                501
            )
        }
    }

    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(
                this,
                PortalMonitorService::class.java
            )

            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {
            statusText(
                "● WIFI MONITOR NOT STARTED",
                red
            )
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
            .setMessage(
                "Enter your Portal User ID and Password."
            )
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
        if (!checkActivationBeforeAction()) return

        if (readCredentials() == null) {
            showCredentialDialog()
            return
        }

        dashboardShown = false
        stage = Stage.LOGIN
        loginSubmitted = false
        vpnOpened = false

        statusText(
            "● FINDING CURRENT PORTAL...",
            cyan
        )

        webTitle.text = "▣  PORTAL LOGIN PAGE"

        discoverPortalUrl()
    }

    private fun discoverPortalUrl() {
        executor.execute {
            var found: String? = null

            val manager =
                getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            val wifi = manager.allNetworks.firstOrNull { n ->
                manager.getNetworkCapabilities(n)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }

            val network = wifi ?: manager.activeNetwork
            portalNetwork = wifi ?: network

            val probes = listOf(
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
                        "Mozilla/5.0 Android"
                    )

                    val code = connection.responseCode
                    val location =
                        connection.getHeaderField("Location")

                    if (
                        code in 300..399 &&
                        !location.isNullOrBlank() &&
                        location.startsWith("http")
                    ) {
                        found = location
                    }
                } catch (_: Exception) {
                } finally {
                    connection?.disconnect()
                }
            }

            runOnUiThread {
                if (!ActivationManager.hasAccess(this)) {
                    statusText("● ACTIVATION EXPIRED", red)
                    updateActivationStatus()
                    return@runOnUiThread
                }

                val selectedNetwork = portalNetwork

                if (selectedNetwork != null) {
                    val bound =
                        (getSystemService(Context.CONNECTIVITY_SERVICE)
                            as ConnectivityManager)
                            .bindProcessToNetwork(selectedNetwork)

                    if (!bound) {
                        statusText(
                            "● WIFI ROUTING NOT AVAILABLE",
                            red
                        )
                    }
                }

                portalUrl = found ?: FALLBACK_PORTAL

                statusText(
                    if (found != null) {
                        "● CURRENT PORTAL FOUND"
                    } else {
                        "● USING PORTAL FALLBACK"
                    },
                    if (found != null) green else cyan
                )

                webView.visibility = View.VISIBLE
                webView.loadUrl(portalUrl)
            }
        }
    }

    private fun tryAutoLogin() {
        if (stage != Stage.LOGIN || loginSubmitted) return
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
              function dec(s){
                try{return decodeURIComponent(escape(atob(s)));}
                catch(e){return atob(s);}
              }

              var u=dec('$user'),
                  p=dec('$pass'),
                  all=Array.from(document.querySelectorAll('input'));

              var pf=all.find(function(x){
                return (x.type||'').toLowerCase()==='password';
              });

              var uf=all.find(function(x){
                if(x===pf)return false;
                var n=((x.name||'')+' '+(x.id||'')+' '+(x.placeholder||'')).toLowerCase();
                return ['user','email','login','account'].some(function(k){
                  return n.includes(k);
                }) || ['text','email'].includes((x.type||'').toLowerCase());
              });

              if(!uf||!pf)return 'FORM_NOT_FOUND';

              uf.focus();
              uf.value=u;
              pf.focus();
              pf.value=p;

              [uf,pf].forEach(function(x){
                x.dispatchEvent(new Event('input',{bubbles:true}));
                x.dispatchEvent(new Event('change',{bubbles:true}));
              });

              Array.from(document.querySelectorAll('input[type=checkbox]'))
                .forEach(function(c){
                  var label=((c.name||'')+' '+(c.id||' ')+' '+(c.parentElement?c.parentElement.innerText:'')).toLowerCase();
                  if(/terms?|conditions?|agreement|accept|policy/.test(label)&&!c.checked&&!c.disabled)c.click();
                });

              var form=pf.closest('form');

              if(form){
                var submit=form.querySelector('button[type=submit],input[type=submit]');
                if(submit)submit.click();
                else if(form.requestSubmit)form.requestSubmit();
                else form.submit();
                return 'SUBMITTED';
              }

              var buttons=Array.from(document.querySelectorAll('button,input[type=button],input[type=submit]'));

              var login=buttons.find(function(b){
                return /log.?in|sign.?in|connect|continue/i.test(b.innerText||b.value||'');
              });

              if(login){
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

                statusText(
                    "● LOGIN SUBMITTED - VERIFYING...",
                    cyan
                )

                handler.postDelayed({
                    checkPortalLogin()
                }, 3000)

            } else if (
                result?.contains("FORM_NOT_FOUND") == true
            ) {
                statusText(
                    "● PORTAL LOGIN FORM NOT FOUND",
                    red
                )
            } else {
                statusText(
                    "● CHECKING PORTAL FORM...",
                    cyan
                )

                handler.postDelayed({
                    tryAutoLogin()
                }, 2000)
            }
        }
    }

    private fun checkPortalLogin() {
        if (stage != Stage.CHECK_LOGIN) return

        val script = """
            (function(){
              var t=(document.body?document.body.innerText:'').toLowerCase();
              var p=!!document.querySelector('input[type=password]');
              var l=/log.?out|sign.?out/.test(t);
              var s=/welcome|successfully connected|connected successfully/.test(t);

              if(!p&&(l||s))return 'LOGIN_OK';
              if(p)return 'LOGIN_FORM_STILL_HERE';
              return 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            when {
                result?.contains("LOGIN_OK") == true -> {
                    statusText(
                        "● PORTAL LOGIN VERIFIED",
                        green
                    )

                    (getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager)
                        .bindProcessToNetwork(null)

                    handler.postDelayed({
                        openThunderVpn()
                    }, 2500)
                }

                result?.contains("LOGIN_FORM_STILL_HERE") == true -> {
                    statusText(
                        "● LOGIN NOT CONFIRMED",
                        red
                    )

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
            statusText(
                "● THUNDER VPN NOT INSTALLED",
                red
            )
            showVpnDialog()
            return
        }

        try {
            statusText(
                "● OPENING THUNDER VPN — CONNECT MANUALLY",
                cyan
            )

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

        return manager.allNetworks.any { n ->
            manager.getNetworkCapabilities(n)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun checkVpnStatus() {
        if (isVpnConnected()) {
            statusText(
                "● VPN CONNECTION DETECTED",
                green
            )

            if (stage == Stage.WAIT_VPN) {
                stage = Stage.LOGOUT

                handler.postDelayed({
                    clickPortalLogout()
                }, 1000)
            }

            updateInternetStatus()

        } else {
            statusText(
                "● PLEASE CONNECT VPN",
                red
            )

            if (stage == Stage.WAIT_VPN) {
                showVpnDialog()
            }
        }
    }

    private fun showVpnDialog() {
        AlertDialog.Builder(this)
            .setTitle("Thunder VPN")
            .setMessage(
                "Tap Connect in Thunder VPN. Android does not allow this app to press another VPN app's Connect button reliably. Return here after connecting."
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

        statusText(
            "● LOOKING FOR PORTAL LOGOUT...",
            cyan
        )

        portalNetwork?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager)
                .bindProcessToNetwork(it)
        }

        val script = """
            (function(){
              var a=Array.from(document.querySelectorAll('a,button,input[type=button],input[type=submit]'));
              var x=a.find(function(n){
                return /log.?out|sign.?out/i.test((n.innerText||'')+' '+(n.value||''));
              });
              if(!x)return 'LOGOUT_NOT_FOUND';
              x.click();
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
            (function(){
              var a=Array.from(document.querySelectorAll('a,button,input[type=button],input[type=submit]'));
              var x=a.find(function(n){
                return /log.?in|sign.?in/i.test((n.innerText||'')+' '+(n.value||''));
              });
              if(!x)return 'LOGIN_AGENT_NOT_FOUND';
              x.click();
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

    // Detect the next login page. Do not submit credentials a second time.
    private fun checkSecondLoginForm() {
        if (stage != Stage.WAIT_SECOND_LOGIN) return

        val script = """
            (function(){
              var u=document.querySelector('input[type=text],input[type=email],input[name*=user i]');
              var p=document.querySelector('input[type=password]');
              return u&&p?'SECOND_FORM_READY':'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            if (result?.contains("SECOND_FORM_READY") == true) {
                stage = Stage.FINISHED
                loginSubmitted = true

                statusText(
                    "● FINAL LOGIN PAGE READY — AUTOMATION STOPPED",
                    green
                )

                if (
                    isVpnConnected() &&
                    hasValidatedInternet()
                ) {
                    handler.postDelayed({
                        loadIpDashboard()
                    }, 1800)
                }

            } else {
                handler.postDelayed({
                    checkSecondLoginForm()
                }, 2000)
            }
        }
    }

    private fun hasValidatedInternet(): Boolean {
        val cm =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true
    }

    private fun updateInternetStatus() {
        if (!::internetStatus.isInitialized) return

        val cm =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val validated =
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true

        if (validated) {
            internetStatus.text =
                "INTERNET: VERIFIED CONNECTION"

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
            internetStatus.text =
                "INTERNET: NOT VERIFIED"

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

        statusText(
            "● VPN + INTERNET VERIFIED",
            green
        )

        webTitle.text =
            "▣  LIVE PUBLIC IP DASHBOARD"

        executor.execute {
            var data: JSONObject? = null

            try {
                val c =
                    URL("https://ipwho.is/")
                        .openConnection() as HttpURLConnection

                c.connectTimeout = 9000
                c.readTimeout = 9000

                c.setRequestProperty(
                    "User-Agent",
                    "UltraLoader/1.0"
                )

                val body = c.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                c.disconnect()
                data = JSONObject(body)

            } catch (_: Exception) {
            }

            val html = buildDashboardHtml(data)

            runOnUiThread {
                if (::webView.isInitialized) {
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

    private fun buildDashboardHtml(
        data: JSONObject?
    ): String {
        val success =
            data?.optBoolean("success", false) == true

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
            if (success) {
                data?.optJSONObject("flag")
                    ?.optString("emoji", "🌐")
            } else "🌐"

        val isp =
            if (success) {
                data?.optJSONObject("connection")
                    ?.optString("isp", "Unavailable")
            } else "Unavailable"

        val lat =
            if (success) data?.optDouble("latitude", Double.NaN)
            else Double.NaN

        val lon =
            if (success) data?.optDouble("longitude", Double.NaN)
            else Double.NaN

        val map =
            if (
                lat != null &&
                lon != null &&
                lat.isFinite() &&
                lon.isFinite()
            ) {
                "<iframe width='100%' height='260' style='border:0;border-radius:14px' src='https://www.openstreetmap.org/export/embed.html?bbox=${lon - 0.08}%2C${lat - 0.05}%2C${lon + 0.08}%2C${lat + 0.05}&layer=mapnik&marker=$lat%2C$lon'></iframe>"
            } else {
                "<div class='empty'>Map location unavailable</div>"
            }

        return """
            <!doctype html>
            <html>
            <head>
            <meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>
            body{
                margin:0;
                padding:18px;
                background:#f2f6fa;
                color:#15243a;
                font-family:Arial,sans-serif
            }
            h2{
                color:#087e9c;
                margin:4px 0 8px
            }
            .sub{
                color:#607080;
                font-size:13px;
                margin-bottom:18px
            }
            .card{
                background:white;
                border:1px solid #dce6ef;
                border-radius:16px;
                padding:16px;
                margin:12px 0;
                box-shadow:0 3px 12px #10203012
            }
            .key{
                font-size:11px;
                color:#718096;
                text-transform:uppercase;
                letter-spacing:1px
            }
            .val{
                font-size:18px;
                font-weight:bold;
                margin-top:5px;
                overflow-wrap:anywhere
            }
            .map{margin-top:8px}
            .empty{
                padding:28px;
                text-align:center;
                background:#e6edf4;
                border-radius:12px;
                color:#637386
            }
            .note{
                font-size:12px;
                line-height:1.5;
                color:#617080
            }
            </style>
            </head>
            <body>
            <h2>🌐 Internet Connection Dashboard</h2>
            <div class='sub'>VPN detected • Internet connectivity verified</div>

            <div class='card'>
                <div class='key'>Public IP Address</div>
                <div class='val'>$ip</div>
            </div>

            <div class='card'>
                <div class='key'>Country</div>
                <div class='val'>$flag $country</div>
            </div>

            <div class='card'>
                <div class='key'>Approximate IP Location</div>
                <div class='val'>$city${if (region.isNullOrBlank()) "" else ", $region"}</div>
            </div>

            <div class='card'>
                <div class='key'>Internet Provider / ISP</div>
                <div class='val'>$isp</div>
            </div>

            <div class='card'>
                <div class='key'>Approximate Map</div>
                <div class='map'>$map</div>
                <p class='note'>
                Location is estimated from the public IP address.
                It is not GPS data and may not represent your exact physical location.
                </p>
            </div>

            <p class='note'>
            If a field says Unavailable, the IP information service did not return that value.
            </p>
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

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getSecretKey()
        )

        val encrypted = cipher.doFinal(
            "$username\n$password".toByteArray(
                StandardCharsets.UTF_8
            )
        )

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(
                "iv",
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                )
            )
            .putString(
                "data",
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )
            )
            .apply()

        true

    } catch (_: Exception) {
        false
    }

    private fun readCredentials(): Pair<String, String>? {
        return try {
            val prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE)

            val ivText =
                prefs.getString("iv", null) ?: return null

            val dataText =
                prefs.getString("data", null) ?: return null

            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                getSecretKey(),
                GCMParameterSpec(
                    128,
                    Base64.decode(
                        ivText,
                        Base64.NO_WRAP
                    )
                )
            )

            val plain = cipher.doFinal(
                Base64.decode(
                    dataText,
                    Base64.NO_WRAP
                )
            ).toString(StandardCharsets.UTF_8)

            val parts = plain.split("\n", limit = 2)

            if (parts.size != 2) {
                null
            } else {
                Pair(parts[0], parts[1])
            }

        } catch (_: Exception) {
            null
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        setIntent(intent)

        if (
            intent?.action == ACTION_OPEN_PORTAL &&
            appInitialized
        ) {
            handler.postDelayed({
                startPortalProcess()
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!appInitialized) return

        updateActivationStatus()
        updateInternetStatus()

        if (!ActivationManager.hasAccess(this)) {
            statusText(
                "● ACTIVATION EXPIRED",
                red
            )

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
            (
                getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager
            ).bindProcessToNetwork(null)
        } catch (_: Exception) {
        }

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
