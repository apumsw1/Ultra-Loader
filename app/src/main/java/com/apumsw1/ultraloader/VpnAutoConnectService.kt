package com.apumsw1.ultraloader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class VpnAutoConnectService : AccessibilityService() {

    companion object {
        @Volatile
        private var activeInstance: VpnAutoConnectService? = null

        fun isEnabled(context: Context): Boolean {
            if (activeInstance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val expected = ComponentName(context, VpnAutoConnectService::class.java)
                enabledServices.split(':').any { entry ->
                    entry.equals(expected.flattenToString(), ignoreCase = true) ||
                        entry.equals(expected.flattenToShortString(), ignoreCase = true)
                }
            } catch (_: Exception) {
                false
            }
        }

        fun requestReturnToUltraLoader(context: Context): Boolean {
            val instance = activeInstance ?: return false
            instance.returnToUltraLoader()
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var connectivityManager: ConnectivityManager? = null
    private var vpnCallbackRegistered = false

    private val inspectRunnable = Runnable {
        inspectActiveWindowAndConnect()
    }

    private var returnRetryCount = 0

    private val retryReturnRunnable: Runnable = object : Runnable {
        override fun run() {
            val cm = connectivityManager
                ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                    connectivityManager = it
                }
            if (!VpnAutomationBridge.appInForeground &&
                !VpnAutomationBridge.isArmed() &&
                PortalNetworkHelper.isVpnConnected(cm)
            ) {
                launchUltraLoaderForeground()
                if (returnRetryCount < 3) {
                    returnRetryCount++
                    handler.postDelayed(this, 650L)
                }
            }
        }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                if (VpnAutomationBridge.isArmed()) {
                    returnToUltraLoader()
                }
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                handler.post {
                    if (VpnAutomationBridge.isArmed()) {
                        returnToUltraLoader()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        registerVpnCallback()
        if (VpnAutomationBridge.isArmed()) {
            scheduleInspect(200L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!VpnAutomationBridge.isArmed()) return

        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }

        if (PortalNetworkHelper.isVpnConnected(cm)) {
            returnToUltraLoader()
            return
        }

        val eventPkg = event?.packageName?.toString().orEmpty()
        if (eventPkg.isNotBlank() &&
            eventPkg != VpnAutomationBridge.VPN_PACKAGE &&
            eventPkg != VpnAutomationBridge.VPN_DIALOG_PACKAGE
        ) {
            return
        }

        scheduleInspect(220L)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(inspectRunnable)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        handler.removeCallbacksAndMessages(null)
        if (vpnCallbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(vpnCallback)
            } catch (_: Exception) {
            }
            vpnCallbackRegistered = false
        }
        super.onDestroy()
    }

    private fun registerVpnCallback() {
        if (vpnCallbackRegistered) return
        try {
            val cm = connectivityManager
                ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                    connectivityManager = it
                }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, vpnCallback)
            vpnCallbackRegistered = true
        } catch (_: Exception) {
            vpnCallbackRegistered = false
        }
    }

    private fun scheduleInspect(delayMs: Long) {
        handler.removeCallbacks(inspectRunnable)
        handler.postDelayed(inspectRunnable, delayMs)
    }

    private fun inspectActiveWindowAndConnect() {
        if (!VpnAutomationBridge.isArmed()) return

        val cm = connectivityManager
            ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).also {
                connectivityManager = it
            }

        if (PortalNetworkHelper.isVpnConnected(cm)) {
            returnToUltraLoader()
            return
        }

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, allNodes)
            val rootPkg = root.packageName?.toString().orEmpty()

            if (rootPkg == VpnAutomationBridge.VPN_DIALOG_PACKAGE) {
                handleSystemVpnDialog(allNodes)
                return
            }

            if (rootPkg != VpnAutomationBridge.VPN_PACKAGE) {
                return
            }

            // Do not tap again if Thunder VPN already shows connected or connecting state.
            val screenTexts = allNodes.mapNotNull { node ->
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .joinToString(" ")
                    .trim()
                    .lowercase(Locale.ROOT)
                    .takeIf { it.isNotBlank() }
            }

            val alreadyConnectedUi = screenTexts.any { txt ->
                (txt.contains("tap to disconnect") ||
                    txt == "disconnect" ||
                    txt == "connected" ||
                    txt.contains("vpn is on")) &&
                    !txt.contains("not connected") &&
                    !txt.contains("disconnected")
            }
            if (alreadyConnectedUi) {
                if (PortalNetworkHelper.isVpnConnected(cm)) {
                    returnToUltraLoader()
                }
                return
            }

            val connectingUi = screenTexts.any { txt ->
                txt.contains("connecting") ||
                    txt.contains("initializing") ||
                    txt.contains("authenticating") ||
                    txt.contains("establishing")
            }
            if (connectingUi) {
                return
            }

            if (!VpnAutomationBridge.canAttemptClick()) {
                return
            }

            val target = findThunderConnectNode(allNodes) ?: return
            VpnAutomationBridge.recordClickAttempt()
            clickNodeOrBounds(target, allNodes)
        } catch (_: Exception) {
        } finally {
            for (node in allNodes) {
                try {
                    @Suppress("DEPRECATION")
                    node.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (node == null || depth > 24 || out.size > 350) return
        out.add(node)
        val count = node.childCount
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                collectNodes(child, out, depth + 1)
            }
        }
    }

    private fun handleSystemVpnDialog(nodes: MutableList<AccessibilityNodeInfo>) {
        if (!VpnAutomationBridge.canAttemptClick(minIntervalMs = 1000L, maxAttempts = 6)) return

        val okButton = nodes.firstOrNull { node ->
            val id = node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
            val txt = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
                .trim()
                .lowercase(Locale.ROOT)
            node.isEnabled && (
                id == "android:id/button1" ||
                    txt == "ok" ||
                    txt == "allow" ||
                    txt == "always allow" ||
                    txt == "connect"
                )
        } ?: return

        VpnAutomationBridge.recordClickAttempt()
        clickNodeOrBounds(okButton, nodes)
    }

    private fun findThunderConnectNode(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        val rect = Rect()

        for (node in nodes) {
            if (!node.isEnabled || !node.isVisibleToUser) continue
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) continue

            val text = node.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase(Locale.ROOT)
            val viewId = node.viewIdResourceName.orEmpty().trim().lowercase(Locale.ROOT)
            val combined = "$text $desc $viewId"

            if (isExcludedUiElement(combined)) continue
            if (isAlreadyConnectedLabel(text) || isAlreadyConnectedLabel(desc)) continue

            var score = 0
            if (text == "connect" || desc == "connect" ||
                text == "tap to connect" || desc == "tap to connect" ||
                text == "start" || desc == "start" ||
                text == "turn on" || desc == "turn on"
            ) {
                score += 120
            } else if (
                viewId.contains("btn_connect") ||
                viewId.contains("connect_btn") ||
                viewId.contains("iv_connect") ||
                viewId.contains("vpn_connect") ||
                viewId.contains("connect_button") ||
                viewId.contains("tv_connect") ||
                viewId.contains("power_btn") ||
                viewId.contains("start_btn") ||
                (viewId.contains("connect") &&
                    !viewId.contains("disconnect") &&
                    !viewId.contains("connected"))
            ) {
                score += 95
            } else if (
                (text.contains("connect") || desc.contains("connect")) &&
                !text.contains("disconnect") &&
                !desc.contains("disconnect") &&
                !isAlreadyConnectedLabel(text) &&
                !isAlreadyConnectedLabel(desc)
            ) {
                score += 75
            }

            if (score <= 0) continue

            if (node.isClickable || hasClickableAncestor(node)) {
                score += 25
            }

            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }
        }

        return if (bestScore >= 75) bestNode else null
    }

    private fun isAlreadyConnectedLabel(value: String): Boolean {
        if (value.isBlank()) return false
        return value.contains("connected") &&
            !value.contains("not connected") &&
            !value.contains("disconnected")
    }

    private fun isExcludedUiElement(combined: String): Boolean {
        val blockedTerms = listOf(
            "disconnect", "not connected", "disconnected",
            "ad_", "_ad", "ads", "sponsored", "install", "download",
            "premium", "vip", "upgrade", "subscribe", "pro ", "buy", "price",
            "free trial", "watch", "reward", "rate", "share",
            "policy", "privacy", "terms", "exit", "quit",
            "setting", "menu", "server", "location", "choose", "select",
            "smart", "split", "faq", "help", "feedback",
            "close", "dismiss", "cancel"
        )
        return blockedTerms.any { combined.contains(it) }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        val ancestors = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (step in 0 until 4) {
                val p = current?.parent ?: break
                ancestors.add(p)
                if (p.isClickable && p.isEnabled) {
                    return true
                }
                current = p
            }
            return false
        } finally {
            for (anc in ancestors) {
                try {
                    @Suppress("DEPRECATION")
                    anc.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun clickNodeOrBounds(
        node: AccessibilityNodeInfo,
        cleanupList: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable && node.isEnabled) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return
            }
        }

        var current: AccessibilityNodeInfo? = node
        for (step in 0 until 4) {
            val parent = try {
                current?.parent
            } catch (_: Exception) {
                null
            } ?: break
            cleanupList.add(parent)
            if (parent.isClickable && parent.isEnabled) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return
                }
            }
            current = parent
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                dispatchTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
    }

    private fun dispatchTapGesture(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
        }
    }

    private fun returnToUltraLoader() {
        handler.removeCallbacks(inspectRunnable)
        VpnAutomationBridge.disarm()
        if (!VpnAutomationBridge.claimReturnToApp()) return

        returnRetryCount = 0
        launchUltraLoaderForeground()
        handler.removeCallbacks(retryReturnRunnable)
        handler.postDelayed(retryReturnRunnable, 350L)
    }

    private fun launchUltraLoaderForeground() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = VpnAutomationBridge.ACTION_RETURN_FROM_VPN
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
