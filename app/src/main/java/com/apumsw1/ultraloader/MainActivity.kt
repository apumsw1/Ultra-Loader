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
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
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

        private const val FLASH_API =
            "https://ultra-loader.ultraloader.workers.dev/api/flash"

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
}  

private enum class Stage {  
    IDLE, LOGIN, CHECK_LOGIN, WAIT_VPN,  
    LOGOUT, WAIT_SECOND_LOGIN, CHECK_INTERNET, FINISHED  
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
private lateinit var activationStatus: TextView  
private lateinit var userInput: EditText  
private lateinit var passwordInput: EditText  
private lateinit var webView: WebView  
private lateinit var webTitle: TextView  

private lateinit var ipDetails: TextView  
private lateinit var licenseDetails: TextView  
private lateinit var dataDetails: TextView  
private lateinit var globeView: GlobeView  
private lateinit var globeLocationDetails: TextView
private lateinit var cyberRadarView: CyberRadarView
private lateinit var radarStatusText: TextView
private lateinit var trafficGraphView: LiveTrafficGraphView
private lateinit var trafficSummaryText: TextView
private lateinit var clockText: TextView  
private lateinit var secondLoginTimerStatus: TextView  
private lateinit var secondLoginStageStatus: TextView  

private var peakSpeedBps = 0L

private var dataBaselineRx = 0L  
private var dataBaselineTx = 0L  
private var lastRx = 0L  
private var lastTx = 0L  
private var dataUsageSupported = false  

private var stage = Stage.IDLE  
private var vpnOpened = false  
private var vpnDialogShown = false
private var loginSubmitted = false  
private var portalUrl = FALLBACK_PORTAL  
private var portalNetwork: Network? = null  
private var dashboardShown = false  
private var appInitialized = false  

private var secondLoginTimerStartedAt = 0L  
private var secondLoginElapsedSeconds = 0L  
private var secondLoginTimerRunning = false  

private var logoutSearchAttempts = 0  
private var loginActionSearchAttempts = 0  
private var secondFormSearchAttempts = 0
private var secondLoginButtonFound = false  

private var logoutSearchRunning = false  
private var loginActionSearchRunning = false  
private var secondFormCheckRunning = false  
private var netCheckRunning = false
private var netWorkingVerified = false
private var netCheckPageHasError = false
private var netCheckRefreshAttempt = 0

private val googleCheckUrl = "https://www.google.com/"

private val netCheckRefreshRunnable = Runnable {
    loadNextNetCheckPage()
}

private val netCheckPageTimeoutRunnable = Runnable {
    if (stage == Stage.CHECK_INTERNET && !netWorkingVerified && !isFinishing && !isDestroyed) {
        if (::webView.isInitialized) {
            webView.stopLoading()
        }
        loadNextNetCheckPage()
    }
}

private var activationPopup: AlertDialog? = null  
private var flashDialog: AlertDialog? = null
private var flashPopupChecked = false

private var connectivityManager: ConnectivityManager? = null  
private var networkCallbackRegistered = false  
private var lastLicenseNetworkState = false  
private var lastIpFetchAt = 0L
private var lastKnownVpnForIp = false

/*  
 * Network callback:  
 * Internet ফিরে এলে saved license server-এ verify হবে।  
 * এখানে কোনো timer বা repeated polling নেই।  
 */  
private val licenseNetworkCallback =  
    object : ConnectivityManager.NetworkCallback() {  

        override fun onAvailable(network: Network) {  
            handler.post {  
                updateInternetStatus()  
                verifyLicenseWhenOnline()  
                refreshIpLocationIfNeeded()
            }  
        }  

        override fun onCapabilitiesChanged(  
            network: Network,  
            capabilities: NetworkCapabilities  
        ) {  
            val validated = capabilities.hasCapability(  
                NetworkCapabilities.NET_CAPABILITY_VALIDATED  
            )  

            handler.post {  
                updateInternetStatus()  

                if (validated) {  
                    verifyLicenseWhenOnline()  
                    refreshIpLocationIfNeeded()
                }  
            }  
        }  

        override fun onLost(network: Network) {  
            handler.post {  
                lastLicenseNetworkState = false  
                updateInternetStatus()  
                refreshIpLocationIfNeeded(force = true)
            }  
        }  
    }  

private val updater = object : Runnable {  
    override fun run() {  
        if (!appInitialized || isFinishing || isDestroyed) return  

        updateDeviceDataUsage()  
        updateActivationStatus()  
        updateClock()  

        if (stage == Stage.WAIT_VPN) {  
            checkVpnStatus()  
        }  

        handler.postDelayed(this, 1000)  
    }  
}  

private val timerUpdater = object : Runnable {  
    override fun run() {  
        if (!secondLoginTimerRunning) return  

        secondLoginElapsedSeconds =  
            (SystemClock.elapsedRealtime() -  
                secondLoginTimerStartedAt) / 1000L  

        if (::secondLoginTimerStatus.isInitialized) {  
            secondLoginTimerStatus.text =  
                "SECOND LOGIN SEARCH: ${secondLoginElapsedSeconds}s"  
        }  

        handler.postDelayed(this, 1000)  
    }  
}  

private val globeUpdater = object : Runnable {  
    override fun run() {  
        if (::globeView.isInitialized) globeView.invalidate()  
        if (::cyberRadarView.isInitialized) cyberRadarView.invalidate()
        handler.postDelayed(this, 40)  
    }  
}  

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    window.statusBarColor = dark
    window.navigationBarColor = dark

    if (hasValidatedInternet()) {
        checkForForcedUpdate(backgroundOnly = false)
    } else {
        ActivationManager.requireActivation(this) {
            initializeApplication()
        }
        checkForForcedUpdate(backgroundOnly = true)
    }
}

