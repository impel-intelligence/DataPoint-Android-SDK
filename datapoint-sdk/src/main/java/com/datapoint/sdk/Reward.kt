package com.datapoint.sdk

import androidx.annotation.Keep
import org.json.JSONObject

/**
 * Represents a reward earned by the user after completing a task.
 *
 * @property id   Unique reward identifier returned by the backend.
 * @property raw  The raw JSON payload from the WebView (useful for debugging).
 */
@Keep
data class Reward(
    val id: String,
    val raw: String? = null
) {
    internal companion object {
        /**
         * Parses a reward from the JSON payload sent by the WebView.
         *
         * Expected format:
         * ```json
         * { "reward": { "id": "abc123" } }
         * ```
         * Falls back to reading `id` directly from the root object.
         */
        fun fromPayload(payload: String?): Reward {
            if (payload.isNullOrBlank()) return Reward(id = "", raw = payload)
            return try {
                val json = JSONObject(payload)
                val rewardObj = json.optJSONObject("reward") ?: json
                Reward(
                    id = rewardObj.optString("id", ""),
                    raw = payload
                )
            } catch (_: Exception) {
                Reward(id = "", raw = payload)
            }
        }
    }
}
