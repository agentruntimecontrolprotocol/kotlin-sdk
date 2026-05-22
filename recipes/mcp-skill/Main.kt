package com.arcp.recipes.mcpskill

import dev.arcp.transport.MemoryTransport
import kotlinx.coroutines.runBlocking

/**
 * Entry point for the mcp-skill recipe.
 *
 * Simulates an MCP host sending four canned JSON-RPC 2.0 messages to the
 * [McpBridge], making the recipe fully self-runnable without real stdin or an
 * external MCP client.
 *
 * Message sequence:
 * 1. `initialize`                   — capability handshake
 * 2. `notifications/initialized`    — notification (no response)
 * 3. `tools/list`                   — discover exposed ARCP skills as tools
 * 4. `tools/call research`          — invoke the research skill end-to-end
 */
fun main(): Unit =
    runBlocking {
        val (clientTransport, serverTransport) = MemoryTransport.pair()
        val runtime = runServer(serverTransport)

        val bridge = McpBridge(clientTransport)
        bridge.connect()

        // Canned MCP host messages (JSON-RPC 2.0).
        val hostMessages =
            listOf(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-host","version":"0.1.0"}}}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"research","arguments":{"query":"advances in multi-agent coordination protocols"}}}""",
            )

        for (msg in hostMessages) {
            println("[host ] → $msg")
            val response = bridge.handleRequest(msg)
            if (response != null) {
                println("[host ] ← $response")
            }
        }

        runtime.close()
    }
