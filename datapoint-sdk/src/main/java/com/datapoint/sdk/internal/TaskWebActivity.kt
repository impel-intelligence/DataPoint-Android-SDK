package com.datapoint.sdk.internal

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.datapoint.sdk.DataPointSDK
import com.datapoint.sdk.R
import java.lang.ref.WeakReference

/**
 * Full-screen activity that hosts the task WebView.
 *
 * Launched internally by [DataPointSDK.showTasks]; never instantiated by consumers.
 * Handles:
 * - WebView setup with JS bridges & cookies
 * - Network loss / restoration with auto-reload
 * - Back-press navigation inside the WebView
 * - Session-expiry re-initialization flow
 * - Proper cleanup in [onDestroy]
 */
class TaskWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorContainer: FrameLayout
    private lateinit var errorTextView: TextView

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var pendingUrl: String? = null
    private var hasReloadedOnConnectionRestore = false
    private var audioVolumeHelper: WebViewAudioVolumeHelper? = null

    // ── Lifecycle ───────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val url = intent.getStringExtra(SdkConstants.EXTRA_TASK_URL)
        if (url.isNullOrBlank()) {
            DataPointLogger.e("No task URL provided – finishing")
            finish()
            return
        }
        pendingUrl = url

        // Register with the SDK so closeTasks() can reach us
        DataPointSDK.onActivityCreated(WeakReference(this))

        buildLayout()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupBackPress()
        setupWebView(url)
        setupNetworkCallback()
        loadUrl(url)
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Unregister network callback
        networkCallback?.let { cb ->
            try { connectivityManager?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null
        connectivityManager = null

        // Stop audio observer
        audioVolumeHelper?.stopObserving()
        audioVolumeHelper = null

        // Remove JS interfaces before destroying WebView
        try {
            webView.removeJavascriptInterface(SdkConstants.JS_BRIDGE_TASK)
            webView.removeJavascriptInterface(SdkConstants.JS_BRIDGE_APP)
            webView.removeJavascriptInterface(SdkConstants.JS_BRIDGE_AUDIO)
        } catch (_: Exception) {}

        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()

        // Tell the SDK this activity is gone
        DataPointSDK.onActivityDestroyed()

        super.onDestroy()
    }

    // ── Layout ──────────────────────────────────────────────────────────

    private fun buildLayout() {
        val root = FrameLayout(this)

        webView = WebView(this)

        // Error overlay
        errorContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }

        errorTextView = TextView(this).apply {
            text = context.getString(R.string.datapoint_no_internet)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }

        errorContainer.addView(
            errorTextView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            errorContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(root)
    }

    // ── WebView setup ───────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    DataPointLogger.d("WebView error: ${error?.description}")
                    showErrorUI()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host ?: return true
                if (isTrustedHost(host)) return false
                DataPointLogger.d("Blocked navigation to untrusted host: $host")
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@TaskWebActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        result?.confirm()
                        dialog.dismiss()
                    }
                    .setOnCancelListener { result?.cancel() }
                    .create()
                    .show()
                return true
            }
        }

        // ── JS bridges ──────────────────────────────────────────────────

        webView.addJavascriptInterface(
            TaskJsBridge(
                onTaskCompleted = ::onTaskCompleted,
                onWatchAd = ::onWatchAd,
                onWindowClose = ::onWindowClose,
                onSessionExpired = ::onSessionExpired
            ),
            SdkConstants.JS_BRIDGE_TASK
        )

        val sessionToken = intent.getStringExtra(SdkConstants.EXTRA_SESSION_TOKEN)
        val userId = intent.getStringExtra(SdkConstants.EXTRA_USER_ID)

        webView.addJavascriptInterface(
            WebAppInterface(
                sessionToken = sessionToken,
                userId = userId,
                sdkVersion = SdkConstants.SDK_VERSION,
                platform = SdkConstants.PLATFORM,
                environment = DataPointSDK.currentEnvironment.name
            ),
            SdkConstants.JS_BRIDGE_APP
        )

        // Audio volume helper
        audioVolumeHelper = WebViewAudioVolumeHelper.attach(webView)

        // ── Cookies ─────────────────────────────────────────────────────

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(url, "session_token=$sessionToken")
            setCookie(url, "user_id=$userId")
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    // ── Trusted host check ──────────────────────────────────────────────

    private fun isTrustedHost(host: String): Boolean {
        return SdkConstants.TRUSTED_HOSTS.any { host.endsWith(it) }
    }

    // ── Network monitoring ──────────────────────────────────────────────

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isInternetAvailable()) {
                    runOnUiThread { onConnectivityRestored() }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isInternetAvailable()) showErrorUI()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network, caps: NetworkCapabilities
            ) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                runOnUiThread {
                    if (hasInternet) onConnectivityRestored() else showErrorUI()
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun onConnectivityRestored() {
        if (errorContainer.isVisible && !hasReloadedOnConnectionRestore) {
            hasReloadedOnConnectionRestore = true
            hideErrorUI()
            pendingUrl?.let { url ->
                DataPointLogger.d("Reloading WebView after connection restored")
                webView.loadUrl(url)
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = connectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── Error UI ────────────────────────────────────────────────────────

    private fun showErrorUI() {
        errorContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE
        hasReloadedOnConnectionRestore = false
    }

    private fun hideErrorUI() {
        errorContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    // ── URL loading ─────────────────────────────────────────────────────

    private fun loadUrl(url: String) {
        if (isInternetAvailable()) {
            DataPointLogger.d("Loading URL: $url")
            webView.loadUrl(url)
            hideErrorUI()
        } else {
            DataPointLogger.d("No internet – showing error UI")
            showErrorUI()
        }
    }

    // ── JS bridge callbacks ─────────────────────────────────────────────

    private fun onTaskCompleted(payload: String?) {
        DataPointLogger.d("Task completed, payload=$payload")
        DataPointSDK.notifyTaskCompleted(payload)
    }

    private fun onWatchAd() {
        DataPointLogger.d("Watch-ad requested")
        DataPointSDK.notifyAdRequested()
        finishIfNotAlready()
    }

    private fun onWindowClose() {
        DataPointLogger.d("Window close from JS")
        DataPointSDK.notifyClosed()
        finishIfNotAlready()
    }

    private fun onSessionExpired() {
        DataPointLogger.d("Session expired from JS – re-initializing")
        DataPointSDK.handleSessionExpired(this)
    }

    // ── Back press ──────────────────────────────────────────────────────

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    DataPointLogger.d("WebView going back")
                    webView.goBack()
                } else {
                    DataPointLogger.d("Back press – closing task screen")
                    DataPointSDK.notifyClosed()
                    finish()
                }
            }
        })
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun finishIfNotAlready() {
        if (!isFinishing) finish()
    }
}
