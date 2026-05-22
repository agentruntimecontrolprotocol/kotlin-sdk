package com.arcp.recipes.streamresume

import dev.arcp.envelope.Envelope
import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.Nack
import dev.arcp.messages.ResultChunkEncoding
import dev.arcp.messages.Resume
import dev.arcp.messages.RuntimeIdentity
import dev.arcp.messages.SessionAccepted
import dev.arcp.messages.SessionLease
import dev.arcp.messages.SessionOpen
import dev.arcp.messages.JobSubmit
import dev.arcp.transport.Transport
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

// ---------------------------------------------------------------------------
// Streaming chunks (5 UTF-8 segments that reassemble to a sentence).
// ---------------------------------------------------------------------------

private val STREAM_WORDS = listOf("The", " quick", " brown", " fox", " jumps")

// ---------------------------------------------------------------------------
// Custom lightweight server
//
// ARCPRuntime Nacks `result_chunk`, so this recipe uses a hand-rolled server
// coroutine that can push JobResultChunk envelopes without going through the
// runtime dispatcher (RFC v1.1 §8.4).
// ---------------------------------------------------------------------------

/**
 * Minimal ARCP session handler that:
 * 1. Completes the `session.open` handshake.
 * 2. Accepts one `job.submit`, immediately streams five UTF-8 result chunks.
 * 3. Nacks any `resume` with UNIMPLEMENTED — demonstrating that the client
 *    handles the failure gracefully.
 *
 * Run this in a `launch {}` coroutine alongside [runClient].
 */
public suspend fun runServer(transport: Transport) {
    transport.receive().collect { env ->
        when (val payload = env.payload) {
            is SessionOpen -> {
                val sessionId = SessionId.random()
                transport.send(
                    Envelope(
                        id = MessageId.random(),
                        sessionId = sessionId,
                        correlationId = env.id,
                        payload =
                            SessionAccepted(
                                sessionId = sessionId,
                                runtime = RuntimeIdentity(kind = "recipe-server", version = "1.0.0"),
                                capabilities = Capabilities(streaming = true),
                                lease = SessionLease(expiresAt = Clock.System.now().plus(1.hours)),
                            ),
                    ),
                )
            }

            is JobSubmit -> {
                val jobId = JobId.random()
                // Send job.accepted first.
                transport.send(
                    Envelope(
                        id = MessageId.random(),
                        sessionId = env.sessionId,
                        correlationId = env.id,
                        payload = JobAccepted(jobId = jobId),
                    ),
                )
                // Stream the result in five consecutive chunks.
                val resultId = "result-${jobId.value}"
                STREAM_WORDS.forEachIndexed { idx, word ->
                    transport.send(
                        Envelope(
                            id = MessageId.random(),
                            sessionId = env.sessionId,
                            jobId = jobId,
                            payload =
                                JobResultChunk(
                                    resultId = resultId,
                                    chunkSeq = idx.toLong(),
                                    data = word,
                                    encoding = ResultChunkEncoding.UTF8,
                                    more = idx < STREAM_WORDS.size - 1,
                                ),
                        ),
                    )
                }
            }

            is Resume ->
                // EventLog replay is not implemented — Nack gracefully.
                transport.send(
                    Envelope(
                        id = MessageId.random(),
                        sessionId = env.sessionId,
                        correlationId = env.id,
                        payload =
                            Nack(
                                nackFor = env.id,
                                code = ErrorCode.UNIMPLEMENTED,
                                message = "EventLog replay not implemented in this recipe server",
                                retryable = false,
                            ),
                    ),
                )

            else -> Unit // Silently ignore any other message types.
        }
    }
}
