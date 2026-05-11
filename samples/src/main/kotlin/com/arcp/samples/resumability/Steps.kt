package com.arcp.samples.resumability

import dev.arcp.client.ARCPClient
import dev.arcp.ids.JobId

/**
 * Step bodies. Real version: a step-graph node per step (anthropic-java-sdk
 * for plan / synth / critique / finalize, vector-store retriever for gather).
 */
internal suspend fun runStep(
    client: ARCPClient,
    jobId: JobId,
    step: String,
    inputs: Map<String, Any?>,
): Any? = TODO("step body for $step")
