package com.apumsw1.ultraloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {

    companion object {
        private const val UPDATE_API =
            "https://ultra-loader.ultraloader.workers.dev/api/update"

        private const val VPN_PACKAGE =
            "com.fast.free.unblock.thunder.vpn"

        private const val FALLBACK_PORTAL =
            "https://guest.ic.peplink.com/cp/?checksum=5d2990d23969fef1bf8c47d2e96355b734a5c5ab&cp_id=~CP_KEY_zmzKdYvIxSy-_Oghe37X5Q&time=1794614523&ip=192.168.40.55&client_mac=5A%3A70%3A3D%3AC9%3ADA%3A8C&host_ip=192.168.40.1&ssid=~CP_KEY_zmzKdYvIxSy-_Oghe37X5Q&sn=1931-AED4-E0FD&host_mac=10%3A56%3ACA%3ADE%3A9D%3A80&browser=generic&orig_url=http%3A%2F%2Fwww.google.in%2Fgenerate_204?"

        private const val PREFS = "ultra_loader_secure"
        private const val KEY_ALIAS = "UltraLoaderCredentialKey"

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        private const val TELEGRAM_URL =
            "https://t.me/ultraloader"

        private const val KEY_USER = "portal_username_enc"
        private const val KEY_USER_IV = "portal_username_iv"
        private const val KEY_PASS = "portal_password_enc"
        private const val KEY_PASS_IV = "portal_password_iv"
    }

    private enum class Stage {
        IDLE,
        LOGIN,
        CHECK_LOGIN,
        WAIT_LOGOUT_BUTTON,
        WAIT_VPN,
        LOGOUT,
        WAIT_SECOND_LOGIN,
        FINISHED
    }

    private val cyan = Color.rgb(0, 225, 255)
    private val green = Color.rgb(0, 235, 145)
    private val red = Color.rgb(255, 65, 85)
    private val dark = Color.rgb(4, 10, 25)
    private val panel = Color.rgb(10, 24, 43)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var internetStatus: TextView
    private lateinit var userInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var webView: WebView
    private lateinit var webTitle: TextView
    private lateinit var ipDetails: TextView
    private lateinit var licenseDetails: TextView
    private lateinit var dataDetails: TextView
    private lateinit var vpnStatus: TextView
    private lateinit var globeView: GlobeView
    private lateinit var clockText: TextView
    private lateinit var secondLoginTimerStatus: TextView
    private lateinit var secondLoginStageStatus: TextView

    private var dataBaselineRx = 0L
    private var dataBaselineTx = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastDataUpdateTime = SystemClock.elapsedRealtime()

    private var stage = Stage.IDLE
    private var portalUrl = FALLBACK_PORTAL
    private var appInitialized = false
    private var dashboardShown = false
    private var loginSubmitted = false

    private var loginSubmissionInProgress = false
    private var portalCheckInProgress = false
    private var secondLoginCheckInProgress = false
    private var logoutActionInProgress = false
    private var secondLoginActionInProgress = false

    private var vpnLaunchAttempted = false
    private var vpnCheckScheduled = false
    private var vpnNetworksBeforeLaunch = emptySet<Network>()

    private var secondLoginTimerStartedAt = 0L
    private var secondLoginElapsedSeconds = 0L
    private var secondLoginTimerRunning = false
    private var secondLoginSubmitted = false

    private var logoutPollRunning = false
    private var loginPollRunning = false
    private var secondLoginPollRunning = false
    private var portalCheckRunning = false

    private var activationPopup: AlertDialog? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private var lastLicenseNetworkState = false
    private var licenseVerificationRunning = false

    private val updater = object : Runnable {
        override fun run() {
            if (!appInitialized || isFinishing || isDestroyed) return

            updateDeviceDataUsage()
            updateActivationStatus()
            updateClock()
            updateVpnLine()
            updateInternetStatus()

            handler.postDelayed(this, 1000)
        }
    }

    private val timerUpdater = object : Runnable {
        override fun run() {
            if (!secondLoginTimerRunning) return

            secondLoginElapsedSeconds =
                (SystemClock.elapsedRealtime() -
                    secondLoginTimerStartedAt) / 1000L

            val minutes = secondLoginElapsedSeconds / 60
            val seconds = secondLoginElapsedSeconds % 60

            if (::secondLoginTimerStatus.isInitialized) {
                secondLoginTimerStatus.text =
                    String.format(
                        Locale.US,
                        "SECOND LOGIN TIMER: %02d:%02d",
                        minutes,
                        seconds
                    )
            }

            handler.postDelayed(this, 1000)
        }
    }

    private val licenseNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                handler.post {
                    updateInternetStatus()
                    verifyLicenseWhenOnline()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                handler.post {
                    updateInternetStatus()

                    if (
                        capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                    ) {
                        verifyLicenseWhenOnline()
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    lastLicenseNetworkState = false
                    updateInternetStatus()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = dark
        window.navigationBarColor = dark

        checkForForcedUpdate()
    }

    private fun checkForForcedUpdate() {
        executor.execute {
            var connection: HttpURLConnection? = null

            try {
                val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    packageManager.getPackageInfo(
                        packageName,
                        0
                    ).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(
                        packageName,
                        0
                    ).versionCode.toLong()
                }

                val url = URL("$UPDATE_API?versionCode=$versionCode")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = connection.inputStream.bufferedReader().use {
                    it.readText()
                }

                val json = JSONObject(response)

                val updateAvailable =
                    json.optBoolean("updateAvailable", false)
                val forceUpdate =
                    json.optBoolean("forceUpdate", false)

                val versionName = json.optString("versionName", "")
                val message = json.optString(
                    "message",
                    "A new version is available."
                )
                val downloadUrl = json.optString("downloadUrl", "")

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (updateAvailable && forceUpdate) {
                        showForceUpdateDialog(
                            versionName,
                            message,
                            downloadUrl
                        )
                    } else {
                        ActivationManager.requireActivation(this) {
                            initializeApplication()
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    ActivationManager.requireActivation(this) {
                        initializeApplication()
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun showForceUpdateDialog(
        versionName: String,
        message: String,
        downloadUrl: String
    ) {
        val validUrl = downloadUrl.startsWith("https://")

        val updateMessage = buildString {
            append("A new version is required.\n\n")

            if (versionName.isNotBlank()) {
                append("Version: $versionName\n\n")
            }

            append(message)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("UPDATE REQUIRED")
            .setMessage(updateMessage)
            .setCancelable(false)
            .setPositiveButton("UPDATE NOW", null)
            .create()

        dialog.setCanceledOnTouchOutside(false)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    if (!validUrl) {
                        toast("Update download link is invalid")
                        return@setOnClickListener
                    }

                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(downloadUrl)
                            )
                        )
                    } catch (_: Exception) {
                        toast("Unable to open update link")
                    }
                }
        }

        dialog.show()
    }

    private fun initializeApplication() {
        if (appInitialized || isFinishing || isDestroyed) return

        if (!ActivationManager.hasAccess(this)) {
            ActivationManager.requireActivation(this) {
                initializeApplication()
            }
            return
        }

        appInitialized = true

        dataBaselineRx = TrafficStats.getTotalRxBytes()
        dataBaselineTx = TrafficStats.getTotalTxBytes()
        lastRx = dataBaselineRx
        lastTx = dataBaselineTx
        lastDataUpdateTime = SystemClock.elapsedRealtime()

        createScreen()
        registerLicenseNetworkCallback()

        handler.post(updater)

        requestNotificationPermission()
        startMonitorService()

        if (intent?.action == ACTION_OPEN_PORTAL) {
            handler.postDelayed({
                startPortalProcess()
            }, 500)
        }

        updateIpDetails()
        updateInternetStatus()
        updateActivationStatus()
        updateDeviceDataUsage()
        updateClock()
        updateVpnLine()
        verifyLicenseWhenOnline()
    }

    private fun registerLicenseNetworkCallback() {
        if (networkCallbackRegistered) return

        try {
            connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            val request = NetworkRequest.Builder()
                .addCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                .build()

            connectivityManager?.registerNetworkCallback(
                request,
                licenseNetworkCallback
            )

            networkCallbackRegistered = true
        } catch (_: Exception) {
            networkCallbackRegistered = false
        }
    }

    private fun verifyLicenseWhenOnline() {
        if (!appInitialized || isFinishing || isDestroyed) return
        if (!hasValidatedInternet()) {
            lastLicenseNetworkState = false
            return
        }

        if (lastLicenseNetworkState || licenseVerificationRunning) return

        lastLicenseNetworkState = true
        licenseVerificationRunning = true

        ActivationManager.verifySavedLicense(this) { result ->
            runOnUiThread {
                licenseVerificationRunning = false

                if (isFinishing || isDestroyed) return@runOnUiThread

                updateActivationStatus()

                when (result) {
                    "INVALID", "REVOKED", "EXPIRED" -> {
                        statusText("● LICENSE $result", red)
                    }

                    "VALID" -> {
                        statusText("● LICENSE VERIFIED", green)
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun createScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(3, 8, 22),
                    Color.rgb(5, 20, 38),
                    Color.rgb(2, 8, 20)
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
            textSize = 25f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(cyan)
            setPadding(4, 12, 4, 2)
        }

        val subtitle = TextView(this).apply {
            text = "CYBER CONTROL CENTER  •  SECURE NETWORK"
            textSize = 9f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(Color.LTGRAY)
            setPadding(4, 3, 4, 12)
        }

        status = TextView(this).apply {
            text = "● SYSTEM READY"
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(8, 11, 8, 11)
            background = rounded(panel, cyan)
        }

        internetStatus = TextView(this).apply {
            text = "INTERNET: CHECKING..."
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            setPadding(6, 8, 6, 8)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val ipPanel = createPanel()
        ipPanel.addView(panelHeading("🌐 IP DETAILS"))

        ipDetails = panelText(
            "PUBLIC IP: CHECKING...\nCOUNTRY: --\nCITY: --\nISP: --"
        )
        ipPanel.addView(ipDetails)

        vpnStatus = panelText("VPN STATUS: CHECKING...")
        ipPanel.addView(vpnStatus)

        val activationPanel = createPanel()
        activationPanel.addView(panelHeading("🔐 ACTIVATION KEY"))

        val activationButton = TextView(this).apply {
            text = "● ON / OFF  ⚙"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(8, 10, 8, 10)
            background = rounded(Color.rgb(7, 48, 48), green)
            setOnClickListener {
                showActivationPopup()
            }
        }

        activationPanel.addView(activationButton)

        licenseDetails = panelText(
            "ULTRA ACTIVATION\nCHECKING LICENSE..."
        )
        activationPanel.addView(licenseDetails)

        activationPanel.addView(
            makeButton("BUY NEW ACTIVATION KEY") {
                openTelegramContact()
            }
        )

        topRow.addView(
            ipPanel,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(3, 3, 5, 3)
            }
        )

        topRow.addView(
            activationPanel,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(5, 3, 3, 3)
            }
        )

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val dataPanel = createPanel()
        dataPanel.addView(panelHeading("📊 DATA USAGE"))

        dataDetails = panelText(
            "DOWNLOAD: --\nUPLOAD: --\nTOTAL: --\nSPEED: --"
        )
        dataPanel.addView(dataDetails)

        val globePanel = createPanel()
        globePanel.addView(panelHeading("🌍 LIVE GLOBE"))

        globeView = GlobeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                185
            )
        }

        globePanel.addView(globeView)

        bottomRow.addView(
            dataPanel,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(3, 3, 5, 3)
            }
        )

        bottomRow.addView(
            globePanel,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(5, 3, 3, 3)
            }
        )

        val loginPanel = createPanel()
        loginPanel.addView(panelHeading("🔑 PORTAL LOGIN"))

        userInput = makeEditText("Portal User ID", false)
        passwordInput = makeEditText("Portal Password", true)

        loginPanel.addView(userInput)
        loginPanel.addView(passwordInput)

        loginPanel.addView(
            makeButton("SAVE LOGIN DETAILS") {
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
        )

        loginPanel.addView(
            makeButton("▶ START ULTRA LOADER") {
                if (!checkActivationBeforeAction()) {
                    return@makeButton
                }

                if (readCredentials() == null) {
                    showCredentialDialog()
                } else {
                    startPortalProcess()
                }
            }.apply {
                textSize = 15f
                background = rounded(Color.rgb(0, 90, 115), cyan)
            }
        )

        webTitle = TextView(this).apply {
            text = "▣ PORTAL LOGIN PAGE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            setPadding(5, 12, 5, 7)
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
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                this,
                true
            )

            addJavascriptInterface(
                PortalBridge(),
                "UltraLoaderBridge"
            )

            webChromeClient = WebChromeClient()

            webViewClient = object : WebViewClient() {

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    if (stage == Stage.WAIT_LOGOUT_BUTTON) {
                        installLogoutObserver()
                    }

                    if (!dashboardShown) {
                        statusText("● PORTAL PAGE LOADING...", cyan)
                    }
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    when (stage) {
                        Stage.LOGIN -> tryAutoLogin()

                        Stage.CHECK_LOGIN -> {
                            schedulePortalCheck(500)
                        }

                        Stage.WAIT_LOGOUT_BUTTON -> {
                            installLogoutObserver()
                        }

                        Stage.LOGOUT -> {
                            if (!secondLoginSubmitted) {
                                handler.postDelayed({
                                    if (stage == Stage.LOGOUT) {
                                        clickPortalLogout()
                                    }
                                }, 500)
                            }
                        }

                        Stage.WAIT_SECOND_LOGIN -> {
                            scheduleSecondLoginCheck(500)
                        }

                        else -> {
                            if (
                                stage == Stage.IDLE &&
                                !dashboardShown
                            ) {
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

        secondLoginTimerStatus =
            panelText("SECOND LOGIN TIMER: NOT STARTED")

        secondLoginStageStatus =
            panelText("FREE DATA CONNECTION: WAITING")

        content.addView(title, matchWrap())
        content.addView(subtitle, matchWrap())
        content.addView(status, matchWrap())
        content.addView(internetStatus, matchWrap())
        content.addView(topRow, matchWrap())
        content.addView(bottomRow, matchWrap())
        content.addView(clockTextView(), matchWrap())
        content.addView(loginPanel, matchWrap())
        content.addView(webTitle, matchWrap())

        content.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                500
            )
        )

        val footer = TextView(this).apply {
            text = "✦ M'r AB Creation ✦"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(cyan)
            setPadding(5, 12, 5, 12)
        }

        content.addView(footer, matchWrap())
        content.addView(secondLoginTimerStatus, matchWrap())
        content.addView(secondLoginStageStatus, matchWrap())

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
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

    private fun createPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(9, 10, 9, 10)
            background = rounded(panel, cyan)
        }
    }

    private fun panelHeading(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            setPadding(3, 3, 3, 9)
        }
    }

    private fun panelText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setLineSpacing(5f, 1f)
            setPadding(4, 8, 4, 8)
        }
    }

    private fun clockTextView(): TextView {
        clockText = TextView(this).apply {
            text = "PHONE TIME: --"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(6, 8, 6, 8)
        }

        return clockText
    }

    private fun updateClock() {
        if (!::clockText.isInitialized) return

        val now = java.text.SimpleDateFormat(
            "dd MMM yyyy  •  hh:mm:ss a",
            Locale.getDefault()
        ).format(java.util.Date())

        clockText.text = "PHONE DATE / TIME: $now"
    }

    private fun showActivationPopup() {
        if (activationPopup?.isShowing == true) return

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(5, 17, 35))
                cornerRadius = 20f
                setStroke(2, cyan)
            }
        }

        val heading = TextView(this).apply {
            text = "ULTRA ACTIVATION"
            textSize = 19f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            setPadding(5, 8, 5, 12)
        }

        val details = TextView(this).apply {
            text = ActivationManager.statusText(this@MainActivity) +
                "\n\n" +
                if (ActivationManager.hasAccess(this@MainActivity)) {
                    "ACTIVATION: ON"
                } else {
                    "ACTIVATION: OFF"
                }

            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(5, 12, 5, 15)
        }

        box.addView(heading)
        box.addView(details)
        box.addView(
            makeButton("BUY NEW ACTIVATION KEY") {
                openTelegramContact()
            }
        )
        box.addView(
            makeButton("CLOSE") {
                activationPopup?.dismiss()
            }
        )

        activationPopup = AlertDialog.Builder(this)
            .setView(box)
            .create()

        activationPopup?.show()
        activationPopup?.window?.setBackgroundDrawableResource(
            android.R.color.transparent
        )
    }

    private fun updateActivationStatus() {
        if (!::licenseDetails.isInitialized) return

        licenseDetails.text =
            "ULTRA ACTIVATION\n" +
            ActivationManager.statusText(this) +
            "\n" +
            if (ActivationManager.hasAccess(this)) {
                "STATUS: ACTIVE"
            } else {
                "STATUS: INACTIVE"
            }
    }

    private fun updateIpDetails() {
        if (!::ipDetails.isInitialized) return

        try {
            executor.execute {
                var ip = "--"
                var country = "--"
                var city = "--"
                var isp = "--"
                var connection: HttpURLConnection? = null

                try {
                    connection = URL("https://ipapi.co/json/")
                        .openConnection() as HttpURLConnection

                    connection.connectTimeout = 7000
                    connection.readTimeout = 7000
                    connection.requestMethod = "GET"

                    val response = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    val json = JSONObject(response)

                    ip = json.optString("ip", "--")
                    country = json.optString("country_name", "--")
                    city = json.optString("city", "--")
                    isp = json.optString("org", "--")
                } catch (_: Exception) {
                } finally {
                    connection?.disconnect()
                }

                runOnUiThread {
                    if (
                        !isFinishing &&
                        !isDestroyed &&
                        ::ipDetails.isInitialized
                    ) {
                        ipDetails.text =
                            "PUBLIC IP: $ip\n" +
                            "COUNTRY: $country\n" +
                            "CITY: $city\n" +
                            "ISP: $isp"

                        updateVpnLine()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateDeviceDataUsage() {
        if (!::dataDetails.isInitialized) return

        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        if (
            rx == TrafficStats.UNSUPPORTED.toLong() ||
            tx == TrafficStats.UNSUPPORTED.toLong()
        ) {
            dataDetails.text = "DATA USAGE: NOT SUPPORTED"
            return
        }

        val downloaded = (rx - dataBaselineRx).coerceAtLeast(0L)
        val uploaded = (tx - dataBaselineTx).coerceAtLeast(0L)
        val total = downloaded + uploaded

        val now = SystemClock.elapsedRealtime()
        val elapsed = (
            (now - lastDataUpdateTime).coerceAtLeast(1L)
            ) / 1000f

        val rxSpeed = (
            (rx - lastRx).coerceAtLeast(0L) / elapsed
            ).toLong()

        val txSpeed = (
            (tx - lastTx).coerceAtLeast(0L) / elapsed
            ).toLong()

        lastRx = rx
        lastTx = tx
        lastDataUpdateTime = now

        dataDetails.text =
            "DOWNLOAD: ${formatSize(downloaded)}\n" +
            "UPLOAD: ${formatSize(uploaded)}\n" +
            "TOTAL: ${formatSize(total)}\n" +
            "SPEED: ↓ ${formatSize(rxSpeed)}/s  ↑ ${formatSize(txSpeed)}/s"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"

        val kb = bytes / 1024.0
        if (kb < 1024) {
            return String.format(Locale.US, "%.2f KB", kb)
        }

        val mb = kb / 1024.0
        if (mb < 1024) {
            return String.format(Locale.US, "%.2f MB", mb)
        }

        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private fun rounded(
        backgroundColor: Int,
        strokeColor: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = 14f
            setStroke(1, strokeColor)
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun makeEditText(
        hintText: String,
        password: Boolean
    ): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 14f
            setSingleLine(true)
            setPadding(14, 10, 14, 10)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)

            background = rounded(
                Color.rgb(10, 28, 48),
                Color.rgb(0, 180, 210)
            )

            inputType = if (password) {
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 5, 0, 5)
            }
        }
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)

            background = rounded(
                Color.rgb(8, 48, 65),
                cyan
            )

            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 5, 0, 5)
            }
        }
    }

    private fun statusText(
        message: String,
        color: Int
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (::status.isInitialized) {
                status.text = message
                status.setTextColor(color)
            }
        } else {
            runOnUiThread {
                if (::status.isInitialized) {
                    status.text = message
                    status.setTextColor(color)
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openTelegramContact() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(TELEGRAM_URL)
                )
            )
        } catch (_: Exception) {
            toast("Unable to open Telegram")
        }
    }

    private fun checkActivationBeforeAction(): Boolean {
        if (ActivationManager.hasAccess(this)) return true

        ActivationManager.requireActivation(this) {
            updateActivationStatus()
        }

        updateActivationStatus()
        return false
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
                2001
            )
        }
    }

    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(
                this,
                MonitorService::class.java
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun showCredentialDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 8)
        }

        val username = makeEditText("Portal User ID", false)
        val password = makeEditText("Portal Password", true)

        box.addView(username)
        box.addView(password)

        AlertDialog.Builder(this)
            .setTitle("Portal Login Details")
            .setView(box)
            .setCancelable(true)
            .setPositiveButton("SAVE") { _, _ ->
                val user = username.text.toString().trim()
                val pass = password.text.toString()

                if (user.isBlank() || pass.isBlank()) {
                    toast("Please enter both login details")
                    return@setPositiveButton
                }

                if (saveCredentials(user, pass)) {
                    userInput.setText(user)
                    passwordInput.setText(pass)
                    startPortalProcess()
                } else {
                    toast("Unable to save login details")
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

        handler.removeCallbacksAndMessages(null)
        handler.post(updater)

        dashboardShown = false
        stage = Stage.LOGIN
        loginSubmitted = false
        loginSubmissionInProgress = false
        portalCheckInProgress = false
        secondLoginCheckInProgress = false
        logoutActionInProgress = false
        secondLoginActionInProgress = false

        vpnLaunchAttempted = false
        vpnCheckScheduled = false
        vpnNetworksBeforeLaunch = emptySet()

        logoutPollRunning = false
        loginPollRunning = false
        secondLoginPollRunning = false
        portalCheckRunning = false

        secondLoginSubmitted = false
        stopSecondLoginTimer()
        secondLoginElapsedSeconds = 0L

        if (::secondLoginTimerStatus.isInitialized) {
            secondLoginTimerStatus.text =
                "SECOND LOGIN TIMER: NOT STARTED"
        }

        if (::secondLoginStageStatus.isInitialized) {
            secondLoginStageStatus.text =
                "LOGIN STAGE: WAITING"
        }

        statusText("● FINDING CURRENT PORTAL...", cyan)

        if (::webTitle.isInitialized) {
            webTitle.text = "▣ PORTAL LOGIN PAGE"
        }

        discoverPortalUrl()
    }

    private fun discoverPortalUrl() {
        statusText("● SEARCHING NETWORK PORTAL...", cyan)

        executor.execute {
            val candidates = listOf(
                "http://connectivitycheck.gstatic.com/generate_204",
                "http://clients3.google.com/generate_204",
                "http://neverssl.com/",
                "http://www.msftconnecttest.com/redirect"
            )

            var foundUrl: String? = null

            for (candidate in candidates) {
                var connection: HttpURLConnection? = null

                try {
                    connection = URL(candidate)
                        .openConnection() as HttpURLConnection

                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    connection.connect()

                    val location =
                        connection.getHeaderField("Location")

                    val responseCode = connection.responseCode

                    if (
                        responseCode in 300..399 &&
                        !location.isNullOrBlank() &&
                        location.startsWith("http", true)
                    ) {
                        foundUrl = location
                        break
                    }
                } catch (_: Exception) {
                } finally {
                    connection?.disconnect()
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                portalUrl = foundUrl ?: FALLBACK_PORTAL
                webView.loadUrl(portalUrl)
            }
        }
    }

    private fun tryAutoLogin() {
        if (stage != Stage.LOGIN) return
        if (loginSubmitted || loginSubmissionInProgress) return

        val credentials = readCredentials()

        if (credentials == null) {
            statusText("● LOGIN DETAILS NOT FOUND", red)
            showCredentialDialog()
            return
        }

        val username = JSONObject.quote(credentials.first)
        val password = JSONObject.quote(credentials.second)

        loginSubmissionInProgress = true

        val script = """
            (function() {
                var user = document.querySelector(
                    'input[name*=user i], input[id*=user i], input[type=text]'
                );

                var pass = document.querySelector(
                    'input[type=password], input[name*=pass i]'
                );

                if (!user || !pass) return 'FORM_NOT_FOUND';

                user.value = $username;
                pass.value = $password;

                user.dispatchEvent(new Event('input', {bubbles:true}));
                user.dispatchEvent(new Event('change', {bubbles:true}));
                pass.dispatchEvent(new Event('input', {bubbles:true}));
                pass.dispatchEvent(new Event('change', {bubbles:true}));

                var checks = document.querySelectorAll(
                    'input[type=checkbox]'
                );

                checks.forEach(function(c) {
                    if (!c.checked) {
                        c.checked = true;
                        c.dispatchEvent(
                            new Event('change', {bubbles:true})
                        );
                    }
                });

                var form = pass.closest('form') || user.closest('form');

                if (form) {
                    var button = form.querySelector(
                        'button[type=submit], input[type=submit]'
                    );

                    if (button) button.click();
                    else if (form.requestSubmit) form.requestSubmit();
                    else form.submit();

                    return 'SUBMITTED';
                }

                var submit = document.querySelector(
                    'button[type=submit], input[type=submit]'
                );

                if (submit) {
                    submit.click();
                    return 'SUBMITTED';
                }

                return 'BUTTON_NOT_FOUND';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            loginSubmissionInProgress = false

            if (stage != Stage.LOGIN) return@evaluateJavascript

            if (result?.contains("SUBMITTED") == true) {
                loginSubmitted = true
                stage = Stage.CHECK_LOGIN

                statusText("● PORTAL LOGIN SUBMITTED", cyan)
                schedulePortalCheck(2500)
            } else {
                statusText(
                    "● SEARCHING PORTAL LOGIN FORM...",
                    cyan
                )
                scheduleLoginRetry(2500)
            }
        }
    }

    private fun scheduleLoginRetry(delay: Long) {
        if (loginPollRunning || stage != Stage.LOGIN) return

        loginPollRunning = true
        handler.postDelayed({
            loginPollRunning = false
            if (stage == Stage.LOGIN) tryAutoLogin()
        }, delay)
    }

    private fun schedulePortalCheck(delay: Long) {
        if (portalCheckRunning || stage != Stage.CHECK_LOGIN) return

        portalCheckRunning = true
        handler.postDelayed({
            portalCheckRunning = false
            if (stage == Stage.CHECK_LOGIN) checkPortalLogin()
        }, delay)
    }

    private fun checkPortalLogin() {
        if (stage != Stage.CHECK_LOGIN) return
        if (portalCheckInProgress) return

        portalCheckInProgress = true

        val script = """
            (function() {
                var text = (document.body.innerText || '').toLowerCase();

                var logout = Array.from(
                    document.querySelectorAll('button, a, input, [role=button]')
                ).some(function(el) {
                    var value = (
                        (el.innerText || '') + ' ' +
                        (el.value || '') + ' ' +
                        (el.getAttribute('aria-label') || '')
                    ).toLowerCase();

                    var rect = el.getBoundingClientRect();

                    return value.includes('logout') &&
                        rect.width > 0 && rect.height > 0;
                });

                if (logout) return 'LOGOUT_VISIBLE';

                if (
                    text.includes('login successful') ||
                    text.includes('logged in successfully') ||
                    text.includes('connected successfully')
                ) return 'LOGIN_SUCCESS';

                if (
                    text.includes('invalid password') ||
                    text.includes('authentication failed') ||
                    text.includes('login failed')
                ) return 'LOGIN_FAILED';

                return 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            portalCheckInProgress = false

            if (stage != Stage.CHECK_LOGIN) return@evaluateJavascript

            when {
                result?.contains("LOGOUT_VISIBLE") == true -> {
                    stage = Stage.WAIT_LOGOUT_BUTTON
                    statusText(
                        "● PORTAL LOGIN CONFIRMED — WAITING FOR LOGOUT BUTTON",
                        cyan
                    )
                    installLogoutObserver()
                }

                result?.contains("LOGIN_SUCCESS") == true -> {
                    stage = Stage.WAIT_LOGOUT_BUTTON
                    statusText(
                        "● PORTAL LOGIN SUCCESS — WAITING FOR LOGOUT BUTTON",
                        cyan
                    )
                    installLogoutObserver()
                }

                result?.contains("LOGIN_FAILED") == true -> {
                    loginSubmitted = false
                    stage = Stage.LOGIN
                    statusText("● PORTAL LOGIN FAILED", red)
                    toast("Portal login failed. Check your credentials.")
                }

                else -> schedulePortalCheck(2000)
            }
        }
    }

    private fun installLogoutObserver() {
        if (stage != Stage.WAIT_LOGOUT_BUTTON) return
        if (logoutPollRunning) return

        logoutPollRunning = true

        val script = """
            (function() {
                function findLogout() {
                    var elements = document.querySelectorAll(
                        'button, a, input, [role=button]'
                    );

                    for (var i = 0; i < elements.length; i++) {
                        var el = elements[i];

                        var value = (
                            (el.innerText || '') + ' ' +
                            (el.value || '') + ' ' +
                            (el.getAttribute('aria-label') || '')
                        ).toLowerCase();

                        var rect = el.getBoundingClientRect();

                        if (
                            value.includes('logout') &&
                            rect.width > 0 && rect.height > 0
                        ) return true;
                    }

                    return false;
                }

                if (findLogout()) {
                    UltraLoaderBridge.logoutButtonFound();
                    return 'FOUND';
                }

                if (!window.ultraLogoutObserver) {
                    window.ultraLogoutObserver =
                        new MutationObserver(function() {
                            if (findLogout()) {
                                UltraLoaderBridge.logoutButtonFound();
                            }
                        });

                    window.ultraLogoutObserver.observe(
                        document.documentElement,
                        {
                            childList: true,
                            subtree: true,
                            attributes: true
                        }
                    );
                }

                return 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) {
            logoutPollRunning = false

            if (stage == Stage.WAIT_LOGOUT_BUTTON) {
                handler.postDelayed({
                    installLogoutObserver()
                }, 2500)
            }
        }
    }

    private inner class PortalBridge {
        @JavascriptInterface
        fun logoutButtonFound() {
            runOnUiThread {
                onPortalLogoutButtonFound()
            }
        }
    }

    private fun onPortalLogoutButtonFound() {
        if (stage != Stage.WAIT_LOGOUT_BUTTON) return
        if (vpnLaunchAttempted) return

        statusText(
            "● PORTAL LOGOUT BUTTON FOUND",
            green
        )

        openThunderVpn()
    }

    private fun openThunderVpn() {
        if (stage != Stage.WAIT_LOGOUT_BUTTON) return
        if (vpnLaunchAttempted) return

        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(VPN_PACKAGE)
        } catch (_: Exception) {
            null
        }

        if (launchIntent == null) {
            statusText("● THUNDER VPN NOT INSTALLED", red)
            toast("Please install Thunder VPN")
            return
        }

        try {
            vpnNetworksBeforeLaunch = getVpnNetworks()

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)

            vpnLaunchAttempted = true
            stage = Stage.WAIT_VPN
            vpnCheckScheduled = false

            statusText(
                "● THUNDER VPN OPENED — WAITING FOR CONNECTION",
                cyan
            )

            scheduleVpnCheck(1500)
        } catch (_: Exception) {
            vpnLaunchAttempted = false
            statusText("● UNABLE TO OPEN THUNDER VPN", red)
        }
    }

    private fun getVpnNetworks(): Set<Network> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return emptySet()
        }

        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

            cm.allNetworks.filter { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN
                ) == true
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isVpnConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getVpnNetworks().isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type ==
                    ConnectivityManager.TYPE_VPN
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isNewVpnConnected(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return isVpnConnected()
        }

        val currentNetworks = getVpnNetworks()
        return currentNetworks.any {
            it !in vpnNetworksBeforeLaunch
        }
    }

    private fun scheduleVpnCheck(delay: Long = 1500L) {
        if (stage != Stage.WAIT_VPN || vpnCheckScheduled) return

        vpnCheckScheduled = true

        handler.postDelayed({
            vpnCheckScheduled = false

            if (stage == Stage.WAIT_VPN) {
                checkVpnStatus()
            }
        }, delay)
    }

    private fun checkVpnStatus() {
        if (stage != Stage.WAIT_VPN) return

        if (isNewVpnConnected()) {
            vpnCheckScheduled = false
            stage = Stage.LOGOUT

            statusText(
                "● VPN CONNECTION DETECTED — PORTAL LOGOUT STARTING",
                green
            )

            updateVpnLine()
            updateIpDetails()

            handler.postDelayed({
                if (stage == Stage.LOGOUT) {
                    clickPortalLogout()
                }
            }, 1000)
        } else {
            statusText(
                "● WAITING FOR VPN CONNECTION...",
                cyan
            )

            updateVpnLine()
            scheduleVpnCheck(1500)
        }
    }

    private fun updateVpnLine() {
        if (!::vpnStatus.isInitialized) return

        val connected = isVpnConnected()

        vpnStatus.text = if (connected) {
            "VPN STATUS: CONNECTED"
        } else {
            "VPN STATUS: DISCONNECTED"
        }

        vpnStatus.setTextColor(
            if (connected) Color.GREEN else Color.LTGRAY
        )
    }

    private fun clickPortalLogout() {
        if (stage != Stage.LOGOUT) return
        if (logoutActionInProgress) return

        logoutActionInProgress = true

        statusText("● CLICKING PORTAL LOGOUT...", cyan)

        val script = """
            (function() {
                var elements = document.querySelectorAll(
                    'button, a, input, [role=button]'
                );

                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];

                    var value = (
                        (el.innerText || '') + ' ' +
                        (el.value || '') + ' ' +
                        (el.getAttribute('aria-label') || '')
                    ).toLowerCase();

                    var rect = el.getBoundingClientRect();

                    if (
                        value.includes('logout') &&
                        rect.width > 0 && rect.height > 0
                    ) {
                        el.click();
                        return 'LOGOUT_CLICKED';
                    }
                }

                return 'LOGOUT_NOT_FOUND';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            logoutActionInProgress = false

            if (stage != Stage.LOGOUT) return@evaluateJavascript

            if (result?.contains("LOGOUT_CLICKED") == true) {
                statusText("● PORTAL LOGOUT CLICKED", green)

                handler.postDelayed({
                    if (stage == Stage.LOGOUT) {
                        clickPortalLoginAgent()
                    }
                }, 2500)
            } else {
                statusText(
                    "● SEARCHING FOR PORTAL LOGOUT BUTTON...",
                    cyan
                )

                handler.postDelayed({
                    if (stage == Stage.LOGOUT) {
                        clickPortalLogout()
                    }
                }, 2000)
            }
        }
    }

    private fun clickPortalLoginAgent() {
        if (stage != Stage.LOGOUT) return
        if (secondLoginActionInProgress) return

        secondLoginActionInProgress = true

        statusText(
            "● STARTING SECOND PORTAL LOGIN...",
            cyan
        )

        val script = """
            (function() {
                var elements = document.querySelectorAll(
                    'button, a, input, [role=button]'
                );

                for (var i = 0; i < elements.length; i++) {
                    var el = elements[i];

                    var value = (
                        (el.innerText || '') + ' ' +
                        (el.value || '') + ' ' +
                        (el.getAttribute('aria-label') || '')
                    ).toLowerCase();

                    var rect = el.getBoundingClientRect();

                    if (
                        value.includes('login') &&
                        !value.includes('logout') &&
                        rect.width > 0 && rect.height > 0
                    ) {
                        el.click();
                        return 'LOGIN_CLICKED';
                    }
                }

                return 'LOGIN_NOT_FOUND';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            secondLoginActionInProgress = false

            if (stage != Stage.LOGOUT) return@evaluateJavascript

            if (result?.contains("LOGIN_CLICKED") == true) {
                stage = Stage.WAIT_SECOND_LOGIN
                secondLoginSubmitted = false
                secondLoginCheckInProgress = false

                startSecondLoginTimer()

                statusText(
                    "● SECOND LOGIN STARTED",
                    green
                )

                if (::secondLoginStageStatus.isInitialized) {
                    secondLoginStageStatus.text =
                        "LOGIN STAGE: SECOND LOGIN"
                }

                scheduleSecondLoginCheck(1500)
            } else {
                handler.postDelayed({
                    if (stage == Stage.LOGOUT) {
                        clickPortalLoginAgent()
                    }
                }, 2000)
            }
        }
    }

    private fun scheduleSecondLoginCheck(delay: Long) {
        if (
            secondLoginPollRunning ||
            stage != Stage.WAIT_SECOND_LOGIN
        ) return

        secondLoginPollRunning = true

        handler.postDelayed({
            secondLoginPollRunning = false

            if (stage == Stage.WAIT_SECOND_LOGIN) {
                checkSecondLoginForm()
            }
        }, delay)
    }

    private fun checkSecondLoginForm() {
        if (stage != Stage.WAIT_SECOND_LOGIN) return
        if (secondLoginCheckInProgress) return

        val credentials = readCredentials()

        if (credentials == null) {
            statusText("● SAVED LOGIN DETAILS NOT FOUND", red)
            stopSecondLoginTimer()
            return
        }

        val username = JSONObject.quote(credentials.first)
        val password = JSONObject.quote(credentials.second)

        secondLoginCheckInProgress = true

        val script = """
            (function() {
                var text = (
                    document.body.innerText || ''
                ).toLowerCase();

                var visible = function(el) {
                    if (!el) return false;
                    var r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                };

                var user = document.querySelector(
                    'input[name*=user i], input[id*=user i], input[type=text]'
                );

                var pass = document.querySelector(
                    'input[type=password], input[name*=pass i]'
                );

                if (user && pass && visible(user) && visible(pass)) {
                    if (window.ultraSecondLoginSubmitted) {
                        return 'SUBMITTED';
                    }

                    user.value = $username;
                    pass.value = $password;

                    user.dispatchEvent(
                        new Event('input', {bubbles:true})
                    );
                    user.dispatchEvent(
                        new Event('change', {bubbles:true})
                    );
                    pass.dispatchEvent(
                        new Event('input', {bubbles:true})
                    );
                    pass.dispatchEvent(
                        new Event('change', {bubbles:true})
                    );

                    var form = pass.closest('form') || user.closest('form');

                    if (form) {
                        var button = form.querySelector(
                            'button[type=submit], input[type=submit]'
                        );

                        window.ultraSecondLoginSubmitted = true;

                        if (button) button.click();
                        else if (form.requestSubmit) form.requestSubmit();
                        else form.submit();

                        return 'SUBMITTED';
                    }

                    var submit = document.querySelector(
                        'button[type=submit], input[type=submit]'
                    );

                    if (submit) {
                        window.ultraSecondLoginSubmitted = true;
                        submit.click();
                        return 'SUBMITTED';
                    }

                    return 'FORM_BUTTON_NOT_FOUND';
                }

                if (
                    text.includes('invalid password') ||
                    text.includes('authentication failed') ||
                    text.includes('login failed')
                ) return 'LOGIN_FAILED';

                if (
                    text.includes('connected successfully') ||
                    text.includes('login successful') ||
                    text.includes('you are connected') ||
                    text.includes('logged in successfully')
                ) return 'SECOND_LOGIN_SUCCESS';

                var logoutVisible = Array.from(
                    document.querySelectorAll(
                        'button, a, input, [role=button]'
                    )
                ).some(function(el) {
                    var value = (
                        (el.innerText || '') + ' ' +
                        (el.value || '') + ' ' +
                        (el.getAttribute('aria-label') || '')
                    ).toLowerCase();

                    return value.includes('logout') && visible(el);
                });

                if (logoutVisible) return 'SECOND_LOGIN_SUCCESS';

                return 'WAITING';
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            secondLoginCheckInProgress = false

            if (stage != Stage.WAIT_SECOND_LOGIN) {
                return@evaluateJavascript
            }

            when {
                result?.contains("LOGIN_FAILED") == true -> {
                    stopSecondLoginTimer()
                    statusText("● SECOND LOGIN FAILED", red)

                    if (::secondLoginStageStatus.isInitialized) {
                        secondLoginStageStatus.text =
                            "LOGIN STAGE: FAILED"
                    }
                }

                result?.contains("SUBMITTED") == true -> {
                    secondLoginSubmitted = true

                    statusText(
                        "● SECOND LOGIN FORM SUBMITTED",
                        cyan
                    )

                    scheduleSecondLoginCheck(2500)
                }

                result?.contains("SECOND_LOGIN_SUCCESS") == true -> {
                    finishSecondLogin()
                }

                else -> {
                    statusText(
                        "● WAITING FOR SECOND LOGIN...",
                        cyan
                    )
                    scheduleSecondLoginCheck(2000)
                }
            }
        }
    }

    private fun finishSecondLogin() {
        if (stage != Stage.WAIT_SECOND_LOGIN) return

        stopSecondLoginTimer()
        stage = Stage.FINISHED

        statusText(
            "● SECOND LOGIN COMPLETED",
            green
        )

        if (::secondLoginStageStatus.isInitialized) {
            secondLoginStageStatus.text =
                "LOGIN STAGE: COMPLETED"
        }

        if (::secondLoginTimerStatus.isInitialized) {
            secondLoginTimerStatus.text =
                "SECOND LOGIN TIMER: ${secondLoginElapsedSeconds}s"
        }
    }

    private fun startSecondLoginTimer() {
        secondLoginTimerRunning = true
        secondLoginTimerStartedAt = SystemClock.elapsedRealtime()

        handler.removeCallbacks(timerUpdater)
        handler.post(timerUpdater)
    }

    private fun stopSecondLoginTimer() {
        secondLoginTimerRunning = false
        handler.removeCallbacks(timerUpdater)
    }

    private fun hasValidatedInternet(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities =
                    cm.getNetworkCapabilities(network) ?: return false

                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) &&
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateInternetStatus() {
        val connected = hasValidatedInternet()

        if (::internetStatus.isInitialized) {
            internetStatus.text = if (connected) {
                "INTERNET: CONNECTED"
            } else {
                "INTERNET: DISCONNECTED"
            }

            internetStatus.setTextColor(
                if (connected) Color.GREEN else red
            )
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existingKey = keyStore.getKey(KEY_ALIAS, null)

        if (existingKey is SecretKey) return existingKey

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

    private fun encryptCredential(
        value: String
    ): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        val encodedValue = Base64.encodeToString(
            encrypted,
            Base64.NO_WRAP
        )

        val encodedIv = Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )

        return Pair(encodedValue, encodedIv)
    }

    private fun decryptCredential(
        cipherText: String,
        ivText: String
    ): String {
        val encrypted = Base64.decode(
            cipherText,
            Base64.NO_WRAP
        )

        val iv = Base64.decode(
            ivText,
            Base64.NO_WRAP
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(encrypted),
            Charsets.UTF_8
        )
    }

    private fun saveCredentials(
        username: String,
        password: String
    ): Boolean {
        return try {
            val encryptedUser = encryptCredential(username)
            val encryptedPass = encryptCredential(password)

            getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            ).edit()
                .putString(KEY_USER, encryptedUser.first)
                .putString(KEY_USER_IV, encryptedUser.second)
                .putString(KEY_PASS, encryptedPass.first)
                .putString(KEY_PASS_IV, encryptedPass.second)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun readCredentials(): Pair<String, String>? {
        return try {
            val preferences = getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

            val encryptedUser =
                preferences.getString(KEY_USER, null) ?: return null

            val userIv =
                preferences.getString(KEY_USER_IV, null) ?: return null

            val encryptedPass =
                preferences.getString(KEY_PASS, null) ?: return null

            val passIv =
                preferences.getString(KEY_PASS_IV, null) ?: return null

            val username = decryptCredential(
                encryptedUser,
                userIv
            )

            val password = decryptCredential(
                encryptedPass,
                passIv
            )

            Pair(username, password)
        } catch (_: Exception) {
            null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        updateInternetStatus()
        updateVpnLine()
    }

    override fun onResume() {
        super.onResume()

        if (!appInitialized) return

        updateInternetStatus()
        updateVpnLine()
        updateActivationStatus()

        when (stage) {
            Stage.WAIT_VPN -> scheduleVpnCheck(500)
            Stage.WAIT_LOGOUT_BUTTON -> installLogoutObserver()
            Stage.CHECK_LOGIN -> schedulePortalCheck(500)
            Stage.LOGIN -> scheduleLoginRetry(500)

            Stage.LOGOUT -> handler.postDelayed({
                if (stage == Stage.LOGOUT) {
                    clickPortalLogout()
                }
            }, 500)

            Stage.WAIT_SECOND_LOGIN -> {
                scheduleSecondLoginCheck(500)
            }

            else -> Unit
        }
    }

    override fun onDestroy() {
        stopSecondLoginTimer()

        handler.removeCallbacksAndMessages(null)

        try {
            if (networkCallbackRegistered) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

                cm.unregisterNetworkCallback(licenseNetworkCallback)
                networkCallbackRegistered = false
            }
        } catch (_: Exception) {
        }

        try {
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.removeJavascriptInterface("UltraLoaderBridge")
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            }
        } catch (_: Exception) {
        }

        try {
            activationPopup?.dismiss()
        } catch (_: Exception) {
        }

        try {
            executor.shutdownNow()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    private inner class GlobeView(
        context: Context
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val globeBounds = RectF()
        private var rotation = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) * 0.38f

            globeBounds.set(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = cyan
            paint.alpha = 255

            canvas.drawCircle(
                centerX,
                centerY,
                radius,
                paint
            )

            paint.strokeWidth = 1.2f
            paint.alpha = 180
            canvas.drawOval(globeBounds, paint)

            canvas.save()
            canvas.rotate(rotation, centerX, centerY)

            val horizontalBounds = RectF(
                centerX - radius,
                centerY - radius * 0.35f,
                centerX + radius,
                centerY + radius * 0.35f
            )

            canvas.drawOval(horizontalBounds, paint)

            val verticalBounds = RectF(
                centerX - radius * 0.35f,
                centerY - radius,
                centerX + radius * 0.35f,
                centerY + radius
            )

            canvas.drawOval(verticalBounds, paint)
            canvas.restore()

            paint.style = Paint.Style.FILL
            paint.alpha = 255
            paint.color = cyan

            canvas.drawCircle(
                centerX,
                centerY,
                4f,
                paint
            )

            rotation = (rotation + 0.35f) % 360f
            postInvalidateDelayed(40)
        }
    }
}