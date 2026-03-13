package com.datapoint.sdk.internal

import android.webkit.JavascriptInterface

/**
 * JavaScript ↔ Android bridge that exposes authentication data
 * to the task WebView as `window.[DataPointApp]`.
 *
 * The WebView uses the token for `Authorization: Bearer` headers.
 */
internal class WebAppInterface(
    private val sessionToken: String?,
    private val userId: String?,
    private val sdkVersion: String,
    private val platform: String,
    private val environment: String
) {

    @JavascriptInterface
    fun getToken(): String? = sessionToken

    @JavascriptInterface
    fun getUUID(): String? = userId

    @JavascriptInterface
    fun getSdkVersion(): String = sdkVersion

    @JavascriptInterface
    fun getPlatform(): String = platform

    @JavascriptInterface
    fun getEnvironment(): String = environment
}
