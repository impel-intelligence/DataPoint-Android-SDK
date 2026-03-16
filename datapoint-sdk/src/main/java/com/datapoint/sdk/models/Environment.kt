package com.datapoint.sdk.models

/**
 * SDK environment configuration.
 *
 * - [PRODUCTION] – Connects to the live backend; performs real validation.
 * - [SANDBOX]    – Uses mock data; no network calls during initialization.
 */
enum class Environment(internal val value: String) {
    PRODUCTION("production"),
    SANDBOX("sandbox")
}
