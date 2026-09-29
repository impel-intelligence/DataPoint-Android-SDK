package com.datapoint.sdk.callbacks

/**
 * Result of [com.datapoint.sdk.DataPoint.checkTaskAvailability].
 *
 * @property isAvailable `true` when [com.datapoint.sdk.DataPoint.showTasks] would have a task to show right now.
 * @property reason Machine-readable explanation, one of the `REASON_*` constants.
 * @property message Human-readable form of [reason], intended for logs and debugging rather than end users.
 */
data class TaskAvailability(
    val isAvailable: Boolean,
    val reason: String,
    val message: String
) {
    companion object {
        /** A task is available. */
        const val REASON_AVAILABLE = "available"

        /** No task matches this user right now. */
        const val REASON_NO_TASK = "no_task"

        /** The user has completed today's maximum number of tasks. */
        const val REASON_DAILY_LIMIT_REACHED = "daily_limit_reached"

        /** The user is not eligible to receive tasks. */
        const val REASON_ACCESS_DISABLED = "access_disabled"
    }
}

/** Callback for [com.datapoint.sdk.DataPoint.checkTaskAvailability]. Delivered on the main thread. */
interface TaskAvailabilityCallback {
    /** The check completed; inspect [TaskAvailability.isAvailable] and [TaskAvailability.reason]. */
    fun onResult(availability: TaskAvailability)

    /**
     * The check could not be completed (no network, server error, SDK not initialized).
     * Decide your own fallback; the SDK makes no guess about availability.
     *
     * @param message Human-readable description.
     * @param code    Machine-readable code from [ErrorCode].
     */
    fun onError(message: String, code: Int)
}
