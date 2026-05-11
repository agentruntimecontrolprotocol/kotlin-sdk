package com.arcp.samples.permission_challenge

import dev.arcp.envelope.Envelope

/** Stand-ins for the generator + reviewer LLM calls. */

internal data class Patch(val diff: String)

internal data class ReviewVerdict(val grant: Boolean, val reason: String)

internal suspend fun propose(ticket: String, priorDenial: String?): Patch =
    TODO("anthropic-java-sdk: propose patch")

internal suspend fun review(ticket: String, request: Envelope): ReviewVerdict =
    TODO("anthropic-java-sdk: review proposed patch")
