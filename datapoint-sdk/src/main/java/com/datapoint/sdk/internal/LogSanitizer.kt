package com.datapoint.sdk.internal

import android.net.Uri

/**
 * Helpers so optional debug logging never prints secrets (keys, tokens, identifiers, PII).
 */
internal object LogSanitizer {

    /** e.g. apiKey / userId / token: only whether present and length. */
    fun secretLength(value: String?): String =
        when {
            value.isNullOrBlank() -> "absent"
            else -> "present(len=${value.length})"
        }

    /** Strip query and fragment so tokens are not copied to logcat. */
    fun urlForLog(url: String): String =
        try {
            Uri.parse(url)
                .buildUpon()
                .clearQuery()
                .fragment(null)
                .build()
                .toString()
        } catch (_: Exception) {
            "(invalid-url)"
        }

    /** Bound server/exception messages so logs stay short and less likely to include payloads. */
    fun safeErrorSnippet(message: String?, maxLen: Int = 160): String {
        if (message.isNullOrBlank()) return ""
        val singleLine = message.trim().replace(Regex("\\s+"), " ")
        return if (singleLine.length <= maxLen) singleLine else singleLine.take(maxLen) + "…"
    }
}
