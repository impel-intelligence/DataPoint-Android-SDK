package com.datapoint.sdk.internal

import android.util.Log

/**
 * Internal SDK logger. Disabled by default; enable via
 * [com.datapoint.sdk.DataPoint.isLoggingEnabled].
 *
 * Never log secrets (API keys, session tokens, raw request/response bodies,
 * advertising IDs, or end-user identifiers). Use [LogSanitizer] helpers when adding logs.
 */
internal object DataPointLogger {

    private const val TAG = "DataPoint"

    @Volatile
    var isEnabled: Boolean = false

    fun d(message: String) {
        if (isEnabled) Log.d(TAG, message)
    }

    fun w(message: String) {
        if (isEnabled) Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    }
}
