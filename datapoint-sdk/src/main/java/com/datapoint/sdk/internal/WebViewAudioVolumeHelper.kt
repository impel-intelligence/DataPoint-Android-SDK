package com.datapoint.sdk.internal

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * Monitors device media volume when audio playback starts inside a WebView
 * and pushes real-time volume changes back to JavaScript.
 *
 * ## How it works
 *  1. A [JavascriptInterface] (`onAudioStarted`) is exposed to JavaScript under
 *     [SdkConstants.JS_BRIDGE_AUDIO] ("AudioVolumeHelper").
 *  2. When the web page starts playing audio it calls `AudioVolumeHelper.onAudioStarted()`.
 *  3. If volume is below [VOLUME_THRESHOLD_PERCENT] (30 %), the system volume slider
 *     is shown so the user can adjust it.
 *  4. A [ContentObserver] watches for volume changes. On change it evaluates
 *     `window.onVolumeChanged(percentInt)` inside the WebView.
 *
 * ## JavaScript usage
 * ```js
 * // Notify Android when audio starts
 * AudioVolumeHelper.onAudioStarted();
 *
 * // Receive volume updates
 * window.onVolumeChanged = function (vol) { console.log(vol + "%"); };
 *
 * // Read / write volume
 * var v = AudioVolumeHelper.getVolume();   // 0–100
 * AudioVolumeHelper.setVolume(75);
 * ```
 */
internal class WebViewAudioVolumeHelper private constructor(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webViewRef: WeakReference<WebView>? = null
    private var lastNotifiedPercent: Int = -1

    // ── ContentObserver ─────────────────────────────────────────────────

    private val volumeObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            pushVolumeToWebView()
        }
    }

    // ── Volume helpers ──────────────────────────────────────────────────

    private fun getVolumePercent(): Float {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max == 0) return 0f
        return current.toFloat() / max.toFloat()
    }

    private fun isVolumeLow(): Boolean = getVolumePercent() < VOLUME_THRESHOLD_PERCENT

    private fun ensureAudibleVolume() {
        if (isVolumeLow()) {
            DataPointLogger.d("Volume is low (${(getVolumePercent() * 100).toInt()}%), raising")
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    // ── Push to WebView ─────────────────────────────────────────────────

    private fun pushVolumeToWebView() {
        val webView = webViewRef?.get() ?: return
        val percent = (getVolumePercent() * 100).toInt()
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent

        DataPointLogger.d("Volume → $percent%, notifying WebView")
        mainHandler.post {
            webView.evaluateJavascript(
                "if(typeof window.onVolumeChanged==='function'){window.onVolumeChanged($percent);}",
                null
            )
        }
    }

    // ── JavascriptInterface ─────────────────────────────────────────────

    @JavascriptInterface
    fun onAudioStarted() {
        mainHandler.post {
            DataPointLogger.d("onAudioStarted() from WebView")
            ensureAudibleVolume()
        }
    }

    @JavascriptInterface
    fun getVolume(): Int = (getVolumePercent() * 100).toInt()

    @JavascriptInterface
    fun setVolume(percent: Int) {
        mainHandler.post {
            val clamped = percent.coerceIn(0, 100)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * clamped / 100f).toInt().coerceIn(0, max)
            DataPointLogger.d("setVolume($clamped%) → index $target/$max")
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun startObserving() {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver
        )
        DataPointLogger.d("Volume observer registered")
    }

    fun stopObserving() {
        try {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        } catch (_: Exception) { /* already unregistered */ }
        webViewRef = null
        DataPointLogger.d("Volume observer unregistered")
    }

    // ── Factory ─────────────────────────────────────────────────────────

    companion object {
        private const val VOLUME_THRESHOLD_PERCENT = 0.30f

        /**
         * Creates the helper, wires it to [webView], and starts observing.
         * Remember to call [stopObserving] in `onDestroy`.
         */
        fun attach(webView: WebView): WebViewAudioVolumeHelper {
            val helper = WebViewAudioVolumeHelper(webView.context)
            helper.webViewRef = WeakReference(webView)
            webView.addJavascriptInterface(helper, SdkConstants.JS_BRIDGE_AUDIO)
            helper.startObserving()
            DataPointLogger.d("AudioVolumeHelper attached")
            return helper
        }
    }
}
