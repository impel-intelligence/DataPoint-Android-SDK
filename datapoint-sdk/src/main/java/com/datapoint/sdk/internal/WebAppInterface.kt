package com.datapoint.sdk.internal

import android.webkit.JavascriptInterface

/**
 * JavaScript ↔ Android bridge that exposes authentication data
 * to the task WebView as `window.[DataPointApp]`.
 *
 * The WebView uses the token for `Authorization: Bearer` headers.
 */
internal class WebAppInterface(
    @Volatile private var sessionToken: String?,
    private val userId: String?,
    private val sdkVersion: String,
    private val platform: String,
    private val environment: String,
    private val apiKey: String?,
    private val appId: String?
) {

    fun updateToken(token: String) {
        sessionToken = token
    }

    @JavascriptInterface
    fun getToken(): String? = sessionToken

    /** Returns external_user_id from the initialize response for the WebView. */
    @JavascriptInterface
    fun getUUID(): String? = userId

    @JavascriptInterface
    fun getSdkVersion(): String = sdkVersion

    @JavascriptInterface
    fun getPlatform(): String = platform

    @JavascriptInterface
    fun getEnvironment(): String = environment

    @JavascriptInterface
    fun getApiKey(): String? = apiKey

    @JavascriptInterface
    fun getAppId(): String? = appId
}
