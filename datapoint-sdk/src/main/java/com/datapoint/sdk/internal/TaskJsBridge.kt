package com.datapoint.sdk.internal

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JavaScript ↔ Android bridge exposed to the task WebView as
 * `window.[DataPointTask]`.
 *
 * All methods are invoked on a binder thread by the WebView engine,
 * so every callback is posted to the main thread via [mainHandler].
 */
internal class TaskJsBridge(
    private val onTaskCompleted: (String?) -> Unit,
    private val onWatchAd: () -> Unit,
    private val onNoTaskAvailable: () -> Unit,
    private val onWindowClose: () -> Unit,
    private val onSessionExpired: () -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called by JS when the user completes a task. Closes the screen. */
    @JavascriptInterface
    fun completeTask(payload: String?) {
        mainHandler.post { onTaskCompleted(payload) }
    }

    /** Called by JS when the user opts to watch an ad instead. Closes the screen. */
    @JavascriptInterface
    fun watchAdInstead() {
        mainHandler.post { onWatchAd() }
    }

    /** Called by JS when there are currently no tasks available. */
    @JavascriptInterface
    fun noTaskAvailable() {
        mainHandler.post { onNoTaskAvailable() }
    }

    /** Called by JS to close the task screen. */
    @JavascriptInterface
    fun closeTasks() {
        mainHandler.post { onWindowClose() }
    }

    /** Called by JS when a 401 SESSION_EXPIRED response is received. */
    @JavascriptInterface
    fun sessionExpired() {
        mainHandler.post { onSessionExpired() }
    }
}
