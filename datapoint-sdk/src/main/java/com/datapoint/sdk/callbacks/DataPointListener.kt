package com.datapoint.sdk.callbacks

/**
 * Callback interface for task-related events.
 *
 * Set via [DataPointSDK.setListener].
 */
interface DataPointListener {

    /**
     * A task was completed.
     * The raw JSON payload from the WebView is provided for inspection.
     *
     * @param payload JSON string sent by the WebView, or `null`.
     */
    fun onTaskCompleted(payload: String?)

    /**
     * The WebView requested that the host app show an ad.
     * The task screen is automatically closed before this callback fires.
     * The host app should present its own ad (e.g. via AdMob / AppLovin).
     */
    fun onAdRequested()

    /**
     * The task screen was closed — either by the user (back press),
     * by the WebView, or programmatically via [DataPointSDK.closeTasks].
     */
    fun onClosed()

    /**
     * An error occurred.
     *
     * @param message Human-readable description.
     * @param code    Machine-readable code from [ErrorCode].
     */
    fun onError(message: String, code: Int)
}
