package com.arcp.recipes.streamresume

import dev.arcp.client.ARCPClient
import dev.arcp.client.ResultChunkAssembler
import dev.arcp.messages.Auth
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.Nack
import dev.arcp.messages.Resume
import dev.arcp.messages.JobSubmit
import dev.arcp.transport.Transport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Client entry point
// ---------------------------------------------------------------------------

/**
 * Opens a session, submits a job to `streamer@1.0.0`, then collects five
 * streaming [JobResultChunk] envelopes via [ResultChunkAssembler].  Once the
 * final chunk arrives (`more = false`) the assembled sentence is printed.
 *
 * The client then sends a [Resume] to demonstrate that the custom server
 * Nacks it gracefully (EventLog replay not implemented), and continues
 * without error.
 *
 * Run this in a `runBlocking {}` alongside a `launch { runServer(...) }`.
 */
public suspend fun runClient(clientTransport: Transport) {
    val client =
        ARCPClient(
            transport = clientTransport,
            auth = Auth(scheme = AuthScheme.NONE),
            client = ARCPClient.defaultClientInfo("stream-recipe"),
            capabilities = Capabilities(streaming = true),
        )
    val session = client.open()
    println("[client] session opened  sessionId=${session.sessionId}")

    // -----------------------------------------------------------------------
    // 1. Submit job
    // -----------------------------------------------------------------------
    val submitId =
        client.send(
            session.sessionId,
            JobSubmit(
                agent = "streamer@1.0.0",
                input = JsonObject(mapOf("prompt" to JsonPrimitive("tell me a sentence"))),
            ),
        )

    val accepted = client.receive().first { it.correlationId == submitId }.payload as JobAccepted
    val jobId = accepted.jobId
    println("[client] job accepted  jobId=$jobId")

    // -----------------------------------------------------------------------
    // 2. Collect streaming chunks until the assembler signals completion
    // -----------------------------------------------------------------------
    val assembler = ResultChunkAssembler()
    var assembled: ResultChunkAssembler.AssembledResult? = null

    while (assembled == null) {
        val env = client.receive().first { env -> env.jobId == jobId && env.payload is JobResultChunk }
        val chunk = env.payload as JobResultChunk
        println("[client] chunk  seq=${chunk.chunkSeq}  more=${chunk.more}  data=\"${chunk.data}\"")
        assembled = assembler.accept(chunk)
    }

    val text = if (assembled.isText) assembled.bytes.decodeToString() else "<binary>"
    println("[client] assembled  \"$text\"")

    // -----------------------------------------------------------------------
    // 3. Send Resume — custom server Nacks UNIMPLEMENTED; handled gracefully
    // -----------------------------------------------------------------------
    val resumeId =
        client.send(
            session.sessionId,
            Resume(
                sessionId = session.sessionId,
                jobId = jobId,
                includeOpenStreams = true,
            ),
        )

    val resumeResp = client.receive().first { it.correlationId == resumeId }
    when (val p = resumeResp.payload) {
        is Nack ->
            println(
                "[client] resume  → nack(${p.code}) — EventLog replay not supported by this server, continuing",
            )
        else -> println("[client] resume  → unexpected response: $p")
    }

    println("[client] done")
}
