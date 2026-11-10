package com.apumsw1.ultraloader

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var webView: WebView

    private val backgroundColor = Color.rgb(10, 14, 28)
    private val accentColor = Color.rgb(0, 220, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
        checkInternet()
    }

    private fun createInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 20, 18, 10)
            setBackgroundColor(backgroundColor)
        }

        val title = TextView(this).apply {
            text = "ULTRA LOADER"
            textSize = 27f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(5, 15, 5, 5)
        }

        val subtitle = TextView(this).apply {
            text = "CYBER ACCESS CONTROL"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(5, 0, 5, 18)
        }

        statusText = TextView(this).apply {
            text = "STATUS: READY"
            textSize = 14f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
            setPadding(10, 12, 10, 12)
        }

        urlInput = EditText(this).apply {
            hint = "Enter your Peplink Portal URL"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.rgb(30, 38, 58))
        }

        val openButton = makeButton("OPEN PORTAL")
        openButton.setOnClickListener {
            openPortal()
        }

        val vpnButton = makeButton("OPEN VPN SETTINGS")
        vpnButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "VPN settings unavailable",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val checkButton = makeButton("CHECK INTERNET")
        checkButton.setOnClickListener {
            checkInternet()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    statusText.text = "STATUS: LOADING PORTAL..."
                    statusText.setTextColor(accentColor)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    statusText.text = "STATUS: PORTAL LOADED"
                    statusText.setTextColor(Color.GREEN)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        statusText.text = "STATUS: PAGE LOAD ERROR"
                        statusText.setTextColor(Color.RED)
                    }
                }
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)

        root.addView(urlInput)

        root.addView(openButton)
        root.addView(vpnButton)
        root.addView(checkButton)

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.BLACK)
            setBackgroundColor(accentColor)

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            params.setMargins(0, 7, 0, 7)
            layoutParams = params
        }
    }

    private fun openPortal() {

        var url = urlInput.text.toString().trim()

        if (url.isEmpty()) {
            urlInput.error = "Enter portal URL"
            return
        }

        if (!url.startsWith("https://") &&
            !url.startsWith("http://")) {
            url = "https://$url"
        }

        val uri = try {
            android.net.Uri.parse(url)
        } catch (e: Exception) {
            null
        }

        if (uri == null ||
            (uri.scheme != "https" && uri.scheme != "http") ||
            uri.host.isNullOrBlank()) {

            Toast.makeText(
                this,
                "Invalid portal URL",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        statusText.text = "STATUS: OPENING PORTAL..."
        webView.loadUrl(uri.toString())
    }

    private fun checkInternet() {

        val manager =
            getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        val network = manager.activeNetwork
        val capabilities =
            manager.getNetworkCapabilities(network)

        val connected = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) == true

        val validated = capabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED
        ) == true

        if (connected && validated) {
            statusText.text = "STATUS: INTERNET CONNECTED"
            statusText.setTextColor(Color.GREEN)
        } else {
            statusText.text = "STATUS: INTERNET NOT VERIFIED"
            statusText.setTextColor(Color.RED)
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}