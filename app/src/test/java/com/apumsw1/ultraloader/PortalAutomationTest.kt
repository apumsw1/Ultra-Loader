package com.apumsw1.ultraloader

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
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

        // Requirement 5: Hide ONLY the 4 specified status strings
        val hiddenStatuses = listOf(
            "LOGIN IN PROGRESS",
            "LOGOUT IN PROGRESS",
            "LOGIN PAGE OPENING",
            "LOGOUT PAGE DETECTED"
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

        // Other process statuses MUST remain visible
        val visibleStatuses = listOf(
            "ORIGINAL PORTAL URL DETECTED",
            "PORTAL LOGIN VERIFIED — DETECTING LOGOUT",
            "VPN CONNECTED — WAITING 3S FOR PORTAL LOGOUT",
            "PORTAL LOGOUT SUCCESS",
            "REFRESHING GOOGLE (1)..."
        )
        for (visible in visibleStatuses) {
            callPrivateMethod(activity, "workflowStatus", arrayOf(String::class.java), visible)
            assertEquals("● $visible", statusView.text.toString())
        }

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
