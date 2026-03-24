@file:Suppress("unused")

package com.datapoint.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.datapoint.sdk.DataPoint.initialize
import com.datapoint.sdk.DataPoint.showTasks
import com.datapoint.sdk.callbacks.DataPointCallback
import com.datapoint.sdk.callbacks.DataPointListener
import com.datapoint.sdk.callbacks.ErrorCode
import com.datapoint.sdk.callbacks.InitCallback
import com.datapoint.sdk.internal.DataPointApi
import com.datapoint.sdk.internal.DataPointLogger
import com.datapoint.sdk.internal.DataPointPreferences
import com.datapoint.sdk.internal.LogSanitizer
import com.datapoint.sdk.internal.DeviceInfoCollector
import com.datapoint.sdk.internal.SdkConstants
import com.datapoint.sdk.internal.TaskWebActivity
import com.datapoint.sdk.callbacks.models.Environment
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.JvmStatic

/**
 * Main entry point for the DataPoint SDK.
 *
 * ```kotlin
 * // 1. Initialize (once, in Application.onCreate or Activity)
 * DataPoint.initialize(
 *     context     = applicationContext,
 *     apiKey      = "YOUR_API_KEY",
 *     userId      = "user_123",           // optional
 *     environment = Environment.PRODUCTION,
 *     callback    = object : InitCallback { … }
 * )
 *
 * // 2. Set listener
 * DataPoint.setListener(object : DataPointListener { … })
 *
 * // 3. Show task wall
 * DataPoint.showTasks(context)
 *
 * // 4. Programmatically close (optional)
 * DataPoint.closeTasks()
 * ```
 */
