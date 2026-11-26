package com.apumsw1.ultraloader

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object NetworkStatusManager {
    private val _captivePortalDetected = MutableLiveData(false)
    val captivePortalDetected: LiveData<Boolean> get() = _captivePortalDetected

    private val _vpnConnected = MutableLiveData(false)
    val vpnConnected: LiveData<Boolean> get() = _vpnConnected

    private val _detectedPortalUrl = MutableLiveData<String?>()
    val detectedPortalUrl: LiveData<String?> get() = _detectedPortalUrl

    fun updateCaptivePortalState(detected: Boolean, url: String? = null) {
        _captivePortalDetected.postValue(detected)
        if (url != null) {
            _detectedPortalUrl.postValue(url)
        }
    }

    fun updateVpnConnectionState(connected: Boolean) {
        _vpnConnected.postValue(connected)
    }
}
