package dev.fizzpop.arcp.transport

import dev.fizzpop.arcp.envelope.Envelope
import kotlinx.coroutines.flow.Flow

/**
 * Bidirectional, ordered transport for ARCP envelopes (RFC §22).
 *
 * Implementations MUST preserve message body and per-stream ordering. They
 * MAY provide stronger global ordering. Cancellation of [receive] cleanly
 * unsubscribes the flow without dropping in-flight envelopes elsewhere.
 */
public interface Transport : AutoCloseable {
    /** Sends [envelope] to the peer. Suspends until the bytes are flushed. */
    public suspend fun send(envelope: Envelope)

    /**
     * Cold flow of envelopes received from the peer. Each call returns a fresh
     * cold flow; collecting twice from the same transport is implementation-
     * defined (most reuse the same upstream channel).
     */
    public fun receive(): Flow<Envelope>

    /** Closes the transport and releases resources. Idempotent. */
    override fun close()
}
