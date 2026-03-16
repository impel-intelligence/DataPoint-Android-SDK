@file:Suppress("unused")

package com.datapoint.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.datapoint.sdk.callbacks.DataPointListener
import com.datapoint.sdk.callbacks.ErrorCode
import com.datapoint.sdk.callbacks.InitCallback
import com.datapoint.sdk.internal.DataPointApi
import com.datapoint.sdk.internal.DataPointLogger
import com.datapoint.sdk.internal.DataPointPreferences
import com.datapoint.sdk.internal.SdkConstants
import com.datapoint.sdk.internal.TaskWebActivity
import com.datapoint.sdk.models.Environment
import com.datapoint.sdk.models.Reward
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Main entry point for the DataPoint SDK.
 *
 * ```kotlin
 * // 1. Initialize (once, in Application.onCreate or Activity)
 * DataPointSDK.initialize(
 *     context     = applicationContext,
 *     appId       = "YOUR_APP_ID",
 *     userId      = "user_123",           // optional
 *     environment = Environment.PRODUCTION,
 *     callback    = object : InitCallback { … }
 * )
 *
 * // 2. Set listener
 * DataPointSDK.setListener(object : DataPointListener { … })
 *
 * // 3. Show task wall
 * DataPointSDK.showTasks(context)
 *
 * // 4. Programmatically close (optional)
 * DataPointSDK.closeTasks()
 * ```
 */
object DataPointSDK {

    // ── State machine ───────────────────────────────────────────────────

    private enum class State { UNINITIALIZED, INITIALIZING, INITIALIZED, FAILED }

    private val state = AtomicReference(State.UNINITIALIZED)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // ── Configuration (set during initialize) ───────────────────────────

    private var applicationContext: Context? = null
    private var preferences: DataPointPreferences? = null
    @Volatile
    internal var environment: Environment = Environment.PRODUCTION
    private var appId: String? = null
    private var userId: String? = null

    // ── Listener & activity reference ───────────────────────────────────

    @Volatile
    private var listener: DataPointListener? = null

    /** Weak reference to the active [TaskWebActivity]. */
    @Volatile
    internal var activeActivityRef: WeakReference<Activity>? = null

    /**
     * `true` once a terminal callback (reward / ad / close / error) has been
     * dispatched for the current task session. Prevents duplicate `onClosed()`
     * calls when the activity is destroyed.
     */
    @Volatile
    private var isCallbackDispatched = false

    // ── Public: logging ─────────────────────────────────────────────────

