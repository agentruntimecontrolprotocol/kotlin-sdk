package com.arcp.samples.reasoningstreams

/** Primary reasoning step + critic LLM call. Stubbed. */

internal suspend fun primaryStep(
    request: String,
    last: Map<String, Any?>?,
): String = TODO("anthropic-java-sdk: primary step")

internal data class Critique(
    val severity: String,
    val summary: String,
    val suggestion: String,
    val consumedTokens: Int,
)

/** Mirror critic LLM call. Returns (severity, summary, suggestion, tokens). */
internal suspend fun critiqueThought(text: String): Critique = TODO("anthropic-java-sdk: critique")
