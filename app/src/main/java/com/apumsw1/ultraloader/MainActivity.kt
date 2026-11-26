package com.apumsw1.ultraloader
 
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val UPDATE_API =
            "https://ultra-loader.ultraloader.workers.dev/api/update"

        private const val FLASH_API =
            "https://ultra-loader.ultraloader.workers.dev/api/flash"

        private const val VPN_PACKAGE =
            "com.fast.free.unblock.thunder.vpn"

        private const val VPN_RETURN_LOGOUT_DELAY_MS = 3000L

        private const val PREFS = "ultra_loader_secure"
        private const val KEY_ALIAS = "UltraLoaderCredentialKey"

        private const val ACTION_OPEN_PORTAL =
            "com.apumsw1.ultraloader.OPEN_PORTAL"

        private const val ACTION_WIFI_PORTAL_DETECTED =
            "com.apumsw1.ultraloader.WIFI_PORTAL_DETECTED"

        private const val TELEGRAM_URL =
            "https://t.me/ultraloader"
    }

private enum class Stage {
    IDLE,
    LOGIN,
    CHECK_LOGIN,
    DETECT_LOGOUT,
    WAIT_VPN,
    LOGOUT,
    VERIFY_LOGOUT,
    WAIT_SECOND_LOGIN,
    CHECK_INTERNET,
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
private lateinit var activationStatus: TextView
private lateinit var userInput: EditText
private lateinit var passwordInput: EditText
private lateinit var notifAccessButton: Button
private lateinit var vpnAutoAccessButton: Button
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
private var portalUrl = ""
private var portalNetwork: Network? = null
private var preservedWifiNetwork: Network? = null
private var detectedLogoutControl: DetectedLogoutControl? = null
private var awaitingLogoutPageReload = false
private var dashboardShown = false
private var appInitialized = false
private var isActivityInForeground = false
private var vpnConnectedConfirmed = false
private var startupPortalChecked = false

private var stepNotificationUrlDetected = false
private var stepOriginalPortalOpened = false
private var stepLoginCompleted = false
private var stepLogoutControlDetected = false
private var stepVpnConnectedConfirmed = false
private var stepReturnedFromVpnToWebView = false
private var stepLogoutVerified = false
private var stepSecondSignInCompleted = false
private var googlePageLoadedInWebView = false
private var realInternetProbeVerified = false
private var vpnReturnDelayPending = false
private var webViewPageProgress = 0
private var pendingWebViewState: Bundle? = null

private var activeSessionUrl: String? = null
private var activeDetectionType: String? = null
private var completedSessionUrl: String? = null

private var secondLoginTimerStartedAt = 0L
private var secondLoginElapsedSeconds = 0L
private var secondLoginTimerRunning = false

private var autoLoginAttempts = 0
private var loginVerifyAttempts = 0
private var logoutDetectAttempts = 0
private var logoutSearchAttempts = 0
private var logoutVerifyAttempts = 0
private var loginActionSearchAttempts = 0
private var secondFormSearchAttempts = 0
private var secondLoginButtonFound = false

private var logoutDetectRunning = false
private var logoutSearchRunning = false
private var logoutVerifyRunning = false
private var loginActionSearchRunning = false
private var secondFormCheckRunning = false
private var netCheckRunning = false
private var netWorkingVerified = false
private var netCheckPageHasError = false
private var netCheckRefreshAttempt = 0

private val googleCheckUrl = "https://www.google.com/"

private val autoLoginRetryRunnable = Runnable { tryAutoLogin() }
private val checkLoginRunnable = Runnable { checkPortalLogin() }
private val detectLogoutRunnable = Runnable { detectLogoutControl() }
private val vpnReturnLogoutDelayRunnable = Runnable {
    vpnReturnDelayPending = false
    if (stage == Stage.LOGOUT && isActivityInForeground && !isFinishing && !isDestroyed) {
        if (!isVpnConnected()) {
            onVpnStateChanged(false)
        } else {
            stepReturnedFromVpnToWebView = true
            clickPortalLogout()
        }
    }
}
private val logoutRetryRunnable = Runnable { clickPortalLogout() }
private val verifyLogoutRunnable = Runnable { verifyPortalLogout() }
private val secondLoginAgentRunnable = Runnable { clickPortalLoginAgent() }
private val secondFormCheckRunnable = Runnable { checkSecondLoginForm() }
private val liveNetCheckRunnable = Runnable { startLiveNetWorkingCheck() }

private val netCheckRefreshRunnable = Runnable {
    loadNextNetCheckPage()
}

private val netCheckPageTimeoutRunnable = Runnable {
    if (
        stage == Stage.CHECK_INTERNET &&
        !googlePageLoadedInWebView &&
        !netWorkingVerified &&
        !isFinishing &&
        !isDestroyed
    ) {
        if (::webView.isInitialized) {
            webView.stopLoading()
        }
        loadNextNetCheckPage()
    }
}

private var activationPopup: AlertDialog? = null
private var flashDialog: AlertDialog? = null
private var vpnDialog: AlertDialog? = null
private var portalLinkDialog: AlertDialog? = null
private var flashPopupChecked = false

private var connectivityManager: ConnectivityManager? = null
private var networkCallbackRegistered = false
private var wifiCallbackRegistered = false
private var vpnCallbackRegistered = false
private var wifiPortalReceiverRegistered = false
private var lastAutoTriggerAt = 0L
private var autoLoginLinkClicks = 0
private var lastLicenseNetworkState = false
private var lastIpFetchAt = 0L
private var lastKnownVpnForIp = false

private val wifiPortalReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_WIFI_PORTAL_DETECTED) return
        val detectedUrl = intent.getStringExtra("detected_url")
        val detectionType = intent.getStringExtra("detection_type")
        val detectedAt = intent.getLongExtra("detected_at", System.currentTimeMillis())
        handleDetectedPortalLaunch(detectedUrl, detectionType, detectedAt)
    }
}

private val wifiNetworkCallback =
    object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = connectivityManager ?: return
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                handler.post {
                    preservedWifiNetwork = network
                    if (portalNetwork == null) {
                        portalNetwork = network
                    }
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                handler.post {
                    preservedWifiNetwork = network
                    if (portalNetwork == null) {
                        portalNetwork = network
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                val cm = connectivityManager ?: return@post
                val replacement = PortalNetworkHelper.findWifiNetwork(cm)
                if (replacement != null) {
                    preservedWifiNetwork = replacement
                    if (portalNetwork == network) {
                        portalNetwork = replacement
                    }
                } else {
                    preservedWifiNetwork = null
                    portalNetwork = null
                    activeSessionUrl = null
                    activeDetectionType = null
                    completedSessionUrl = null
                    detectedLogoutControl = null
                    clearPersistedLogoutControl()
                }
            }
        }
    }

private val vpnNetworkCallback =
    object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (isVpnConnected()) {
                    onVpnStateChanged(true)
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                handler.post {
                    onVpnStateChanged(true)
                }
            }
        }

        override fun onLost(network: Network) {
            handler.post {
                onVpnStateChanged(isVpnConnected())
            }
        }
    }

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

    if (savedInstanceState != null) {
        restoreWorkflowInstanceState(savedInstanceState)
    }

    window.decorView.post {
        if (isFinishing || isDestroyed) return@post
        if (pendingWebViewState != null || stage != Stage.IDLE) {
            ActivationManager.requireActivation(this) {
                initializeApplication()
            }
            checkForForcedUpdate(backgroundOnly = true)
        } else if (hasValidatedInternet()) {
            checkForForcedUpdate(backgroundOnly = false)
        } else {
            ActivationManager.requireActivation(this) {
                initializeApplication()
            }
            checkForForcedUpdate(backgroundOnly = true)
        }
    }
}

