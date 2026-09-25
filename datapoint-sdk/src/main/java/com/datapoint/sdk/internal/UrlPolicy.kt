package com.datapoint.sdk.internal

/**
 * Pure string rules for where a URL may go. Kept free of Android types so the policy is
 * unit-testable on the JVM; [BrowserLauncher] and [TaskWebActivity] apply it to real URIs.
 */
internal object UrlPolicy {

    /** Schemes a Custom Tab can render. */
    private val WEB_SCHEMES = setOf("http", "https")

    /** Schemes a remote page must never be able to hand to the SDK. */
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "data", "about")

    /** `true` for `http`/`https`; every other scheme belongs to another app. */
    fun isWebScheme(scheme: String?): Boolean = scheme?.lowercase() in WEB_SCHEMES

    /** `true` for schemes that could reach back into the app and must be refused outright. */
    fun isBlockedScheme(scheme: String?): Boolean = scheme?.lowercase() in BLOCKED_SCHEMES

    /**
     * `true` when [host] is one of [SdkConstants.TRUSTED_HOSTS] or a subdomain of one.
     * A plain suffix match is not enough: `evil-trydatapoint.com` must not pass.
     */
    fun isTrustedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return SdkConstants.TRUSTED_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
