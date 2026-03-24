package com.datapoint.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import java.util.UUID

/**
 * Persistent key-value store for the SDK.
 *
 * Stores the device ID, session token, expiry,
 * user ID, and app ID in a private [SharedPreferences] file.
 */
internal class DataPointPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(SdkConstants.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Device-specific ID sourced from [Settings.Secure.ANDROID_ID].
     * Persists across app reinstalls (same signing key & device).
     * Falls back to a random UUID if ANDROID_ID is unavailable.
     */
    val deviceId: String
        get() {
            val existing = prefs.getString(SdkConstants.PREF_DEVICE_ID, null)
            if (existing != null) return existing
            val id = androidId ?: UUID.randomUUID().toString()
            prefs.edit { putString(SdkConstants.PREF_DEVICE_ID, id) }
            DataPointLogger.d("Device ID generated and stored (value omitted)")
            return id
        }

    /** [Settings.Secure.ANDROID_ID] resolved at construction time. */
    @SuppressLint("HardwareIds")
    private val androidId: String? =
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            DataPointLogger.w(
                "Could not read ANDROID_ID: ${LogSanitizer.safeErrorSnippet(e.message, 80)}"
            )
            null
        }

    // ── Session ─────────────────────────────────────────────────────────

    var sessionToken: String?
        get() = prefs.getString(SdkConstants.PREF_SESSION_TOKEN, null)
        set(value) {
            prefs.edit { putString(SdkConstants.PREF_SESSION_TOKEN, value) }
        }

    var sessionExpiry: Long
        get() = prefs.getLong(SdkConstants.PREF_SESSION_EXPIRY, 0L)
        set(value) {
            prefs.edit { putLong(SdkConstants.PREF_SESSION_EXPIRY, value) }
        }

    fun isSessionValid(): Boolean {
        val token = sessionToken
        if (token.isNullOrBlank()) return false
        val expiry = sessionExpiry
        if (expiry == 0L) return false
        return System.currentTimeMillis() < expiry
    }

    fun clearSession() {
        prefs.edit {
            remove(SdkConstants.PREF_SESSION_TOKEN)
                .remove(SdkConstants.PREF_SESSION_EXPIRY)
        }
    }

    // ── Install ID (persists across sessions, unique per install) ──────

    val installId: String
        get() {
            val existing = prefs.getString(SdkConstants.PREF_INSTALL_ID, null)
            if (existing != null) return existing
            val id = UUID.randomUUID().toString()
            prefs.edit { putString(SdkConstants.PREF_INSTALL_ID, id) }
            DataPointLogger.d("Install ID generated and stored (value omitted)")
            return id
        }

    /** Exposes the raw ANDROID_ID for the identifiers payload. */
    val rawAndroidId: String?
        get() = androidId

    // ── User / App ──────────────────────────────────────────────────────

    var userId: String?
        get() = prefs.getString(SdkConstants.PREF_USER_ID, null)
        set(value) {
            prefs.edit { putString(SdkConstants.PREF_USER_ID, value) }
        }

    /** External user ID from initialize response; exposed to WebView as getUUID(). */
    var externalUserId: String?
        get() = prefs.getString(SdkConstants.PREF_EXTERNAL_USER_ID, null)
        set(value) {
            prefs.edit { putString(SdkConstants.PREF_EXTERNAL_USER_ID, value) }
        }

    /** API key used to initialize the SDK (for session reuse check). */
    var apiKey: String?
        get() = prefs.getString(SdkConstants.PREF_API_KEY, null)
        set(value) {
            prefs.edit { putString(SdkConstants.PREF_API_KEY, value) }
        }

    /** Backend app_id from initialize response; passed to the task WebView. */
    var appId: String?
        get() = prefs.getString(SdkConstants.PREF_APP_ID, null)
        set(value) {
            prefs.edit { putString(SdkConstants.PREF_APP_ID, value) }
        }
}
