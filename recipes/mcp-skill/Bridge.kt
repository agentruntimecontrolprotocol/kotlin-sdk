package com.arcp.recipes.mcpskill

import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.messages.Ack
import dev.arcp.messages.Auth
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobSubmit
import dev.arcp.transport.Transport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---------------------------------------------------------------------------
// MCP-to-ARCP bridge
//
// Translates MCP JSON-RPC 2.0 requests into ARCP job submissions and relays
// results back as MCP tool responses (RFC §16 bridge pattern).
// ---------------------------------------------------------------------------

/**
 * Minimal MCP-to-ARCP bridge.
 *
 * Handles three MCP JSON-RPC 2.0 methods:
 *
 * - `initialize`   — capability handshake; returns server info.
 * - `tools/list`   — enumerates the exposed ARCP skill as an MCP tool.
 * - `tools/call`   — submits an ARCP `job.submit`, awaits `job.accepted`,
 *                    simulates the agent result, sends `job.completed`, then
 *                    returns the result as an MCP content block.
 *
 * Notifications (requests with no `id`) receive no response.
 */
public class McpBridge(private val transport: Transport) {

    private lateinit var client: ARCPClient
    private var sessionId: SessionId? = null

    /** Connect to the ARCP runtime and open a session. */
    public suspend fun connect() {
        client =
            ARCPClient(
                transport = transport,
                auth = Auth(scheme = AuthScheme.NONE),
                client = ARCPClient.defaultClientInfo("mcp-bridge"),
                capabilities = Capabilities(),
            )
        val session = client.open()
        sessionId = session.sessionId
        println("[bridge] connected  sessionId=${session.sessionId}")
    }

    /**
     * Handle one MCP JSON-RPC 2.0 message.
     *
     * @return the serialised response string, or **null** for notifications
     *   (no `id` field) which require no response per the MCP spec.
     */
    public suspend fun handleRequest(json: String): String? {
        val req = Json.parseToJsonElement(json).jsonObject
        val id = req["id"] ?: return null // notification — no response required
        val method =
            req["method"]?.jsonPrimitive?.content
                ?: return buildError(id, -32600, "Missing method")

        return when (method) {
            "initialize" ->
                buildResponse(
                    id,
                    buildJsonObject {
                        put("protocolVersion", "2024-11-05")
                        putJsonObject("serverInfo") {
                            put("name", "arcp-mcp-bridge")
                            put("version", "1.0.0")
                        }
                        putJsonObject("capabilities") {
                            putJsonObject("tools") {}
                        }
                    },
                )

            "tools/list" ->
                buildResponse(
                    id,
                    buildJsonObject {
                        putJsonArray("tools") {
                            addJsonObject {
                                put("name", "research")
                                put("description", "Research a topic using the ARCP planner skill")
                                putJsonObject("inputSchema") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("query") {
                                            put("type", "string")
                                            put("description", "The research query to run")
                                        }
                                    }
                                    putJsonArray("required") { add(JsonPrimitive("query")) }
                                }
                            }
                        }
                    },
                )

            "tools/call" -> {
                val params =
                    req["params"]?.jsonObject
                        ?: return buildError(id, -32602, "Missing params")
                val name =
                    params["name"]?.jsonPrimitive?.content
                        ?: return buildError(id, -32602, "Missing tool name")
                if (name != "research") {
                    return buildError(id, -32602, "Unknown tool: $name")
                }
                val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                val query = args["query"]?.jsonPrimitive?.content ?: "(no query)"
                callResearch(id, query)
            }

            else -> buildError(id, -32601, "Method not found: $method")
        }
    }

    // -------------------------------------------------------------------------
    // Internal: translate tools/call research → ARCP job round-trip
    // -------------------------------------------------------------------------

    private suspend fun callResearch(
        mcpId: JsonElement,
        query: String,
    ): String {
        println("[bridge] tools/call research  query=\"$query\"")

        // 1. Submit job to the planner agent.
        val submitId =
            client.send(
                sessionId!!,
                JobSubmit(
                    agent = "planner@1.0.0",
                    input = buildJsonObject { put("query", query) },
                ),
            )

        // 2. Await job.accepted.
        val accepted =
            client.receive().first { it.correlationId == submitId }.payload as JobAccepted
        val jobId = accepted.jobId
        println("[bridge] job accepted  jobId=$jobId")

        // 3. Simulate the planner agent producing a result (in-process stub).
        val simulatedResult =
            buildJsonObject {
                put("summary", "Research complete for: $query")
                put(
                    "sources",
                    JsonArray(
                        listOf(
                            JsonPrimitive("https://example.invalid/paper-1"),
                            JsonPrimitive("https://example.invalid/paper-2"),
                        ),
                    ),
                )
            }
        println("[bridge] agent result  summary=${simulatedResult["summary"]}")

        // 4. Complete the job (agent role simulated in-process).
        val completeId = MessageId.random()
        transport.send(
            Envelope(
                id = completeId,
                sessionId = sessionId!!,
                jobId = jobId,
                payload = JobCompleted(result = simulatedResult),
            ),
        )

        // 5. Await Ack from runtime.
        client.receive().first { (it.payload as? Ack)?.ackFor == completeId }
        println("[bridge] job completed  ackReceived=true")

        // 6. Return MCP tool result as a text content block.
        val text = Json.encodeToString(JsonObject.serializer(), simulatedResult)
        return buildResponse(
            mcpId,
            buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            },
        )
    }

    // -------------------------------------------------------------------------
    // JSON-RPC helpers
    // -------------------------------------------------------------------------

    private fun buildResponse(
        id: JsonElement,
        result: JsonObject,
    ): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )

    private fun buildError(
        id: JsonElement,
        code: Int,
        message: String,
    ): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            },
        )
}
