package com.voiceauto.app.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** How careful the policy engine must be with a tool. */
enum class Risk {
    /** Easy to reverse or harmless: open an app, turn on the torch. */
    SAFE,
    /** Changes something that persists: alarms, volume, new notes. */
    MEDIUM,
    /** Deletes or cannot be undone. Always confirmed by the user. */
    DESTRUCTIVE,
}

sealed interface ToolResult {
    /** Done. [undo] reverses it when that is possible. */
    data class Done(val message: String, val undo: (suspend () -> String)? = null) : ToolResult
    data class Failed(val reason: String) : ToolResult
}

/**
 * One action the model can choose. Follow Cactus's tool-design guide:
 * one tool per action, names people say, closed sets as enums, bounds in the schema.
 */
interface Tool {
    val name: String
    val risk: Risk

    /** Needle's compact schema: {"name", "description", "parameters", "triggers"?}. */
    fun schema(): JSONObject

    /** One short line for the confirmation card, e.g. "Delete the note “shopping list”". */
    fun describe(args: JSONObject): String

    /** Re-validates the arguments, then acts. Runs on the main thread. */
    suspend fun execute(context: Context, args: JSONObject): ToolResult
}

/** Small helpers to keep the schemas readable. */
internal object Schema {
    fun tool(name: String, description: String, parameters: JSONObject) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("parameters", parameters)

    fun params(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { (key, value) -> properties.put(key, value) }
        return JSONObject().put("type", "object").put("properties", properties).apply {
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
    }

    fun string(description: String, enum: List<String>? = null, pattern: String? = null, maxLength: Int? = null) =
        JSONObject().put("type", "string").put("description", description).apply {
            enum?.let { put("enum", JSONArray(it)) }
            pattern?.let { put("pattern", it) }
            maxLength?.let { put("maxLength", it) }
        }

    fun integer(description: String, min: Int, max: Int, default: Int? = null) =
        JSONObject().put("type", "integer").put("description", description)
            .put("minimum", min).put("maximum", max)
            .apply { default?.let { put("default", it) } }

    fun boolean(description: String) =
        JSONObject().put("type", "boolean").put("description", description)
}