private fun restoreWorkflowInstanceState(savedInstanceState: Bundle) {
    pendingWebViewState = savedInstanceState.getBundle("webview_state")
    stage = try {
        Stage.valueOf(savedInstanceState.getString("stage", Stage.IDLE.name) ?: Stage.IDLE.name)
    } catch (_: Exception) {
        Stage.IDLE
    }
    portalUrl = savedInstanceState.getString("portal_url", "") ?: ""
    activeSessionUrl = savedInstanceState.getString("active_session_url", null)
    activeDetectionType = savedInstanceState.getString("active_detection_type", null)
    completedSessionUrl = savedInstanceState.getString("completed_session_url", null)
    vpnOpened = savedInstanceState.getBoolean("vpn_opened", false)
    loginSubmitted = savedInstanceState.getBoolean("login_submitted", false)
    vpnConnectedConfirmed = savedInstanceState.getBoolean("vpn_connected_confirmed", false)
    secondLoginButtonFound = savedInstanceState.getBoolean("second_login_button_found", false)
    stepNotificationUrlDetected = savedInstanceState.getBoolean("step_notif_url", false)
    stepOriginalPortalOpened = savedInstanceState.getBoolean("step_portal_opened", false)
    stepLoginCompleted = savedInstanceState.getBoolean("step_login_done", false)
    stepLogoutControlDetected = savedInstanceState.getBoolean("step_logout_detected", false)
    stepVpnConnectedConfirmed = savedInstanceState.getBoolean("step_vpn_confirmed", false)
    stepReturnedFromVpnToWebView = savedInstanceState.getBoolean("step_returned_from_vpn", false)
    stepLogoutVerified = savedInstanceState.getBoolean("step_logout_verified", false)
    stepSecondSignInCompleted = savedInstanceState.getBoolean("step_second_signin", false)
    googlePageLoadedInWebView = savedInstanceState.getBoolean("step_google_loaded", false)
    realInternetProbeVerified = savedInstanceState.getBoolean("step_real_net", false)
    netWorkingVerified = savedInstanceState.getBoolean("net_working_verified", false)
}

override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString("stage", stage.name)
    outState.putString("portal_url", portalUrl)
    outState.putString("active_session_url", activeSessionUrl)
    outState.putString("active_detection_type", activeDetectionType)
    outState.putString("completed_session_url", completedSessionUrl)
    outState.putBoolean("vpn_opened", vpnOpened)
    outState.putBoolean("login_submitted", loginSubmitted)
    outState.putBoolean("vpn_connected_confirmed", vpnConnectedConfirmed)
    outState.putBoolean("second_login_button_found", secondLoginButtonFound)
    outState.putBoolean("step_notif_url", stepNotificationUrlDetected)
    outState.putBoolean("step_portal_opened", stepOriginalPortalOpened)
    outState.putBoolean("step_login_done", stepLoginCompleted)
    outState.putBoolean("step_logout_detected", stepLogoutControlDetected)
    outState.putBoolean("step_vpn_confirmed", stepVpnConnectedConfirmed)
    outState.putBoolean("step_returned_from_vpn", stepReturnedFromVpnToWebView)
    outState.putBoolean("step_logout_verified", stepLogoutVerified)
    outState.putBoolean("step_second_signin", stepSecondSignInCompleted)
    outState.putBoolean("step_google_loaded", googlePageLoadedInWebView)
    outState.putBoolean("step_real_net", realInternetProbeVerified)
    outState.putBoolean("net_working_verified", netWorkingVerified)
    if (::webView.isInitialized) {
        val wvBundle = Bundle()
        webView.saveState(wvBundle)
        outState.putBundle("webview_state", wvBundle)
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

    pendingWebViewState?.let { savedWv ->
        webView.restoreState(savedWv)
        pendingWebViewState = null
    }

    registerLicenseNetworkCallback()
    registerWifiPortalReceiver()
    restorePersistedLogoutControl()

    handler.post(updater)
    handler.post(globeUpdater)

    requestNotificationPermission()
    startMonitorService()
    requestWifiNotificationAccess()

    NetworkStatusManager.captivePortalDetected.observe(this) { detected ->
        if (detected && stage == Stage.IDLE) {
            // Portal detected via LiveData
        }
    }
    NetworkStatusManager.vpnConnected.observe(this) { vpnActive ->
        if (vpnActive) {
            updateInternetStatus()
        }
    }

    val incomingUrl = extractIncomingPortalUrl(intent)
    if (!incomingUrl.isNullOrBlank()) {
        saveCapturedUrlForCurrentNetwork(incomingUrl)
        WifiNotificationListener.markSessionCaptured(
            this,
            incomingUrl,
            WifiNotificationListener.TYPE_VIEW_WEBSITE
        )
        intent?.action = null
        intent?.data = null
        handler.post {
            handleDetectedPortalLaunch(
                incomingUrl,
                WifiNotificationListener.TYPE_VIEW_WEBSITE,
                System.currentTimeMillis(),
                forceIfIdleOrFinished = true
            )
        }
    } else if (intent?.action == ACTION_OPEN_PORTAL) {
        val detectedUrl = intent?.getStringExtra("detected_url")
        val detectionType = intent?.getStringExtra("detection_type")
        val detectedAt = intent?.getLongExtra("detected_at", System.currentTimeMillis()) ?: System.currentTimeMillis()
        intent?.action = null
        handler.post {
            handleDetectedPortalLaunch(detectedUrl, detectionType, detectedAt, forceIfIdleOrFinished = true)
        }
    } else if (stage == Stage.IDLE) {
        checkExistingWifiPortalOnStartup()
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
    val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
        connectivityManager = it
    }

    if (!networkCallbackRegistered) {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, licenseNetworkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
            networkCallbackRegistered = false
        }
    }

    if (!wifiCallbackRegistered) {
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(wifiRequest, wifiNetworkCallback)
            wifiCallbackRegistered = true
            preservedWifiNetwork = PortalNetworkHelper.findWifiNetwork(cm)
        } catch (_: Exception) {
            wifiCallbackRegistered = false
        }
    }

    if (!vpnCallbackRegistered) {
        try {
            val vpnRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(vpnRequest, vpnNetworkCallback)
            vpnCallbackRegistered = true
            vpnConnectedConfirmed = PortalNetworkHelper.isVpnConnected(cm)
        } catch (_: Exception) {
            vpnCallbackRegistered = false
        }
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
                    if (!netWorkingVerified) {
                        workflowStatus("LICENSE VERIFIED")
                    }
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
        text = "● WAIT TO CONNECT"
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(cyan)
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
            startPortalProcess(userInitiated = true)
        }.apply {
            textSize = 15f
            background = rounded(Color.rgb(0, 90, 115), cyan)
        }
    )

    notifAccessButton = makeButton("⚠ ENABLE WIFI NOTIFICATION ACCESS") {
        try {
            startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            )
        } catch (_: Exception) {
            toast("Unable to open Notification Access settings")
        }
    }.apply {
        textSize = 11.5f
        background = rounded(Color.rgb(120, 35, 35), red)
        visibility = if (isNotificationAccessEnabled()) View.GONE else View.VISIBLE
    }
    loginPanel.addView(notifAccessButton)

    vpnAutoAccessButton = makeButton("⚡ ENABLE AUTO VPN CONNECT (ACCESSIBILITY)") {
        openVpnAutoConnectSettings()
    }.apply {
        textSize = 11.5f
        background = rounded(Color.rgb(0, 72, 102), cyan)
        visibility = if (isVpnAutoConnectEnabled()) View.GONE else View.VISIBLE
    }
    loginPanel.addView(vpnAutoAccessButton)

    webTitle = TextView(this).apply {
        text = "📈 LIVE INTERNET USAGE GRAPH"
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(cyan)
        setPadding(5, 12, 5, 7)
    }

    webView = WebView(this).apply {
        alpha = 1f
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)

        settings.userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
            .replace("; wv", "")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                webViewPageProgress = newProgress
            }
        }

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
                webViewPageProgress = 0
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
                    if (stage == Stage.CHECK_INTERNET && !googlePageLoadedInWebView && !netWorkingVerified) {
                        handler.removeCallbacks(netCheckPageTimeoutRunnable)
                        scheduleNextNetCheckRefresh(1500L)
                    } else if (stage == Stage.LOGOUT || stage == Stage.VERIFY_LOGOUT) {
                        scheduleLogoutRetryAfterNetworkError()
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
                    if (stage == Stage.CHECK_INTERNET && !googlePageLoadedInWebView && !netWorkingVerified) {
                        handler.removeCallbacks(netCheckPageTimeoutRunnable)
                        scheduleNextNetCheckRefresh(1500L)
                    } else if (stage == Stage.LOGOUT || stage == Stage.VERIFY_LOGOUT) {
                        scheduleLogoutRetryAfterNetworkError()
                    }
                }
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                webViewPageProgress = 100
                if (
                    !url.isNullOrBlank() &&
                    !url.startsWith("about:", ignoreCase = true) &&
                    !url.startsWith("chrome-error:", ignoreCase = true) &&
                    stage != Stage.CHECK_INTERNET &&
                    stage != Stage.IDLE
                ) {
                    stepOriginalPortalOpened = true
                }
                when (stage) {
                    Stage.LOGIN -> {
                        handler.removeCallbacks(autoLoginRetryRunnable)
                        tryAutoLogin()
                    }
                    Stage.CHECK_LOGIN -> {
                        handler.removeCallbacks(checkLoginRunnable)
                        checkPortalLogin()
                    }
                    Stage.DETECT_LOGOUT -> {
                        handler.removeCallbacks(detectLogoutRunnable)
                        detectLogoutControl()
                    }
                    Stage.LOGOUT -> {
                        if (awaitingLogoutPageReload) {
                            awaitingLogoutPageReload = false
                            handler.removeCallbacks(logoutRetryRunnable)
                            clickPortalLogout()
                        } else if (secondLoginTimerRunning) {
                            handler.removeCallbacks(secondLoginAgentRunnable)
                            clickPortalLoginAgent()
                        }
                    }
                    Stage.VERIFY_LOGOUT -> {
                        handler.removeCallbacks(verifyLogoutRunnable)
                        verifyPortalLogout()
                    }
                    Stage.WAIT_SECOND_LOGIN -> {
                        handler.removeCallbacks(secondFormCheckRunnable)
                        checkSecondLoginForm()
                    }
                    Stage.CHECK_INTERNET -> verifyCheckInternetPageLoaded(url)
                    else -> {
                        if (stage == Stage.IDLE && !dashboardShown && !netWorkingVerified) {
                            workflowStatus("WAIT TO CONNECT")
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

    val webViewPanel = createPanel()
    webViewPanel.addView(panelHeading("🌐 LIVE PORTAL & AUTOMATION BROWSER"))
    webView.layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        (260 * density).toInt()
    )
    (webView.parent as? ViewGroup)?.removeView(webView)
    webViewPanel.addView(webView)
    content.addView(webViewPanel, matchWrap())

    content.addView(trafficPanel, matchWrap())

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
    root.isClickable = true
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

private fun isNotificationAccessEnabled(): Boolean {
    return try {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        enabled.contains(packageName)
    } catch (_: Exception) {
        false
    }
}

private fun updateNotificationAccessButton() {
    if (!::notifAccessButton.isInitialized) return
    notifAccessButton.visibility =
        if (isNotificationAccessEnabled()) View.GONE else View.VISIBLE
}

private fun isVpnAutoConnectEnabled(): Boolean {
    return VpnAutoConnectService.isEnabled(this)
}

private fun updateVpnAutoAccessButton() {
    if (!::vpnAutoAccessButton.isInitialized) return
    vpnAutoAccessButton.visibility =
        if (isVpnAutoConnectEnabled()) View.GONE else View.VISIBLE
}

private fun openVpnAutoConnectSettings() {
    AlertDialog.Builder(this)
        .setTitle("Enable Auto Thunder VPN Connect")
        .setMessage(
            "Enable 'Ultra Loader Auto VPN Connect' in Accessibility Settings so Ultra Loader " +
                "can automatically tap Connect in Thunder VPN and return to Ultra Loader for portal logout."
        )
        .setPositiveButton("OPEN SETTINGS") { _, _ ->
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            } catch (_: Exception) {
                toast("Unable to open Accessibility settings")
            }
        }
        .setNegativeButton("LATER", null)
        .show()
}

private fun requestWifiNotificationAccess() {
    if (isNotificationAccessEnabled()) return

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

private fun showCredentialDialog(resumeLoginAfterSave: Boolean = false) {
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
                    if (resumeLoginAfterSave && stage == Stage.LOGIN) {
                        tryAutoLogin()
                    } else {
                        startPortalProcess(userInitiated = true)
                    }
                } else {
                    toast("Unable to save credentials")
                }
            } else {
                toast("Both fields are required")
            }
        }
        .setNegativeButton("CANCEL") { _, _ ->
            if (resumeLoginAfterSave && stage == Stage.LOGIN) {
                stage = Stage.IDLE
                statusText("● LOGIN CREDENTIALS REQUIRED", red)
            }
        }
        .show()
}

