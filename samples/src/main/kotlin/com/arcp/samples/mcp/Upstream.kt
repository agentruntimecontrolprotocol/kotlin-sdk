package com.arcp.samples.mcp

import dev.arcp.messages.MessageType

/**
 * Upstream MCP server invocation. Real version: stdio transport from the
 * official `io.modelcontextprotocol:kotlin-sdk` once stable.
 */

internal data class StdioServerParameters(
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

internal fun upstreamParams(): StdioServerParameters = TODO("MCP server stdio invocation")

// --- MCP client stubs (illustrative) -------------------------------------

internal data class McpToolDescriptor(
    val name: String,
)

internal data class McpContent(
    val text: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf("type" to "text", "text" to text)
}

internal data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean,
)

internal class McpSession : AutoCloseable {
    suspend fun initialize(): Unit = TODO("mcp initialize")

    suspend fun listTools(): List<McpToolDescriptor> = TODO("mcp tools/list")

    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): McpToolResult = TODO("mcp call_tool")

    override fun close(): Unit = TODO("mcp close")

    companion object {
        fun connect(params: StdioServerParameters): McpSession = TODO("mcp stdio connect")
    }
}

// --- envelope payload helper for the bridge ------------------------------
//
// The Kotlin SDK's [MessageType] is sealed in `dev.arcp.messages`, so the
// bridge cannot mint ad-hoc payloads from a sample package. In production
// the runtime hands these envelopes through the typed builders in
// `dev.arcp.messages.Execution` etc.; the sample elides that with [stub].

internal class StubPayload(
    val wireType: String,
    val fields: Map<String, Any?>,
) {
    companion object
}

internal fun stub(
    wireType: String,
    fields: Map<String, Any?>,
): MessageType = TODO("v1.0: mint typed MessageType for $wireType from $fields")
