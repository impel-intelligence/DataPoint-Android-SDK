package com.datapoint.sdk.internal

import com.datapoint.sdk.callbacks.models.Environment

/**
 * Internal constants used across the SDK. Not exposed to consumers.
 */
internal object SdkConstants {

    const val SDK_VERSION = "1.2.0"
    const val PLATFORM = "android"

    // ── API ─────────────────────────────────────────────────────────────
    const val PRODUCTION_BASE_URL = "https://api.trydatapoint.com/data-labelling/v1"
    /** QA / staging API (used with [Environment.SANDBOX]). */
    const val QA_BASE_URL = "https://qa-api.trydatapoint.com/data-labelling/v1"
    const val VALIDATE_ENDPOINT = "/initialize"
    const val USER_ATTRIBUTES_ENDPOINT = "/user/attributes"
    const val ASSIGN_APP_USER_ID_ENDPOINT = "/assign_app_user_id"
    const val AVAILABILITY_ENDPOINT = "/availability"

    // ── Task WebView URLs ───────────────────────────────────────────────
    const val PRODUCTION_TASK_URL = "https://task.trydatapoint.com/"
    /** QA task wall (used with [Environment.SANDBOX]). */
    const val QA_TASK_URL = PRODUCTION_TASK_URL

    fun apiBaseUrl(environment: Environment): String =
        when (environment) {
            Environment.PRODUCTION -> PRODUCTION_BASE_URL
            Environment.SANDBOX -> QA_BASE_URL
        }

    fun taskUrl(environment: Environment): String =
        when (environment) {
            Environment.PRODUCTION -> PRODUCTION_TASK_URL
            Environment.SANDBOX -> QA_TASK_URL
        }

    // ── JavaScript bridge names ─────────────────────────────────────────
    const val JS_BRIDGE_TASK = "DataPointTask"
    const val JS_BRIDGE_APP = "DataPointApp"
    const val JS_BRIDGE_AUDIO = "AudioVolumeHelper"

    // ── Intent extras ───────────────────────────────────────────────────
    const val EXTRA_TASK_URL = "dp_extra_task_url"
    const val EXTRA_SESSION_TOKEN = "dp_extra_session_token"
    const val EXTRA_USER_ID = "dp_extra_user_id"
    const val EXTRA_EXTERNAL_USER_ID = "dp_extra_external_user_id"
    const val EXTRA_APP_ID = "dp_extra_app_id"

    // ── SharedPreferences ───────────────────────────────────────────────
    const val PREFS_NAME = "datapoint_sdk_prefs"
    const val PREF_DEVICE_ID = "device_id"
    const val PREF_SESSION_TOKEN = "session_token"
    const val PREF_SESSION_EXPIRY = "session_expiry"
    const val PREF_USER_ID = "user_id"
    const val PREF_EXTERNAL_USER_ID = "external_user_id"
    const val PREF_API_KEY = "api_key"
    const val PREF_APP_ID = "app_id"
    const val PREF_INSTALL_ID = "install_id"

    // ── Trusted hosts for WebView navigation ────────────────────────────
    val TRUSTED_HOSTS = listOf("trydatapoint.com", "trydatapoint.ai")
}
