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
    LOGOUT, WAIT_SECOND_LOGIN, FINISHED  
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
private lateinit var clockText: TextView  
private lateinit var secondLoginTimerStatus: TextView  
private lateinit var secondLoginStageStatus: TextView  

private var dataBaselineRx = 0L  
private var dataBaselineTx = 0L  
private var lastRx = 0L  
private var lastTx = 0L  
private var dataUsageSupported = false  

private var stage = Stage.IDLE  
private var vpnOpened = false  
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
private var secondLoginButtonFound = false  

private var logoutSearchRunning = false  
private var loginActionSearchRunning = false  
private var secondFormCheckRunning = false  

private var activationPopup: AlertDialog? = null  

private var connectivityManager: ConnectivityManager? = null  
private var networkCallbackRegistered = false  
private var lastLicenseNetworkState = false  

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
        if (!::globeView.isInitialized) return  
        globeView.invalidate()  
        handler.postDelayed(this, 45)  
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
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
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

            val updateAvailable = json.optBoolean("updateAvailable", false)
            val forceUpdate = json.optBoolean("forceUpdate", false)

            val versionName = json.optString("versionName", "")
            val message = json.optString("message", "A new version is available.")
            val downloadUrl = json.optString("downloadUrl", "")

            runOnUiThread {
                if (updateAvailable && forceUpdate) {
                    showForceUpdateDialog(versionName, message, downloadUrl)
                } else {
                    ActivationManager.requireActivation(this) {
                        initializeApplication()
                    }
                }
            }

        } catch (e: Exception) {
            // সার্ভারে যোগাযোগ না হলে পুরোনো অ্যাপের স্বাভাবিক কাজ চালু থাকবে।
            runOnUiThread {
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

    if (intent?.action == ACTION_OPEN_PORTAL) {  
        handler.postDelayed({ startPortalProcess() }, 500)  
    }  

    updateIpDetails()  
    updateInternetStatus()  
    updateActivationStatus()  
    updateDeviceDataUsage()  
    updateClock()  

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

    val topRow = LinearLayout(this).apply {  
        orientation = LinearLayout.HORIZONTAL  
        gravity = Gravity.CENTER  
    }  

    val ipPanel = createPanel()  
    ipPanel.addView(panelHeading("🌐 IP DETAILS"))  

    ipDetails = panelText(  
        "PUBLIC IP: CHECKING...\nCOUNTRY: --\nCITY: --\nISP: --\nVPN: CHECKING..."  
    )  
    ipPanel.addView(ipDetails)  

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
        setOnClickListener { showActivationPopup() }  
    }  

    activationPanel.addView(activationButton)  

    licenseDetails = panelText(  
        "ULTRA ACTIVATION\nCHECKING LICENSE..."  
    )  
    activationPanel.addView(licenseDetails)  

    val buyButton = makeButton("BUY NEW ACTIVATION KEY") {  
        openTelegramContact()  
    }  
    activationPanel.addView(buyButton)  

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
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)  

        webChromeClient = WebChromeClient()  

        webViewClient = object : WebViewClient() {  

            override fun onPageStarted(  
                view: WebView?,  
                url: String?,  
                favicon: Bitmap?  
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
                    Stage.LOGIN -> tryAutoLogin()  
                    Stage.CHECK_LOGIN -> checkPortalLogin()  
                    Stage.WAIT_SECOND_LOGIN -> checkSecondLoginForm()  
                    else -> {  
                        if (stage == Stage.IDLE && !dashboardShown) {  
                            statusText("● PORTAL PAGE READY", green)  
                        }  
                    }  
                }  
            }  
        }  
    }  

    secondLoginTimerStatus = panelText("LOGIN TIMER: NOT STARTED")  
    secondLoginStageStatus = panelText("FREE DATA CANECTION: WAITING")  

    content.addView(title, matchWrap())  
    content.addView(subtitle, matchWrap())  
    content.addView(status, matchWrap())  
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

