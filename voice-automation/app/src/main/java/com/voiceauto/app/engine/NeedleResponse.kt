package com.voiceauto.app.engine

import org.json.JSONArray
import org.json.JSONObject

/** One tool call returned by Needle: the tool name and its arguments. */
data class ToolCall(val name: String, val arguments: JSONObject)

/**
 * The JSON envelope every Needle turn returns.
 *
 * - [calls]: calls the engine is willing to run.
 * - [suppressed]: calls the engine withheld (confidence under its 0.1 floor, or a
 *   grounding check fired). Show these for confirmation, never run them directly.
 * - [confidence]: null when the weights carry no calibrated confidence head
 *   (for example some fine-tuned builds).
 */
data class NeedleResponse(
    val calls: List<ToolCall>,
    val suppressed: List<ToolCall>,
    val reasoning: String?,
    val confidence: Double?,
) {
    companion object {
        fun parse(json: String): NeedleResponse {
            val root = JSONObject(json)
            return NeedleResponse(
                calls = parseCalls(root.optJSONArray("function_calls")),
                suppressed = parseCalls(root.optJSONArray("suppressed_calls")),
                reasoning = reasoningOf(root.opt("reasoning")),
                confidence = if (root.isNull("confidence")) null else root.optDouble("confidence"),
            )
        }

        private fun parseCalls(array: JSONArray?): List<ToolCall> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // Arguments may arrive as an object or as a JSON string.
                val args = when (val raw = item.opt("arguments")) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                    else -> JSONObject()
                }
                ToolCall(name, args)
            }
        }

        private fun reasoningOf(value: Any?): String? = when (value) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until value.length()).joinToString("; ") { value.opt(it).toString() }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }
}