private fun cancelWorkflowCallbacks() {
    vpnReturnDelayPending = false
    handler.removeCallbacks(autoLoginRetryRunnable)
    handler.removeCallbacks(checkLoginRunnable)
    handler.removeCallbacks(detectLogoutRunnable)
    handler.removeCallbacks(vpnReturnLogoutDelayRunnable)
    handler.removeCallbacks(logoutRetryRunnable)
    handler.removeCallbacks(verifyLogoutRunnable)
    handler.removeCallbacks(secondLoginAgentRunnable)
    handler.removeCallbacks(secondFormCheckRunnable)
    handler.removeCallbacks(liveNetCheckRunnable)
    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.removeCallbacks(netCheckPageTimeoutRunnable)
}

private fun persistDetectedLogoutControl(control: DetectedLogoutControl?) {
    try {
        val prefs = getSharedPreferences(WifiNotificationListener.PREFS, Context.MODE_PRIVATE)
        if (control == null) {
            prefs.edit().remove("detected_logout_json").apply()
        } else {
            prefs.edit().putString("detected_logout_json", control.toJson()).apply()
        }
    } catch (_: Exception) {
    }
}

private fun restorePersistedLogoutControl() {
    try {
        val prefs = getSharedPreferences(WifiNotificationListener.PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("detected_logout_json", null)
        if (detectedLogoutControl == null) {
            detectedLogoutControl = DetectedLogoutControl.fromJson(raw)
        }
        if (activeSessionUrl.isNullOrBlank()) {
            val savedSession = WifiNotificationListener.getSessionCapturedPortal(this)
            if (savedSession != null) {
                activeSessionUrl = savedSession.first
                activeDetectionType = savedSession.second
                if (portalUrl.isBlank()) {
                    portalUrl = savedSession.first
                }
            }
        }
    } catch (_: Exception) {
    }
}

private fun clearPersistedLogoutControl() {
    persistDetectedLogoutControl(null)
}

private fun checkExistingWifiPortalOnStartup() {
    if (startupPortalChecked || !appInitialized) return
    startupPortalChecked = true

    executor.execute {
        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }

        val wifi = PortalNetworkHelper.findWifiNetwork(cm) ?: return@execute
        preservedWifiNetwork = wifi
        portalNetwork = wifi

        // Scan any active Wi-Fi notifications already present before Ultra Loader launched.
        WifiNotificationListener.scanActiveNotificationsNow(notifyService = false)

        val sessionCaptured = WifiNotificationListener.getSessionCapturedPortal(this)
        val capPortalUrl = PortalNetworkHelper.getCaptivePortalUrl(cm, wifi, preferVenue = false)
        val capVenueUrl = PortalNetworkHelper.getCaptivePortalUrl(cm, wifi, preferVenue = true)

        val resolvedUrl = sessionCaptured?.first
            ?: capVenueUrl
            ?: capPortalUrl

        val resolvedType = sessionCaptured?.second
            ?: if (!capVenueUrl.isNullOrBlank()) {
                WifiNotificationListener.TYPE_VIEW_WEBSITE
            } else {
                WifiNotificationListener.TYPE_PORTAL_LOGIN
            }

        if (!resolvedUrl.isNullOrBlank() && PortalNetworkHelper.isValidHttpUrl(resolvedUrl)) {
            WifiNotificationListener.markSessionCaptured(this, resolvedUrl, resolvedType)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && stage == Stage.IDLE) {
                    handleDetectedPortalLaunch(
                        resolvedUrl,
                        resolvedType,
                        System.currentTimeMillis(),
                        forceIfIdleOrFinished = false
                    )
                }
            }
        }
    }
}

