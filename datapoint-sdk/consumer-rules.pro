# ── DataPoint SDK consumer ProGuard / R8 rules ──────────────────────────────

# Keep all public SDK API classes
-keep class com.datapoint.sdk.DataPoint { *; }
-keep interface com.datapoint.sdk.callbacks.DataPointListener { *; }
-keep interface com.datapoint.sdk.callbacks.InitCallback { *; }
-keep interface com.datapoint.sdk.callbacks.DataPointCallback { *; }
-keep class com.datapoint.sdk.callbacks.ErrorCode { *; }
-keep class com.datapoint.sdk.callbacks.models.Reward { *; }
-keep class com.datapoint.sdk.callbacks.models.Environment { *; }

# Keep TaskWebActivity (referenced in AndroidManifest)
-keep class com.datapoint.sdk.internal.TaskWebActivity { *; }

# Keep all @JavascriptInterface annotated methods (critical for WebView JS bridges)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