private fun checkForForcedUpdate(backgroundOnly: Boolean = false) {
    executor.execute {
        var connection: HttpURLConnection? = null

        try {
            val versionCode = if (Build.VERSION.SDK_INT >= 28) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }

            val url = URL("$UPDATE_API?versionCode=$versionCode")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = if (backgroundOnly) 5000 else 10000
            connection.readTimeout = if (backgroundOnly) 5000 else 10000

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val json = JSONObject(response)

            val updateAvailable = json.optBoolean("updateAvailable", false)
            val forceUpdate = json.optBoolean("forceUpdate", false)

            val versionName = json.optString("versionName", "")
            val message = json.optString("message", "A new version is available.")
            val downloadUrl = json.optString("downloadUrl", "")

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (updateAvailable && forceUpdate) {
                    showForceUpdateDialog(versionName, message, downloadUrl)
                } else if (!backgroundOnly) {
                    ActivationManager.requireActivation(this) {
                        initializeApplication()
                    }
                }
            }

        } catch (e: Exception) {
            // সার্ভারে যোগাযোগ না হলে পুরোনো অ্যাপের স্বাভাবিক কাজ চালু থাকবে।
            if (!backgroundOnly) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        ActivationManager.requireActivation(this) {
                            initializeApplication()
                        }
                    }
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (validUrl) {
                try {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(downloadUrl)
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Unable to open update link",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    this,
                    "Update download link is invalid",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    dialog.show()
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

    dataBaselineRx = TrafficStats.getTotalRxBytes()  
    dataBaselineTx = TrafficStats.getTotalTxBytes()  
    lastRx = dataBaselineRx  
    lastTx = dataBaselineTx  

    dataUsageSupported =  
        dataBaselineRx != TrafficStats.UNSUPPORTED.toLong() &&  
        dataBaselineTx != TrafficStats.UNSUPPORTED.toLong()  

    createScreen()  

    registerLicenseNetworkCallback()  

    handler.post(updater)  
    handler.post(globeUpdater)  

    requestNotificationPermission()  
    startMonitorService()  
    requestWifiNotificationAccess()

    if (intent?.action == ACTION_OPEN_PORTAL) {  
        intent?.action = null
        handler.postDelayed({ startPortalProcess() }, 350)  
    }  

    updateIpDetails()  
    updateInternetStatus()  
    updateActivationStatus()  
    updateDeviceDataUsage()  
    updateClock()  

    verifyLicenseWhenOnline()  
    checkForFlashPopup()
}

private fun checkForFlashPopup() {
    if (flashPopupChecked || isFinishing || isDestroyed) return
    flashPopupChecked = true

    executor.execute {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(FLASH_API)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 9000
            connection.readTimeout = 9000

            val response = connection.inputStream.bufferedReader().use {
                it.readText()
            }

            val json = JSONObject(response)
            val active = json.optBoolean("active", false)
            val title = json.optString("title", "").trim()
            val message = json.optString("message", "").trim()
            val hasFile = json.optBoolean("hasFile", false)
            val fileUrl = json.optString("fileUrl", "").trim()
            val fileName = json.optString("fileName", "").trim()

            if (active && (title.isNotBlank() || message.isNotBlank())) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showFlashPopupDialog(
                            title = title,
                            message = message,
                            hasFile = hasFile && fileUrl.startsWith("https://"),
                            fileUrl = fileUrl,
                            fileName = fileName
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Optional flash popup failure does not block app usage.
        } finally {
            connection?.disconnect()
        }
    }
}

private fun showFlashPopupDialog(
    title: String,
    message: String,
    hasFile: Boolean,
    fileUrl: String,
    fileName: String
) {
    if (flashDialog?.isShowing == true || isFinishing || isDestroyed) return

    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.rgb(5, 17, 35))
            cornerRadius = dp(16).toFloat()
            setStroke(2, cyan)
        }
    }

    val heading = TextView(this).apply {
        text = title.ifBlank { "FLASH NOTICE" }
        textSize = 17f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(cyan)
        setPadding(0, 0, 0, dp(10))
    }
    box.addView(heading)

    if (message.isNotBlank()) {
        val msgView = TextView(this).apply {
            text = message
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setLineSpacing(4f, 1.1f)
            setPadding(0, 0, 0, dp(14))
        }
        val scroll = ScrollView(this).apply {
            addView(msgView)
        }
        box.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    val dialog = AlertDialog.Builder(this)
        .setView(box)
        .create()

    if (hasFile && fileUrl.startsWith("https://")) {
        val label = if (fileName.isNotBlank()) {
            "DOWNLOAD $fileName"
        } else {
            "DOWNLOAD FILE"
        }

        val downloadBtn = makeButton(label) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl))
                )
            } catch (_: Exception) {
                toast("Unable to open download link")
            }
        }.apply {
            background = rounded(Color.rgb(0, 90, 115), green)
        }
        box.addView(downloadBtn)
    }

    val closeBtn = makeButton("CLOSE") {
        dialog.dismiss()
    }
    box.addView(closeBtn)

    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    flashDialog = dialog
    dialog.show()
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

    val online = hasValidatedInternet()  

    if (!online) {  
        lastLicenseNetworkState = false  
        return  
    }  

    if (lastLicenseNetworkState) return  

    lastLicenseNetworkState = true  

    ActivationManager.verifySavedLicense(this) { result ->  
        runOnUiThread {  
            updateActivationStatus()  

            when (result) {  
                "INVALID", "REVOKED", "EXPIRED" -> {  
                    statusText(  
                        "● LICENSE $result",  
                        red  
                    )  
                }  

                "VALID" -> {  
                    statusText(  
                        "● LICENSE VERIFIED",  
                        green  
                    )  
                }  

                else -> {  
                    // Network/server failure:  
                    // local license is not removed.  
                }  
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

    val dashboardColumns = LinearLayout(this).apply {  
        orientation = LinearLayout.HORIZONTAL  
        gravity = Gravity.TOP  
    }  

    val leftColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    val rightColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    val ipPanel = createPanel()  
    ipPanel.addView(panelHeading("🌐 IP DETAILS"))  

    ipDetails = panelText(  
        "PUBLIC IP: CHECKING...\nCOUNTRY: --\nCITY: --\nISP: --\nVPN: CHECKING..."  
    )  
    ipPanel.addView(ipDetails)  

    val dataPanel = createPanel()  
    dataPanel.addView(panelHeading("📊 DATA USAGE"))  

    dataDetails = panelText(  
        "DOWNLOAD: --\nUPLOAD: --\nTOTAL: --\nSPEED: --"  
    )  
    dataPanel.addView(dataDetails)  

    val radarPanel = createPanel()
    radarPanel.addView(panelHeading("🛰 NETWORK PULSE"))

    val radarHeightPx = (145 * resources.displayMetrics.density).toInt()
    cyberRadarView = CyberRadarView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            radarHeightPx
        )
    }
    radarPanel.addView(cyberRadarView)

    radarStatusText = panelText("RADAR: SCANNING\nSIGNAL: ACTIVE")
    radarPanel.addView(radarStatusText)

    val activationPanel = createPanel()  
    activationPanel.addView(panelHeading("🔐 ACTIVATION KEY"))  

    licenseDetails = panelText(  
        "ULTRA ACTIVATION\nCHECKING LICENSE..."  
    )  
    activationPanel.addView(licenseDetails)  

    val buyButton = makeButton("BUY NEW ACTIVATION KEY") {  
        openTelegramContact()  
    }  
    activationPanel.addView(buyButton)  

    val globePanel = createPanel()  
    globePanel.addView(panelHeading("🌍 LIVE GLOBE"))  

    val globeSizePx = (155 * resources.displayMetrics.density).toInt()
    globeView = GlobeView(this).apply {  
        layoutParams = LinearLayout.LayoutParams(  
            LinearLayout.LayoutParams.MATCH_PARENT,  
            globeSizePx
        )  
    }  

    globePanel.addView(globeView)  

    globeLocationDetails = panelText(
        "📍 LOCATION: CHECKING...\nIP: --"
    )
    globePanel.addView(globeLocationDetails)

    leftColumn.addView(
        ipPanel,
        matchWrap().apply {
            setMargins(0, 0, 0, 8)
        }
    )
    leftColumn.addView(
        dataPanel,
        matchWrap().apply {
            setMargins(0, 0, 0, 8)
        }
    )
    leftColumn.addView(
        radarPanel,
        matchWrap()
    )

    rightColumn.addView(
        activationPanel,
        matchWrap().apply {
            setMargins(0, 0, 0, 8)
        }
    )
    rightColumn.addView(
        globePanel,
        matchWrap()
    )

    dashboardColumns.addView(  
        leftColumn,  
        LinearLayout.LayoutParams(0, -2, 1f).apply {  
            setMargins(3, 4, 5, 3)  
        }  
    )  

    dashboardColumns.addView(  
        rightColumn,  
        LinearLayout.LayoutParams(0, -2, 1f).apply {  
            setMargins(5, 4, 3, 3)  
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
            if (!checkActivationBeforeAction()) return@makeButton  

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
        text = "📈 LIVE INTERNET USAGE GRAPH"  
        textSize = 12f  
        typeface = Typeface.DEFAULT_BOLD  
        setTextColor(cyan)  
        setPadding(5, 12, 5, 7)  
    }  

    webView = WebView(this).apply {  
        visibility = View.INVISIBLE
        alpha = 0f

        settings.javaScriptEnabled = true  
        settings.domStorageEnabled = true  
        settings.loadsImagesAutomatically = true  
        settings.javaScriptCanOpenWindowsAutomatically = false  
        settings.setSupportMultipleWindows(false)  

        settings.userAgentString =  
            WebSettings.getDefaultUserAgent(this@MainActivity)  
                .replace("; wv", "")  

        CookieManager.getInstance().setAcceptCookie(true)  
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)  

        webChromeClient = WebChromeClient()  

        webViewClient = object : WebViewClient() {  

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (
                    stage == Stage.CHECK_INTERNET &&
                    !netWorkingVerified &&
                    request?.isForMainFrame == true
                ) {
                    val targetUrl = request.url?.toString()
                    if (!isValidGoogleCheckUrl(targetUrl)) {
                        handler.removeCallbacks(netCheckPageTimeoutRunnable)
                        scheduleNextNetCheckRefresh(1500L)
                        return true
                    }
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageStarted(  
                view: WebView?,  
                url: String?,  
                favicon: Bitmap?  
            ) {  
                netCheckPageHasError = false
                if (
                    stage == Stage.CHECK_INTERNET &&
                    !netWorkingVerified &&
                    !isValidGoogleCheckUrl(url)
                ) {
                    view?.stopLoading()
                    handler.removeCallbacks(netCheckPageTimeoutRunnable)
                    scheduleNextNetCheckRefresh(1500L)
                    return
                }
                if (!dashboardShown && stage != Stage.IDLE && !netWorkingVerified) {  
                    workflowStatus("PORTAL PAGE LOADING...")  
                }  
            }  

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    netCheckPageHasError = true
                    if (stage == Stage.CHECK_INTERNET && !netWorkingVerified) {
                        handler.removeCallbacks(netCheckPageTimeoutRunnable)
                        scheduleNextNetCheckRefresh(1500L)
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (
                    request?.isForMainFrame == true &&
                    errorResponse != null &&
                    errorResponse.statusCode >= 400
                ) {
                    netCheckPageHasError = true
                    if (stage == Stage.CHECK_INTERNET && !netWorkingVerified) {
                        handler.removeCallbacks(netCheckPageTimeoutRunnable)
                        scheduleNextNetCheckRefresh(1500L)
                    }
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
                    Stage.CHECK_INTERNET -> verifyCheckInternetPageLoaded(url)
                    else -> {  
                        if (stage == Stage.IDLE && !dashboardShown) {  
                            statusText("● PORTAL PAGE READY", green)  
                        }  
                    }  
                }  
            }  
        }  
    }  

    val density = resources.displayMetrics.density
    val trafficPanel = createPanel()

    trafficSummaryText = TextView(this).apply {
        text = "↓ DL: 0 B/s  •  ↑ UL: 0 B/s  •  PEAK: 0 B/s"
        textSize = 10.5f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(green)
        setPadding(4, 2, 4, 8)
    }
    trafficPanel.addView(trafficSummaryText, matchWrap())

    trafficGraphView = LiveTrafficGraphView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (165 * density).toInt()
        )
    }
    trafficPanel.addView(trafficGraphView)

    secondLoginTimerStatus = panelText("LOGIN TIMER: NOT STARTED")  
    secondLoginStageStatus = panelText("FREE DATA CANECTION: WAITING")  

    content.addView(title, matchWrap())  
    content.addView(subtitle, matchWrap())  
    content.addView(status, matchWrap())  
    content.addView(dashboardColumns, matchWrap())  
    content.addView(clockTextView(), matchWrap())  
    content.addView(loginPanel, matchWrap())  
    content.addView(webTitle, matchWrap())  
    content.addView(trafficPanel, matchWrap())

    // Keep WebView attached but invisible for background portal automation
    root.addView(webView, LinearLayout.LayoutParams(1, 1))

    val footer = TextView(this).apply {
        text = "Copy Right ULTRA"
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(cyan)
        setPadding(5, 12, 5, 12)
    }

    content.addView(footer, matchWrap())

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
            "\n\n${if (ActivationManager.hasAccess(this@MainActivity)) "ACTIVATION: ON" else "ACTIVATION: OFF"}"  
        textSize = 13f  
        gravity = Gravity.CENTER  
        setTextColor(green)  
        setPadding(5, 12, 5, 15)  
    }  

    val buy = makeButton("BUY NEW ACTIVATION KEY") {  
        openTelegramContact()  
    }  

    val close = makeButton("CLOSE") {  
        activationPopup?.dismiss()  
    }  

    box.addView(heading)  
    box.addView(details)  
    box.addView(buy)  
    box.addView(close)  

    activationPopup = AlertDialog.Builder(this)  
        .setView(box)  
        .create()  

    activationPopup?.window?.setBackgroundDrawableResource(  
        android.R.color.transparent  
    )  

    activationPopup?.show()  
}  