private fun startPortalProcess(userInitiated: Boolean = false) {
    if (!checkActivationBeforeAction()) return

    if (userInitiated) {
        completedSessionUrl = null
    }

    cancelWorkflowCallbacks()
    VpnAutomationBridge.disarm()
    PortalMonitorService.cancelVpnReturnNotification(this)

    dashboardShown = false
    stage = Stage.LOGIN
    loginSubmitted = false
    vpnOpened = false
    vpnDialogShown = false
    awaitingLogoutPageReload = false
    detectedLogoutControl = null
    clearPersistedLogoutControl()

    stepNotificationUrlDetected = false
    stepOriginalPortalOpened = false
    stepLoginCompleted = false
    stepLogoutControlDetected = false
    stepVpnConnectedConfirmed = false
    stepReturnedFromVpnToWebView = false
    stepLogoutVerified = false
    stepSecondSignInCompleted = false
    googlePageLoadedInWebView = false
    realInternetProbeVerified = false
    vpnReturnDelayPending = false
    webViewPageProgress = 0

    vpnDialog?.dismiss()
    vpnDialog = null
    portalLinkDialog?.dismiss()
    portalLinkDialog = null

    stopSecondLoginTimer()
    secondLoginElapsedSeconds = 0L

    secondLoginTimerStatus.text = "SECOND LOGIN TIMER: NOT STARTED"
    secondLoginStageStatus.text = "LOGIN STAGE: WAITING"

    autoLoginAttempts = 0
    loginVerifyAttempts = 0
    logoutDetectAttempts = 0
    logoutSearchAttempts = 0
    logoutVerifyAttempts = 0
    loginActionSearchAttempts = 0
    secondFormSearchAttempts = 0
    autoLoginLinkClicks = 0
    secondLoginButtonFound = false

    logoutDetectRunning = false
    logoutSearchRunning = false
    logoutVerifyRunning = false
    loginActionSearchRunning = false
    secondFormCheckRunning = false
    netCheckRunning = false
    netWorkingVerified = false
    netCheckPageHasError = false
    netCheckRefreshAttempt = 0
    if (::webView.isInitialized) {
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
    }

    try {
        getSharedPreferences(WifiNotificationListener.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pending_auto_start", false)
            .apply()
    } catch (_: Exception) {
    }

    workflowStatus("FINDING CURRENT PORTAL...")

    discoverPortalUrl()
}  

private fun getWifiGatewayIp(manager: ConnectivityManager, network: Network?): String? {
    return PortalNetworkHelper.getWifiGatewayIp(this, manager, network)
}

private fun discoverPortalUrl() {
    executor.execute {
        val manager = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }

        val wifi = PortalNetworkHelper.findWifiNetwork(manager)
        val network = wifi ?: manager.activeNetwork
        if (wifi != null) {
            preservedWifiNetwork = wifi
        }
        portalNetwork = wifi ?: network

        val prefs = getSharedPreferences(
            WifiNotificationListener.PREFS,
            Context.MODE_PRIVATE
        )

        var found: String? = null

        // 1. Use ONLY the exact URL captured from the active Wi-Fi notification session.
        val activeUrl = activeSessionUrl
        if (PortalNetworkHelper.isValidHttpUrl(activeUrl)) {
            found = activeUrl
        }

        if (found == null) {
            val sessionPortal = WifiNotificationListener.getSessionCapturedPortal(this)
            if (sessionPortal != null && PortalNetworkHelper.isValidHttpUrl(sessionPortal.first)) {
                found = sessionPortal.first
                activeDetectionType = sessionPortal.second
            }
        }

        if (found == null) {
            WifiNotificationListener.scanActiveNotificationsNow(notifyService = false)
            val afterScan = WifiNotificationListener.getSessionCapturedPortal(this)
            if (afterScan != null && PortalNetworkHelper.isValidHttpUrl(afterScan.first)) {
                found = afterScan.first
                activeDetectionType = afterScan.second
            }
        }

        // 2. Check OS CaptivePortalData for this Wi-Fi network (Android 11+ Detection A & Detection B).
        if (found == null && network != null) {
            val pendingType = activeDetectionType ?: prefs.getString("pending_detection_type", null)
            val preferVenue = pendingType == WifiNotificationListener.TYPE_VIEW_WEBSITE
            val capUrl = PortalNetworkHelper.getCaptivePortalUrl(manager, network, preferVenue)
            if (PortalNetworkHelper.isValidHttpUrl(capUrl)) {
                found = capUrl
            }
        }

        // 3. Fallback: Router Gateway IP URL if no notification or capport data found yet
        if (found == null && network != null) {
            val gwIp = PortalNetworkHelper.getWifiGatewayIp(this, manager, network)
            if (!gwIp.isNullOrBlank()) {
                found = "http://$gwIp/"
                activeDetectionType = WifiNotificationListener.TYPE_PORTAL_LOGIN
            }
        }

        if (!found.isNullOrBlank() && PortalNetworkHelper.isValidHttpUrl(found)) {
            activeSessionUrl = found
            val detType = activeDetectionType
                ?: prefs.getString("pending_detection_type", null)
                ?: WifiNotificationListener.TYPE_PORTAL_LOGIN
            WifiNotificationListener.markSessionCaptured(this, found, detType)
            saveCapturedUrlForCurrentNetwork(found)
        }

        runOnUiThread {
            if (isFinishing || isDestroyed || stage != Stage.LOGIN) return@runOnUiThread

            if (!ActivationManager.hasAccess(this)) {
                statusText("● ACTIVATION EXPIRED", red)
                updateActivationStatus()
                return@runOnUiThread
            }

            if (found.isNullOrBlank()) {
                updateNotificationAccessButton()
                if (!isNotificationAccessEnabled()) {
                    stage = Stage.IDLE
                    statusText("● ENABLE WIFI NOTIFICATION ACCESS", red)
                } else {
                    workflowStatus("WAITING FOR PORTAL LINK...")
                    handler.postDelayed({
                        if (stage == Stage.LOGIN && !isFinishing && !isDestroyed) {
                            discoverPortalUrl()
                        }
                    }, 2000)
                }
                return@runOnUiThread
            }

            val boundNet = PortalNetworkHelper.bindToWifiNetwork(manager, preservedWifiNetwork ?: portalNetwork)
            if (boundNet != null) {
                preservedWifiNetwork = boundNet
                portalNetwork = boundNet
            }

            stepNotificationUrlDetected = true
            stepOriginalPortalOpened = false
            webViewPageProgress = 0
            portalUrl = found

            workflowStatus("ORIGINAL PORTAL URL DETECTED")

            webView.loadUrl(portalUrl)
        }
    }
}

private fun tryAutoLogin() {
    if (stage != Stage.LOGIN || loginSubmitted || isFinishing || isDestroyed) return
    if (!ActivationManager.hasAccess(this)) return

    handler.removeCallbacks(autoLoginRetryRunnable)
    autoLoginAttempts++

    val credentials = readCredentials()
    val hasCredentials = credentials != null
    val user = if (credentials != null) {
        Base64.encodeToString(
            credentials.first.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
    } else {
        ""
    }
    val pass = if (credentials != null) {
        Base64.encodeToString(
            credentials.second.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
    } else {
        ""
    }

    val script = PortalJsScripts.buildAutoLoginScript(
        encodedUser = user,
        encodedPass = pass,
        hasCredentials = hasCredentials,
        allowClickNav = autoLoginLinkClicks < 3
    )

    webView.evaluateJavascript(script) { result ->
        if (stage != Stage.LOGIN || loginSubmitted || isFinishing || isDestroyed) {
            return@evaluateJavascript
        }

        when {
            result?.contains("PAGE_LOADING") == true -> {
                if (autoLoginAttempts < 25) {
                    autoLoginAttempts = (autoLoginAttempts - 1).coerceAtLeast(0)
                    workflowStatus("PORTAL PAGE LOADING...")
                    handler.postDelayed(autoLoginRetryRunnable, 250)
                } else {
                    stage = Stage.IDLE
                    workflowStatus("PORTAL LOAD TIMEOUT")
                }
            }

            result?.contains("LOGIN_BUTTON_DISABLED") == true -> {
                if (autoLoginAttempts < 12) {
                    workflowStatus("WAITING FOR LOGIN BUTTON ENABLE...")
                    handler.postDelayed(autoLoginRetryRunnable, 350)
                } else {
                    stage = Stage.IDLE
                    workflowStatus("LOGIN BUTTON DISABLED")
                }
            }

            result?.contains("SUBMITTED") == true -> {
                loginSubmitted = true
                stage = Stage.CHECK_LOGIN
                loginVerifyAttempts = 0
                workflowStatus("LOGIN SUBMITTED — VERIFYING...")
                handler.removeCallbacks(checkLoginRunnable)
                handler.postDelayed(checkLoginRunnable, 600)
            }

            result?.contains("ALREADY_LOGGED_IN") == true -> {
                loginSubmitted = true
                stepLoginCompleted = true
                stage = Stage.DETECT_LOGOUT
                logoutDetectAttempts = 0
                logoutDetectRunning = false
                workflowStatus("PORTAL LOGGED IN — INSPECTING LOGOUT...")
                detectLogoutControl()
            }

            result?.contains("CREDENTIALS_REQUIRED") == true -> {
                workflowStatus("PORTAL CREDENTIALS REQUIRED")
                showCredentialDialog(resumeLoginAfterSave = true)
            }

            result?.contains("NAVIGATED") == true -> {
                autoLoginLinkClicks++
                workflowStatus("OPENING PORTAL LOGIN...")
                handler.postDelayed(autoLoginRetryRunnable, 800)
            }

            result?.contains("FORM_NOT_FOUND") == true ||
            result?.contains("NO_SUBMIT_BUTTON") == true -> {
                if (autoLoginAttempts < 6) {
                    workflowStatus("INSPECTING PORTAL PAGE... $autoLoginAttempts/6")
                    handler.postDelayed(autoLoginRetryRunnable, 450)
                } else {
                    stage = Stage.DETECT_LOGOUT
                    logoutDetectAttempts = 0
                    logoutDetectRunning = false
                    detectLogoutControl()
                }
            }

            else -> {
                if (autoLoginAttempts < 10) {
                    workflowStatus("CHECKING PORTAL FORM...")
                    handler.postDelayed(autoLoginRetryRunnable, 450)
                } else {
                    stage = Stage.IDLE
                    statusText("● PORTAL LOGIN FORM NOT FOUND", red)
                }
            }
        }
    }
}

private fun checkPortalLogin() {
    if (stage != Stage.CHECK_LOGIN || isFinishing || isDestroyed) return

    handler.removeCallbacks(checkLoginRunnable)
    loginVerifyAttempts++

    val script = PortalJsScripts.buildCheckLoginScript()

    webView.evaluateJavascript(script) { result ->
        if (stage != Stage.CHECK_LOGIN || isFinishing || isDestroyed) {
            return@evaluateJavascript
        }

        when {
            result?.contains("LOGIN_OK_") == true -> {
                stepLoginCompleted = true
                workflowStatus("PORTAL LOGIN VERIFIED — DETECTING LOGOUT")
                stage = Stage.DETECT_LOGOUT
                logoutDetectAttempts = 0
                logoutDetectRunning = false
                detectLogoutControl()
            }

            result?.contains("LOGIN_FORM_STILL_HERE") == true -> {
                if (loginVerifyAttempts < 15) {
                    workflowStatus("WAITING FOR LOGIN CONFIRMATION...")
                    handler.postDelayed(checkLoginRunnable, 700)
                } else {
                    stage = Stage.IDLE
                    statusText("● LOGIN NOT CONFIRMED — CHECK DETAILS", red)
                }
            }

            else -> {
                if (loginVerifyAttempts < 12) {
                    handler.postDelayed(checkLoginRunnable, 600)
                } else {
                    stage = Stage.DETECT_LOGOUT
                    logoutDetectAttempts = 0
                    logoutDetectRunning = false
                    detectLogoutControl()
                }
            }
        }
    }
}

private fun parseJsJsonObject(raw: String?): JSONObject? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"")) {
            val unescaped = org.json.JSONTokener(trimmed).nextValue() as? String ?: return null
            JSONObject(unescaped)
        } else {
            JSONObject(trimmed)
        }
    } catch (_: Exception) {
        null
    }
}

