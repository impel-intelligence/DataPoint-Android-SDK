package com.datapoint.sdk.callbacks

/**
 * General-purpose callback for asynchronous SDK operations
 * such as setting user attributes.
 */
interface DataPointCallback {

    /** The operation completed successfully. */
    fun onSuccess()

    /**
     * The operation failed.
     *
     * @param message Human-readable description.
     * @param code    Machine-readable code from [ErrorCode].
     */
    fun onError(message: String, code: Int)
}
