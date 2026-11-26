package com.apumsw1.ultraloader

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.lang.reflect.Field
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PortalAutomationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WifiNotificationListener.clearSessionState(context)
        NetworkStatusManager.updateCaptivePortalState(false, null)
        NetworkStatusManager.updateVpnConnectionState(false)
    }

    @After
    fun tearDown() {
        WifiNotificationListener.clearSessionState(context)
        NetworkStatusManager.updateCaptivePortalState(false, null)
        NetworkStatusManager.updateVpnConnectionState(false)
    }

    @Test
    fun portalUrlFilterRejectsMozillaAndConnectivityCheckUrls() {
        val rejectedUrls = listOf(
            "http://detectportal.firefox.com/canonical.html",
            "https://detectportal.firefox.com/success.txt",
            "https://www.mozilla.org/en-US/",
            "http://connectivitycheck.gstatic.com/generate_24",
            "https://connectivitycheck.android.com/generate_24",
            "http://connectivitycheck.google.com/generate_24",
            "http://clients3.google.com/generate_204",
            "https://www.google.com/",
            "https://google.com/generate_24",
            "http://captive.apple.com/hotspot-detect.html",
            "https://www.apple.com/library/test/success.html",
            "http://www.msftconnecttest.com/connecttest.txt",
            "http://www.msftncsi.com/ncsi.txt",
            "http://schemas.android.com/apk/res/android",
            "javascript:void(0)",
            ""
        )

        for (url in rejectedUrls) {
            assertFalse(
                "Expected URL to be rejected as portal URL: $url",
                PortalNetworkHelper.isUsablePortalUrl(url)
            )
        }

        val acceptedPortalUrls = listOf(
            "http://172.16.0.1/login",
            "https://wifi.airport-portal.net:8443/portal/auth?session=abc",
            "http://hotspot.local/status?dst=http://example.com"
        )

        for (url in acceptedPortalUrls) {
            assertTrue(
                "Expected genuine captive portal URL to be accepted: $url",
                PortalNetworkHelper.isUsablePortalUrl(url)
            )
        }
    }

    @Test
    fun notificationSessionStorageIgnoresBrowserAndConnectivityUrls() {
        WifiNotificationListener.markSessionCaptured(
            context,
            "http://detectportal.firefox.com/canonical.html",
            WifiNotificationListener.TYPE_PORTAL_LOGIN
        )
        assertNull(WifiNotificationListener.getSessionCapturedPortal(context))

        WifiNotificationListener.markSessionCaptured(
            context,
            "http://connectivitycheck.gstatic.com/generate_24",
            WifiNotificationListener.TYPE_VIEW_WEBSITE
        )
        assertNull(WifiNotificationListener.getSessionCapturedPortal(context))

        WifiNotificationListener.markSessionCaptured(
            context,
            "https://portal.example-wifi.net/login",
            WifiNotificationListener.TYPE_VIEW_WEBSITE
        )
        val saved = WifiNotificationListener.getSessionCapturedPortal(context)
        assertNotNull(saved)
        assertEquals("https://portal.example-wifi.net/login", saved?.first)
        assertEquals(WifiNotificationListener.TYPE_VIEW_WEBSITE, saved?.second)
    }

    @Test
    fun connectedTapToViewWebsiteCapturesOriginalUrlFromSpannedNotification() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        val wifiNetwork = ShadowNetwork.newInstance(101)
        @Suppress("DEPRECATION")
        val wifiInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val wifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(wifiCaps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowCm.addNetwork(wifiNetwork, wifiInfo)
        shadowCm.setNetworkCapabilities(wifiNetwork, wifiCaps)

        val listener = Robolectric.buildService(WifiNotificationListener::class.java).create().get()

        val spannedText = SpannableString("Tap to view website").apply {
            setSpan(
                URLSpan("https://wifi.realportal.example/welcome?id=42"),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val notification = Notification.Builder(context)
            .setContentTitle("Connected")
            .setContentText(spannedText)
            .build()

        val sbn = StatusBarNotification(
            "com.android.networkstack",
            "com.android.networkstack",
            1,
            "captive_tag",
            1000,
            0,
            0,
            notification,
            UserHandle.getUserHandleForUid(1000),
            System.currentTimeMillis()
        )

        listener.onNotificationPosted(sbn)

        val captured = WifiNotificationListener.getSessionCapturedPortal(context)
        assertNotNull("Expected portal URL to be captured from Wi-Fi notification", captured)
        assertEquals("https://wifi.realportal.example/welcome?id=42", captured?.first)
        assertEquals(WifiNotificationListener.TYPE_VIEW_WEBSITE, captured?.second)
    }

    @Test
    fun mainActivityInitialStatusHiddenStatusesHiddenBrowserAndFloatingSuccessBanner() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()

        // Invoke createScreen() directly so UI state is initialized without requiring AndroidKeyStore TEE.
        callPrivateMethod(activity, "createScreen")

        val statusView = getPrivateField<TextView>(activity, "status")

        // Requirement 4: Initial connection status starts with CONNECTING, PLEASE WAIT...
        assertEquals("● CONNECTING, PLEASE WAIT...", statusView.text.toString())

        // Simulate idle -> SYSTEM READY -> ACTIVATION KEY VERIFIED progression
        callPrivateMethod(activity, "workflowStatus", arrayOf(String::class.java), "SYSTEM READY")
        assertEquals("● SYSTEM READY", statusView.text.toString())

        callPrivateMethod(activity, "workflowStatus", arrayOf(String::class.java), "ACTIVATION KEY VERIFIED")
        assertEquals("● ACTIVATION KEY VERIFIED", statusView.text.toString())

        // Requirement 5 & 10: Hide Login and Logout status messages from visible UI
        val hiddenStatuses = listOf(
            "LOGIN IN PROGRESS",
            "LOGOUT IN PROGRESS",
            "LOGIN PAGE OPENING",
            "LOGOUT PAGE DETECTED",
            "Login",
            "Logout",
            "Login Detected",
            "Logout Detected",
            "Login Successful",
            "Logout Successful",
            "Detected Login",
            "Detected Logout",
            "Login Page Opening",
            "Logout Page Detecting",
            "PORTAL LOGIN VERIFIED — DETECTING LOGOUT",
            "PORTAL LOGOUT SUCCESS"
        )
        for (hidden in hiddenStatuses) {
            callPrivateMethod(activity, "workflowStatus", arrayOf(String::class.java), hidden)
            assertEquals("Hidden status '$hidden' leaked into UI", "● ACTIVATION KEY VERIFIED", statusView.text.toString())

            callPrivateMethod(
                activity,
                "statusText",
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!),
                "● $hidden",
                Color.CYAN
            )
            assertEquals("Hidden status '$hidden' leaked via statusText", "● ACTIVATION KEY VERIFIED", statusView.text.toString())
        }

        // Other process statuses MUST remain visible (never globally filter "Detected")
        val visibleStatuses = listOf(
            "ORIGINAL PORTAL URL DETECTED",
            "Original Portal Detected",
            "Portal Detected",
            "Router Detected",
            "VPN CONNECTED",
            "VPN Disconnected",
            "REFRESHING GOOGLE (1)..."
        )
        for (visible in visibleStatuses) {
            callPrivateMethod(activity, "workflowStatus", arrayOf(String::class.java), visible)
            assertEquals("● $visible", statusView.text.toString())
        }

        // Router panel must appear at the very top of the dashboard (before status and dashboard columns)
        val routerPanel = getPrivateField<LinearLayout>(activity, "routerPanel")
        val statusParent = statusView.parent as ViewGroup
        val routerIndex = statusParent.indexOfChild(routerPanel)
        val statusIndex = statusParent.indexOfChild(statusView)
        assertTrue("Router panel must be placed above status banner", routerIndex in 0 until statusIndex)

        // Requirement 6: Lower LIVE PORTAL & AUTOMATION BROWSER panel hidden, internal WebView stays alive & full size
        val rootView = getPrivateField<LinearLayout>(activity, "root")
        val browserHeading = findTextViewWithExactText(rootView, "🌐 LIVE PORTAL & AUTOMATION BROWSER")
        assertNotNull(browserHeading)
        val webViewPanel = browserHeading?.parent as? View
        assertNotNull(webViewPanel)
        assertEquals(View.GONE, webViewPanel?.visibility)

        val liveUsageHeading = findTextViewWithExactText(rootView, "📈 LIVE INTERNET USAGE GRAPH")
        assertNotNull(liveUsageHeading)
        assertEquals(View.VISIBLE, liveUsageHeading?.visibility)

        val internalWebView = getPrivateField<WebView>(activity, "webView")
        assertEquals(View.VISIBLE, internalWebView.visibility)
        assertEquals(0f, internalWebView.alpha, 0.001f)
        assertTrue(internalWebView.settings.javaScriptEnabled)

        // Requirement 3: Floating banner stays hidden before all verification + VPN complete
        val floatingBanner = getPrivateField<LinearLayout>(activity, "floatingConnectedBanner")
        assertEquals(View.GONE, floatingBanner.visibility)

        // Calling markInternetConnected prematurely MUST NOT show the success banner
        callPrivateMethod(activity, "markInternetConnected")
        assertEquals(View.GONE, floatingBanner.visibility)
        assertFalse(getPrivateBooleanField(activity, "netWorkingVerified"))

        // Satisfy VPN + all automation verification flags
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnNetwork = ShadowNetwork.newInstance(202)
        @Suppress("DEPRECATION")
        val vpnInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_VPN,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val vpnCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(vpnCaps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(vpnCaps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        shadowOf(cm).addNetwork(vpnNetwork, vpnInfo)
        shadowOf(cm).setNetworkCapabilities(vpnNetwork, vpnCaps)

        setPrivateField(activity, "portalUrl", "https://wifi.realportal.example/login")
        setPrivateField(activity, "stepNotificationUrlDetected", true)
        setPrivateField(activity, "stepOriginalPortalOpened", true)
        setPrivateField(activity, "stepLoginCompleted", true)
        setPrivateField(activity, "stepLogoutControlDetected", true)
        setPrivateField(activity, "stepVpnConnectedConfirmed", true)
        setPrivateField(activity, "stepReturnedFromVpnToWebView", true)
        setPrivateField(activity, "stepLogoutVerified", true)
        setPrivateField(activity, "stepSecondSignInCompleted", true)
        setPrivateField(activity, "googlePageLoadedInWebView", true)
        setPrivateField(activity, "realInternetProbeVerified", true)

        callPrivateMethod(activity, "markInternetConnected")
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(getPrivateBooleanField(activity, "netWorkingVerified"))
        assertEquals(View.VISIBLE, floatingBanner.visibility)

        val bannerTexts = collectAllTextViews(floatingBanner).map { it.text.toString() }
        assertTrue(bannerTexts.contains("✓ CONNECTED SUCCESSFULLY"))
        assertTrue(bannerTexts.contains("INTERNET VERIFIED • VPN CONNECTED"))

        // Requirement 2: Duplicate internet-check probe must NOT start once Google page is loaded
        setPrivateField(activity, "netCheckRunning", false)
        callPrivateMethod(activity, "startLiveNetWorkingCheck")
        assertFalse(getPrivateBooleanField(activity, "netCheckRunning"))

        controller.destroy()
    }

    @Test
    fun googleCheckValidationRejectsPortalHostAndAllowsGoogleHttps() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()
        setPrivateField(activity, "portalUrl", "https://hotspot.example.com/login")

        val isValidGoogle = activity.javaClass.getDeclaredMethod("isValidGoogleCheckUrl", String::class.java)
            .apply { isAccessible = true }

        assertTrue(isValidGoogle.invoke(activity, "https://www.google.com/?ul_chk=123") as Boolean)
        assertFalse(isValidGoogle.invoke(activity, "https://hotspot.example.com/login") as Boolean)
        assertFalse(isValidGoogle.invoke(activity, "about:blank") as Boolean)
        assertFalse(isValidGoogle.invoke(activity, "chrome-error://chromewebdata/") as Boolean)

        controller.destroy()
    }

    @Test
    fun statusDisplayFilterHidesOnlyLoginLogoutAndPreservesAllOtherStatuses() {
        val hidden = listOf(
            "Login",
            "Logout",
            "Login Detected",
            "Logout Detected",
            "Login Successful",
            "Logout Successful",
            "Detected Login",
            "Detected Logout",
            "Login Page Opening",
            "Logout Page Detecting",
            "● LOGIN IN PROGRESS",
            "● LOGOUT IN PROGRESS",
            "● LOGIN PAGE OPENING",
            "● LOGOUT PAGE DETECTED"
        )
        for (msg in hidden) {
            assertTrue("Expected status to be hidden: $msg", StatusDisplayFilter.isHiddenLoginLogoutStatus(msg))
        }

        val visible = listOf(
            "Original Portal Detected",
            "Portal Detected",
            "Connecting, Please Wait",
            "Please Wait",
            "Connected",
            "Connected Successful",
            "Connection Successful",
            "System Ready",
            "Ready",
            "Router Detected",
            "VPN Connected",
            "VPN Disconnected",
            "Key Verification Status",
            "ACTIVATION KEY VERIFIED",
            "PORTAL PAGE LOADING...",
            "CHECKING INTERNET CONNECTION..."
        )
        for (msg in visible) {
            assertFalse("Expected status to remain visible: $msg", StatusDisplayFilter.isHiddenLoginLogoutStatus(msg))
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun wifiRouterInfoExtractsDynamicSsidAndUpdatesWithNetworkChanges() {
        assertEquals("CafeWifi", PortalNetworkHelper.cleanSsid("\"CafeWifi\""))
        assertEquals("Office-5G", PortalNetworkHelper.cleanSsid("Office-5G"))
        assertNull(PortalNetworkHelper.cleanSsid("<unknown ssid>"))
        assertNull(PortalNetworkHelper.cleanSsid("0x"))
        assertNull(PortalNetworkHelper.cleanSsid(""))

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        shadowCm.clearAllNetworks()
        shadowCm.setActiveNetworkInfo(null)

        // Initially no Wi-Fi network
        val disconnected = PortalNetworkHelper.getWifiRouterInfo(context, cm)
        assertFalse(disconnected.connected)
        assertNull(disconnected.ssid)
        assertEquals("DISCONNECTED", disconnected.statusLabel)

        // Connect Wi-Fi #1 with captive portal capability
        val wifiNetwork = ShadowNetwork.newInstance(301)
        val wifiNetInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED
        )
        val portalCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(portalCaps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(portalCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        shadowCm.addNetwork(wifiNetwork, wifiNetInfo)
        shadowCm.setNetworkCapabilities(wifiNetwork, portalCaps)

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo1 = WifiInfo.Builder().setSsid("Campus_WiFi_A".toByteArray()).build()
        shadowOf(wm).setConnectionInfo(wifiInfo1)

        val info1 = PortalNetworkHelper.getWifiRouterInfo(context, cm, wifiNetwork, portalCaps)
        assertTrue(info1.connected)
        assertEquals("Campus_WiFi_A", info1.ssid)
        assertEquals("CONNECTED • PORTAL DETECTED", info1.statusLabel)

        // Switch Wi-Fi #2 and transition to validated online
        val onlineCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(onlineCaps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(onlineCaps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowCm.setNetworkCapabilities(wifiNetwork, onlineCaps)

        val wifiInfo2 = WifiInfo.Builder().setSsid("Hotel_Guest_5G".toByteArray()).build()
        shadowOf(wm).setConnectionInfo(wifiInfo2)

        val info2 = PortalNetworkHelper.getWifiRouterInfo(context, cm, wifiNetwork, onlineCaps)
        assertTrue(info2.connected)
        assertEquals("Hotel_Guest_5G", info2.ssid)
        assertEquals("CONNECTED • ONLINE", info2.statusLabel)
    }

    @Test
    fun homePageSideBySideButtonsEqualWidthAndStartButtonVisibleWithoutScrolling() {
        val dm = context.resources.displayMetrics
        val savedDensity = dm.density
        val savedWidth = dm.widthPixels
        val savedHeight = dm.heightPixels

        try {
            // Simulate a compact 360x640dp phone screen (e.g., 1080x1920 @ 3.0 density)
            dm.density = 3.0f
            dm.widthPixels = 1080
            dm.heightPixels = 1920

            val controller = Robolectric.buildActivity(MainActivity::class.java)
            val activity = controller.create().get()
            callPrivateMethod(activity, "createScreen")

            val saveBtn = getPrivateField<Button>(activity, "saveLoginButton")
            val defaultBtn = getPrivateField<Button>(activity, "defaultAppButton")
            val startBtn = getPrivateField<Button>(activity, "startLoaderButton")
            val rootView = getPrivateField<LinearLayout>(activity, "root")

            // Hide permission warning banners as on a configured device
            getPrivateField<Button>(activity, "notifAccessButton").visibility = View.GONE
            getPrivateField<Button>(activity, "vpnAutoAccessButton").visibility = View.GONE

            assertEquals("SAVE LOGIN", saveBtn.text.toString())
            assertEquals("DEFAULT APP", defaultBtn.text.toString())
            assertEquals("▶ START ULTRA LOADER", startBtn.text.toString())

            // Must be side-by-side in the same horizontal row with 50% / 50% equal weight
            val actionRow = saveBtn.parent as LinearLayout
            assertEquals(LinearLayout.HORIZONTAL, actionRow.orientation)
            assertEquals(defaultBtn.parent, actionRow)
            assertEquals(0, actionRow.indexOfChild(saveBtn))
            assertEquals(1, actionRow.indexOfChild(defaultBtn))
            val saveLp = saveBtn.layoutParams as LinearLayout.LayoutParams
            val defaultLp = defaultBtn.layoutParams as LinearLayout.LayoutParams
            assertEquals(0, saveLp.width)
            assertEquals(0, defaultLp.width)
            assertEquals(1f, saveLp.weight, 0.001f)
            assertEquals(1f, defaultLp.weight, 0.001f)

            // Measure and layout at 1080x1920 (minus ~150px status/nav inset => 1770px app viewport)
            val viewportHeightPx = 1770
            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeightPx, View.MeasureSpec.EXACTLY)
            )
            rootView.layout(0, 0, 1080, viewportHeightPx)

            // Equal measured width on both side-by-side buttons
            assertEquals(saveBtn.measuredWidth, defaultBtn.measuredWidth)
            assertTrue("Side-by-side buttons must occupy usable width", saveBtn.measuredWidth > 350)

            // Locate startBtn bottom relative to the ScrollView viewport to verify no scrolling needed
            val startCoords = IntArray(2)
            val rootCoords = IntArray(2)
            startBtn.getLocationOnScreen(startCoords)
            rootView.getLocationOnScreen(rootCoords)
            val startBottomInViewport = (startCoords[1] - rootCoords[1]) + startBtn.measuredHeight
            assertTrue(
                "START button bottom ($startBottomInViewport px) must fit within viewport ($viewportHeightPx px) without scrolling",
                startBottomInViewport <= viewportHeightPx
            )

            controller.destroy()
        } finally {
            dm.density = savedDensity
            dm.widthPixels = savedWidth
            dm.heightPixels = savedHeight
        }
    }

    @Test
    fun defaultBrowserSettingsAndPortalNotificationRedirectionWorkCorrectly() {
        val shadowPm = shadowOf(context.packageManager)

        // Ensure Manage Default Apps Settings resolves
        val settingsResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "com.android.settings.DefaultAppsActivity"
            }
        }
        shadowPm.addResolveInfoForIntent(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            settingsResolve
        )
        val settingsIntent = PortalNetworkHelper.buildDefaultBrowserSettingsIntent(context)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, settingsIntent.action)

        val portalUrl = "https://wifi.airport-portal.example/login"
        val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        // Case 1: ULTRA LOADER is selected as the default browser
        val ultraResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = context.packageName
                name = MainActivity::class.java.name
            }
        }
        shadowPm.addResolveInfoForIntent(probeIntent, ultraResolve)
        assertTrue(PortalNetworkHelper.isUltraLoaderDefaultBrowser(context))

        val ultraLaunchIntent = PortalNetworkHelper.buildPortalNotificationLaunchIntent(
            context = context,
            portalUrl = portalUrl,
            detectionType = WifiNotificationListener.TYPE_VIEW_WEBSITE
        )
        assertEquals(MainActivity::class.java.name, ultraLaunchIntent.component?.className)
        assertEquals("com.apumsw1.ultraloader.OPEN_PORTAL", ultraLaunchIntent.action)
        assertEquals(portalUrl, ultraLaunchIntent.getStringExtra("detected_url"))

        // Case 2: Google Chrome is selected as the default browser
        val chromeResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.chrome"
                name = "com.google.android.apps.chrome.Main"
            }
        }
        shadowPm.removeResolveInfosForIntent(probeIntent, context.packageName)
        shadowPm.addResolveInfoForIntent(probeIntent, chromeResolve)
        assertFalse(PortalNetworkHelper.isUltraLoaderDefaultBrowser(context))

        val chromeLaunchIntent = PortalNetworkHelper.buildPortalNotificationLaunchIntent(
            context = context,
            portalUrl = portalUrl,
            detectionType = WifiNotificationListener.TYPE_VIEW_WEBSITE
        )
        assertEquals(Intent.ACTION_VIEW, chromeLaunchIntent.action)
        assertEquals(portalUrl, chromeLaunchIntent.dataString)
        assertEquals("com.android.chrome", chromeLaunchIntent.`package`)
    }

    @Test
    fun incomingNotificationUrlLaunchesWhetherNotLoggedInOrAlreadyLoggedIn() {
        ActivationManager.cipherDelegate = object : ActivationManager.CipherDelegate {
            override fun encrypt(plain: ByteArray): ByteArray = plain
            override fun decrypt(payload: ByteArray): ByteArray = payload
        }
        try {
            ActivationManager.clearAllLicensesForTesting(context)
            ActivationManager.upsertLicense(
                context,
                ActivationManager.License(
                    key = "ULTR-TEST-1234",
                    deviceId = "test-device",
                    expiresAt = System.currentTimeMillis() + 86_400_000L,
                    status = ActivationManager.STATUS_ACTIVE,
                    durationDays = 7,
                    paidAmount = 200
                )
            )

            val controller = Robolectric.buildActivity(MainActivity::class.java)
            val activity = controller.create().get()
            callPrivateMethod(activity, "createScreen")

            setPrivateField(activity, "appInitialized", true)

            // Case 1: Not yet logged in (Stage.IDLE) -> tapping notification / VIEW intent launches portal flow
            val url1 = "https://portal.hotel-wifi.example/auth"
            val viewIntent1 = Intent(Intent.ACTION_VIEW, Uri.parse(url1))
            val handled1 = callPrivateMethod(
                activity,
                "handleIncomingLaunchIntent",
                arrayOf(Intent::class.java),
                viewIntent1
            ) as Boolean
            assertTrue(handled1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(url1, getPrivateField<String>(activity, "activeSessionUrl"))

            // Case 2: Already logged in (netWorkingVerified = true, Stage.FINISHED) -> tapping another portal notification still opens ULTRA LOADER for that URL
            val stageEnumClass = Class.forName("com.apumsw1.ultraloader.MainActivity\$Stage")
            val finishedStage = stageEnumClass.enumConstants!!.first { (it as Enum<*>).name == "FINISHED" }
            setPrivateField(activity, "stage", finishedStage)
            setPrivateField(activity, "netWorkingVerified", true)

            val url2 = "https://portal.cafe-wifi.example/status"
            val viewIntent2 = Intent(Intent.ACTION_VIEW, Uri.parse(url2))
            val handled2 = callPrivateMethod(
                activity,
                "handleIncomingLaunchIntent",
                arrayOf(Intent::class.java),
                viewIntent2
            ) as Boolean
            assertTrue(handled2)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(url2, getPrivateField<String>(activity, "activeSessionUrl"))

            controller.destroy()
        } finally {
            ActivationManager.clearAllLicensesForTesting(context)
            ActivationManager.resetCipherForProduction()
        }
    }

    private fun findTextViewWithExactText(view: View, text: String): TextView? {
        if (view is TextView && view.text?.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextViewWithExactText(view.getChildAt(i), text)
                if (found != null) return found
            }
        }
        return null
    }

    private fun collectAllTextViews(view: View): List<TextView> {
        val out = mutableListOf<TextView>()
        if (view is TextView) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                out.addAll(collectAllTextViews(view.getChildAt(i)))
            }
        }
        return out
    }

    private fun callPrivateMethod(
        target: Any,
        name: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        vararg args: Any?
    ): Any? {
        val m: Method = target.javaClass.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(target: Any, name: String): T {
        val f: Field = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(target) as T
    }

    private fun getPrivateBooleanField(target: Any, name: String): Boolean {
        val f: Field = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.getBoolean(target)
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val f: Field = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.set(target, value)
    }
}
