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
        val externalUserId: String,
        val appId: String,
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
     * Sends the full device context (sdk, app, device, display, network,
     * locale, battery, identifiers, privacy) in a nested JSON body.
     *
     * **Must be called on a background thread.**
     */
    private const val MAX_INIT_ATTEMPTS = 3
    private const val RETRY_BASE_DELAY_MS = 1000L

    fun validate(
        baseUrl: String,
        apiKey: String,
        userId: String?,
        deviceId: String,
        timestamp: Long,
        environment: String,
        sdkInfo: JSONObject,
        appInfo: JSONObject,
        deviceInfo: JSONObject,
        displayInfo: JSONObject,
        networkInfo: JSONObject,
        localeInfo: JSONObject,
        batteryInfo: JSONObject,
        identifiersInfo: JSONObject,
        privacyInfo: JSONObject
    ): ApiResult<InitResponse> {

        val body = JSONObject().apply {
            put("api_key", apiKey)
            if (!userId.isNullOrBlank()) put("user_id", userId)
            put("device_id", deviceId)
            put("timestamp", timestamp)
            put("environment", environment)

            put("sdk", sdkInfo)
            put("app", appInfo)
            put("device", deviceInfo)
            put("display", displayInfo)
            put("network", networkInfo)
            put("locale", localeInfo)
            put("battery", batteryInfo)
            put("identifiers", identifiersInfo)
            put("privacy", privacyInfo)
        }

        var lastResult: ApiResult<InitResponse> = ApiResult.Error("Initialization failed", 0)

        for (attempt in 1..MAX_INIT_ATTEMPTS) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl${SdkConstants.VALIDATE_ENDPOINT}")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    doOutput = true
                }

                DataPointLogger.d(
                    "POST ${url.path} attempt $attempt/$MAX_INIT_ATTEMPTS (body omitted)"
                )

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

                DataPointLogger.d(
                    "initialize response http=$responseCode bytes=${responseBody.length} (body omitted)"
                )

                // 5xx → retry
                if (responseCode in 500..599) {
                    val msg = parseErrorMessage(responseBody, responseCode)
                    lastResult = ApiResult.Error(msg, responseCode)
                    if (attempt < MAX_INIT_ATTEMPTS) {
                        val delay = attempt * RETRY_BASE_DELAY_MS
                        DataPointLogger.w(
                            "Server error ($responseCode), retrying in ${delay}ms " +
                                    "(attempt $attempt/$MAX_INIT_ATTEMPTS)"
                        )
                        Thread.sleep(delay)
                        continue
                    }
                    return lastResult
                }

                // Other non-2xx (4xx etc.) → fail immediately
                if (responseCode !in 200..299) {
                    val msg = parseErrorMessage(responseBody, responseCode)
                    return ApiResult.Error(msg, responseCode)
                }

                // 2xx → parse response
                val json = JSONObject(responseBody)
                if (json.optString("status") != "success") {
                    return ApiResult.Error(
                        json.optString("message", "Initialization failed"),
                        responseCode
                    )
                }

                val data = json.optJSONObject("data")
                    ?: return ApiResult.Error("Invalid response: missing 'data'", responseCode)

                return ApiResult.Success(
                    InitResponse(
                        userId = data.optString("user_id", ""),
                        externalUserId = data.optString("external_user_id", ""),
                        appId = data.optString("app_id", ""),
                        sessionToken = data.optString("session_token", ""),
                        expiresIn = data.optLong("expires_in", 86400)
                    )
                )
            } catch (e: Exception) {
                DataPointLogger.e("Validate request failed (attempt $attempt/$MAX_INIT_ATTEMPTS)", e)
                lastResult = ApiResult.Error(e.message ?: "Network error", 0)
                if (attempt < MAX_INIT_ATTEMPTS) {
                    val delay = attempt * RETRY_BASE_DELAY_MS
                    DataPointLogger.w("Retrying in ${delay}ms…")
                    Thread.sleep(delay)
                    continue
                }
            } finally {
                connection?.disconnect()
            }
        }

        return lastResult
    }

    // ── Set User Attributes ────────────────────────────────────────────

    /**
     * PUT /user/attributes
     *
     * Sends user attributes to the backend.
     *
     * **Must be called on a background thread.**
     */
    fun setAttributes(
        baseUrl: String,
        sessionToken: String,
        attributes: Map<String, Any>
    ): ApiResult<Unit> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl${SdkConstants.USER_ATTRIBUTES_ENDPOINT}")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $sessionToken")
                connectTimeout = 30_000
                readTimeout = 30_000
                doOutput = true
            }

            val body = JSONObject().apply {
                val attrsObj = JSONObject()
                for ((key, value) in attributes) {
                    attrsObj.put(key, value)
                }
                put("attributes", attrsObj)
            }

            DataPointLogger.d("PUT ${url.path} (request body omitted)")

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

            DataPointLogger.d(
                "setAttributes response http=$responseCode bytes=${responseBody.length} (body omitted)"
            )

            if (responseCode !in 200..299) {
                val msg = parseErrorMessage(responseBody, responseCode)
                return ApiResult.Error(msg, responseCode)
            }

            ApiResult.Success(Unit)
        } catch (e: Exception) {
            DataPointLogger.e("Set attributes request failed", e)
            ApiResult.Error(e.message ?: "Network error", 0)
        } finally {
            connection?.disconnect()
        }
    }

    // ── Assign app user id ───────────────────────────────────────────────

    /**
     * POST /assign_app_user_id
     *
     * Associates an app-level user identifier with the current session.
     * Body: `{ "app_user_id": "<value>" }`.
     *
     * **Must be called on a background thread.**
     */
    fun setAppUserId(
        baseUrl: String,
        sessionToken: String,
        appUserId: String
    ): ApiResult<Unit> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$baseUrl${SdkConstants.ASSIGN_APP_USER_ID_ENDPOINT}")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $sessionToken")
                connectTimeout = 30_000
                readTimeout = 30_000
                doOutput = true
            }

            val body = JSONObject().apply {
                put("app_user_id", appUserId)
            }

            DataPointLogger.d("POST ${url.path} (request body omitted)")

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

            DataPointLogger.d(
                "assign_app_user_id response http=$responseCode bytes=${responseBody.length} (body omitted)"
            )

            if (responseCode !in 200..299) {
                val msg = parseErrorMessage(responseBody, responseCode)
                return ApiResult.Error(msg, responseCode)
            }

            ApiResult.Success(Unit)
        } catch (e: Exception) {
            DataPointLogger.e("assign_app_user_id request failed", e)
            ApiResult.Error(e.message ?: "Network error", 0)
        } finally {
            connection?.disconnect()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun parseErrorMessage(body: String?, httpCode: Int): String {
        if (body.isNullOrBlank()) return "Request failed ($httpCode)"
        return try {
            val json = JSONObject(body)
            json.optString("detail", "").ifBlank {
                json.optString("message", "").ifBlank {
                    "Request failed ($httpCode)"
                }
            }
        } catch (_: Exception) {
            "Request failed ($httpCode)"
        }
    }

    fun httpCodeToErrorCode(httpCode: Int): Int {
        return when (httpCode) {
            400 -> com.datapoint.sdk.callbacks.ErrorCode.INVALID_REQUEST
            401 -> com.datapoint.sdk.callbacks.ErrorCode.INVALID_API_KEY
            in 500..599 -> com.datapoint.sdk.callbacks.ErrorCode.SERVER_ERROR
            0 -> com.datapoint.sdk.callbacks.ErrorCode.NETWORK_ERROR
            else -> com.datapoint.sdk.callbacks.ErrorCode.INITIALIZATION_FAILED
        }
    }
}
