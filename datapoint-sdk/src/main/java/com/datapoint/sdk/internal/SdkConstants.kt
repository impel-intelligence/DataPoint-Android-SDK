package com.datapoint.sdk.internal

/**
 * Internal constants used across the SDK. Not exposed to consumers.
 */
internal object SdkConstants {

    const val SDK_VERSION = "1.0.0"
    const val PLATFORM = "android"

    // ── API ─────────────────────────────────────────────────────────────
    const val PRODUCTION_BASE_URL = "https://qa-api.trydatapoint.com/data-labelling/v1"
    const val VALIDATE_ENDPOINT = "/initialize"

    // ── Task WebView URLs ───────────────────────────────────────────────
    const val PRODUCTION_TASK_URL = "https://shayne-exorcistic-porsha.ngrok-free.dev/"
    const val SANDBOX_TASK_URL = PRODUCTION_TASK_URL

    // ── JavaScript bridge names ─────────────────────────────────────────
    const val JS_BRIDGE_TASK = "DataPointTask"
    const val JS_BRIDGE_APP = "DataPointApp"
    const val JS_BRIDGE_AUDIO = "AudioVolumeHelper"

    // ── Intent extras ───────────────────────────────────────────────────
    const val EXTRA_TASK_URL = "dp_extra_task_url"
    const val EXTRA_SESSION_TOKEN = "dp_extra_session_token"
    const val EXTRA_USER_ID = "dp_extra_user_id"

    // ── SharedPreferences ───────────────────────────────────────────────
    const val PREFS_NAME = "datapoint_sdk_prefs"
    const val PREF_DEVICE_ID = "device_id"
    const val PREF_SESSION_TOKEN = "session_token"
    const val PREF_SESSION_EXPIRY = "session_expiry"
    const val PREF_USER_ID = "user_id"
    const val PREF_APP_ID = "app_id"

    // ── Trusted hosts for WebView navigation ────────────────────────────
    val TRUSTED_HOSTS = listOf("trydatapoint.com", "trydatapoint.ai", "dippy.ai", "ngrok-free.dev")
}
