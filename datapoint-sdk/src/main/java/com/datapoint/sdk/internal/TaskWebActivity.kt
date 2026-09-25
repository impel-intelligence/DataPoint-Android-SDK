package com.datapoint.sdk.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.datapoint.sdk.DataPoint
import com.datapoint.sdk.R
import java.lang.ref.WeakReference

/**
 * Full-screen activity that hosts the task WebView.
 *
 * Launched internally by [DataPoint.showTasks]; never instantiated by consumers.
 * Handles:
 * - WebView setup with JS bridges & cookies
 * - Network loss / restoration with auto-reload
 * - Back-press navigation inside the WebView
 * - Session-expiry re-initialization flow
 * - Proper cleanup in [onDestroy]
 */
class TaskWebActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var errorContainer: FrameLayout
    private lateinit var errorTextView: TextView

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private var pendingUrl: String? = null
    private var hasReloadedOnConnectionRestore = false
    private var audioVolumeHelper: WebViewAudioVolumeHelper? = null
    private var webAppInterface: WebAppInterface? = null

    /** Single-use WebView that captures the destination of a `target="_blank"` link. */
    private var relayWebView: WebView? = null

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
        DataPoint.onActivityCreated(WeakReference(this))

        buildLayout()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        discardRelayWebView()

        // Remove JS interfaces before destroying WebView
        try {
            webView.apply {
                removeJavascriptInterface(SdkConstants.JS_BRIDGE_TASK)
                removeJavascriptInterface(SdkConstants.JS_BRIDGE_APP)
                removeJavascriptInterface(SdkConstants.JS_BRIDGE_AUDIO)
            }
        } catch (_: Exception) {}

        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()

        // Tell the SDK this activity is gone
        DataPoint.onActivityDestroyed()

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
            // target="_blank" links arrive as window requests instead of normal navigations.
            setSupportMultipleWindows(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    DataPointLogger.d(
                        "WebView error: ${LogSanitizer.safeErrorSnippet(error?.description?.toString())}"
                    )
                    showErrorUI()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url
                // Subframes (custom creatives) may only load trusted hosts. Anything else is
                // dropped silently — a frame must never be able to launch a browser.
                if (request != null && !request.isForMainFrame) {
                    return uri == null || !isTrustedUrl(uri)
                }
                return handleNavigation(uri)
            }

            /**
             * API 23 never calls the [WebResourceRequest] overload above — it only calls this
             * deprecated one, and the base implementation would let every navigation through.
             * Both must be overridden while minSdk is below 24.
             */
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleNavigation(url?.let { runCatching { Uri.parse(it) }.getOrNull() })
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                // Only a tap may open a browser; scripted window.open on load is ignored.
                if (!isUserGesture) {
                    DataPointLogger.d("Ignored window request without user gesture")
                    return false
                }

                // The destination of a target="_blank" link is only known once the new window
                // starts navigating, so hand the page a throwaway WebView, read the URL from its
                // first navigation, route it to a browser, and discard it.
                // A previous window that never navigated would otherwise linger.
                discardRelayWebView()

                val relay = WebView(this@TaskWebActivity)
                relayWebView = relay
                relay.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        relayView: WebView?, request: WebResourceRequest?
                    ): Boolean = handleBlankTarget(request?.url)

                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(
                        relayView: WebView?, url: String?
                    ): Boolean =
                        handleBlankTarget(url?.let { runCatching { Uri.parse(it) }.getOrNull() })

                    /** Routes the destination, then drops the relay — it is single-use. */
                    private fun handleBlankTarget(uri: Uri?): Boolean {
                        uri?.let {
                            // A _blank link back to our own task wall belongs in the main WebView.
                            if (isTrustedUrl(it)) webView.loadUrl(it.toString())
                            else routeOutsideWebView(it)
                        }
                        webView.post { discardRelayWebView() }
                        return true
                    }
                }

                transport.webView = relay
                resultMsg.sendToTarget()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage ?: return false
                val text = "[WebConsole] ${msg.sourceId()}:${msg.lineNumber()} – ${msg.message()}"
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> DataPointLogger.e(text)
                    ConsoleMessage.MessageLevel.WARNING -> DataPointLogger.w(text)
                    else -> DataPointLogger.d(text)
                }
                return true
            }

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
                onNoTaskAvailable = ::onNoTaskAvailable,
                onWindowClose = ::onWindowClose,
                onSessionExpired = ::onSessionExpired,
                onOpenUrl = ::onOpenUrl
            ),
            SdkConstants.JS_BRIDGE_TASK
        )

        val sessionToken = intent.getStringExtra(SdkConstants.EXTRA_SESSION_TOKEN)
        val userId = intent.getStringExtra(SdkConstants.EXTRA_USER_ID)
        val appId = intent.getStringExtra(SdkConstants.EXTRA_APP_ID) ?: packageName

        val appInterface = WebAppInterface(
            sessionToken = sessionToken,
            userId = userId,
            sdkVersion = SdkConstants.SDK_VERSION,
            platform = SdkConstants.PLATFORM,
            environment = DataPoint.currentEnvironment.name,
            apiKey = DataPoint.currentApiKey,
            appId = appId
        )
        webAppInterface = appInterface

        webView.addJavascriptInterface(appInterface, SdkConstants.JS_BRIDGE_APP)

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

    private fun isTrustedHost(host: String?): Boolean = UrlPolicy.isTrustedHost(host)

    /**
     * Decides where a navigation goes. Returns `true` when the WebView must not load it —
     * either it was routed to a browser, or the URL was unusable and is dropped.
     */
    private fun handleNavigation(uri: Uri?): Boolean {
        if (uri == null) return true
        if (isTrustedUrl(uri)) return false
        routeOutsideWebView(uri)
        return true
    }

    /** A task-wall page that should keep rendering inside the WebView. */
    private fun isTrustedUrl(uri: Uri): Boolean =
        BrowserLauncher.isWebScheme(uri.scheme) && isTrustedHost(uri.host)

    /**
     * Sends a navigation the WebView must not handle to a browser: web links open in an in-app
     * Custom Tab, other schemes (mailto:, tel:, market:, intent:, …) go to their handler app.
     */
    private fun routeOutsideWebView(uri: Uri) {
        val mode = if (BrowserLauncher.isWebScheme(uri.scheme)) {
            BrowserLauncher.OpenMode.IN_APP
        } else {
            BrowserLauncher.OpenMode.EXTERNAL
        }
        openInBrowser(uri.toString(), mode)
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

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
            DataPointLogger.d("Loading URL: ${LogSanitizer.urlForLog(url)}")
            webView.loadUrl(url)
            hideErrorUI()
        } else {
            DataPointLogger.d("No internet – showing error UI")
            showErrorUI()
        }
    }

    // ── JS bridge callbacks ─────────────────────────────────────────────

    private fun onTaskCompleted(payload: String?) {
        DataPointLogger.d(
            "Task completed (payload omitted, len=${payload?.length ?: 0})"
        )
        DataPoint.notifyTaskCompleted(payload)
    }

    private fun onWatchAd() {
        DataPointLogger.d("Watch-ad requested")
        DataPoint.notifyAdRequested()
        finishIfNotAlready()
    }

    private fun onWindowClose() {
        DataPointLogger.d("Window close from JS")
        DataPoint.notifyClosed()
        finishIfNotAlready()
    }

    private fun onNoTaskAvailable() {
        DataPointLogger.d("No task available from JS")
        DataPoint.notifyNoTaskAvailable()
        finishIfNotAlready()
    }

    /**
     * Bridge entry point for `DataPointTask.openExternalUrl(url[, mode])`. The task screen stays open
     * so the user returns to it when the browser closes.
     */
    private fun onOpenUrl(url: String, mode: String?) {
        openInBrowser(url, BrowserLauncher.OpenMode.from(mode))
    }

    private fun onSessionExpired() {
        DataPointLogger.d("Session expired from JS – re-initializing in background")
        DataPoint.handleSessionExpired(this) { newToken ->
            runOnUiThread {
                webAppInterface?.updateToken(newToken)

                pendingUrl?.let { url ->
                    CookieManager.getInstance().setCookie(url, "session_token=$newToken")
                }

                DataPointLogger.d("Delivering new token to WebView via onNewTokenGenerate")
                webView.evaluateJavascript("onNewTokenGenerate('$newToken')", null)
            }
        }
    }

    // ── Back press ──────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            DataPointLogger.d("WebView going back")
            webView.goBack()
        } else {
            DataPointLogger.d("Back press – closing task screen")
            DataPoint.notifyClosed()
            super.onBackPressed()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun finishIfNotAlready() {
        if (!isFinishing) finish()
    }

    private fun discardRelayWebView() {
        relayWebView?.let { relay ->
            relayWebView = null
            (relay.parent as? ViewGroup)?.removeView(relay)
            relay.destroy()
        }
    }

    private fun openInBrowser(
        url: String,
        mode: BrowserLauncher.OpenMode = BrowserLauncher.OpenMode.IN_APP
    ) {
        if (isFinishing || isDestroyed) return
        if (!BrowserLauncher.open(this, url, mode)) {
            DataPointLogger.w("Could not open ${LogSanitizer.urlForLog(url)}")
        }
    }
}
