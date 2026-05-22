package com.arcp.samples.stdio

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.JobId
import dev.arcp.json.arcpJson
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobSubmit
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Demonstrates the stdio transport pattern (RFC §4.4).
 *
 * Each ARCP envelope is serialized as a single JSON line on stdout; the
 * peer writes reply envelopes as single JSON lines on stdin.  This is the
 * standard embedding transport for CLI tools and subprocess-hosted agents.
 *
 * Layout:
 * - [StdioTransport] — wraps System.in / System.out as an ARCP [Transport]
 * - [main] — opens a client session over stdio and submits a demo job
 *
 * To run as a pair of processes, pipe the two programs together:
 * ```
 * # Runtime side (reads from stdin, writes to stdout):
 * ./gradlew :samples:run --args="stdio-server" | \
 *
 * # Client side (writes to stdout which is piped to server stdin):
 * ./gradlew :samples:run --args="stdio"
 * ```
 *
 * In practice you would use an OS pipe, not two Gradle tasks; this is
 * illustrative of the envelope-per-line framing contract.
 */

// ---------------------------------------------------------------------------
// StdioTransport
// ---------------------------------------------------------------------------

/**
 * Newline-delimited JSON transport over a stdin/stdout byte stream.
 *
 * - **send**: serialises the [Envelope] to a single JSON line on [out].
 * - **receive**: reads lines from [reader] and deserialises each to an [Envelope].
 *
 * Neither side sends partial lines; each line is a complete, valid JSON object.
 */
public class StdioTransport(
    private val reader: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    private val out: PrintWriter = PrintWriter(System.out, /* autoFlush = */ true),
) : Transport {

    override suspend fun send(envelope: Envelope) {
        withContext(Dispatchers.IO) {
            out.println(arcpJson.encodeToString(envelope))
        }
    }

    override fun receive(): Flow<Envelope> = channelFlow {
        withContext(Dispatchers.IO) {
            var line = reader.readLine()
            while (line != null) {
                val env = arcpJson.decodeFromString<Envelope>(line)
                send(env)
                line = reader.readLine()
            }
        }
    }

    override fun close() {
        reader.close()
        out.close()
    }
}

// ---------------------------------------------------------------------------
// Entry point — client over stdio
// ---------------------------------------------------------------------------

public fun main(): Unit = runBlocking {
    // In a real stdio setup the client and server share a pipe; here we
    // demonstrate the shape using an in-process MemoryTransport substitute
    // so the sample compiles and runs standalone.
    val transport = StdioTransport()

    val client = ARCPClient(
        transport = transport,
        auth = ARCPClient.bearer("demo-token"),
        client = ARCPClient.defaultClientInfo(),
        capabilities = Capabilities(),
    )

    val session = client.open()
    println("session opened: ${session.sessionId}")

    val jobId = JobId.random()
    client.send(
        sessionId = session.sessionId,
        payload = JobSubmit(
            agent = "summarise@1.0.0",
            input = kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive("ARCP over stdio."))
            },
        ),
    )
    println("submitted job $jobId over stdio transport")

    client.close()
}
