package com.arcp.recipes.emailvendorleases

import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.MessageId
import dev.arcp.messages.Ack
import dev.arcp.messages.Capabilities
import dev.arcp.messages.EventEmit
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.Metric
import dev.arcp.messages.Nack
import dev.arcp.messages.StandardMetrics
import dev.arcp.transport.Transport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Allowed tools — send_reply is intentionally absent to demonstrate
// self-enforced PERMISSION_DENIED (RFC §13.4).
// ---------------------------------------------------------------------------

private val ALLOWED_TOOLS = listOf("inbox_list", "inbox_read")

// ---------------------------------------------------------------------------
// Simulated tool stubs
// ---------------------------------------------------------------------------

private fun simulateInboxList(): List<Map<String, String>> =
    listOf(
        mapOf("id" to "msg-001", "from" to "urgent@example.com", "subject" to "Server is down!"),
        mapOf("id" to "msg-002", "from" to "ceo@example.com", "subject" to "Quarterly review"),
    )

private fun simulateInboxRead(messageId: String): Map<String, String> =
    mapOf(
        "id" to messageId,
        "from" to "urgent@example.com",
        "subject" to "Server is down!",
        "body" to "Production server unreachable since 14:03 UTC. Investigating.",
    )

// ---------------------------------------------------------------------------
// Client entry point
// ---------------------------------------------------------------------------

/**
 * Opens a session, submits a job to `triage@1.0.0` with a read-only tool
 * lease, then acts as the agent implementation using [clientTransport]
 * directly.
 *
 * Tool calls are self-gated against [ALLOWED_TOOLS] before execution.
 * Attempting `send_reply` (absent from the lease) logs a self-enforced
 * PERMISSION_DENIED and drafts the reply locally instead. The agent also
 * emits a vendor extension event; the runtime Nacks it as UNIMPLEMENTED
 * and the client handles that gracefully.
 */
public suspend fun runClient(clientTransport: Transport) {
    val client =
        ARCPClient(
            transport = clientTransport,
            auth = ARCPClient.bearer(TOKEN),
            client = ARCPClient.defaultClientInfo("demo-client"),
            capabilities = Capabilities(),
        )
    val session = client.open()
    println("[client] session opened: ${session.sessionId}")

    // -----------------------------------------------------------------------
    // 1. Submit job with read-only tool lease
    // -----------------------------------------------------------------------
    val submitId =
        client.send(
            session.sessionId,
            JobSubmit(
                agent = "triage@1.0.0",
                input = JsonObject(mapOf("mailbox" to JsonPrimitive("support"))),
                leaseRequest =
                    JsonObject(
                        mapOf(
                            "tool.call" to JsonArray(ALLOWED_TOOLS.map { JsonPrimitive(it) }),
                        ),
                    ),
            ),
        )

    val accepted =
        client.receive().first { it.correlationId == submitId }.payload as JobAccepted
    val jobId = accepted.jobId
    println("[client] job accepted  jobId=$jobId")

    // Print provisioned credentials if any were issued.
    accepted.credentials?.forEach { cred ->
        println("[client] credential issued  scheme=${cred.scheme}  endpoint=${cred.endpoint}")
    }

    // -----------------------------------------------------------------------
    // 2. Agent: call inbox_list (allowed)
    // -----------------------------------------------------------------------
    if ("inbox_list" in ALLOWED_TOOLS) {
        val messages = simulateInboxList()
        println("[client] tool  inbox_list  → ${messages.size} messages")
    } else {
        println("[client] tool  inbox_list  → PERMISSION_DENIED (self-enforced)")
    }

    // -----------------------------------------------------------------------
    // 3. Agent: call inbox_read (allowed)
    // -----------------------------------------------------------------------
    if ("inbox_read" in ALLOWED_TOOLS) {
        val msg = simulateInboxRead("msg-001")
        println("[client] tool  inbox_read(msg-001)  → subject: ${msg["subject"]}")
    } else {
        println("[client] tool  inbox_read  → PERMISSION_DENIED (self-enforced)")
    }

    // -----------------------------------------------------------------------
    // 4. Agent: attempt send_reply (NOT in lease — self-enforced denial)
    // -----------------------------------------------------------------------
    if ("send_reply" !in ALLOWED_TOOLS) {
        println("[client] tool  send_reply  → PERMISSION_DENIED (self-enforced, drafting locally)")
        println("[client] draft  Re: Server is down! — Acknowledged. On it.")
    } else {
        println("[client] tool  send_reply  → sent reply")
    }

    // -----------------------------------------------------------------------
    // 5. Emit vendor-extension event (runtime will Nack UNIMPLEMENTED)
    // -----------------------------------------------------------------------
    val eventId = MessageId.random()
    clientTransport.send(
        Envelope(
            id = eventId,
            sessionId = session.sessionId,
            jobId = jobId,
            payload =
                EventEmit(
                    eventType = "x-vendor.acme.email.parsed",
                    data =
                        JsonObject(
                            mapOf(
                                "message_id" to JsonPrimitive("msg-001"),
                                "from" to JsonPrimitive("urgent@example.com"),
                                "subject" to JsonPrimitive("Server is down!"),
                                "urgency" to JsonPrimitive("high"),
                            ),
                        ),
                ),
        ),
    )

    val eventResponse = client.receive().first { it.correlationId == eventId }
    when (val resp = eventResponse.payload) {
        is Ack -> println("[client] event  x-vendor.acme.email.parsed  → ack")
        is Nack -> println("[client] event  x-vendor.acme.email.parsed  → nack(${resp.code}) — vendor events not stored by this runtime, continuing")
        else -> println("[client] event  x-vendor.acme.email.parsed  → unexpected response")
    }

    // -----------------------------------------------------------------------
    // 6. Report cost metric
    // -----------------------------------------------------------------------
    clientTransport.send(
        Envelope(
            id = MessageId.random(),
            sessionId = session.sessionId,
            jobId = jobId,
            payload =
                Metric(
                    name = StandardMetrics.COST_USD,
                    value = JsonPrimitive(0.0012),
                    unit = "USD",
                    dims = JsonObject(mapOf("agent" to JsonPrimitive("triage"))),
                ),
        ),
    )
    // Consume the budget-remaining metric the runtime emits.
    client.receive().first { it.jobId == jobId }
    println("[client] metric  ${StandardMetrics.COST_USD}  0.0012 USD")

    // -----------------------------------------------------------------------
    // 7. Complete the job
    // -----------------------------------------------------------------------
    val completeId = MessageId.random()
    clientTransport.send(
        Envelope(
            id = completeId,
            sessionId = session.sessionId,
            jobId = jobId,
            payload =
                JobCompleted(
                    result =
                        JsonObject(
                            mapOf(
                                "drafted_reply" to JsonPrimitive("Re: Server is down! — Acknowledged. On it."),
                                "sent" to JsonPrimitive(false),
                                "urgency_flags" to JsonArray(listOf(JsonPrimitive("msg-002"))),
                            ),
                        ),
                ),
        ),
    )
    client.receive().first { (it.payload as? Ack)?.ackFor == completeId }
    println("[client] job completed")
}
