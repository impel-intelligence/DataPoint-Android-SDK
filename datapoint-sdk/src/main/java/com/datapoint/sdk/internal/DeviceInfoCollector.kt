package com.datapoint.sdk.internal

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Currency
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

/**
 * Collects device, app, display, network, locale, and battery info
 * to construct the full initialize payload.
 */
internal object DeviceInfoCollector {

    // ── App Info ────────────────────────────────────────────────────────

    fun collectAppInfo(context: Context, sha256Cert: String?): JSONObject {
        val pm = context.packageManager
        val packageName = context.packageName
        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
        val pkgInfo = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }

        val appName = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: ""
        val appVersion = pkgInfo?.versionName ?: ""
        val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode?.toInt() ?: 0
        } else {
            @Suppress("DEPRECATION")
            pkgInfo?.versionCode ?: 0
        }

        return JSONObject().apply {
            put("package_name", packageName)
            put("app_name", appName)
            put("app_version", appVersion)
            put("build_number", buildNumber)
            if (!sha256Cert.isNullOrBlank()) put("sha256_cert", sha256Cert)
        }
    }

    // ── SDK Info ────────────────────────────────────────────────────────

    fun collectSdkInfo(): JSONObject {
        return JSONObject().apply {
            put("sdk_version", SdkConstants.SDK_VERSION)
            put("sdk_platform", SdkConstants.PLATFORM)
        }
    }

    // ── Device Info ─────────────────────────────────────────────────────

    fun collectDeviceInfo(context: Context): JSONObject {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()

        // Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val storageTotalMb = (stat.blockSizeLong * stat.blockCountLong / (1024 * 1024)).toInt()
        val storageAvailableMb =
            (stat.blockSizeLong * stat.availableBlocksLong / (1024 * 1024)).toInt()

        // Device type heuristic
        val deviceType = getDeviceType(context)

        return JSONObject().apply {
            put("platform", SdkConstants.PLATFORM)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device_type", deviceType)
            put("os", "Android")
            put("os_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("cpu_architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("ram_mb", totalRamMb)
            put("storage_total_mb", storageTotalMb)
            put("storage_available_mb", storageAvailableMb)
        }
    }

    // ── Display Info ────────────────────────────────────────────────────

    fun collectDisplayInfo(context: Context): JSONObject {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)

        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        // Screen size in inches
        val widthInches = widthPx.toDouble() / densityDpi
        val heightInches = heightPx.toDouble() / densityDpi
        val screenSizeInches =
            sqrt(widthInches * widthInches + heightInches * heightInches)

        val orientation =
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "landscape"
            } else {
                "portrait"
            }

        return JSONObject().apply {
            put("screen_resolution", "${widthPx}x${heightPx}")
            put("screen_density", densityDpi)
            put("screen_size_inches", String.format("%.1f", screenSizeInches).toDouble())
            put("orientation", orientation)
        }
    }

    // ── Network Info ────────────────────────────────────────────────────

    fun collectNetworkInfo(context: Context): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        var networkType = "unknown"
        var connectionType = "unknown"

        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val capabilities = network?.let { cm.getNetworkCapabilities(it) }

                if (capabilities != null) {
                    when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                            networkType = "wifi"
                            connectionType = "wifi"
                        }
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                            networkType = "cellular"
                            connectionType = "cellular"
                        }
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                            networkType = "ethernet"
                            connectionType = "ethernet"
                        }
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> {
                            networkType = "bluetooth"
                            connectionType = "bluetooth"
                        }
                    }
                }
            } else {
                // Fallback for < API 23
                val networkInfo = cm.activeNetworkInfo
                if (networkInfo != null && networkInfo.isConnected) {
                    when (networkInfo.type) {
                        ConnectivityManager.TYPE_WIFI -> {
                            networkType = "wifi"
                            connectionType = "wifi"
                        }
                        ConnectivityManager.TYPE_MOBILE -> {
                            networkType = "cellular"
                            connectionType = "cellular"
                        }
                    }
                }
            }
        }

        val carrier = tm?.networkOperatorName ?: ""

        return JSONObject().apply {
            put("network_type", networkType)
            put("carrier", carrier)
            put("connection_type", connectionType)
        }
    }
    // ── Locale Info ─────────────────────────────────────────────────────

    fun collectLocaleInfo(): JSONObject {
        val locale = Locale.getDefault()
        val timezone = TimeZone.getDefault()
        val currency = try {
            Currency.getInstance(locale).currencyCode
        } catch (_: Exception) {
            ""
        }

        return JSONObject().apply {
            put("language", locale.language)
            put("locale", locale.toString())
            put("timezone", timezone.id)
            put("country", locale.country)
            put("currency", currency)
        }
    }

    // ── Battery Info ────────────────────────────────────────────────────

    fun collectBatteryInfo(context: Context): JSONObject {
        val batteryLevel = getBatteryLevel(context)
        return JSONObject().apply {
            put("battery_level", batteryLevel)
        }
    }

    // ── Identifiers ─────────────────────────────────────────────────────

    fun collectIdentifiers(
        advertisingId: String?,
        installId: String,
        androidId: String?
    ): JSONObject {
        return JSONObject().apply {
            if (!advertisingId.isNullOrBlank()) put("advertising_id", advertisingId)
            put("install_id", installId)
            if (!androidId.isNullOrBlank()) put("android_id", androidId)
        }
    }

    // ── Privacy ─────────────────────────────────────────────────────────

    fun collectPrivacy(limitAdTracking: Boolean): JSONObject {
        return JSONObject().apply {
            put("limit_ad_tracking", limitAdTracking)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun getDeviceType(context: Context): String {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)

        val widthInches = metrics.widthPixels.toDouble() / metrics.densityDpi
        val heightInches = metrics.heightPixels.toDouble() / metrics.densityDpi
        val diag = sqrt(widthInches * widthInches + heightInches * heightInches)
        return when {
            diag >= 10.0 -> "tablet"
            diag >= 7.0 -> "tablet"
            else -> "phone"
        }
    }

    private fun getBatteryLevel(context: Context): Int {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
