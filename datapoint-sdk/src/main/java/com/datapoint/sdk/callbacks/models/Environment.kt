package com.datapoint.sdk.callbacks.models

/**
 * SDK environment configuration.
 *
 * - [PRODUCTION] – Live API and task URLs.
 * - [SANDBOX]    – QA / staging API and task WebView URLs (not production hosts).
 */
enum class Environment(internal val value: String) {
    PRODUCTION("production"),
    SANDBOX("sandbox")
}
