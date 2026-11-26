package com.apumsw1.ultraloader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object NetworkStatusManager {
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
        _captivePortalDetected.postValue(detected)
        if (!url.isNullOrBlank()) {
            _detectedPortalUrl.postValue(url)
        }
        if (!detected) {
            _portalLoginDetected.postValue(false)
            _viewWebsiteDetected.postValue(false)
            _latestDetectionType.postValue(null)
            _detectedPortalUrl.postValue(null)
        }
    }

    fun updateDetectionType(type: String, url: String? = null) {
        _captivePortalDetected.postValue(true)
        _latestDetectionType.postValue(type)
        if (type == WifiNotificationListener.TYPE_PORTAL_LOGIN) {
            _portalLoginDetected.postValue(true)
        } else if (type == WifiNotificationListener.TYPE_VIEW_WEBSITE) {
            _viewWebsiteDetected.postValue(true)
        }
        if (!url.isNullOrBlank()) {
            _detectedPortalUrl.postValue(url)
        }
    }

    fun updateVpnConnectionState(connected: Boolean) {
        _vpnConnected.postValue(connected)
    }
}
