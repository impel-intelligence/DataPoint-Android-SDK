package com.datapoint.sdk

/**
 * Optional callback for [DataPointSDK.initialize].
 * Notifies the host app whether initialization succeeded or failed.
 */
interface InitCallback {

    /** SDK initialized and is ready to show tasks. */
    fun onSuccess()

    /**
     * Initialization failed.
     *
     * @param message Human-readable description.
     * @param code    Machine-readable code from [ErrorCode].
     */
    fun onError(message: String, code: Int)
}
