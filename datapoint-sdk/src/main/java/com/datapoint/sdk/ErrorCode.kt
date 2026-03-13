package com.datapoint.sdk

/**
 * Error codes returned by SDK callbacks.
 */
object ErrorCode {
    /** [DataPointSDK.initialize] has not been called or has not completed. */
    const val SDK_NOT_INITIALIZED = 1001

    /** The initialization network request failed. */
    const val INITIALIZATION_FAILED = 1002

    /** The session token expired and automatic re-initialization failed. */
    const val SESSION_EXPIRED = 1003

    /** Invalid parameters passed to the SDK (e.g. blank appId). */
    const val INVALID_CONFIGURATION = 1004

    /** [DataPointSDK.showTasks] was called while the task screen is already visible. */
    const val TASK_ALREADY_SHOWING = 1005

    /** A WebView-level error occurred while loading the task page. */
    const val WEBVIEW_ERROR = 1006

    /** A generic network error (no connectivity, timeout, etc.). */
    const val NETWORK_ERROR = 1007

    /** Catch-all for unexpected errors. */
    const val UNKNOWN_ERROR = 1099
}
