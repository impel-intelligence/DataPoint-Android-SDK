package com.datapoint.sdk.callbacks

/**
 * Error codes returned by SDK callbacks.
 */
object ErrorCode {
    /** [com.datapoint.sdk.DataPoint.initialize] has not been called or has not completed. */
    const val SDK_NOT_INITIALIZED = 1001

    /** The initialization network request failed. */
    const val INITIALIZATION_FAILED = 1002

    /** The session token expired and automatic re-initialization failed. */
    const val SESSION_EXPIRED = 1003

    /** Invalid parameters passed to the SDK (e.g. blank apiKey). */
    const val INVALID_CONFIGURATION = 1004

    /** [com.datapoint.sdk.DataPoint.showTasks] was called while the task screen is already visible. */
    const val TASK_ALREADY_SHOWING = 1005

    /** A generic network error (no connectivity, timeout, etc.). */
    const val NETWORK_ERROR = 1007

    /** Invalid request parameters sent to the server (HTTP 400). */
    const val INVALID_REQUEST = 1008

    /** The API key is invalid or unauthorized (HTTP 401). */
    const val INVALID_API_KEY = 1009

    /** The server encountered an internal error (HTTP 5xx). */
    const val SERVER_ERROR = 1012
}