private fun detectLogoutControl() {
    if (stage != Stage.DETECT_LOGOUT || logoutDetectRunning || isFinishing || isDestroyed) return

    handler.removeCallbacks(detectLogoutRunnable)
    logoutDetectRunning = true
    logoutDetectAttempts++

    workflowStatus("DETECTING PORTAL LOGOUT... $logoutDetectAttempts/60")

    val script = PortalJsScripts.buildDetectLogoutScript()

    webView.evaluateJavascript(script) { result ->
        logoutDetectRunning = false
        if (stage != Stage.DETECT_LOGOUT || isFinishing || isDestroyed) {
            return@evaluateJavascript
        }

        val json = parseJsJsonObject(result)
        val found = json?.optBoolean("found", false) == true
        val reason = json?.optString("reason", "")
        val controlObj = json?.optJSONObject("control")

        if (found && controlObj != null) {
            val control = DetectedLogoutControl(
                pageUrl = controlObj.optString("pageUrl", portalUrl),
                tagName = controlObj.optString("tagName", ""),
                id = controlObj.optString("id", ""),
                name = controlObj.optString("name", ""),
                href = controlObj.optString("href", ""),
                text = controlObj.optString("text", ""),
                title = controlObj.optString("title", ""),
                ariaLabel = controlObj.optString("ariaLabel", ""),
                onclick = controlObj.optString("onclick", ""),
                selector = controlObj.optString("selector", ""),
                detectedAt = System.currentTimeMillis()
            )
            stepLoginCompleted = true
            stepLogoutControlDetected = true
            detectedLogoutControl = control
            persistDetectedLogoutControl(control)

            // Do NOT click Logout yet. Release temporary process binding so Thunder VPN
            // can establish cleanly while keeping preservedWifiNetwork for the Logout step.
            connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }

            openThunderVpn()
        } else if (reason == "PAGE_LOADING" && logoutDetectAttempts < 60) {
            handler.postDelayed(detectLogoutRunnable, 300)
        } else if (logoutDetectAttempts < 60) {
            handler.postDelayed(detectLogoutRunnable, 900)
        } else {
            detectedLogoutControl = null
            clearPersistedLogoutControl()
            connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
            stage = Stage.IDLE
            statusText("● NO PORTAL LOGOUT CONTROL FOUND", red)
        }
    }
}

private fun openThunderVpn() {
    if (!checkActivationBeforeAction()) return

    val cm = connectivityManager
        ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
            connectivityManager = it
        }
    PortalNetworkHelper.clearProcessNetworkBinding(cm)

    if (detectedLogoutControl == null) {
        statusText("● LOGOUT CONTROL NOT DETECTED", red)
        return
    }

    stage = Stage.WAIT_VPN
    vpnConnectedConfirmed = isVpnConnected()

    if (vpnConnectedConfirmed && isActivityInForeground) {
        proceedToLogoutAfterVpnConfirmed()
        return
    }

    if (vpnOpened) {
        VpnAutomationBridge.arm(60_000L)
        workflowStatus("WAITING FOR THUNDER VPN...")
        checkVpnStatus()
        return
    }

    vpnOpened = true
    VpnAutomationBridge.arm(60_000L)

    val launch = packageManager.getLaunchIntentForPackage(VPN_PACKAGE)

    if (launch == null) {
        VpnAutomationBridge.disarm()
        statusText("● THUNDER VPN NOT INSTALLED", red)
        showVpnDialog()
        return
    }

    try {
        workflowStatus("OPENING THUNDER VPN...")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    } catch (_: Exception) {
        VpnAutomationBridge.disarm()
        statusText("● VPN OPEN FAILED", red)
        showVpnDialog()
    }
}

private fun isVpnConnected(): Boolean {
    val manager = connectivityManager
        ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
            connectivityManager = it
        }
    return PortalNetworkHelper.isVpnConnected(manager)
}

private fun onVpnStateChanged(connected: Boolean) {
    vpnConnectedConfirmed = connected

    if (::ipDetails.isInitialized) {
        updateVpnLine(connected)
    }

    if (connected) {
        try {
            vpnDialog?.dismiss()
            vpnDialog = null
            vpnDialogShown = false
        } catch (_: Exception) {
        }

        if (stage == Stage.WAIT_VPN) {
            if (isActivityInForeground) {
                VpnAutomationBridge.disarm()
                PortalMonitorService.cancelVpnReturnNotification(this)
                proceedToLogoutAfterVpnConfirmed()
            } else {
                VpnAutoConnectService.requestReturnToUltraLoader(this)
            }
        } else if (stage == Stage.IDLE || stage == Stage.FINISHED) {
            updateInternetStatus()
            refreshIpLocationIfNeeded()
        }
    } else {
        if (stage == Stage.WAIT_VPN ||
            (stage == Stage.LOGOUT && !secondLoginTimerRunning) ||
            stage == Stage.VERIFY_LOGOUT
        ) {
            VpnAutomationBridge.disarm()
            vpnReturnDelayPending = false
            handler.removeCallbacks(vpnReturnLogoutDelayRunnable)
            handler.removeCallbacks(logoutRetryRunnable)
            handler.removeCallbacks(verifyLogoutRunnable)
            logoutSearchRunning = false
            logoutVerifyRunning = false
            awaitingLogoutPageReload = false
            connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
            stage = Stage.WAIT_VPN
            workflowStatus("VPN DISCONNECTED — RECONNECT THUNDER VPN")
            if (isActivityInForeground) {
                showVpnDialog()
            }
        } else {
            updateInternetStatus()
        }
    }
}

