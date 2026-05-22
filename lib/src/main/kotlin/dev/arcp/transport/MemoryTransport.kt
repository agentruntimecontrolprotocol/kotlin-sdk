package dev.arcp.transport

import dev.arcp.envelope.Envelope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * In-process transport pair used for testing (RFC §22 — non-mandatory but
 * useful as a reference). [pair] returns two [MemoryTransport]s that exchange
 * envelopes synchronously: [Pair.first] sends to [Pair.second] and vice versa.
 *
 * Channels are bounded with [BufferOverflow.SUSPEND]; backpressure is
 * therefore real, not faked.
 */
public class MemoryTransport private constructor(
    private val outbound: Channel<Envelope>,
    private val inbound: Channel<Envelope>,
) : Transport {
    override suspend fun send(envelope: Envelope) {
        outbound.send(envelope)
    }

    override fun receive(): Flow<Envelope> = inbound.receiveAsFlow()

    override fun close() {
        outbound.close()
        inbound.close()
    }

    public companion object {
        /** Default per-direction channel capacity. */
        public const val DEFAULT_CAPACITY: Int = 64

        /** Returns a connected pair (client, server). */
        public fun pair(capacity: Int = DEFAULT_CAPACITY): Pair<MemoryTransport, MemoryTransport> {
            val a = Channel<Envelope>(capacity, BufferOverflow.SUSPEND)
            val b = Channel<Envelope>(capacity, BufferOverflow.SUSPEND)
            // client.outbound -> server.inbound (channel a)
            // server.outbound -> client.inbound (channel b)
            val client = MemoryTransport(outbound = a, inbound = b)
            val server = MemoryTransport(outbound = b, inbound = a)
            return client to server
        }
    }
}
