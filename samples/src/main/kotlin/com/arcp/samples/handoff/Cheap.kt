package com.arcp.samples.handoff

/** Cheap-tier LLM call → (answer, confidence). Stubbed. */
internal suspend fun attempt(request: String): Pair<String, Double> =
    TODO("anthropic-java-sdk against haiku pool")

/** Trivial canonical JSON encoder for the artifact body. Real version: kotlinx.serialization. */
internal fun canonicalJson(value: Map<String, Any?>): String =
    TODO("emit deterministic JSON for sha256 stability")
