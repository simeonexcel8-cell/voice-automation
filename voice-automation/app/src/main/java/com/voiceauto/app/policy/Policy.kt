package com.voiceauto.app.policy

import com.voiceauto.app.engine.NeedleResponse
import com.voiceauto.app.engine.ToolCall
import com.voiceauto.app.tools.Risk
import com.voiceauto.app.tools.ToolRegistry

sealed interface Decision {
    /** Safe and confident: run now. */
    data class Run(val calls: List<ToolCall>) : Decision

    /** Show the calls and wait for the user to tap Confirm. */
    data class Confirm(
        val calls: List<ToolCall>,
        val why: String,
        val reasoning: String?,
        val destructive: Boolean,
    ) : Decision

    /** Nothing to run. */
    data class Refuse(val message: String) : Decision
}

/**
 * The confirmation rules from the architecture doc, in order:
 * 1. No calls at all → refuse.
 * 2. Any destructive tool → always confirm, whatever the confidence.
 * 3. Calls the engine withheld, or confidence under the threshold → confirm.
 * 4. No confidence score (some fine-tuned weights) → only SAFE tools run directly.
 * 5. Otherwise → run.
 */
class Policy(
    private val registry: ToolRegistry,
    private val threshold: Double = 0.7,
) {
    fun decide(response: NeedleResponse): Decision {
        val withheld = response.calls.isEmpty()
        val calls = response.calls.ifEmpty { response.suppressed }
        if (calls.isEmpty()) return Decision.Refuse("I can't do that on this phone yet.")

        val unknown = calls.filter { registry.find(it.name) == null }
        if (unknown.isNotEmpty()) return Decision.Refuse("I don't have a tool called ${unknown.first().name}.")

        val risks = calls.map { registry.find(it.name)!!.risk }
        val confidence = response.confidence

        return when {
            Risk.DESTRUCTIVE in risks ->
                Decision.Confirm(calls, "This can't be undone easily.", response.reasoning, destructive = true)
            withheld ->
                Decision.Confirm(calls, "I'm not sure I got that right.", response.reasoning, destructive = false)
            confidence == null && risks.any { it != Risk.SAFE } ->
                Decision.Confirm(calls, "Please check this before I do it.", response.reasoning, destructive = false)
            confidence != null && confidence < threshold ->
                Decision.Confirm(calls, "I'm not sure I got that right.", response.reasoning, destructive = false)
            else -> Decision.Run(calls)
        }
    }
}
