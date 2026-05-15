package com.arcp.samples.handoff

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import com.arcp.samples.sessionIdOrNull
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.ids.ArtifactId
import dev.arcp.ids.TraceId
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64

/** Cheap-tier first; escalate to deep tier via agent.handoff. */

private const val CONFIDENCE_THRESHOLD: Double = 0.65
private const val CHEAP_URL: String = "wss://haiku-pool.tier1.internal"
private const val DEEP_URL: String = "wss://opus-pool.tier3.internal"
private const val DEEP_KIND: String = "arcp-opus-pool"
private const val DEEP_FINGERPRINT: String = "sha256:0a37bf7d61cca21f00..." // pinned

private suspend fun packageContext(
    cheap: ARCPClient,
    transcript: Map<String, Any?>,
): Map<String, Any?> {
    val body = canonicalJson(transcript).toByteArray()
    val artifactId = ArtifactId.random()
    val sha =
        MessageDigest
            .getInstance("SHA-256")
            .digest(body)
            .joinToString("") { "%02x".format(it) }
    val reply =
        cheap.request(
            envelope =
                cheap.envelope(
                    type = "artifact.put",
                    payload =
                        mapOf(
                            "artifact_id" to artifactId.value,
                            "media_type" to "application/json",
                            "size" to body.size,
                            "sha256" to sha,
                            "data" to Base64.getEncoder().encodeToString(body),
                        ),
                ),
            timeoutMs = 15_000,
        )
    if (reply.type != "artifact.ref") {
        throw ARCPException.Internal("got ${reply.type}")
    }
    return reply.payloadMap()
}

private suspend fun emitHandoff(
    cheap: ARCPClient,
    artifactRef: Map<String, Any?>,
    traceId: TraceId,
) {
    cheap.dispatch(
        cheap.envelope(
            type = "agent.handoff",
            traceId = traceId.value,
            payload =
                mapOf(
                    "target_runtime" to
                        mapOf(
                            "url" to DEEP_URL,
                            "kind" to DEEP_KIND,
                            "fingerprint" to DEEP_FINGERPRINT,
                        ),
                    "session_id" to cheap.sessionIdOrNull()?.value,
                    // RFC §14 gestures at shared_memory_ref; we use it
                    // explicitly so the deep tier knows where the transcript lives.
                    "shared_memory_ref" to artifactRef,
                ),
        ),
    )
}

public fun main(): Unit = runBlocking {
    val cheap: ARCPClient = TODO("transport=WebSocketTransport($CHEAP_URL), pinned identity")
    val accepted = cheap.open()
    // Pin runtime kind + fingerprint (RFC §8.3); refuse on mismatch.
    if (accepted.runtime.kind != "arcp-haiku-pool") {
        throw ARCPException.Unauthenticated("cheap kind mismatch")
    }

    val request = "what does CRDT stand for?"
    val traceId = TraceId.random()

    val (answer, confidence) = attempt(request)
    if (confidence >= CONFIDENCE_THRESHOLD) {
        println(answer)
    } else {
        val artifact =
            packageContext(
                cheap,
                transcript =
                    mapOf(
                        "user_request" to request,
                        "transcript" to
                            listOf(
                                mapOf("role" to "user", "content" to request),
                                mapOf("role" to "assistant", "content" to answer),
                            ),
                        "cheap_confidence" to confidence,
                    ),
            )
        emitHandoff(cheap, artifact, traceId)
        println("[handed off to $DEEP_KIND trace_id=${traceId.value}]")
    }
    cheap.close()
}
