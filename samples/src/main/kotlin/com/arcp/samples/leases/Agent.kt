package com.arcp.samples.leases

import kotlinx.coroutines.flow.Flow

/**
 * Stand-in for the Anthropic tool-use loop. Real version: an
 * `anthropic-java-sdk` client with a system prompt, emitting one [LlmStep]
 * per turn.
 */

internal data class ToolCall(
    val argv: List<String>,
    val reason: String,
)

internal data class LlmStep(
    val thought: String,
    val toolCall: ToolCall? = null,
    val final: String? = null,
)

internal fun llmLoop(userRequest: String): Flow<LlmStep> = TODO("anthropic-java-sdk loop")
