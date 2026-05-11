package com.arcp.samples.mcp

import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * ARCP runtime fronting an MCP server (RFC §20).
 *
 * MCP describes capabilities; ARCP operationalizes them. This bridge
 * translates inbound ARCP `tool.invoke` envelopes into MCP `call_tool`
 * calls against an upstream MCP server, and emits the ARCP job
 * lifecycle back to the calling client.
 *
 * ```
 * ARCP client ──tool.invoke──> bridge ──call_tool──> MCP server
 * ARCP client <─job.{accepted,started,completed,failed}─ bridge
 * ```
 *
 * Per RFC §20:
 * - MCP tool schema  → ARCP capability  (advertised at session.accepted)
 * - MCP tool call    → ARCP job
 * - MCP resource     → ARCP stream of `kind: event`  (delegated to MCP)
 *
 * The official `io.modelcontextprotocol:kotlin-sdk` is not yet stable in this
 * tree, so the MCP client is stubbed via [McpSession]. Replace with a vendored
 * bridge once the upstream Kotlin SDK lands.
 */

internal typealias SendEnvelope = suspend (Envelope) -> Unit

/**
 * MCP `tools/list` → namespaced ARCP capability extensions.
 *
 * Each upstream tool surfaces as `arcpx.mcp.tool.<name>.v1` so clients
 * can negotiate which tools they require at session open.
 */
internal suspend fun advertiseFromMcp(mcp: McpSession): List<String> =
    mcp.listTools().map { "arcpx.mcp.tool.${it.name}.v1" }

/**
 * Translate ARCP `tool.invoke.payload` into MCP `call_tool`.
 *
 * MCP returns a list of typed content blocks; we flatten to a JSON-
 * serializable map for the ARCP `tool.result` / `job.completed`
 * payload. MCP errors become canonical ARCP error codes.
 */
internal suspend fun callViaMcp(
    mcp: McpSession,
    tool: String,
    arguments: Map<String, Any?>,
): Map<String, Any?> {
    val result =
        try {
            mcp.callTool(tool, arguments)
        } catch (e: Exception) {
            throw ARCPException.Internal(e.message ?: "mcp call failed", cause = e)
        }
    if (result.isError) {
        val text = result.content.joinToString("\n") { it.text.orEmpty() }
        // MCP doesn't carry a typed error code; FAILED_PRECONDITION is
        // the right canonical mapping for "tool ran, said no".
        throw ARCPException.FailedPrecondition(text.ifBlank { "tool error" })
    }
    return mapOf("content" to result.content.map { it.toMap() })
}

/** One inbound ARCP `tool.invoke` → MCP call → ARCP job lifecycle. */
internal suspend fun handleInvoke(
    send: SendEnvelope,
    mcp: McpSession,
    request: Envelope,
) {
    val jobId = JobId.random()

    send(
        Envelope(
            id = MessageId.random(),
            jobId = jobId,
            correlationId = request.id,
            payload = stub("job.accepted", mapOf("job_id" to jobId.value, "state" to "accepted")),
        ),
    )
    send(
        Envelope(
            id = MessageId.random(),
            jobId = jobId,
            payload = stub("job.started", mapOf("job_id" to jobId.value)),
        ),
    )

    // Real version: pattern-match `request.payload` as `dev.arcp.messages.ToolInvoke`.
    val tool: String = TODO("extract from request.payload (ToolInvoke.tool)")
    val arguments: Map<String, Any?> = TODO("extract from request.payload (ToolInvoke.arguments)")

    try {
        val result = callViaMcp(mcp, tool = tool, arguments = arguments)
        send(
            Envelope(
                id = MessageId.random(),
                jobId = jobId,
                payload = stub("job.completed", mapOf("result" to result)),
            ),
        )
    } catch (e: ARCPException) {
        send(
            Envelope(
                id = MessageId.random(),
                jobId = jobId,
                payload = stub(
                    "job.failed",
                    mapOf("code" to e.code.wire, "message" to e.message, "retryable" to e.retryable),
                ),
            ),
        )
    }
}

/** Wire one MCP session as the upstream for one ARCP runtime. */
internal suspend fun runBridge(send: SendEnvelope, inbound: Flow<Envelope>) {
    McpSession.connect(upstreamParams()).use { mcp ->
        mcp.initialize()
        val extensions = advertiseFromMcp(mcp)
        // In production this list would feed `Capabilities.extensions`
        // at the runtime's `session.accepted` so clients negotiate
        // exactly the MCP tools they expect to use.
        println("bridged: $extensions")

        inbound.collect { envelope ->
            if (envelope.type == "tool.invoke") handleInvoke(send, mcp, envelope)
        }
    }
}

public fun main(): Unit = runBlocking {
    // Production version: instantiate `dev.arcp.runtime.ARCPRuntime`, point its
    // tool-invoke handler at `handleInvoke`, and let the WebSocket transport
    // carry inbound envelopes from real ARCP clients. We elide the runtime
    // wiring (symmetric with examples in `dev.arcp.runtime`) so this file
    // stays focused on the §20 translation between protocols.
    val send: SendEnvelope = TODO("bound to the runtime's outbound channel")
    val inbound: Flow<Envelope> = TODO("async iterator of inbound envelopes")
    runBridge(send, inbound)
}
