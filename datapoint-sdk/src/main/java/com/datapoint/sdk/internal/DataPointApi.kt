package com.datapoint.sdk.internal

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight HTTP client for SDK–backend communication.
 * Uses [HttpURLConnection] to keep the SDK dependency-free.
 */
internal object DataPointApi {

    // ── Response models ─────────────────────────────────────────────────

    data class InitResponse(
        val userId: String,
        val sessionToken: String,
        val expiresIn: Long
    )

    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val httpCode: Int) : ApiResult<Nothing>()
    }

    // ── Validate / Initialize ───────────────────────────────────────────

    /**
     * POST /initialize
     *
     * Registers / validates the app and returns a session token.
     * **Must be called on a background thread.**
     */
    fun validate(
        baseUrl: String,
        appId: String,
        userId: String?,
        deviceId: String,
        packageName: String,
        sdkVersion: String,
        sha256Cert: String?,
        environment: String? = null,
        advertisingId: String? = null
    ): ApiResult<InitResponse> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl${SdkConstants.VALIDATE_ENDPOINT}")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 30_000
                readTimeout = 30_000
                doOutput = true
            }

            val body = JSONObject().apply {
                put("api_key", appId)
                put("device_id", deviceId)
                put("platform", SdkConstants.PLATFORM)
                put("package_name", packageName)
                if (!userId.isNullOrBlank()) put("user_id", userId)
                if (!sha256Cert.isNullOrBlank()) put("sha256_cert", sha256Cert)
                put("sdk_version", sdkVersion)
                put("timestamp", (System.currentTimeMillis() / 1000).toString())
                if (!environment.isNullOrBlank()) put("environment", environment)
                if (!advertisingId.isNullOrBlank()) put("advertising_id", advertisingId)
            }

            DataPointLogger.d("POST $url  body=$body")

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody =
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            DataPointLogger.d("Response ($responseCode): $responseBody")

            if (responseCode !in 200..299) {
                val msg = parseErrorMessage(responseBody, responseCode)
                return ApiResult.Error(msg, responseCode)
            }

            val json = JSONObject(responseBody)
            if (json.optString("status") != "success") {
                return ApiResult.Error(
                    json.optString("message", "Initialization failed"),
                    responseCode
                )
            }

            val data = json.optJSONObject("data")
                ?: return ApiResult.Error("Invalid response: missing 'data'", responseCode)

            ApiResult.Success(
                InitResponse(
                    userId = data.optString("user_id", ""),
                    sessionToken = data.optString("session_token", ""),
                    expiresIn = data.optLong("expires_in", 86400)
                )
            )
        } catch (e: Exception) {
            DataPointLogger.e("Validate request failed", e)
            ApiResult.Error(e.message ?: "Network error", 0)
        } finally {
            connection?.disconnect()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun parseErrorMessage(body: String?, httpCode: Int): String {
        if (body.isNullOrBlank()) return "Request failed ($httpCode)"
        return try {
            JSONObject(body).optString("message", "Request failed ($httpCode)")
        } catch (_: Exception) {
            "Request failed ($httpCode)"
        }
    }
}
