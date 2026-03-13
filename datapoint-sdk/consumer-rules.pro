# ── DataPoint SDK consumer ProGuard / R8 rules ──────────────────────────────

# Keep all public SDK API classes
-keep class com.datapoint.sdk.DataPointSDK { *; }
-keep interface com.datapoint.sdk.DataPointListener { *; }
-keep interface com.datapoint.sdk.InitCallback { *; }
-keep class com.datapoint.sdk.Reward { *; }
-keep class com.datapoint.sdk.Environment { *; }
-keep class com.datapoint.sdk.ErrorCode { *; }

# Keep TaskWebActivity (referenced in AndroidManifest)
-keep class com.datapoint.sdk.internal.TaskWebActivity { *; }

# Keep all @JavascriptInterface annotated methods (critical for WebView JS bridges)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