private fun proceedToLogoutAfterVpnConfirmed() {
    if (stage != Stage.WAIT_VPN || !isActivityInForeground || isFinishing || isDestroyed) return
    if (!isVpnConnected()) return
    if (vpnReturnDelayPending) return

    stepVpnConnectedConfirmed = true
    VpnAutomationBridge.disarm()
    PortalMonitorService.cancelVpnReturnNotification(this)

    try {
        vpnDialog?.dismiss()
        vpnDialog = null
        vpnDialogShown = false
    } catch (_: Exception) {
    }

    stage = Stage.LOGOUT
    logoutSearchAttempts = 0
    logoutSearchRunning = false
    awaitingLogoutPageReload = false
    vpnReturnDelayPending = true
    updateInternetStatus()

    // Mandatory exact 3-second delay after returning to Ultra Loader's existing WebView.
    workflowStatus("VPN CONNECTED — WAITING 3S FOR PORTAL LOGOUT")
    handler.removeCallbacks(vpnReturnLogoutDelayRunnable)
    handler.postDelayed(vpnReturnLogoutDelayRunnable, VPN_RETURN_LOGOUT_DELAY_MS)
}

private fun checkVpnStatus() {
    val connected = isVpnConnected()
    vpnConnectedConfirmed = connected

    if (::ipDetails.isInitialized) {
        updateVpnLine(connected)
    }

    if (connected) {
        try {
            vpnDialog?.dismiss()
            vpnDialog = null
            vpnDialogShown = false
        } catch (_: Exception) {
        }

        if (stage == Stage.WAIT_VPN && isActivityInForeground) {
            proceedToLogoutAfterVpnConfirmed()
        } else {
            updateInternetStatus()
            refreshIpLocationIfNeeded()
        }
    } else if (stage == Stage.WAIT_VPN) {
        workflowStatus("WAITING FOR THUNDER VPN CONNECTION")
        if (isActivityInForeground) {
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
    if (vpnDialogShown || !isActivityInForeground || isFinishing || isDestroyed) return

    vpnDialogShown = true
    val autoEnabled = isVpnAutoConnectEnabled()

    val builder = AlertDialog.Builder(this)
        .setTitle("THUNDER VPN")
        .setMessage(
            if (autoEnabled) {
                "Waiting for Thunder VPN connection. Ultra Loader will continue automatically once connected."
            } else {
                "Connect Thunder VPN so Ultra Loader can continue Portal Logout automatically."
            }
        )
        .setPositiveButton("CHECK / CONTINUE") { _, _ ->
            vpnDialog = null
            vpnDialogShown = false
            if (isVpnConnected()) {
                if (stage == Stage.WAIT_VPN && isActivityInForeground) {
                    proceedToLogoutAfterVpnConfirmed()
                }
            } else {
                toast("Connect Thunder VPN first")
                showVpnDialog()
            }
        }
        .setNeutralButton("OPEN VPN") { _, _ ->
            vpnDialog = null
            vpnDialogShown = false
            vpnOpened = false
            openThunderVpn()
        }

    if (!autoEnabled) {
        builder.setNegativeButton("ENABLE AUTO VPN") { _, _ ->
            vpnDialog = null
            vpnDialogShown = false
            openVpnAutoConnectSettings()
        }
    } else {
        builder.setNegativeButton("LATER") { _, _ ->
            vpnDialog = null
            vpnDialogShown = false
        }
    }

    val dialog = builder.create()
    vpnDialog = dialog
    dialog.show()
}

private fun scheduleLogoutRetryAfterNetworkError() {
    if ((stage != Stage.LOGOUT && stage != Stage.VERIFY_LOGOUT) || secondLoginTimerRunning) return
    if (!isVpnConnected()) {
        onVpnStateChanged(false)
        return
    }
    handler.removeCallbacks(verifyLogoutRunnable)
    handler.removeCallbacks(logoutRetryRunnable)
    logoutVerifyRunning = false
    logoutSearchRunning = false
    stage = Stage.LOGOUT
    if (logoutSearchAttempts < 6) {
        awaitingLogoutPageReload = true
        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }
        val boundWifi = PortalNetworkHelper.bindToWifiNetwork(cm, preservedWifiNetwork ?: portalNetwork)
        if (boundWifi != null) {
            preservedWifiNetwork = boundWifi
            portalNetwork = boundWifi
        }
        val targetPage = detectedLogoutControl?.pageUrl?.takeIf { PortalNetworkHelper.isValidHttpUrl(it) }
            ?: portalUrl
        if (::webView.isInitialized && PortalNetworkHelper.isValidHttpUrl(targetPage)) {
            webView.loadUrl(targetPage)
        } else {
            handler.postDelayed(logoutRetryRunnable, 1000)
        }
    } else {
        connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
        stage = Stage.IDLE
        statusText("● PORTAL LOGOUT FAILED OVER WIFI", red)
    }
}

private fun clickPortalLogout() {
    if (stage != Stage.LOGOUT || secondLoginTimerRunning || logoutSearchRunning || isFinishing || isDestroyed) return

    if (!isVpnConnected()) {
        connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
        stage = Stage.WAIT_VPN
        statusText("● VPN DISCONNECTED — RECONNECT THUNDER VPN", red)
        if (isActivityInForeground) showVpnDialog()
        return
    }

    if (logoutSearchAttempts >= 6) {
        connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
        stage = Stage.IDLE
        statusText("● PORTAL LOGOUT BUTTON NOT FOUND", red)
        return
    }

    handler.removeCallbacks(logoutRetryRunnable)
    logoutSearchRunning = true
    logoutSearchAttempts++

    workflowStatus("CLICKING PORTAL LOGOUT... $logoutSearchAttempts/6")

    val cm = connectivityManager
        ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
            connectivityManager = it
        }

    // Route portal Logout directly over the preserved Wi-Fi network, not the VPN tunnel.
    val boundWifi = PortalNetworkHelper.bindToWifiNetwork(cm, preservedWifiNetwork ?: portalNetwork)
    if (boundWifi != null) {
        preservedWifiNetwork = boundWifi
        portalNetwork = boundWifi
    }

    val script = PortalJsScripts.buildClickLogoutScript(detectedLogoutControl)

    webView.evaluateJavascript(script) { result ->
        logoutSearchRunning = false

        if (stage != Stage.LOGOUT || isFinishing || isDestroyed) return@evaluateJavascript

        if (result?.contains("LOGOUT_CLICKED") == true) {
            stage = Stage.VERIFY_LOGOUT
            logoutVerifyAttempts = 0
            logoutVerifyRunning = false
            workflowStatus("VERIFYING PORTAL LOGOUT...")
            handler.removeCallbacks(verifyLogoutRunnable)
            handler.postDelayed(verifyLogoutRunnable, 900)
        } else if (logoutSearchAttempts < 6) {
            val curUrl = if (::webView.isInitialized) webView.url.orEmpty() else ""
            val lostPage = curUrl.isBlank() ||
                curUrl == "about:blank" ||
                curUrl.startsWith("chrome-error:", ignoreCase = true)
            if (lostPage && PortalNetworkHelper.isValidHttpUrl(portalUrl)) {
                awaitingLogoutPageReload = true
                webView.loadUrl(portalUrl)
            } else {
                handler.postDelayed(logoutRetryRunnable, 700)
            }
        } else {
            connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
            stage = Stage.IDLE
            statusText("● PORTAL LOGOUT BUTTON NOT FOUND", red)
        }
    }
}