private fun updateActivationStatus() {  
    if (::licenseDetails.isInitialized) {  
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
}  

private fun refreshIpLocationIfNeeded(force: Boolean = false) {
    if (!appInitialized || isFinishing || isDestroyed) return
    val now = SystemClock.elapsedRealtime()
    val vpnNow = isVpnConnected()
    val vpnChanged = vpnNow != lastKnownVpnForIp

    if (force || vpnChanged || (now - lastIpFetchAt >= 4000L)) {
        lastKnownVpnForIp = vpnNow
        updateIpDetails()
    }
}

private fun updateIpDetails() {  
    if (!::ipDetails.isInitialized) return  

    lastIpFetchAt = SystemClock.elapsedRealtime()
    lastKnownVpnForIp = isVpnConnected()

    ipDetails.text =  
        "PUBLIC IP: CHECKING...\nCOUNTRY: --\nCITY: --\nISP: --\nVPN: CHECKING..."  

    if (::globeLocationDetails.isInitialized) {
        globeLocationDetails.text = "📍 LOCATION: CHECKING...\nIP: --"
        globeLocationDetails.setTextColor(cyan)
    }

    executor.execute {  
        var data: JSONObject? = null  

        try {  
            val c = URL("https://ipwho.is/")  
                .openConnection() as HttpURLConnection  

            c.connectTimeout = 9000  
            c.readTimeout = 9000  
            c.setRequestProperty("User-Agent", "UltraLoader/1.0")  

            val body = c.inputStream.bufferedReader()  
                .use { it.readText() }  

            c.disconnect()  
            data = JSONObject(body)  
        } catch (_: Exception) {  
        }  

        val success = data?.optBoolean("success", false) == true  

        val ip = if (success) data?.optString("ip").orEmpty().ifBlank { "Unavailable" } else "Unavailable"  
        val country = if (success) data?.optString("country").orEmpty().ifBlank { "Unavailable" } else "Unavailable"  
        val countryCode = if (success) data?.optString("country_code").orEmpty() else ""
        val city = if (success) data?.optString("city").orEmpty().ifBlank { "Unavailable" } else "Unavailable"  
        val isp = if (success) {  
            data?.optJSONObject("connection")?.optString("isp")  
        } else "Unavailable"  

        val hasCoords = success &&
            data?.has("latitude") == true &&
            data.has("longitude")
        val lat = if (hasCoords) data?.optDouble("latitude", 0.0) ?: 0.0 else 0.0
        val lon = if (hasCoords) data?.optDouble("longitude", 0.0) ?: 0.0 else 0.0

        runOnUiThread {  
            if (!::ipDetails.isInitialized) return@runOnUiThread  

            val vpnConnected = isVpnConnected()

            ipDetails.text =  
                "PUBLIC IP: $ip\n" +  
                "COUNTRY: $country\n" +  
                "CITY: $city\n" +  
                "ISP: ${isp ?: "Unavailable"}\n" +  
                "VPN: ${if (vpnConnected) "CONNECTED" else "DISCONNECTED"}"  

            ipDetails.setTextColor(  
                if (vpnConnected) green else cyan  
            )  

            if (::globeView.isInitialized) {
                val shortLabel = when {
                    city != "Unavailable" && countryCode.isNotBlank() -> "$city, $countryCode"
                    country != "Unavailable" -> country
                    else -> ""
                }
                globeView.setLiveLocation(
                    available = hasCoords,
                    lat = lat,
                    lon = lon,
                    label = shortLabel,
                    vpnConnected = vpnConnected
                )
            }

            if (::globeLocationDetails.isInitialized) {
                globeLocationDetails.text = if (success) {
                    val place = listOf(city, country)
                        .filter { it.isNotBlank() && it != "Unavailable" }
                        .joinToString(", ")
                        .ifBlank { "Detected" }
                    "📍 $place\nIP: $ip\n${if (vpnConnected) "🛡 VPN CONNECTED" else "🌐 DIRECT IP"}"
                } else {
                    "📍 LOCATION: UNAVAILABLE\nIP: Offline"
                }
                globeLocationDetails.setTextColor(
                    if (success && vpnConnected) green else cyan
                )
            }
        }  
    }  
}  

private fun updateDeviceDataUsage() {  
    if (!::dataDetails.isInitialized) return  

    if (!dataUsageSupported) {  
        dataDetails.text = "PHONE DATA COUNTER NOT AVAILABLE"  
        dataDetails.setTextColor(red)  
        return  
    }  

    val rx = TrafficStats.getTotalRxBytes()  
    val tx = TrafficStats.getTotalTxBytes()  

    if (  
        rx == TrafficStats.UNSUPPORTED.toLong() ||  
        tx == TrafficStats.UNSUPPORTED.toLong()  
    ) {  
        dataDetails.text = "DATA COUNTER NOT AVAILABLE"  
        dataDetails.setTextColor(red)  
        return  
    }  

    val download = (rx - dataBaselineRx).coerceAtLeast(0L)  
    val upload = (tx - dataBaselineTx).coerceAtLeast(0L)  

    val rxSpeed = (rx - lastRx).coerceAtLeast(0L)  
    val txSpeed = (tx - lastTx).coerceAtLeast(0L)  

    lastRx = rx  
    lastTx = tx  

    dataDetails.text =  
        "DOWNLOAD: ${formatSize(download)}\n" +  
        "UPLOAD: ${formatSize(upload)}\n" +  
        "TOTAL: ${formatSize(download + upload)}\n" +  
        "SPEED: ↓${formatSize(rxSpeed)}/s  ↑${formatSize(txSpeed)}/s"  

    dataDetails.setTextColor(green)  

    val combinedSpeed = rxSpeed + txSpeed
    if (combinedSpeed > peakSpeedBps) {
        peakSpeedBps = combinedSpeed
    }

    if (::trafficGraphView.isInitialized) {
        trafficGraphView.addSample(rxSpeed, txSpeed)
    }

    val vpnNow = isVpnConnected()
    if (::cyberRadarView.isInitialized) {
        cyberRadarView.updateTelemetry(rxSpeed, txSpeed, vpnNow)
    }

    if (::radarStatusText.isInitialized) {
        val stateLabel = if (vpnNow) "SHIELD: VPN LOCKED" else "SIGNAL: DIRECT LINK"
        val flowLabel = if (combinedSpeed > 0L) "FLOW: ${formatSize(combinedSpeed)}/s" else "FLOW: STANDBY"
        radarStatusText.text = "$stateLabel\n$flowLabel"
        radarStatusText.setTextColor(if (vpnNow) green else cyan)
    }

    if (::trafficSummaryText.isInitialized) {
        trafficSummaryText.text =
            "↓ DL: ${formatSize(rxSpeed)}/s  •  ↑ UL: ${formatSize(txSpeed)}/s  •  PEAK: ${formatSize(peakSpeedBps)}/s"
    }
}  

private fun formatSize(bytes: Long): String {  
    if (bytes <= 0L) return "0 B"  

    val kb = bytes / 1024.0  
    val mb = kb / 1024.0  
    val gb = mb / 1024.0  

    return when {  
        gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)  
        mb >= 1 -> String.format(Locale.US, "%.2f MB", mb)  
        kb >= 1 -> String.format(Locale.US, "%.2f KB", kb)  
        else -> "$bytes B"  
    }  
}  

private fun rounded(  
    color: Int,  
    stroke: Int  
): GradientDrawable {  
    return GradientDrawable().apply {  
        setColor(color)  
        cornerRadius = 17f  
        setStroke(1, stroke)  
    }  
}  

private fun matchWrap() =  
    LinearLayout.LayoutParams(  
        LinearLayout.LayoutParams.MATCH_PARENT,  
        LinearLayout.LayoutParams.WRAP_CONTENT  
    )  

private fun makeEditText(  
    hintText: String,  
    password: Boolean  
): EditText {  
    return EditText(this).apply {  
        hint = hintText  
        setSingleLine(true)  
        setTextColor(Color.WHITE)  
        setHintTextColor(Color.LTGRAY)  
        textSize = 13f  
        setPadding(13, 6, 13, 6)  

        if (password) inputType = 129  

        background = rounded(panel, cyan)  

        layoutParams = matchWrap().apply {  
            setMargins(0, 4, 0, 4)  
        }  
    }  
}  

private fun makeButton(  
    label: String,  
    action: () -> Unit  
): Button {  
    return Button(this).apply {  
        text = label  
        isAllCaps = false  
        textSize = 11f  
        typeface = Typeface.DEFAULT_BOLD  
        setTextColor(Color.WHITE)  
        background = rounded(Color.rgb(8, 48, 68), cyan)  
        setPadding(7, 7, 7, 7)  

        layoutParams = matchWrap().apply {  
            setMargins(0, 4, 0, 4)  
        }  

        setOnClickListener { action() }  
    }  
}  

private fun statusText(message: String, color: Int) {  
    if (::status.isInitialized) {  
        status.text = message  
        status.setTextColor(color)  
    }
}

private fun workflowStatus(@Suppress("UNUSED_PARAMETER") logDetail: String) {
    if (::status.isInitialized && !netWorkingVerified) {
        status.text = "● WAIT TO CONNECT"
        status.setTextColor(cyan)
    }
}

private fun toast(message: String) {  
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()  
}  

