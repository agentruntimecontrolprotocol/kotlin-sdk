package com.arcp.samples

import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.ids.StreamId
import dev.arcp.ids.SubscriptionId
import kotlinx.coroutines.flow.Flow

// Illustrative wire helpers shared across the `com.arcp.samples.*` examples.
//
// Each example pretends the SDK already exposes a Python-style
// `client.envelope(...)` mint + `client.request(...)` round-trip +
// `client.events()` flow. The real Kotlin SDK reaches v1.0 via
// `ARCPRuntime` instead; until then, these stubs keep the *protocol* code
// in each sample readable without forcing every example to re-elide the
// same five helpers.
//
// Every helper is `TODO` — calling it at runtime explodes. The samples
// are illustrative, not runnable.

/** Pretend handle for an inflight session id. */
public fun ARCPClient.sessionIdOrNull(): SessionId? = TODO("v1.0: derive from open()")

/** Mint an envelope shaped like Python's `client.envelope(type, payload=...)`. */
@Suppress("UNUSED_PARAMETER")
public fun ARCPClient.envelope(
    type: String,
    payload: Any? = null,
    sessionId: SessionId? = null,
    jobId: JobId? = null,
    streamId: StreamId? = null,
    subscriptionId: SubscriptionId? = null,
    correlationId: MessageId? = null,
    idempotencyKey: String? = null,
    target: String? = null,
    traceId: String? = null,
    extensions: Map<String, Any> = emptyMap(),
): Envelope = TODO("v1.0: ARCPClient.envelope mint helper")

/** Send + await the correlated reply. */
@Suppress("UNUSED_PARAMETER")
public suspend fun ARCPClient.request(
    envelope: Envelope,
    timeoutMs: Long = 30_000,
): Envelope = TODO("v1.0: ARCPClient.request round-trip")

/** Fire-and-forget send. Returns the minted message id. */
@Suppress("UNUSED_PARAMETER")
public suspend fun ARCPClient.dispatch(envelope: Envelope): MessageId =
    TODO("v1.0: ARCPClient.send")

/** Inbound envelope flow. */
public fun ARCPClient.events(): Flow<Envelope> = receive()

/** Convenience accessor: the `payload` field as a typed map for illustrative samples. */
public fun Envelope.payloadMap(): Map<String, Any?> = TODO("v1.0: payload reflection helper")