private fun verifyPortalLogout() {
    if (stage != Stage.VERIFY_LOGOUT || logoutVerifyRunning || isFinishing || isDestroyed) return

    if (!isVpnConnected()) {
        connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
        stage = Stage.WAIT_VPN
        workflowStatus("VPN DISCONNECTED — RECONNECT THUNDER VPN")
        if (isActivityInForeground) showVpnDialog()
        return
    }

    handler.removeCallbacks(verifyLogoutRunnable)
    logoutVerifyRunning = true
    logoutVerifyAttempts++

    val previousPage = detectedLogoutControl?.pageUrl ?: portalUrl
    val script = PortalJsScripts.buildVerifyLogoutScript(previousPage)

    webView.evaluateJavascript(script) { result ->
        logoutVerifyRunning = false
        if (stage != Stage.VERIFY_LOGOUT || isFinishing || isDestroyed) return@evaluateJavascript

        when {
            result?.contains("LOGOUT_VERIFIED_") == true -> {
                stepLogoutVerified = true
                workflowStatus("PORTAL LOGOUT SUCCESS")
                stage = Stage.LOGOUT
                loginActionSearchAttempts = 0
                loginActionSearchRunning = false
                startSecondLoginTimer()
                clickPortalLoginAgent()
            }

            result?.contains("LOGOUT_ERROR_PAGE") == true -> {
                scheduleLogoutRetryAfterNetworkError()
            }

            result?.contains("LOGOUT_STILL_PRESENT") == true -> {
                if (logoutVerifyAttempts == 3 || logoutVerifyAttempts == 6) {
                    stage = Stage.LOGOUT
                    clickPortalLogout()
                } else if (logoutVerifyAttempts < 12) {
                    handler.postDelayed(verifyLogoutRunnable, 600)
                } else {
                    connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
                    stage = Stage.IDLE
                    statusText("● LOGOUT NOT CONFIRMED BY PORTAL", red)
                }
            }

            else -> {
                if (logoutVerifyAttempts < 14) {
                    handler.postDelayed(verifyLogoutRunnable, 550)
                } else {
                    connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
                    stage = Stage.IDLE
                    statusText("● LOGOUT VERIFICATION TIMEOUT", red)
                }
            }
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
    if (stage != Stage.LOGOUT || loginActionSearchRunning || isFinishing || isDestroyed) return

    handler.removeCallbacks(secondLoginAgentRunnable)

    if (loginActionSearchAttempts >= 60) {
        stopSecondLoginTimer()
        connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
        secondLoginStageStatus.text = "SECOND LOGIN BUTTON NOT FOUND"
        secondLoginStageStatus.setTextColor(red)
        return
    }

    loginActionSearchRunning = true
    loginActionSearchAttempts++

    val script = PortalJsScripts.buildSecondLoginAgentScript()

    webView.evaluateJavascript(script) { result ->
        loginActionSearchRunning = false

        if (stage != Stage.LOGOUT || isFinishing || isDestroyed) return@evaluateJavascript

        if (result?.contains("PAGE_LOADING") == true) {
            loginActionSearchAttempts = (loginActionSearchAttempts - 1).coerceAtLeast(0)
            handler.postDelayed(secondLoginAgentRunnable, 250)
        } else if (result?.contains("LOGIN_AGENT_FORM_ALREADY_OPEN") == true) {
            secondLoginButtonFound = true
            stepSecondSignInCompleted = true
            secondFormSearchAttempts = 0
            stage = Stage.WAIT_SECOND_LOGIN
            stopSecondLoginTimer()
            secondLoginStageStatus.text = "SECOND LOGIN FORM READY"
            startInAppInternetRefreshCheck()
        } else if (result?.contains("LOGIN_AGENT_CLICKED") == true) {
            secondLoginButtonFound = true
            secondFormSearchAttempts = 0
            stage = Stage.WAIT_SECOND_LOGIN

            stopSecondLoginTimer()
            secondLoginStageStatus.text = "LOGIN BUTTON CLICKED"

            handler.postDelayed(secondFormCheckRunnable, 500)
        } else if (loginActionSearchAttempts < 60) {
            handler.postDelayed(secondLoginAgentRunnable, 1200)
        } else {
            stopSecondLoginTimer()
            connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
            secondLoginStageStatus.text = "SECOND LOGIN BUTTON NOT FOUND"
            secondLoginStageStatus.setTextColor(red)
        }
    }
}

private fun checkSecondLoginForm() {
    if (stage != Stage.WAIT_SECOND_LOGIN || isFinishing || isDestroyed) return
    if (!secondLoginButtonFound || secondFormCheckRunning) return

    handler.removeCallbacks(secondFormCheckRunnable)
    secondFormCheckRunning = true
    secondFormSearchAttempts++

    val formScript = PortalJsScripts.buildSecondLoginFormScript()

    webView.evaluateJavascript(formScript) { result ->
        if (stage != Stage.WAIT_SECOND_LOGIN || isFinishing || isDestroyed) {
            secondFormCheckRunning = false
            return@evaluateJavascript
        }

        if (result?.contains("SECOND_FORM_READY") == true) {
            secondFormCheckRunning = false
            stepSecondSignInCompleted = true
            startInAppInternetRefreshCheck()
        } else if (secondFormSearchAttempts < 30) {
            secondFormCheckRunning = false
            workflowStatus("WAITING FOR SECOND LOGIN FORM...")
            handler.postDelayed(secondFormCheckRunnable, 800)
        } else {
            secondFormCheckRunning = false
            connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }
            stage = Stage.IDLE
            secondLoginStageStatus.text = "SECOND LOGIN FORM NOT FOUND"
            secondLoginStageStatus.setTextColor(red)
            statusText("● SECOND LOGIN FORM NOT FOUND", red)
        }
    }
}  

private fun startInAppInternetRefreshCheck() {
    if (stage == Stage.CHECK_INTERNET || netWorkingVerified || isFinishing || isDestroyed) return

    stage = Stage.CHECK_INTERNET
    loginSubmitted = true
    secondFormCheckRunning = false
    googlePageLoadedInWebView = false
    realInternetProbeVerified = false
    stopSecondLoginTimer()

    val cm = connectivityManager
        ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
            connectivityManager = it
        }
    PortalNetworkHelper.clearProcessNetworkBinding(cm)

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
    if (
        stage != Stage.CHECK_INTERNET ||
        googlePageLoadedInWebView ||
        netWorkingVerified ||
        isFinishing ||
        isDestroyed
    ) return
    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.postDelayed(netCheckRefreshRunnable, delayMs)
}

private fun loadNextNetCheckPage() {
    if (
        stage != Stage.CHECK_INTERNET ||
        googlePageLoadedInWebView ||
        netWorkingVerified ||
        isFinishing ||
        isDestroyed
    ) return
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
    if (
        stage != Stage.CHECK_INTERNET ||
        googlePageLoadedInWebView ||
        netWorkingVerified ||
        isFinishing ||
        isDestroyed
    ) return

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
        if (
            stage != Stage.CHECK_INTERNET ||
            googlePageLoadedInWebView ||
            netWorkingVerified ||
            isFinishing ||
            isDestroyed
        ) {
            return@evaluateJavascript
        }
        if (result?.contains("PAGE_OPENED") == true) {
            googlePageLoadedInWebView = true
            handler.removeCallbacks(netCheckRefreshRunnable)
            handler.removeCallbacks(netCheckPageTimeoutRunnable)
            if (realInternetProbeVerified) {
                markInternetConnected()
            } else {
                workflowStatus("GOOGLE LOADED — VERIFYING INTERNET...")
                startLiveNetWorkingCheck()
            }
        } else {
            scheduleNextNetCheckRefresh(1500L)
        }
    }
}

private fun markInternetConnected() {
    if (netWorkingVerified || isFinishing || isDestroyed) return

    val allStepsReady =
        stepNotificationUrlDetected &&
            stepOriginalPortalOpened &&
            stepLoginCompleted &&
            stepLogoutControlDetected &&
            stepVpnConnectedConfirmed &&
            stepReturnedFromVpnToWebView &&
            stepLogoutVerified &&
            stepSecondSignInCompleted &&
            googlePageLoadedInWebView &&
            realInternetProbeVerified &&
            isVpnConnected()

    if (!allStepsReady) return

    handler.removeCallbacks(netCheckRefreshRunnable)
    handler.removeCallbacks(netCheckPageTimeoutRunnable)

    netWorkingVerified = true
    netCheckRunning = false
    stage = Stage.FINISHED
    completedSessionUrl = portalUrl

    if (::webView.isInitialized) {
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
    }

    statusText("● CONNECTED", green)

    if (::secondLoginStageStatus.isInitialized) {
        secondLoginStageStatus.text = "SECOND LOGIN FORM FOUND • CONNECTED"
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

    handler.removeCallbacks(liveNetCheckRunnable)
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
                realInternetProbeVerified = true
                if (googlePageLoadedInWebView) {
                    markInternetConnected()
                } else {
                    workflowStatus("WAITING FOR GOOGLE PAGE LOAD...")
                }
            } else {
                workflowStatus("CHECKING INTERNET CONNECTION...")
                handler.postDelayed(liveNetCheckRunnable, 1500)
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
    return try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (_: Exception) {
        false
    }
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
        statusText("● CONNECTED", green)
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

private fun registerWifiPortalReceiver() {
    if (wifiPortalReceiverRegistered) return
    try {
        val filter = IntentFilter(ACTION_WIFI_PORTAL_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiPortalReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiPortalReceiver, filter)
        }
        wifiPortalReceiverRegistered = true
    } catch (_: Exception) {
        wifiPortalReceiverRegistered = false
    }
}

private fun extractIncomingPortalUrl(intent: Intent?): String? {
    if (intent == null) return null
    when (intent.action) {
        Intent.ACTION_VIEW -> {
            val uriString = intent.dataString ?: intent.data?.toString()
            if (
                !uriString.isNullOrBlank() &&
                (uriString.startsWith("http://", ignoreCase = true) ||
                    uriString.startsWith("https://", ignoreCase = true))
            ) {
                return uriString
            }
        }

        Intent.ACTION_SEND -> {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val match = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
                    .find(sharedText)
                    ?.value
                    ?.trimEnd('.', ',', ';', ')', ']', '}', '!', '?')
                if (!match.isNullOrBlank()) {
                    return match
                }
            }
        }
    }
    return null
}