private fun openTelegramContact() {  
    try {  
        startActivity(  
            Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL))  
        )  
    } catch (_: Exception) {  
        toast("Unable to open Telegram")  
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

private fun requestWifiNotificationAccess() {
    val enabled = android.provider.Settings.Secure.getString(
        contentResolver,
        "enabled_notification_listeners"
    ).orEmpty()

    if (enabled.contains(packageName)) return

    val prefs = getSharedPreferences("wifi_notification_links", MODE_PRIVATE)

    if (prefs.getBoolean("access_prompt_shown", false)) return

    prefs.edit().putBoolean("access_prompt_shown", true).apply()

    AlertDialog.Builder(this)
        .setTitle("Enable Wi-Fi Link Detection")
        .setMessage(
            "Allow Ultra Loader to read URLs displayed in Wi-Fi " +
                "connection and portal notifications. Enable Notification Access."
        )
        .setPositiveButton("OPEN SETTINGS") { _, _ ->
            try {
                startActivity(
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                )
            } catch (_: Exception) {
                toast("Unable to open Notification Access settings")
            }
        }
        .setNegativeButton("LATER", null)
        .show()
}

private fun startMonitorService(refresh: Boolean = false) {  
    try {  
        val i = Intent(this, PortalMonitorService::class.java).apply {
            if (refresh) {
                action = PortalMonitorService.ACTION_REFRESH_NOTIFICATIONS
            }
        }

        if (Build.VERSION.SDK_INT >= 26) {  
            startForegroundService(i)  
        } else {  
            startService(i)  
        }  
    } catch (_: Exception) {  
        statusText("● WIFI MONITOR NOT STARTED", red)  
    }  
}

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    if (
        requestCode == 501 &&
        grantResults.isNotEmpty() &&
        grantResults[0] == PackageManager.PERMISSION_GRANTED
    ) {
        startMonitorService(refresh = true)
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
    if (!checkActivationBeforeAction()) return  

    if (readCredentials() == null) {  
        showCredentialDialog()  
        return  
    }  

    dashboardShown = false  
    stage = Stage.LOGIN  
    loginSubmitted = false  
    vpnOpened = false  
    vpnDialogShown = false

    stopSecondLoginTimer()  
    secondLoginElapsedSeconds = 0L  

    secondLoginTimerStatus.text = "SECOND LOGIN TIMER: NOT STARTED"  
    secondLoginStageStatus.text = "LOGIN STAGE: WAITING"  

    logoutSearchAttempts = 0  
    loginActionSearchAttempts = 0  
    secondFormSearchAttempts = 0
    secondLoginButtonFound = false  

    logoutSearchRunning = false  
    loginActionSearchRunning = false  
    secondFormCheckRunning = false  
    netCheckRunning = false
    netWorkingVerified = false
    netCheckPageHasError = false
    netCheckRefreshAttempt = 0
    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.removeCallbacks(netCheckPageTimeoutRunnable)
    if (::webView.isInitialized) {
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
    }

    workflowStatus("FINDING CURRENT PORTAL...")

    discoverPortalUrl()  
}  

private fun discoverPortalUrl() {
    executor.execute {

        var found: String? = null
        var notificationLink = false

        val prefs = getSharedPreferences(
            "wifi_notification_links",
            Context.MODE_PRIVATE
        )

        val savedUrl = prefs.getString("latest_url", null)
        val savedTime = prefs.getLong("latest_url_time", 0L)

        // Use a recently captured Wi-Fi notification URL first.
        if (
            !savedUrl.isNullOrBlank() &&
            System.currentTimeMillis() - savedTime <= 30L * 60L * 1000L
        ) {
            try {
                val parsed = URL(savedUrl)

                if (
                    parsed.protocol == "http" ||
                    parsed.protocol == "https"
                ) {
                    found = savedUrl
                    notificationLink = true
                }
            } catch (_: Exception) {
            }
        }

        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val wifi = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        val network = wifi ?: manager.activeNetwork
        portalNetwork = wifi ?: network

        // Keep the old portal discovery as fallback.
        val probes = listOf(
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://www.google.com/generate_204",
            "http://clients3.google.com/generate_204"
        )

        if (found == null) {
            for (probe in probes) {
                if (found != null) break

                var connection: HttpURLConnection? = null

                try {
                    connection = (
                        if (network != null) network.openConnection(URL(probe))
                        else URL(probe).openConnection()
                    ) as HttpURLConnection

                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 7000
                    connection.readTimeout = 7000
                    connection.requestMethod = "GET"

                    val code = connection.responseCode
                    val location = connection.getHeaderField("Location")

                    if (
                        code in 300..399 &&
                        !location.isNullOrBlank() &&
                        (
                            location.startsWith("http://") ||
                            location.startsWith("https://")
                        )
                    ) {
                        found = location
                    }

                } catch (_: Exception) {
                } finally {
                    connection?.disconnect()
                }
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
                    statusText("● WIFI ROUTING NOT AVAILABLE", red)
                }
            }

            portalUrl = found ?: FALLBACK_PORTAL

            workflowStatus(
                when {
                    notificationLink -> "WIFI NOTIFICATION LINK FOUND"
                    found != null -> "CURRENT PORTAL FOUND"
                    else -> "USING PORTAL FALLBACK"
                }
            )

            webView.visibility = View.INVISIBLE
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

          var u=dec('$user'), p=dec('$pass');  
          var all=Array.from(document.querySelectorAll('input'));  

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

          uf.focus(); uf.value=u;  
          pf.focus(); pf.value=p;  

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

          if(login){login.click();return 'SUBMITTED';}  
          return 'NO_SUBMIT_BUTTON';  
        })();  
    """.trimIndent()  

    webView.evaluateJavascript(script) { result ->  
        if (result?.contains("SUBMITTED") == true) {  
            loginSubmitted = true  
            stage = Stage.CHECK_LOGIN  

            workflowStatus("LOGIN SUBMITTED - VERIFYING...")  

            handler.postDelayed({ checkPortalLogin() }, 3000)  

        } else if (result?.contains("FORM_NOT_FOUND") == true) {  
            workflowStatus("PORTAL LOGIN FORM NOT FOUND")  
        } else {  
            workflowStatus("CHECKING PORTAL FORM...")  
            handler.postDelayed({ tryAutoLogin() }, 2000)  
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
                workflowStatus("PORTAL LOGIN VERIFIED")  

                (getSystemService(Context.CONNECTIVITY_SERVICE)  
                    as ConnectivityManager).bindProcessToNetwork(null)  

                handler.postDelayed({ openThunderVpn() }, 2500)  
            }  

            result?.contains("LOGIN_FORM_STILL_HERE") == true -> {  
                workflowStatus("LOGIN NOT CONFIRMED")  
                handler.postDelayed({ checkPortalLogin() }, 3000)  
            }  

            else -> handler.postDelayed({ checkPortalLogin() }, 2500)  
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

    val launch = packageManager.getLaunchIntentForPackage(VPN_PACKAGE)  

    if (launch == null) {  
        statusText("● THUNDER VPN NOT INSTALLED", red)  
        showVpnDialog()  
        return  
    }  

    try {  
        workflowStatus("OPENING THUNDER VPN — CONNECT MANUALLY")  
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  
        startActivity(launch)  

        handler.postDelayed({ checkVpnStatus() }, 2500)  
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
    val connected = isVpnConnected()  

    if (::ipDetails.isInitialized) {  
        updateVpnLine(connected)  
    }  

    if (connected) {  
        workflowStatus("VPN CONNECTION DETECTED")  

        if (stage == Stage.WAIT_VPN) {  
            stage = Stage.LOGOUT  
            logoutSearchAttempts = 0  
            logoutSearchRunning = false  

            handler.postDelayed({ clickPortalLogout() }, 5000)  
        }  

        updateInternetStatus()  
        updateIpDetails()  
    } else {  
        if (stage == Stage.WAIT_VPN) {  
            workflowStatus("WAITING FOR VPN CONNECTION")  
            showVpnDialog()  
        }  
    }  
}  

private fun updateVpnLine(connected: Boolean) {  
    if (!::ipDetails.isInitialized) return  

    val lines = ipDetails.text.toString().lines().toMutableList()  

    if (lines.size >= 5) {  
        lines[4] =  
            "VPN: ${if (connected) "CONNECTED" else "DISCONNECTED"}"  
        ipDetails.text = lines.joinToString("\n")  
    }  
}  

private fun showVpnDialog() {
    if (vpnDialogShown || isFinishing || isDestroyed) return

    vpnDialogShown = true

    AlertDialog.Builder(this)
        .setTitle("THUNDER VPN")
        .setMessage(
            "Please open Thunder VPN and connect manually. " +
            "After connecting, return to ULTRA LOADER. " +
            "Your VPN connection will be checked automatically."
        )
        .setPositiveButton("OPEN VPN") { _, _ ->
            openThunderVpn()
        }
        .setNeutralButton("CHECK CONNECTION") { _, _ ->
            checkVpnStatus()
        }
        .setNegativeButton("LATER", null)
        .show()
}  

private fun clickPortalLogout() {  
    if (stage != Stage.LOGOUT || logoutSearchRunning) return  

    if (logoutSearchAttempts >= 10) {  
        workflowStatus("PORTAL LOGOUT BUTTON NOT FOUND")  
        return  
    }  

    logoutSearchRunning = true  
    logoutSearchAttempts++  

    workflowStatus("SEARCHING PORTAL LOGOUT... $logoutSearchAttempts/10")  

    portalNetwork?.let {  
        (getSystemService(Context.CONNECTIVITY_SERVICE)  
            as ConnectivityManager).bindProcessToNetwork(it)  
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
        logoutSearchRunning = false  

        if (stage != Stage.LOGOUT) return@evaluateJavascript  

        if (result?.contains("LOGOUT_CLICKED") == true) {  
            workflowStatus("LOGOUT CLICKED")  
            startSecondLoginTimer()  
            handler.postDelayed({ clickPortalLoginAgent() }, 2500)  
        } else if (logoutSearchAttempts < 10) {  
            handler.postDelayed({ clickPortalLogout() }, 3000)  
        } else {  
            workflowStatus("PORTAL LOGOUT BUTTON NOT FOUND")  
        }  
    }  
}  

private fun startSecondLoginTimer() {  
    stopSecondLoginTimer()  

    secondLoginTimerStartedAt = SystemClock.elapsedRealtime()  
    secondLoginElapsedSeconds = 0L  
    secondLoginTimerRunning = true  

    secondLoginTimerStatus.text = "SECOND LOGIN SEARCH: 0s"  
    secondLoginStageStatus.text = "SEARCHING FOR SECOND LOGIN BUTTON"  

    handler.removeCallbacks(timerUpdater)  
    handler.post(timerUpdater)  
}  

private fun stopSecondLoginTimer() {  
    if (secondLoginTimerRunning) {  
        secondLoginElapsedSeconds =  
            (SystemClock.elapsedRealtime() -  
                secondLoginTimerStartedAt) / 1000L  
    }  

    secondLoginTimerRunning = false  
    handler.removeCallbacks(timerUpdater)  
}  

private fun clickPortalLoginAgent() {  
    if (stage != Stage.LOGOUT || loginActionSearchRunning) return  

    if (loginActionSearchAttempts >= 60) {  
        stopSecondLoginTimer()  
        secondLoginStageStatus.text = "SECOND LOGIN BUTTON NOT FOUND"  
        secondLoginStageStatus.setTextColor(red)  
        workflowStatus("PORTAL LOGIN ACTION NOT FOUND")  
        return  
    }  

    loginActionSearchRunning = true  
    loginActionSearchAttempts++  

    workflowStatus(  
        "SEARCHING SECOND LOGIN BUTTON... $loginActionSearchAttempts/60"
    )  

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
        loginActionSearchRunning = false  

        if (stage != Stage.LOGOUT) return@evaluateJavascript  

        if (result?.contains("LOGIN_AGENT_CLICKED") == true) {  
            secondLoginButtonFound = true  
            secondFormSearchAttempts = 0
            stage = Stage.WAIT_SECOND_LOGIN  

            stopSecondLoginTimer()  
            secondLoginStageStatus.text = "LOGIN BUTTON CLICKED"  
            workflowStatus("SECOND LOGIN BUTTON FOUND")  

            handler.postDelayed({ checkSecondLoginForm() }, 1000)  

        } else if (loginActionSearchAttempts < 60) {  
            handler.postDelayed({ clickPortalLoginAgent() }, 2000)  
        } else {  
            stopSecondLoginTimer()  
            secondLoginStageStatus.text = "SECOND LOGIN BUTTON NOT FOUND"  
            secondLoginStageStatus.setTextColor(red)  
            workflowStatus("PORTAL LOGIN ACTION NOT FOUND")  
        }  
    }  
}  

private fun checkSecondLoginForm() {  
    if (stage != Stage.WAIT_SECOND_LOGIN) return  
    if (!secondLoginButtonFound || secondFormCheckRunning) return  

    secondFormCheckRunning = true  
    secondFormSearchAttempts++

    val formScript = """  
        (function(){  
          var u=document.querySelector('input[type=text],input[type=email],input[name*=user i]');  
          var p=document.querySelector('input[type=password]');  
          return u&&p?'SECOND_FORM_READY':'WAITING';  
        })();  
    """.trimIndent()  

    webView.evaluateJavascript(formScript) { result ->  
        if (stage != Stage.WAIT_SECOND_LOGIN) {  
            secondFormCheckRunning = false  
            return@evaluateJavascript  
        }  

        if (result?.contains("SECOND_FORM_READY") == true || secondFormSearchAttempts >= 5) {  
            secondFormCheckRunning = false
            startInAppInternetRefreshCheck()
        } else {  
            secondFormCheckRunning = false  

            workflowStatus("WAITING FOR SECOND LOGIN FORM...")  

            handler.postDelayed({ checkSecondLoginForm() }, 2000)  
        }  
    }  
}  

private fun startInAppInternetRefreshCheck() {
    if (stage == Stage.CHECK_INTERNET || netWorkingVerified || isFinishing || isDestroyed) return

    stage = Stage.CHECK_INTERNET
    loginSubmitted = true
    secondFormCheckRunning = false
    stopSecondLoginTimer()

    try {
        (getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager).bindProcessToNetwork(null)
    } catch (_: Exception) {
    }

    if (::secondLoginStageStatus.isInitialized) {
        secondLoginStageStatus.text = "REFRESHING IN-APP — CHECKING NET..."
        secondLoginStageStatus.setTextColor(cyan)
    }

    workflowStatus("REFRESHING TO CHECK INTERNET...")

    netCheckRefreshAttempt = 0
    netCheckPageHasError = false
    if (::webView.isInitialized) {
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.clearCache(false)
    }

    loadNextNetCheckPage()
    startLiveNetWorkingCheck()
}

private fun scheduleNextNetCheckRefresh(delayMs: Long = 1500L) {
    if (stage != Stage.CHECK_INTERNET || netWorkingVerified || isFinishing || isDestroyed) return
    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.postDelayed(netCheckRefreshRunnable, delayMs)
}

private fun loadNextNetCheckPage() {
    if (stage != Stage.CHECK_INTERNET || netWorkingVerified || isFinishing || isDestroyed) return
    if (!::webView.isInitialized) return

    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.removeCallbacks(netCheckPageTimeoutRunnable)

    netCheckRefreshAttempt++
    netCheckPageHasError = false

    val checkUrl = "${googleCheckUrl}?ul_chk=${System.currentTimeMillis()}"
    workflowStatus("REFRESHING GOOGLE ($netCheckRefreshAttempt)...")

    webView.stopLoading()
    webView.loadUrl(checkUrl)
    handler.postDelayed(netCheckPageTimeoutRunnable, 5500L)
}

private fun isValidGoogleCheckUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase(Locale.ROOT)
    if (
        lower.startsWith("about:") ||
        lower.startsWith("chrome-error:") ||
        lower.startsWith("data:") ||
        !lower.startsWith("https://")
    ) {
        return false
    }

    val loadedHost = try { Uri.parse(url).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
    val portalHost = try { Uri.parse(portalUrl).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
    if (loadedHost.isNullOrBlank()) return false
    if (!portalHost.isNullOrBlank() && loadedHost == portalHost) return false

    return loadedHost == "google.com" ||
        loadedHost.endsWith(".google.com") ||
        loadedHost.matches(Regex("^(www\\.)?google\\.[a-z]{2,3}(\\.[a-z]{2})?$"))
}

private fun verifyCheckInternetPageLoaded(url: String?) {
    if (stage != Stage.CHECK_INTERNET || netWorkingVerified || isFinishing || isDestroyed) return

    handler.removeCallbacks(netCheckPageTimeoutRunnable)

    if (netCheckPageHasError || !isValidGoogleCheckUrl(url)) {
        scheduleNextNetCheckRefresh(1500L)
        return
    }

    val script = """
        (function(){
          var href = (window.location && window.location.href) ? window.location.href : '';
          if (!href || href.indexOf('chrome-error://') === 0 || href === 'about:blank') return 'ERROR_PAGE';
          if (!/google\./i.test(window.location.hostname || '')) return 'NOT_GOOGLE';
          if (document.querySelector('input[type=password]')) return 'PORTAL_PAGE';
          var text = (document.body ? document.body.innerText : '').trim();
          var title = (document.title || '').trim();
          if (text.length < 10 && title.length === 0) return 'EMPTY_PAGE';
          return 'PAGE_OPENED';
        })();
    """.trimIndent()

    webView.evaluateJavascript(script) { result ->
        if (stage != Stage.CHECK_INTERNET || netWorkingVerified || isFinishing || isDestroyed) {
            return@evaluateJavascript
        }
        if (result?.contains("PAGE_OPENED") == true) {
            markInternetConnected()
        } else {
            scheduleNextNetCheckRefresh(1500L)
        }
    }
}

private fun markInternetConnected() {
    if (netWorkingVerified || isFinishing || isDestroyed) return

    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.removeCallbacks(netCheckPageTimeoutRunnable)

    netWorkingVerified = true
    netCheckRunning = false
    stage = Stage.FINISHED

    if (::webView.isInitialized) {
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
    }

    statusText("● CONNECTED INTERNET", green)

    if (::secondLoginStageStatus.isInitialized) {
        secondLoginStageStatus.text = "SECOND LOGIN FORM FOUND • CONNECTED INTERNET"
        secondLoginStageStatus.setTextColor(green)
    }

    loadIpDashboard()
    updateIpDetails()
    refreshIpLocationIfNeeded(force = true)
}

private fun startLiveNetWorkingCheck() {
    if (
        netCheckRunning ||
        netWorkingVerified ||
        (stage != Stage.CHECK_INTERNET && stage != Stage.FINISHED) ||
        isFinishing ||
        isDestroyed
    ) {
        return
    }

    netCheckRunning = true

    executor.execute {
        val working = isRealInternetResponding()

        runOnUiThread {
            netCheckRunning = false
            if (
                (stage != Stage.CHECK_INTERNET && stage != Stage.FINISHED) ||
                netWorkingVerified ||
                isFinishing ||
                isDestroyed
            ) return@runOnUiThread

            if (working) {
                markInternetConnected()
            } else {
                workflowStatus("CHECKING INTERNET CONNECTION...")
                handler.postDelayed({ startLiveNetWorkingCheck() }, 1500)
            }
        }
    }
}

private fun isRealInternetResponding(): Boolean {
    val testUrls = listOf(
        "https://clients3.google.com/generate_204",
        "http://connectivitycheck.gstatic.com/generate_204"
    )
    for (testUrl in testUrls) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(testUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.useCaches = false
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code == 204) {
                return true
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }
    return false
}

private fun hasValidatedInternet(): Boolean {  
    val cm =  
        getSystemService(Context.CONNECTIVITY_SERVICE)  
            as ConnectivityManager  

    return cm.getNetworkCapabilities(cm.activeNetwork)  
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true  
}  

private fun updateInternetStatus() {  
    if (!::internetStatus.isInitialized) return  

    val validated = hasValidatedInternet()  

    internetStatus.text =  
        if (validated) "INTERNET: VERIFIED"  
        else "INTERNET: NOT VERIFIED"  

    internetStatus.setTextColor(if (validated) green else red)  

    if ((stage == Stage.CHECK_INTERNET || stage == Stage.FINISHED) && !netWorkingVerified) {
        startLiveNetWorkingCheck()
    }

    if (  
        stage == Stage.FINISHED &&  
        isVpnConnected() &&  
        validated &&  
        !dashboardShown  
    ) {  
        loadIpDashboard()  
    }  
}  

private fun loadIpDashboard() {  
    if (  
        dashboardShown ||  
        !isVpnConnected() ||  
        !(hasValidatedInternet() || netWorkingVerified)  
    ) return  

    dashboardShown = true  
    if (netWorkingVerified) {
        statusText("● CONNECTED INTERNET", green)
    }

    updateIpDetails()  
}  

private fun getSecretKey(): SecretKey {  
    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")  
    keyStore.load(null)  

    val oldKey = keyStore.getKey(KEY_ALIAS, null)  
    if (oldKey is SecretKey) return oldKey  

    val generator = KeyGenerator.getInstance(  
        KeyProperties.KEY_ALGORITHM_AES,  
        "AndroidKeyStore"  
    )  

    val spec = KeyGenParameterSpec.Builder(  
        KEY_ALIAS,  
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT  
    )  
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)  
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)  
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
        "$username\n$password".toByteArray(StandardCharsets.UTF_8)  
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

    if (  
        intent?.action == ACTION_OPEN_PORTAL &&  
        appInitialized  
    ) {  
        intent.action = null
        handler.postDelayed({ startPortalProcess() }, 250)  
    }  
}  

override fun onResume() {  
    super.onResume()  

    if (!appInitialized) return  

    updateActivationStatus()  
    updateInternetStatus()  
    updateDeviceDataUsage()  
    verifyLicenseWhenOnline()  

    if (!ActivationManager.hasAccess(this)) {  
        statusText("● ACTIVATION EXPIRED", red)  

        ActivationManager.requireActivation(this) {  
            updateActivationStatus()  
        }  
        return  
    }  

    if (stage == Stage.WAIT_VPN) {  
        handler.postDelayed({ checkVpnStatus() }, 700)  
    }  
}  

override fun onDestroy() {  
    secondLoginTimerRunning = false  
    handler.removeCallbacksAndMessages(null)  
    executor.shutdownNow()  

    activationPopup?.dismiss()  
    flashDialog?.dismiss()

    if (networkCallbackRegistered) {  
        try {  
            connectivityManager?.unregisterNetworkCallback(  
                licenseNetworkCallback  
            )  
        } catch (_: Exception) {  
        }  

        networkCallbackRegistered = false  
    }  

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

private inner class GlobeView(context: Context) : View(context) {

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val landFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(42, 196, 114)
    }
    private val landStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(128, 250, 178)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(65, 130, 235, 255)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(215, 4, 18, 38)
    }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val clipPath = Path()
    private val polyPath = Path()
    private val graticulePath = Path()

    private var rotation = 75f
    private var pulsePhase = 0f

    private var hasLocation = false
    private var locationLat = 0.0
    private var locationLon = 0.0
    private var locationLabel = ""
    private var vpnActive = false

    private val axialTiltRad = Math.toRadians(14.0)
    private val cosTilt = kotlin.math.cos(axialTiltRad)
    private val sinTilt = kotlin.math.sin(axialTiltRad)

    // World continent polygons in (lon, lat) degrees
    private val continents: List<List<Pair<Double, Double>>> = listOf(
        // North America
        listOf(
            -168.0 to 66.0, -160.0 to 71.0, -135.0 to 69.0, -110.0 to 72.0,
            -82.0 to 68.0, -65.0 to 60.0, -53.0 to 47.0, -66.0 to 44.0,
            -75.0 to 35.0, -81.0 to 25.0, -87.0 to 21.0, -83.0 to 10.0,
            -78.0 to 8.0, -85.0 to 11.0, -92.0 to 15.0, -105.0 to 20.0,
            -110.0 to 24.0, -117.0 to 32.0, -124.0 to 41.0, -125.0 to 49.0,
            -137.0 to 58.0, -152.0 to 60.0, -165.0 to 60.0
        ),
        // Greenland
        listOf(
            -52.0 to 60.0, -42.0 to 60.0, -22.0 to 70.0, -18.0 to 81.0,
            -45.0 to 83.0, -68.0 to 77.0, -55.0 to 68.0
        ),
        // South America
        listOf(
            -78.0 to 9.0, -71.0 to 12.0, -62.0 to 10.0, -50.0 to 1.0,
            -35.0 to -6.0, -39.0 to -15.0, -42.0 to -23.0, -53.0 to -33.0,
            -62.0 to -40.0, -67.0 to -54.0, -74.0 to -52.0, -73.0 to -40.0,
            -71.0 to -30.0, -70.0 to -18.0, -81.0 to -5.0, -79.0 to 2.0
        ),
        // Europe
        listOf(
            -9.0 to 37.0, -9.0 to 43.0, -2.0 to 44.0, -5.0 to 48.0,
            2.0 to 51.0, 8.0 to 54.0, 10.0 to 58.0, 5.0 to 61.0,
            15.0 to 69.0, 28.0 to 71.0, 42.0 to 68.0, 55.0 to 67.0,
            58.0 to 55.0, 45.0 to 45.0, 36.0 to 45.0, 29.0 to 41.0,
            23.0 to 37.0, 18.0 to 40.0, 13.0 to 44.0, 16.0 to 38.0,
            12.0 to 42.0, 7.0 to 44.0, 3.0 to 42.0, -5.0 to 36.0
        ),
        // UK & Ireland
        listOf(
            -6.0 to 50.0, 1.0 to 51.0, -2.0 to 58.0, -7.0 to 57.0, -9.0 to 52.0
        ),
        // Africa
        listOf(
            -17.0 to 15.0, -13.0 to 28.0, -6.0 to 36.0, 10.0 to 37.0,
            12.0 to 33.0, 25.0 to 32.0, 33.0 to 31.0, 40.0 to 16.0,
            51.0 to 12.0, 45.0 to 2.0, 40.0 to -10.0, 35.0 to -24.0,
            28.0 to -33.0, 18.0 to -35.0, 14.0 to -22.0, 12.0 to -12.0,
            9.0 to 4.0, 2.0 to 6.0, -8.0 to 5.0, -15.0 to 11.0
        ),
        // Madagascar
        listOf(
            44.0 to -25.0, 47.0 to -25.0, 50.0 to -13.0, 48.0 to -15.0, 44.0 to -20.0
        ),
        // Asia
        listOf(
            28.0 to 41.0, 36.0 to 37.0, 35.0 to 31.0, 43.0 to 13.0,
            58.0 to 22.0, 56.0 to 26.0, 62.0 to 25.0, 68.0 to 23.0,
            77.0 to 8.0, 82.0 to 16.0, 90.0 to 22.0, 98.0 to 15.0,
            104.0 to 2.0, 105.0 to 10.0, 109.0 to 14.0, 108.0 to 21.0,
            120.0 to 25.0, 122.0 to 31.0, 119.0 to 39.0, 126.0 to 38.0,
            129.0 to 35.0, 131.0 to 42.0, 141.0 to 53.0, 155.0 to 60.0,
            175.0 to 66.0, 160.0 to 70.0, 130.0 to 73.0, 105.0 to 76.0,
            72.0 to 73.0, 58.0 to 68.0, 58.0 to 55.0, 46.0 to 44.0, 38.0 to 45.0
        ),
        // Japan
        listOf(
            131.0 to 32.0, 136.0 to 34.0, 141.0 to 38.0, 145.0 to 44.0,
            140.0 to 42.0, 137.0 to 37.0, 130.0 to 34.0
        ),
        // Southeast Asia Islands (Sumatra/Java)
        listOf(
            96.0 to 5.0, 105.0 to -6.0, 114.0 to -8.0, 114.0 to -7.0,
            102.0 to -3.0, 98.0 to 2.0
        ),
        // Borneo
        listOf(
            109.0 to 1.0, 115.0 to 6.0, 118.0 to 4.0, 117.0 to -3.0, 111.0 to -3.0
        ),
        // Australia
        listOf(
            114.0 to -22.0, 122.0 to -17.0, 131.0 to -12.0, 136.0 to -12.0,
            137.0 to -15.0, 142.0 to -11.0, 146.0 to -18.0, 153.0 to -28.0,
            150.0 to -37.0, 141.0 to -38.0, 136.0 to -35.0, 129.0 to -32.0,
            116.0 to -34.0, 114.0 to -27.0
        ),
        // New Zealand
        listOf(
            172.0 to -35.0, 178.0 to -38.0, 175.0 to -42.0, 168.0 to -46.0,
            166.0 to -45.0, 172.0 to -41.0
        ),
        // Antarctica polar cap
        listOf(
            -180.0 to -72.0, -140.0 to -73.0, -100.0 to -72.0, -60.0 to -66.0,
            -20.0 to -70.0, 20.0 to -69.0, 60.0 to -68.0, 100.0 to -67.0,
            140.0 to -67.0, 180.0 to -72.0, 180.0 to -88.0, 120.0 to -88.0,
            60.0 to -88.0, 0.0 to -88.0, -60.0 to -88.0, -120.0 to -88.0,
            -180.0 to -88.0
        )
    )

    fun setLiveLocation(
        available: Boolean,
        lat: Double,
        lon: Double,
        label: String,
        vpnConnected: Boolean
    ) {
        hasLocation = available
        locationLat = lat
        locationLon = lon
        locationLabel = label
        vpnActive = vpnConnected
        if (available) {
            // Smoothly bring the newly connected location into front view
            rotation = (lon.toFloat() - 12f + 360f) % 360f
        }
        invalidate()
    }

    private fun projectPoint(
        lonDeg: Double,
        latDeg: Double,
        viewLonDeg: Double
    ): Triple<Float, Float, Float> {
        var relLon = (lonDeg - viewLonDeg) % 360.0
        if (relLon > 180.0) relLon -= 360.0
        if (relLon < -180.0) relLon += 360.0

        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(relLon)
        val cosLat = kotlin.math.cos(latRad)

        val x0 = cosLat * kotlin.math.sin(lonRad)
        val y0 = kotlin.math.sin(latRad)
        val z0 = cosLat * kotlin.math.cos(lonRad)

        val yRot = y0 * cosTilt - z0 * sinTilt
        val zRot = z0 * cosTilt + y0 * sinTilt

        return Triple(x0.toFloat(), yRot.toFloat(), zRot.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.44f

        rotation = (rotation + 0.85f) % 360f
        pulsePhase = (pulsePhase + 0.055f) % 1f

        val viewLon = rotation.toDouble()

        // Outer atmospheric halo
        haloPaint.shader = RadialGradient(
            cx,
            cy,
            radius * 1.15f,
            intArrayOf(
                Color.argb(95, 0, 210, 255),
                Color.argb(30, 0, 180, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.82f, 0.94f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.14f, haloPaint)

        // Earth Ocean Sphere
        oceanPaint.style = Paint.Style.FILL
        oceanPaint.shader = RadialGradient(
            cx - radius * 0.32f,
            cy - radius * 0.32f,
            radius * 1.45f,
            intArrayOf(
                Color.rgb(52, 175, 245),
                Color.rgb(16, 102, 192),
                Color.rgb(5, 34, 84)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, oceanPaint)
        oceanPaint.shader = null

        // Clip interior to globe circle
        canvas.save()
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Subtle latitude/longitude graticule
        for (lat in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) {
            graticulePath.reset()
            var started = false
            var lon = -180.0
            while (lon <= 180.0) {
                val (px, py, pz) = projectPoint(lon, lat, viewLon)
                if (pz >= -0.02f) {
                    val sx = cx + px * radius
                    val sy = cy - py * radius
                    if (!started) {
                        graticulePath.moveTo(sx, sy)
                        started = true
                    } else {
                        graticulePath.lineTo(sx, sy)
                    }
                } else {
                    started = false
                }
                lon += 10.0
            }
            canvas.drawPath(graticulePath, gridPaint)
        }

        for (lonLine in 0 until 360 step 45) {
            graticulePath.reset()
            var started = false
            var lat = -85.0
            while (lat <= 85.0) {
                val (px, py, pz) = projectPoint(lonLine.toDouble(), lat, viewLon)
                if (pz >= -0.02f) {
                    val sx = cx + px * radius
                    val sy = cy - py * radius
                    if (!started) {
                        graticulePath.moveTo(sx, sy)
                        started = true
                    } else {
                        graticulePath.lineTo(sx, sy)
                    }
                } else {
                    started = false
                }
                lat += 10.0
            }
            canvas.drawPath(graticulePath, gridPaint)
        }

        // Draw World Continents
        for (poly in continents) {
            var anyFront = false
            val projected = ArrayList<Triple<Float, Float, Float>>(poly.size)

            for ((lon, lat) in poly) {
                val pt = projectPoint(lon, lat, viewLon)
                if (pt.third >= -0.05f) {
                    anyFront = true
                }
                projected.add(pt)
            }

            if (!anyFront) continue

            polyPath.reset()
            var first = true

            for ((px, py, pz) in projected) {
                var fx = px
                var fy = py
                if (pz < 0f) {
                    val len = kotlin.math.hypot(fx.toDouble(), fy.toDouble()).toFloat()
                    if (len > 0.0001f) {
                        fx /= len
                        fy /= len
                    }
                }
                val sx = cx + fx * radius
                val sy = cy - fy * radius
                if (first) {
                    polyPath.moveTo(sx, sy)
                    first = false
                } else {
                    polyPath.lineTo(sx, sy)
                }
            }
            polyPath.close()

            canvas.drawPath(polyPath, landFillPaint)
            canvas.drawPath(polyPath, landStrokePaint)
        }

        // Specular sunlight highlight
        shinePaint.shader = RadialGradient(
            cx - radius * 0.38f,
            cy - radius * 0.38f,
            radius * 0.95f,
            intArrayOf(
                Color.argb(55, 255, 255, 255),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, shinePaint)

        canvas.restore()

        // Outer neon rim
        rimPaint.style = Paint.Style.STROKE
        rimPaint.strokeWidth = 2.2f
        rimPaint.color = cyan
        canvas.drawCircle(cx, cy, radius, rimPaint)

        // Live Connected IP Location Marker
        val vpnNow = isVpnConnected() || vpnActive
        if (hasLocation) {
            val (px, py, pz) = projectPoint(locationLon, locationLat, viewLon)
            val pinColor = if (vpnNow) green else Color.rgb(255, 80, 95)

            if (pz >= 0f) {
                val mx = cx + px * radius
                val my = cy - py * radius

                // Expanding radar ring
                val pulseRadius = (6f + pulsePhase * 18f) * (resources.displayMetrics.density / 2.5f).coerceAtLeast(1f)
                pulsePaint.color = Color.argb(
                    ((1f - pulsePhase) * 230).toInt().coerceIn(0, 255),
                    Color.red(pinColor),
                    Color.green(pinColor),
                    Color.blue(pinColor)
                )
                canvas.drawCircle(mx, my, pulseRadius, pulsePaint)

                // Soft glow + bright core dot
                dotPaint.color = Color.argb(110, Color.red(pinColor), Color.green(pinColor), Color.blue(pinColor))
                canvas.drawCircle(mx, my, 11f, dotPaint)

                dotPaint.color = Color.WHITE
                canvas.drawCircle(mx, my, 6.5f, dotPaint)

                dotPaint.color = pinColor
                canvas.drawCircle(mx, my, 4.5f, dotPaint)

                // Floating location badge next to pin
                if (locationLabel.isNotBlank()) {
                    drawLocationBadge(canvas, mx, my - 16f, "📍 $locationLabel", pinColor)
                }
            } else if (locationLabel.isNotBlank()) {
                // Keep live connected location indicator visible while pin rotates around the back
                drawLocationBadge(canvas, cx, cy + radius - 8f, "↻ $locationLabel", pinColor)
            }
        } else if (vpnNow) {
            // Fallback pulsing VPN marker if coordinates are still resolving
            val (px, py, pz) = projectPoint(40.0, 22.0, viewLon)
            if (pz >= 0f) {
                val mx = cx + px * radius
                val my = cy - py * radius
                dotPaint.color = green
                canvas.drawCircle(mx, my, 5.5f, dotPaint)
            }
        }
    }

    private fun drawLocationBadge(
        canvas: Canvas,
        anchorX: Float,
        anchorY: Float,
        text: String,
        accentColor: Int
    ) {
        val density = resources.displayMetrics.density
        badgeTextPaint.textSize = 9.5f * density

        val textWidth = badgeTextPaint.measureText(text)
        val padH = 7f * density
        val boxW = textWidth + padH * 2f
        val boxH = 17f * density

        val left = (anchorX - boxW / 2f).coerceIn(4f, (width - boxW - 4f).coerceAtLeast(4f))
        val top = (anchorY - boxH).coerceIn(4f, (height - boxH - 4f).coerceAtLeast(4f))
        val right = left + boxW
        val bottom = top + boxH

        val rect = RectF(left, top, right, bottom)
        badgeStrokePaint.color = accentColor

        canvas.drawRoundRect(rect, boxH / 2f, boxH / 2f, badgeBgPaint)
        canvas.drawRoundRect(rect, boxH / 2f, boxH / 2f, badgeStrokePaint)

        val fm = badgeTextPaint.fontMetrics
        val textY = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, rect.centerX(), textY, badgeTextPaint)
    }
}

private inner class LiveTrafficGraphView(context: Context) : View(context) {

    private val maxSamples = 60
    private val rxSamples = ArrayList<Long>(maxSamples)
    private val txSamples = ArrayList<Long>(maxSamples)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(4, 13, 28)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.rgb(18, 78, 108)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(55, 0, 225, 255)
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 175, 225, 245)
    }
    private val rxLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = green
    }
    private val txLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = cyan
    }
    private val rxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val txFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rxLinePath = Path()
    private val rxFillPath = Path()
    private val txLinePath = Path()
    private val txFillPath = Path()

    fun addSample(rxBps: Long, txBps: Long) {
        if (rxSamples.size >= maxSamples) rxSamples.removeAt(0)
        if (txSamples.size >= maxSamples) txSamples.removeAt(0)
        rxSamples.add(rxBps.coerceAtLeast(0L))
        txSamples.add(txBps.coerceAtLeast(0L))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val padLeft = 56f * density / 2.5f
        val padRight = 10f * density
        val padTop = 22f * density
        val padBottom = 18f * density

        val chartRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(chartRect, 14f, 14f, bgPaint)
        canvas.drawRoundRect(chartRect, 14f, 14f, borderPaint)

        val graphLeft = padLeft.coerceAtLeast(18f * density)
        val graphRight = (width - padRight).coerceAtLeast(graphLeft + 20f)
        val graphTop = padTop
        val graphBottom = (height - padBottom).coerceAtLeast(graphTop + 20f)
        val graphW = graphRight - graphLeft
        val graphH = graphBottom - graphTop

        // Compute dynamic Y scale (minimum 10 KB/s so small spikes stay clean)
        val rawMax = maxOf(
            rxSamples.maxOrNull() ?: 0L,
            txSamples.maxOrNull() ?: 0L,
            10L * 1024L
        )
        val maxVal = (rawMax * 1.15).toLong().coerceAtLeast(10L * 1024L)

        axisTextPaint.textSize = 8.5f * density
        axisTextPaint.textAlign = Paint.Align.LEFT

        // Horizontal grid & Y-axis labels
        for (step in 0..3) {
            val frac = step / 3f
            val y = graphBottom - frac * graphH
            canvas.drawLine(graphLeft, y, graphRight, y, gridPaint)

            val labelVal = (maxVal * frac).toLong()
            val labelText = "${formatSize(labelVal)}/s"
            canvas.drawText(labelText, 6f * density, y - 2f * density, axisTextPaint)
        }

        // Vertical time gridlines
        for (col in 1..4) {
            val x = graphLeft + (col / 5f) * graphW
            canvas.drawLine(x, graphTop, x, graphBottom, gridPaint)
        }

        // Legend header
        axisTextPaint.textAlign = Paint.Align.RIGHT
        axisTextPaint.color = green
        canvas.drawText("● DL (RX)", graphRight - 66f * density, 13f * density, axisTextPaint)
        axisTextPaint.color = cyan
        canvas.drawText("● UL (TX)", graphRight - 4f * density, 13f * density, axisTextPaint)
        axisTextPaint.color = Color.argb(185, 175, 225, 245)
        axisTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("-60s", graphLeft, height - 4f * density, axisTextPaint)
        axisTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("LIVE", graphRight, height - 4f * density, axisTextPaint)

        if (rxSamples.isEmpty()) return

        rxFillPaint.shader = LinearGradient(
            0f, graphTop, 0f, graphBottom,
            Color.argb(95, 0, 235, 145),
            Color.argb(5, 0, 235, 145),
            Shader.TileMode.CLAMP
        )
        txFillPaint.shader = LinearGradient(
            0f, graphTop, 0f, graphBottom,
            Color.argb(80, 0, 225, 255),
            Color.argb(5, 0, 225, 255),
            Shader.TileMode.CLAMP
        )

        fun buildSeriesPaths(
            samples: List<Long>,
            linePath: Path,
            fillPath: Path
        ): Pair<Float, Float> {
            linePath.reset()
            fillPath.reset()

            val count = samples.size
            val startIndex = maxSamples - count
            var lastX = graphLeft
            var lastY = graphBottom

            for (i in samples.indices) {
                val x = graphLeft + ((startIndex + i).toFloat() / (maxSamples - 1).coerceAtLeast(1)) * graphW
                val norm = (samples[i].toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
                val y = graphBottom - norm * graphH

                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, graphBottom)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = lastX
                    val midX = (prevX + x) / 2f
                    linePath.cubicTo(midX, lastY, midX, y, x, y)
                    fillPath.cubicTo(midX, lastY, midX, y, x, y)
                }
                lastX = x
                lastY = y
            }

            fillPath.lineTo(lastX, graphBottom)
            fillPath.close()
            return Pair(lastX, lastY)
        }

        val (rxEndX, rxEndY) = buildSeriesPaths(rxSamples, rxLinePath, rxFillPath)
        val (txEndX, txEndY) = buildSeriesPaths(txSamples, txLinePath, txFillPath)

        canvas.drawPath(rxFillPath, rxFillPaint)
        canvas.drawPath(txFillPath, txFillPaint)
        canvas.drawPath(rxLinePath, rxLinePaint)
        canvas.drawPath(txLinePath, txLinePaint)

        // Glowing live head dots
        dotPaint.color = Color.argb(110, 0, 235, 145)
        canvas.drawCircle(rxEndX, rxEndY, 7f, dotPaint)
        dotPaint.color = green
        canvas.drawCircle(rxEndX, rxEndY, 3.8f, dotPaint)

        dotPaint.color = Color.argb(110, 0, 225, 255)
        canvas.drawCircle(txEndX, txEndY, 6f, dotPaint)
        dotPaint.color = cyan
        canvas.drawCircle(txEndX, txEndY, 3.2f, dotPaint)
    }
}

private inner class CyberRadarView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
        color = Color.argb(85, 0, 225, 255)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        color = Color.argb(140, 0, 225, 255)
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arcBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(40, 255, 255, 255)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(6, 20, 40)
    }
    private val coreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var sweepAngle = 0f
    private var pulseWave = 0f
    private var rxLevel = 0.08f
    private var txLevel = 0.05f
    private var targetRxLevel = 0.08f
    private var targetTxLevel = 0.05f
    private var vpnShield = false

    fun updateTelemetry(rxBps: Long, txBps: Long, vpnConnected: Boolean) {
        vpnShield = vpnConnected
        targetRxLevel = if (rxBps <= 0L) 0.06f else {
            (kotlin.math.log10((rxBps + 1).toDouble()).toFloat() / 6.5f).coerceIn(0.12f, 1f)
        }
        targetTxLevel = if (txBps <= 0L) 0.05f else {
            (kotlin.math.log10((txBps + 1).toDouble()).toFloat() / 6.5f).coerceIn(0.10f, 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) * 0.44f
        val innerR = maxR * 0.80f

        sweepAngle = (sweepAngle + 3.8f) % 360f
        pulseWave = (pulseWave + 0.035f) % 1f
        rxLevel += (targetRxLevel - rxLevel) * 0.18f
        txLevel += (targetTxLevel - txLevel) * 0.18f

        val accent = if (vpnShield) green else cyan

        // Radar scope background glow
        bgPaint.shader = RadialGradient(
            cx,
            cy,
            innerR,
            intArrayOf(
                Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.rgb(4, 15, 32)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, innerR, bgPaint)
        bgPaint.shader = null

        // Concentric radar rings & crosshairs
        for (factor in listOf(0.35f, 0.65f, 1f)) {
            canvas.drawCircle(cx, cy, innerR * factor, ringPaint)
        }
        canvas.drawLine(cx - innerR, cy, cx + innerR, cy, ringPaint)
        canvas.drawLine(cx, cy - innerR, cx, cy + innerR, ringPaint)

        // Outer compass ticks
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            val r1 = maxR * 0.84f
            val r2 = maxR * 0.90f
            canvas.drawLine(
                cx + (r1 * kotlin.math.cos(rad)).toFloat(),
                cy + (r1 * kotlin.math.sin(rad)).toFloat(),
                cx + (r2 * kotlin.math.cos(rad)).toFloat(),
                cy + (r2 * kotlin.math.sin(rad)).toFloat(),
                tickPaint
            )
        }

        // Rotating radar sweep beam
        val sweepMatrix = Matrix().apply {
            setRotate(sweepAngle, cx, cy)
        }
        val sweepShader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent))
            ),
            floatArrayOf(0f, 0.72f, 1f)
        )
        sweepShader.setLocalMatrix(sweepMatrix)
        sweepPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, innerR, sweepPaint)
        sweepPaint.shader = null

        // Expanding sonar pulse ring
        val waveRadius = innerR * pulseWave
        val waveAlpha = ((1f - pulseWave) * 165).toInt().coerceIn(0, 255)
        ringPaint.color = Color.argb(waveAlpha, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy, waveRadius, ringPaint)
        ringPaint.color = Color.argb(85, 0, 225, 255)

        // Blips on radar
        val blips = listOf(
            35.0 to 0.55f,
            155.0 to 0.72f,
            265.0 to 0.45f
        )
        for ((angleDeg, distFrac) in blips) {
            val diff = ((sweepAngle - angleDeg.toFloat()) + 360f) % 360f
            val blipAlpha = if (diff < 95f) ((1f - diff / 95f) * 235).toInt() else 35
            val rad = Math.toRadians(angleDeg)
            val bx = cx + (innerR * distFrac * kotlin.math.cos(rad)).toFloat()
            val by = cy + (innerR * distFrac * kotlin.math.sin(rad)).toFloat()
            blipPaint.color = Color.argb(blipAlpha, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(bx, by, 3.2f * density, blipPaint)
        }

        // Outer Download & Upload live activity gauge arcs
        val gaugeStroke = 3.2f * density
        arcBgPaint.strokeWidth = gaugeStroke
        arcPaint.strokeWidth = gaugeStroke

        val outerRect = RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        canvas.drawArc(outerRect, -165f, 150f, false, arcBgPaint)
        arcPaint.color = green
        canvas.drawArc(outerRect, -165f, 150f * rxLevel, false, arcPaint)

        canvas.drawArc(outerRect, 15f, 150f, false, arcBgPaint)
        arcPaint.color = cyan
        canvas.drawArc(outerRect, 15f, 150f * txLevel, false, arcPaint)

        // Center shield core badge
        val coreR = innerR * 0.34f
        canvas.drawCircle(cx, cy, coreR, corePaint)
        ringPaint.color = accent
        canvas.drawCircle(cx, cy, coreR, ringPaint)
        ringPaint.color = Color.argb(85, 0, 225, 255)

        coreTextPaint.color = accent
        coreTextPaint.textSize = 8.5f * density
        val coreLabel = if (vpnShield) "VPN" else "NET"
        val fm = coreTextPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(coreLabel, cx, textY, coreTextPaint)
    }
}
}