object DataPoint {

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
    private var apiKey: String? = null
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
    @get:JvmStatic
    @set:JvmStatic
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
     * @param apiKey      API key provided by DataPoint.
     * @param userId      Optional user identifier from the host app.
     * @param environment [Environment.PRODUCTION] or [Environment.SANDBOX].
     * @param callback    Optional callback for init result.
     */
    @JvmStatic
    fun initialize(
        context: Context,
        apiKey: String,
        userId: String? = null,
        environment: Environment = Environment.PRODUCTION,
        callback: InitCallback? = null
    ) {
        DataPointLogger.d(
            "initialize() env=$environment apiKey=${LogSanitizer.secretLength(apiKey)} " +
                "userId=${LogSanitizer.secretLength(userId)}"
        )

        // ── Validate input ──────────────────────────────────────────────
        if (apiKey.isBlank()) {
            DataPointLogger.e("initialize() failed: apiKey is blank")
            postOnMain { callback?.onError("apiKey cannot be empty", ErrorCode.INVALID_CONFIGURATION) }
            return
        }

        // ── Store config ────────────────────────────────────────────────
        val appCtx = context.applicationContext
        this.applicationContext = appCtx
        this.environment = environment
        this.apiKey = apiKey
        this.userId = userId

        val prefs = DataPointPreferences(appCtx)
        this.preferences = prefs

        // ── Prevent redundant concurrent calls ──────────────────────────
        if (!moveToInitializing()) {
            DataPointLogger.w("initialize() ignored – already initializing")
            return
        }

        // ── Reuse valid session ─────────────────────────────────────────
        if (prefs.isSessionValid() && prefs.apiKey == apiKey) {
            DataPointLogger.d("Existing session is still valid – reusing")
            prefs.userId = userId ?: prefs.userId
            state.set(State.INITIALIZED)
            postOnMain { callback?.onSuccess() }
            return
        }

        // ── Production: call backend ────────────────────────────────────
        val deviceId = prefs.deviceId
        val sha256 = getSigningCertSha256(appCtx)
        val installId = prefs.installId
        val androidId = prefs.rawAndroidId

        executor.execute {
            // ── Fetch Google Advertising ID (best-effort) ────────────
            var advertisingId: String? = null
            var limitAdTracking = false
            try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(appCtx)
                limitAdTracking = adInfo.isLimitAdTrackingEnabled
                advertisingId = if (limitAdTracking) null else adInfo.id
            } catch (e: Exception) {
                DataPointLogger.w(
                    "Could not retrieve Advertising ID: ${LogSanitizer.safeErrorSnippet(e.message, 80)}"
                )
            }
            DataPointLogger.d(
                "Advertising ID: " +
                    when {
                        limitAdTracking -> "omitted (limit ad tracking)"
                        advertisingId == null -> "unavailable"
                        else -> "present(len=${advertisingId.length})"
                    }
            )

            // ── Collect device context ───────────────────────────────
            val sdkInfo = DeviceInfoCollector.collectSdkInfo()
            val appInfo = DeviceInfoCollector.collectAppInfo(appCtx, sha256)
            val deviceInfo = DeviceInfoCollector.collectDeviceInfo(appCtx)
            val displayInfo = DeviceInfoCollector.collectDisplayInfo(appCtx)
            val networkInfo = DeviceInfoCollector.collectNetworkInfo(appCtx)
            val localeInfo = DeviceInfoCollector.collectLocaleInfo()
            val batteryInfo = DeviceInfoCollector.collectBatteryInfo(appCtx)
            val identifiersInfo = DeviceInfoCollector.collectIdentifiers(
                advertisingId = advertisingId,
                installId = installId,
                androidId = androidId
            )
            val privacyInfo = DeviceInfoCollector.collectPrivacy(limitAdTracking)

            val result = DataPointApi.validate(
                baseUrl = SdkConstants.PRODUCTION_BASE_URL,
                apiKey = apiKey,
                userId = userId,
                deviceId = deviceId,
                timestamp = System.currentTimeMillis() / 1000,
                environment = environment.name,
                sdkInfo = sdkInfo,
                appInfo = appInfo,
                deviceInfo = deviceInfo,
                displayInfo = displayInfo,
                networkInfo = networkInfo,
                localeInfo = localeInfo,
                batteryInfo = batteryInfo,
                identifiersInfo = identifiersInfo,
                privacyInfo = privacyInfo
            )

            when (result) {
                is DataPointApi.ApiResult.Success -> {
                    prefs.sessionToken = result.data.sessionToken
                    prefs.sessionExpiry =
                        System.currentTimeMillis() + (result.data.expiresIn * 1000)
                    prefs.userId = result.data.userId
                    prefs.externalUserId = result.data.externalUserId.ifBlank { null }
                    prefs.apiKey = apiKey
                    prefs.appId = result.data.appId.ifBlank { null }
                    state.set(State.INITIALIZED)
                    DataPointLogger.d("Initialization successful")
                    postOnMain { callback?.onSuccess() }
                }

                is DataPointApi.ApiResult.Error -> {
                    state.set(State.FAILED)
                    val errorCode = DataPointApi.httpCodeToErrorCode(result.httpCode)
                    DataPointLogger.e(
                        "Initialization failed (HTTP ${result.httpCode}): " +
                            LogSanitizer.safeErrorSnippet(result.message)
                    )
                    postOnMain {
                        callback?.onError(result.message, errorCode)
                    }
                }
            }
        }
    }

    /**
     * Register a listener for task events.
     * Pass `null` to remove.
     */
    @JvmStatic
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
    @JvmStatic
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
    @JvmStatic
    fun closeTasks() {
        DataPointLogger.d("closeTasks()")
        val activity = activeActivityRef?.get() ?: return
        notifyClosed()
        postOnMain { activity.finish() }
    }

    // ── User Attributes ─────────────────────────────────────────────────

    /**
     * Set the user's age.
     *
     * @param age      User's age in years.
     * @param callback Optional callback for the result.
     */
    @JvmStatic
    fun setAge(age: Int, callback: DataPointCallback? = null) {
        setAttributesInternal(mapOf("age" to age), callback)
    }

    /**
     * Set the user's age range (e.g. "18-24", "25-34").
     *
     * @param ageRange Age range string.
     * @param callback Optional callback for the result.
     */
    @JvmStatic
    fun setAgeRange(ageRange: String, callback: DataPointCallback? = null) {
        setAttributesInternal(mapOf("age_range" to ageRange), callback)
    }

    /**
     * Set the user's occupation.
     *
     * @param occupation Occupation string.
     * @param callback   Optional callback for the result.
     */
    @JvmStatic
    fun setOccupation(occupation: String, callback: DataPointCallback? = null) {
        setAttributesInternal(mapOf("occupation" to occupation), callback)
    }

    /**
     * Set the user's gender.
     *
     * @param gender   Gender string.
     * @param callback Optional callback for the result.
     */
    @JvmStatic
    fun setGender(gender: String, callback: DataPointCallback? = null) {
        setAttributesInternal(mapOf("gender" to gender), callback)
    }

    /**
     * Set arbitrary user attributes as key-value pairs.
     *
     * @param attributes Map of attribute names to values.
     * @param callback   Optional callback for the result.
     */
    @JvmStatic
    fun setUserAttributes(attributes: Map<String, String>, callback: DataPointCallback? = null) {
        setAttributesInternal(attributes.toMap(), callback)
    }

    /**
     * Assigns the host app's user identifier to the current DataPoint session.
     *
     * Calls `POST /assign_app_user_id` with JSON body `{ "app_user_id": ... }`.
     *
     * @param appUserId Non-blank identifier from the host app.
     * @param callback  Optional callback for the result.
     */
    @JvmStatic
    fun setAppUserId(appUserId: String, callback: DataPointCallback? = null) {
        if (appUserId.isBlank()) {
            DataPointLogger.e("setAppUserId() failed: appUserId is blank")
            postOnMain {
                callback?.onError(
                    "appUserId cannot be empty",
                    ErrorCode.INVALID_CONFIGURATION
                )
            }
            return
        }

        if (state.get() != State.INITIALIZED) {
            DataPointLogger.e("setAppUserId() – SDK not initialized")
            postOnMain {
                callback?.onError(
                    "SDK not initialized. Call initialize() first.",
                    ErrorCode.SDK_NOT_INITIALIZED
                )
            }
            return
        }

        val prefs = preferences ?: run {
            postOnMain {
                callback?.onError("SDK not initialized", ErrorCode.SDK_NOT_INITIALIZED)
            }
            return
        }

        val token = prefs.sessionToken
        if (token.isNullOrBlank()) {
            postOnMain {
                callback?.onError("No valid session token", ErrorCode.SESSION_EXPIRED)
            }
            return
        }

        executor.execute {
            val result = DataPointApi.setAppUserId(
                baseUrl = SdkConstants.PRODUCTION_BASE_URL,
                sessionToken = token,
                appUserId = appUserId
            )

            when (result) {
                is DataPointApi.ApiResult.Success -> {
                    DataPointLogger.d("assign_app_user_id succeeded")
                    postOnMain { callback?.onSuccess() }
                }

                is DataPointApi.ApiResult.Error -> {
                    val errorCode = DataPointApi.httpCodeToErrorCode(result.httpCode)
                    DataPointLogger.e(
                        "assign_app_user_id failed (HTTP ${result.httpCode}): " +
                            LogSanitizer.safeErrorSnippet(result.message)
                    )
                    postOnMain { callback?.onError(result.message, errorCode) }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNAL (called by TaskWebActivity)
    // ════════════════════════════════════════════════════════════════════

    internal val currentEnvironment: Environment
        get() = environment

    internal val currentApiKey: String?
        get() = apiKey

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

    internal fun notifyNoTaskAvailable() {
        isCallbackDispatched = true
        postOnMain { listener?.noTaskAvailable() }
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
     * Re-initializes the SDK in the background **without closing** the
     * WebView. On success the new token is delivered via [onNewToken]
     * so the WebView can continue with fresh credentials.
     */
    internal fun handleSessionExpired(
        activity: Activity,
        onNewToken: (String) -> Unit
    ) {
        val ctx = applicationContext ?: return
        val key = apiKey ?: return

        DataPointLogger.d("Session expired – re-initializing in background")

        preferences?.clearSession()

        initialize(ctx, key, userId, environment, object : InitCallback {
            override fun onSuccess() {
                val newToken = preferences?.sessionToken
                if (newToken.isNullOrBlank()) {
                    DataPointLogger.e("Re-init succeeded but no token available")
                    listener?.onError(
                        "Session expired and token refresh failed",
                        ErrorCode.SESSION_EXPIRED
                    )
                    return
                }
                DataPointLogger.d("Re-init successful – delivering new token to WebView")
                onNewToken(newToken)
            }

            override fun onError(message: String, code: Int) {
                DataPointLogger.e(
                    "Re-init after session expiry failed: ${LogSanitizer.safeErrorSnippet(message)}"
                )
                listener?.onError(
                    "Session expired and re-initialization failed: $message",
                    ErrorCode.SESSION_EXPIRED
                )
                postOnMain { activity.finish() }
            }
        })
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE
    // ════════════════════════════════════════════════════════════════════

    private fun setAttributesInternal(attributes: Map<String, Any>, callback: DataPointCallback?) {
        if (state.get() != State.INITIALIZED) {
            DataPointLogger.e("setAttributes() – SDK not initialized")
            postOnMain {
                callback?.onError(
                    "SDK not initialized. Call initialize() first.",
                    ErrorCode.SDK_NOT_INITIALIZED
                )
            }
            return
        }

        val prefs = preferences ?: run {
            postOnMain {
                callback?.onError("SDK not initialized", ErrorCode.SDK_NOT_INITIALIZED)
            }
            return
        }


        val token = prefs.sessionToken
        if (token.isNullOrBlank()) {
            postOnMain {
                callback?.onError("No valid session token", ErrorCode.SESSION_EXPIRED)
            }
            return
        }

        executor.execute {
            val result = DataPointApi.setAttributes(
                baseUrl = SdkConstants.PRODUCTION_BASE_URL,
                sessionToken = token,
                attributes = attributes
            )

            when (result) {
                is DataPointApi.ApiResult.Success -> {
                    DataPointLogger.d("Attributes set successfully")
                    postOnMain { callback?.onSuccess() }
                }

                is DataPointApi.ApiResult.Error -> {
                    val errorCode = DataPointApi.httpCodeToErrorCode(result.httpCode)
                    DataPointLogger.e(
                        "Set attributes failed (HTTP ${result.httpCode}): " +
                            LogSanitizer.safeErrorSnippet(result.message)
                    )
                    postOnMain { callback?.onError(result.message, errorCode) }
                }
            }
        }
    }

    private fun handleSandboxInit(prefs: DataPointPreferences, callback: InitCallback?) {
        DataPointLogger.d("Sandbox mode – using mock session")
        prefs.sessionToken = "sandbox_token_${System.currentTimeMillis()}"
        prefs.sessionExpiry = System.currentTimeMillis() + 86_400_000L // 24 h
        prefs.userId = userId ?: "sandbox_user_${prefs.deviceId.take(8)}"
        prefs.apiKey = apiKey
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
        val key = apiKey ?: return

//        preferences?.clearSession()

        initialize(ctx, key, userId, environment, object : InitCallback {
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
            putExtra(SdkConstants.EXTRA_EXTERNAL_USER_ID, prefs.externalUserId)
            putExtra(SdkConstants.EXTRA_APP_ID, prefs.appId)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        context.startActivity(intent)
        DataPointLogger.d(
            "TaskWebActivity launched, url=${LogSanitizer.urlForLog(baseTaskUrl)}"
        )
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