private fun saveCapturedUrlForCurrentNetwork(
    url: String,
    preResolvedGateway: String? = null
) {
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        return
    }
    try {
        val now = System.currentTimeMillis()
        val gw = preResolvedGateway ?: run {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = manager.allNetworks.firstOrNull { n ->
                manager.getNetworkCapabilities(n)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            getWifiGatewayIp(manager, wifi ?: manager.activeNetwork)
        }

        val prefs = getSharedPreferences("wifi_notification_links", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("latest_url", url)
            .putLong("latest_url_time", now)
        if (!gw.isNullOrBlank()) {
            editor.putString("gateway_url_$gw", url)
                .putLong("gateway_url_${gw}_time", now)
        }
        editor.apply()
    } catch (_: Exception) {
    }
}

private fun showSystemPortalLinkDialog() {
    // No-op: popup removed to keep all workflow inside WebView.
}

private fun handleDetectedPortalLaunch(
    detectedUrl: String?,
    detectionType: String?,
    detectedAt: Long,
    forceIfIdleOrFinished: Boolean = false
) {
    if (!appInitialized || isFinishing || isDestroyed) return
    if (!ActivationManager.hasAccess(this)) return

    // Never interrupt an active mid-flow automation cycle.
    if (stage != Stage.IDLE && stage != Stage.FINISHED) return

    val normalizedUrl = detectedUrl?.trim()?.takeIf { PortalNetworkHelper.isValidHttpUrl(it) }

    // Capture once per Wi-Fi session: do not restart/recapture the same URL after completion.
    if (!forceIfIdleOrFinished) {
        if (stage == Stage.FINISHED &&
            (normalizedUrl == null || normalizedUrl == completedSessionUrl || normalizedUrl == activeSessionUrl)
        ) {
            return
        }
        if (normalizedUrl != null && normalizedUrl == activeSessionUrl && stage == Stage.IDLE && completedSessionUrl == normalizedUrl) {
            return
        }
        if (normalizedUrl == null && System.currentTimeMillis() - lastAutoTriggerAt < 30_000L) {
            return
        }
        if (detectedAt > 0L && detectedAt <= lastAutoTriggerAt) {
            return
        }
    }

    lastAutoTriggerAt = if (detectedAt > 0L) detectedAt else System.currentTimeMillis()
    if (normalizedUrl != null) {
        activeSessionUrl = normalizedUrl
    }
    if (!detectionType.isNullOrBlank()) {
        activeDetectionType = detectionType
    }

    try {
        val prefs = getSharedPreferences(WifiNotificationListener.PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putBoolean("pending_auto_start", false)
        if (!detectionType.isNullOrBlank()) {
            editor.putString("pending_detection_type", detectionType)
        }
        editor.apply()
        if (normalizedUrl != null) {
            val typeToSave = detectionType ?: activeDetectionType ?: WifiNotificationListener.TYPE_PORTAL_LOGIN
            WifiNotificationListener.markSessionCaptured(this, normalizedUrl, typeToSave)
            saveCapturedUrlForCurrentNetwork(normalizedUrl)
        }
    } catch (_: Exception) {
    }

    startPortalProcess(userInitiated = forceIfIdleOrFinished)
}

override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)

    if (!appInitialized || intent == null) return

    val incomingUrl = extractIncomingPortalUrl(intent)
    if (!incomingUrl.isNullOrBlank()) {
        saveCapturedUrlForCurrentNetwork(incomingUrl)
        WifiNotificationListener.markSessionCaptured(
            this,
            incomingUrl,
            WifiNotificationListener.TYPE_VIEW_WEBSITE
        )
        intent.action = null
        intent.data = null
        handler.post {
            handleDetectedPortalLaunch(
                incomingUrl,
                WifiNotificationListener.TYPE_VIEW_WEBSITE,
                System.currentTimeMillis(),
                forceIfIdleOrFinished = true
            )
        }
    } else if (intent.action == ACTION_OPEN_PORTAL) {
        val detectedUrl = intent.getStringExtra("detected_url")
        val detectionType = intent.getStringExtra("detection_type")
        val detectedAt = intent.getLongExtra("detected_at", System.currentTimeMillis())
        intent.action = null
        handler.post {
            handleDetectedPortalLaunch(detectedUrl, detectionType, detectedAt, forceIfIdleOrFinished = true)
        }
    } else if (intent.action == VpnAutomationBridge.ACTION_RETURN_FROM_VPN) {
        intent.action = null
    }
}

override fun onResume() {
    super.onResume()
    isActivityInForeground = true
    VpnAutomationBridge.appInForeground = true
    PortalMonitorService.cancelVpnReturnNotification(this)

    if (!appInitialized) return

    if (::webView.isInitialized) {
        webView.onResume()
    }

    updateActivationStatus()
    updateNotificationAccessButton()
    updateVpnAutoAccessButton()
    updateInternetStatus()
    updateDeviceDataUsage()
    verifyLicenseWhenOnline()

    // Only refresh notifications when idle and no session URL has been captured yet,
    // so switching back from Thunder VPN never re-triggers "Connected / Tap to view website".
    if (isNotificationAccessEnabled() &&
        stage == Stage.IDLE &&
        WifiNotificationListener.getSessionCapturedPortal(this) == null
    ) {
        startMonitorService(refresh = true)
    }

    if (!ActivationManager.hasAccess(this)) {
        statusText("● ACTIVATION EXPIRED", red)

        ActivationManager.requireActivation(this) {
            updateActivationStatus()
        }
        return
    }

    if (stage == Stage.WAIT_VPN) {
        checkVpnStatus()
    } else if (stage == Stage.LOGOUT && !vpnReturnDelayPending && !secondLoginTimerRunning && !logoutSearchRunning) {
        if (isVpnConnected()) {
            stepVpnConnectedConfirmed = true
            vpnReturnDelayPending = true
            workflowStatus("VPN CONNECTED — WAITING 3S FOR PORTAL LOGOUT")
            handler.removeCallbacks(vpnReturnLogoutDelayRunnable)
            handler.postDelayed(vpnReturnLogoutDelayRunnable, VPN_RETURN_LOGOUT_DELAY_MS)
        } else {
            onVpnStateChanged(false)
        }
    }
}

override fun onPause() {
    isActivityInForeground = false
    VpnAutomationBridge.appInForeground = false
    super.onPause()
}

override fun onDestroy() {
    isActivityInForeground = false
    VpnAutomationBridge.appInForeground = false
    VpnAutomationBridge.disarm()
    secondLoginTimerRunning = false
    handler.removeCallbacksAndMessages(null)
    executor.shutdownNow()

    activationPopup?.dismiss()
    flashDialog?.dismiss()
    vpnDialog?.dismiss()
    portalLinkDialog?.dismiss()

    if (wifiPortalReceiverRegistered) {
        try {
            unregisterReceiver(wifiPortalReceiver)
        } catch (_: Exception) {
        }
        wifiPortalReceiverRegistered = false
    }

    if (networkCallbackRegistered) {
        try {
            connectivityManager?.unregisterNetworkCallback(licenseNetworkCallback)
        } catch (_: Exception) {
        }
        networkCallbackRegistered = false
    }

    if (wifiCallbackRegistered) {
        try {
            connectivityManager?.unregisterNetworkCallback(wifiNetworkCallback)
        } catch (_: Exception) {
        }
        wifiCallbackRegistered = false
    }

    if (vpnCallbackRegistered) {
        try {
            connectivityManager?.unregisterNetworkCallback(vpnNetworkCallback)
        } catch (_: Exception) {
        }
        vpnCallbackRegistered = false
    }

    connectivityManager?.let { PortalNetworkHelper.clearProcessNetworkBinding(it) }

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
        color = Color.rgb(68, 118, 82)
    }
    private val landStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(140, 225, 170)
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
            cx - radius * 0.35f,
            cy - radius * 0.35f,
            radius * 1.5f,
            intArrayOf(
                Color.rgb(25, 115, 205),
                Color.rgb(10, 52, 120),
                Color.rgb(2, 14, 42)
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
            val pinColor = Color.rgb(255, 45, 65)

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