    /** Enable / disable SDK debug logging (default: `false`). */
    var isLoggingEnabled: Boolean
        get() = DataPointLogger.isEnabled
        set(value) {
            DataPointLogger.isEnabled = value
        }

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Initialize the SDK. Must be called **once** before [showTasks].
     *
     * In [Environment.PRODUCTION] this performs a `POST /initialize` call to
     * register the app and obtain a session token. In [Environment.SANDBOX]
     * it skips the network call and uses mock data.
     *
     * @param context     Application or Activity context.
     * @param appId       API key provided by DataPoint.
     * @param userId      Optional user identifier from the host app.
     * @param environment [Environment.PRODUCTION] or [Environment.SANDBOX].
     * @param callback    Optional callback for init result.
     */
    fun initialize(
        context: Context,
        appId: String,
        userId: String? = null,
        environment: Environment = Environment.PRODUCTION,
        callback: InitCallback? = null
    ) {
        DataPointLogger.d("initialize() appId=$appId env=$environment userId=$userId")

        // ── Validate input ──────────────────────────────────────────────
        if (appId.isBlank()) {
            DataPointLogger.e("initialize() failed: appId is blank")
            postOnMain { callback?.onError("appId cannot be empty", ErrorCode.INVALID_CONFIGURATION) }
            return
        }

        // ── Store config ────────────────────────────────────────────────
        val appCtx = context.applicationContext
        this.applicationContext = appCtx
        this.environment = environment
        this.appId = appId
        this.userId = userId

        val prefs = DataPointPreferences(appCtx)
        this.preferences = prefs

        // ── Sandbox: skip network ───────────────────────────────────────
        if (environment == Environment.SANDBOX) {
            handleSandboxInit(prefs, callback)
            return
        }

        // ── Prevent redundant concurrent calls ──────────────────────────
        if (!moveToInitializing()) {
            DataPointLogger.w("initialize() ignored – already initializing")
            return
        }

        // ── Reuse valid session ─────────────────────────────────────────
        if (prefs.isSessionValid() && prefs.appId == appId) {
            DataPointLogger.d("Existing session is still valid – reusing")
            prefs.userId = userId ?: prefs.userId
            state.set(State.INITIALIZED)
            postOnMain { callback?.onSuccess() }
            return
        }

        // ── Production: call backend ────────────────────────────────────
//        val packageName = appCtx.packageName
        val packageName = "com.tryimpel.dippy"
        val deviceId = prefs.deviceId
        val sha256 = getSigningCertSha256(appCtx)

        executor.execute {
            // ── Fetch Google Advertising ID (best-effort) ────────────
            val advertisingId: String? = try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(appCtx)
                if (adInfo.isLimitAdTrackingEnabled) null else adInfo.id
            } catch (e: Exception) {
                DataPointLogger.w("Could not retrieve Advertising ID: ${e.message}")
                null
            }
            DataPointLogger.d("Advertising ID: ${advertisingId ?: "unavailable"}")

            val result = DataPointApi.validate(
                baseUrl = SdkConstants.PRODUCTION_BASE_URL,
                appId = appId,
                userId = userId,
                deviceId = deviceId,
                packageName = packageName,
                sdkVersion = SdkConstants.SDK_VERSION,
                sha256Cert = sha256,
                environment = environment.name,
                advertisingId = advertisingId
            )

            when (result) {
                is DataPointApi.ApiResult.Success -> {
                    prefs.sessionToken = result.data.sessionToken
                    prefs.sessionExpiry =
                        System.currentTimeMillis() + (result.data.expiresIn * 1000)
                    prefs.userId = result.data.userId
                    prefs.appId = appId
                    state.set(State.INITIALIZED)
                    DataPointLogger.d("Initialization successful")
                    postOnMain { callback?.onSuccess() }
                }

                is DataPointApi.ApiResult.Error -> {
                    state.set(State.FAILED)
                    DataPointLogger.e("Initialization failed: ${result.message}")
                    postOnMain {
                        callback?.onError(result.message, ErrorCode.INITIALIZATION_FAILED)
                    }
                }
            }
        }
    }

    /**
     * Register a listener for task events.
     * Pass `null` to remove.
     */
    fun setListener(listener: DataPointListener?) {
        this.listener = listener
    }

    /**
     * Open the task wall in a full-screen WebView.
     *
     * Requires a prior successful [initialize] call.
     *
     * @param context Activity or Application context.
     */
    fun showTasks(context: Context) {
        DataPointLogger.d("showTasks()")

        if (state.get() != State.INITIALIZED) {
            DataPointLogger.e("showTasks() – SDK not initialized")
            listener?.onError(
                "SDK not initialized. Call initialize() first.",
                ErrorCode.SDK_NOT_INITIALIZED
            )
            return
        }

        if (activeActivityRef?.get() != null) {
            DataPointLogger.w("showTasks() – task screen already visible")
            listener?.onError("Task screen is already showing", ErrorCode.TASK_ALREADY_SHOWING)
            return
        }

        val prefs = preferences ?: run {
            listener?.onError("SDK not initialized", ErrorCode.SDK_NOT_INITIALIZED)
            return
        }

        // Auto-reinitialize if token expired
        if (environment == Environment.PRODUCTION && !prefs.isSessionValid()) {
            DataPointLogger.d("Session expired – re-initializing before showing tasks")
            reinitializeAndShow(context)
            return
        }

        launchTaskActivity(context)
    }

    /**
     * Programmatically close the task screen if it is currently visible.
     */
    fun closeTasks() {
        DataPointLogger.d("closeTasks()")
        val activity = activeActivityRef?.get() ?: return
        notifyClosed()
        postOnMain { activity.finish() }
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNAL (called by TaskWebActivity)
    // ════════════════════════════════════════════════════════════════════

    internal val currentEnvironment: Environment
        get() = environment

    internal fun onActivityCreated(ref: WeakReference<Activity>) {
        activeActivityRef = ref
        isCallbackDispatched = false
    }

    internal fun onActivityDestroyed() {
        activeActivityRef = null
        if (!isCallbackDispatched) {
            // Activity was destroyed without a JS bridge callback – treat as close
            postOnMain { listener?.onClosed() }
        }
        isCallbackDispatched = false
    }

    internal fun notifyTaskCompleted(payload: String?) {
        isCallbackDispatched = true
        postOnMain { listener?.onTaskCompleted(payload) }
    }

    internal fun notifyAdRequested() {
        isCallbackDispatched = true
        postOnMain { listener?.onAdRequested() }
    }

    internal fun notifyClosed() {
        isCallbackDispatched = true
        postOnMain { listener?.onClosed() }
    }

    internal fun notifyError(message: String, code: Int) {
        isCallbackDispatched = true
        postOnMain { listener?.onError(message, code) }
    }

    /**
     * Called by the WebView when a 401 SESSION_EXPIRED is received.
     * Closes the current activity, re-initializes, and re-shows tasks.
     */
    internal fun handleSessionExpired(activity: Activity) {
        isCallbackDispatched = true // prevent duplicate onClosed()
        activity.finish()
        activeActivityRef = null

        val ctx = applicationContext ?: return
        val aid = appId ?: return

        DataPointLogger.d("Session expired – starting re-initialization")

        // Force fresh session
        preferences?.clearSession()

        initialize(ctx, aid, userId, environment, object : InitCallback {
            override fun onSuccess() {
                DataPointLogger.d("Re-init successful – re-showing tasks")
                launchTaskActivity(ctx)
            }

            override fun onError(message: String, code: Int) {
                DataPointLogger.e("Re-init after session expiry failed: $message")
                listener?.onError(
                    "Session expired and re-initialization failed: $message",
                    ErrorCode.SESSION_EXPIRED
                )
            }
        })
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE
    // ════════════════════════════════════════════════════════════════════

    private fun handleSandboxInit(prefs: DataPointPreferences, callback: InitCallback?) {
        DataPointLogger.d("Sandbox mode – using mock session")
        prefs.sessionToken = "sandbox_token_${System.currentTimeMillis()}"
        prefs.sessionExpiry = System.currentTimeMillis() + 86_400_000L // 24 h
        prefs.userId = userId ?: "sandbox_user_${prefs.deviceId.take(8)}"
        prefs.appId = appId
        state.set(State.INITIALIZED)
        postOnMain { callback?.onSuccess() }
    }

    /**
     * Tries to transition from any "idle" state to [State.INITIALIZING].
     * Returns `false` if the SDK is already in [State.INITIALIZING].
     */
    private fun moveToInitializing(): Boolean {
        while (true) {
            val current = state.get()
            if (current == State.INITIALIZING) return false
            if (state.compareAndSet(current, State.INITIALIZING)) return true
        }
    }

    private fun reinitializeAndShow(context: Context) {
        val ctx = applicationContext ?: context.applicationContext
        val aid = appId ?: return

//        preferences?.clearSession()

        initialize(ctx, aid, userId, environment, object : InitCallback {
            override fun onSuccess() {
                launchTaskActivity(context)
            }

            override fun onError(message: String, code: Int) {
                listener?.onError(
                    "Session expired and re-initialization failed: $message",
                    ErrorCode.SESSION_EXPIRED
                )
            }
        })
    }

    private fun launchTaskActivity(context: Context) {
        val prefs = preferences ?: return
        val baseTaskUrl = when (environment) {
            Environment.PRODUCTION -> SdkConstants.PRODUCTION_TASK_URL
            Environment.SANDBOX -> SdkConstants.SANDBOX_TASK_URL
        }

        val intent = Intent(context, TaskWebActivity::class.java).apply {
            putExtra(SdkConstants.EXTRA_TASK_URL, baseTaskUrl)
            putExtra(SdkConstants.EXTRA_SESSION_TOKEN, prefs.sessionToken)
            putExtra(SdkConstants.EXTRA_USER_ID, prefs.userId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        DataPointLogger.d("TaskWebActivity launched, url=$baseTaskUrl")
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private fun postOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Attempts to read the SHA-256 fingerprint of the app's signing certificate.
     * Returns `null` on any failure (the field is optional).
     */
    @Suppress("DEPRECATION")
    private fun getSigningCertSha256(context: Context): String? {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            }

            val sig = signatures?.firstOrNull() ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            DataPointLogger.e("Failed to read signing cert SHA-256", e)
            null
        }
    }
}