private fun updateIpDetails() {  
    if (!::ipDetails.isInitialized) return  

    ipDetails.text =  
        "PUBLIC IP: CHECKING...\nCOUNTRY: --\nCITY: --\nISP: --\nVPN: CHECKING..."  

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

        val ip = if (success) data?.optString("ip") else "Unavailable"  
        val country = if (success) data?.optString("country") else "Unavailable"  
        val city = if (success) data?.optString("city") else "Unavailable"  
        val isp = if (success) {  
            data?.optJSONObject("connection")?.optString("isp")  
        } else "Unavailable"  

        runOnUiThread {  
            if (!::ipDetails.isInitialized) return@runOnUiThread  

            ipDetails.text =  
                "PUBLIC IP: $ip\n" +  
                "COUNTRY: $country\n" +  
                "CITY: $city\n" +  
                "ISP: ${isp ?: "Unavailable"}\n" +  
                "VPN: ${if (isVpnConnected()) "CONNECTED" else "DISCONNECTED"}"  

            ipDetails.setTextColor(  
                if (isVpnConnected()) green else cyan  
            )  
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

private fun startMonitorService() {  
    try {  
        val i = Intent(this, PortalMonitorService::class.java)  

        if (Build.VERSION.SDK_INT >= 26) {  
            startForegroundService(i)  
        } else {  
            startService(i)  
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

    stopSecondLoginTimer()  
    secondLoginElapsedSeconds = 0L  

    secondLoginTimerStatus.text = "SECOND LOGIN TIMER: NOT STARTED"  
    secondLoginStageStatus.text = "LOGIN STAGE: WAITING"  

    logoutSearchAttempts = 0  
    loginActionSearchAttempts = 0  
    secondLoginButtonFound = false  

    logoutSearchRunning = false  
    loginActionSearchRunning = false  
    secondFormCheckRunning = false  

    statusText("● FINDING CURRENT PORTAL...", cyan)  
    webTitle.text = "▣ PORTAL LOGIN PAGE"  

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
                    statusText("● WIFI ROUTING NOT AVAILABLE", red)  
                }  
            }  

            portalUrl = found ?: FALLBACK_PORTAL  

            statusText(  
                if (found != null) "● CURRENT PORTAL FOUND"  
                else "● USING PORTAL FALLBACK",  
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

            statusText("● LOGIN SUBMITTED - VERIFYING...", cyan)  

            handler.postDelayed({ checkPortalLogin() }, 3000)  

        } else if (result?.contains("FORM_NOT_FOUND") == true) {  
            statusText("● PORTAL LOGIN FORM NOT FOUND", red)  
        } else {  
            statusText("● CHECKING PORTAL FORM...", cyan)  
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
                statusText("● PORTAL LOGIN VERIFIED", green)  

                (getSystemService(Context.CONNECTIVITY_SERVICE)  
                    as ConnectivityManager).bindProcessToNetwork(null)  

                handler.postDelayed({ openThunderVpn() }, 2500)  
            }  

            result?.contains("LOGIN_FORM_STILL_HERE") == true -> {  
                statusText("● LOGIN NOT CONFIRMED", red)  
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
        statusText("● OPENING THUNDER VPN — CONNECT MANUALLY", cyan)  
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
        statusText("● VPN CONNECTION DETECTED", green)  

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
            statusText("● PLEASE CONNECT VPN", red)  
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
    AlertDialog.Builder(this)  
        .setTitle("Thunder VPN")  
        .setMessage(  
            "Tap Connect in Thunder VPN. Android does not allow this app to press another VPN app's Connect button reliably. Return here after connecting."  
        )  
        .setPositiveButton("OPEN VPN") { _, _ ->  
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
    if (stage != Stage.LOGOUT || logoutSearchRunning) return  

    if (logoutSearchAttempts >= 10) {  
        statusText("● PORTAL LOGOUT BUTTON NOT FOUND", red)  
        return  
    }  

    logoutSearchRunning = true  
    logoutSearchAttempts++  

    statusText("● SEARCHING PORTAL LOGOUT... $logoutSearchAttempts/10", cyan)  

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
            statusText("● LOGOUT CLICKED", green)  
            startSecondLoginTimer()  
            handler.postDelayed({ clickPortalLoginAgent() }, 2500)  
        } else if (logoutSearchAttempts < 10) {  
            handler.postDelayed({ clickPortalLogout() }, 3000)  
        } else {  
            statusText("● PORTAL LOGOUT BUTTON NOT FOUND", red)  
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
        statusText("● PORTAL LOGIN ACTION NOT FOUND", red)  
        return  
    }  

    loginActionSearchRunning = true  
    loginActionSearchAttempts++  

    statusText(  
        "● SEARCHING SECOND LOGIN BUTTON... $loginActionSearchAttempts/60",  
        cyan  
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
            stage = Stage.WAIT_SECOND_LOGIN  

            stopSecondLoginTimer()  
            secondLoginStageStatus.text = "LOGIN BUTTON CLICKED"  
            statusText("● SECOND LOGIN BUTTON FOUND", green)  

            handler.postDelayed({ checkSecondLoginForm() }, 1000)  

        } else if (loginActionSearchAttempts < 60) {  
            handler.postDelayed({ clickPortalLoginAgent() }, 2000)  
        } else {  
            stopSecondLoginTimer()  
            secondLoginStageStatus.text = "SECOND LOGIN BUTTON NOT FOUND"  
            secondLoginStageStatus.setTextColor(red)  
            statusText("● PORTAL LOGIN ACTION NOT FOUND", red)  
        }  
    }  
}  

private fun checkSecondLoginForm() {  
    if (stage != Stage.WAIT_SECOND_LOGIN) return  
    if (!secondLoginButtonFound || secondFormCheckRunning) return  

    secondFormCheckRunning = true  

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

        if (result?.contains("SECOND_FORM_READY") == true) {  
            stage = Stage.FINISHED  
            loginSubmitted = true  
            secondFormCheckRunning = false  
            stopSecondLoginTimer()  

            secondLoginStageStatus.text =  
                "LOGIN FORM READY — AUTOMATION STOPPED"  
            secondLoginStageStatus.setTextColor(green)  

            statusText(  
                "● FINAL LOGIN PAGE READY — AUTOMATION STOPPED",  
                green  
            )  

            loadIpDashboard()  
        } else {  
            secondFormCheckRunning = false  

            statusText("● WAITING FOR SECOND LOGIN FORM...", cyan)  

            handler.postDelayed({ checkSecondLoginForm() }, 2000)  
        }  
    }  
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
        !hasValidatedInternet()  
    ) return  

    dashboardShown = true  
    statusText("● VPN + INTERNET VERIFIED", green)  
    webTitle.text = "▣ LIVE CONNECTION INFORMATION"  

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
        handler.postDelayed({ startPortalProcess() }, 300)  
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

    private val globePaint = Paint(Paint.ANTI_ALIAS_FLAG)  
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)  
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)  

    private var rotation = 0f  
    private var blink = false  

    override fun onDraw(canvas: Canvas) {  
        super.onDraw(canvas)  

        val cx = width / 2f  
        val cy = height / 2f  
        val radius = minOf(width, height) * 0.38f  

        rotation += 1.2f  
        blink = !blink  

        globePaint.shader = RadialGradient(  
            cx - radius * 0.3f,  
            cy - radius * 0.3f,  
            radius * 1.5f,  
            intArrayOf(  
                Color.rgb(25, 105, 145),  
                Color.rgb(5, 45, 85),  
                Color.rgb(2, 13, 34)  
            ),  
            null,  
            Shader.TileMode.CLAMP  
        )  

        canvas.drawCircle(cx, cy, radius, globePaint)  
        globePaint.shader = null  

        globePaint.style = Paint.Style.STROKE  
        globePaint.strokeWidth = 1.5f  
        globePaint.color = cyan  
        canvas.drawCircle(cx, cy, radius, globePaint)  

        linePaint.color = Color.argb(150, 0, 220, 190)  
        linePaint.strokeWidth = 1.2f  
        linePaint.style = Paint.Style.STROKE  

        for (fraction in listOf(-0.65f, -0.30f, 0f, 0.30f, 0.65f)) {  
            val h = radius * kotlin.math.sqrt(  
                1f - fraction * fraction  
            )  

            canvas.drawOval(  
                cx - radius,  
                cy + fraction * radius - h * 0.15f,  
                cx + radius,  
                cy + fraction * radius + h * 0.15f,  
                linePaint  
            )  
        }  

        for (i in 0..3) {  
            val angle = Math.toRadians(  
                (rotation + i * 45.0).toDouble()  
            )  

            val ovalWidth = kotlin.math.abs(  
                kotlin.math.cos(angle).toFloat()  
            ) * radius  

            canvas.drawOval(  
                cx - ovalWidth,  
                cy - radius,  
                cx + ovalWidth,  
                cy + radius,  
                linePaint  
            )  
        }  

        if (isVpnConnected()) {  
            dotPaint.color =  
                if (blink) Color.RED else Color.rgb(255, 100, 100)  

            dotPaint.setShadowLayer(10f, 0f, 0f, Color.RED)  

            val markerAngle = Math.toRadians(  
                (rotation + 65.0).toDouble()  
            )  

            val mx = cx +  
                (radius * 0.65f * kotlin.math.cos(markerAngle)).toFloat()  

            val my = cy - radius * 0.25f  

            canvas.drawCircle(mx, my, 5f, dotPaint)  
            dotPaint.clearShadowLayer()  
        }  

                globePaint.style = Paint.Style.FILL
    }
}
}