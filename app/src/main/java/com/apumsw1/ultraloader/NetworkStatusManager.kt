package com.apumsw1.ultraloader

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object NetworkStatusManager {
    private const val TAG = "UltraLoaderNetState"

    private val _captivePortalDetected = MutableLiveData(false)
    val captivePortalDetected: LiveData<Boolean> get() = _captivePortalDetected

    private val _portalLoginDetected = MutableLiveData(false)
    val portalLoginDetected: LiveData<Boolean> get() = _portalLoginDetected

    private val _viewWebsiteDetected = MutableLiveData(false)
    val viewWebsiteDetected: LiveData<Boolean> get() = _viewWebsiteDetected

    private val _vpnConnected = MutableLiveData(false)
    val vpnConnected: LiveData<Boolean> get() = _vpnConnected

    private val _detectedPortalUrl = MutableLiveData<String?>()
    val detectedPortalUrl: LiveData<String?> get() = _detectedPortalUrl

    private val _latestDetectionType = MutableLiveData<String?>()
    val latestDetectionType: LiveData<String?> get() = _latestDetectionType

    fun updateCaptivePortalState(detected: Boolean, url: String? = null) {
        if (!detected) {
            Log.d(TAG, "Resetting captive portal state")
            _portalLoginDetected.postValue(false)
            _viewWebsiteDetected.postValue(false)
            _latestDetectionType.postValue(null)
            _detectedPortalUrl.postValue(null)
            _captivePortalDetected.postValue(false)
            return
        }

        if (!url.isNullOrBlank() && PortalNetworkHelper.isUsablePortalUrl(url)) {
            _detectedPortalUrl.postValue(url)
        }
        _captivePortalDetected.postValue(true)
    }

    fun updateDetectionType(type: String, url: String? = null) {
        val usableUrl = url?.trim()?.takeIf { PortalNetworkHelper.isUsablePortalUrl(it) }
        Log.d(TAG, "updateDetectionType type=$type url=${usableUrl ?: "none"}")
        if (usableUrl != null) {
            _detectedPortalUrl.postValue(usableUrl)
        }
        _latestDetectionType.postValue(type)
        if (type == WifiNotificationListener.TYPE_PORTAL_LOGIN) {
            _portalLoginDetected.postValue(true)
        } else if (type == WifiNotificationListener.TYPE_VIEW_WEBSITE) {
            _viewWebsiteDetected.postValue(true)
        }
        _captivePortalDetected.postValue(true)
    }

    fun resetPortalState() {
        updateCaptivePortalState(false, null)
    }

    fun updateVpnConnectionState(connected: Boolean) {
        if (_vpnConnected.value != connected) {
            Log.d(TAG, "VPN state changed: connected=$connected")
        }
        _vpnConnected.postValue(connected)
    }
}
