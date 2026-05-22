package com.arcp.recipes.multiagentbudget

import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.MessageId
import dev.arcp.messages.Ack
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.Metric
import dev.arcp.messages.StandardMetrics
import dev.arcp.transport.Transport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Simulated LLM work
// ---------------------------------------------------------------------------

/**
 * Pretends to call an LLM and returns a simulated cost in USD.
 * Replace with a real HTTP client if you want live token accounting.
 */
private fun simulateLlmWork(agentName: String): Double =
    if (agentName == "worker") 0.0031 else 0.0008

// ---------------------------------------------------------------------------
// Client entry point
// ---------------------------------------------------------------------------

/**
 * Opens a session, submits a job to `planner@1.0.0`, then acts as *both* the
 * requesting client **and** the agent implementation for the planner and worker.
 *
 * The agent-side envelopes (metrics, job.completed) are sent directly via
 * [clientTransport] so they flow through the same Channel pair that the
 * [ARCPRuntime] is already reading — no second transport needed.
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
    // 1. Submit job to planner
    // -----------------------------------------------------------------------
    val plannerSubmitId =
        client.send(
            session.sessionId,
            JobSubmit(
                agent = "planner@1.0.0",
                input = JsonObject(mapOf("task" to JsonPrimitive("research and plan"))),
                leaseRequest =
                    JsonObject(
                        mapOf("cost.budget" to JsonArray(listOf(JsonPrimitive("USD:0.50")))),
                    ),
            ),
        )

    val plannerJobId =
        (client.receive().first { it.correlationId == plannerSubmitId }.payload as JobAccepted).jobId
    println("[client] planner accepted  jobId=$plannerJobId")

    // -----------------------------------------------------------------------
    // 2. Planner sub-delegates to worker (parentage via jobId on the envelope)
    // -----------------------------------------------------------------------
    val workerSubmitId = MessageId.random()
    clientTransport.send(
        Envelope(
            id = workerSubmitId,
            sessionId = session.sessionId,
            jobId = plannerJobId,
            payload =
                JobSubmit(
                    agent = "worker@1.0.0",
                    input = JsonObject(mapOf("subtask" to JsonPrimitive("do the work"))),
                    leaseRequest =
                        JsonObject(
                            mapOf("cost.budget" to JsonArray(listOf(JsonPrimitive("USD:0.20")))),
                        ),
                ),
        ),
    )

    val workerJobId =
        (client.receive().first { it.correlationId == workerSubmitId }.payload as JobAccepted).jobId
    println("[client] worker accepted   jobId=$workerJobId")

    // -----------------------------------------------------------------------
    // 3. Worker does work, reports cost, completes
    // -----------------------------------------------------------------------
    val workerSpend = simulateLlmWork("worker")
    clientTransport.send(
        Envelope(
            id = MessageId.random(),
            sessionId = session.sessionId,
            jobId = workerJobId,
            payload =
                Metric(
                    name = StandardMetrics.COST_USD,
                    value = JsonPrimitive(workerSpend),
                    unit = "USD",
                    dims = JsonObject(mapOf("agent" to JsonPrimitive("worker"))),
                ),
        ),
    )
    val workerBudgetEnv =
        client.receive().first {
            (it.payload as? Metric)?.name == StandardMetrics.COST_BUDGET_REMAINING &&
                it.jobId == workerJobId
        }
    println(
        "[client]  metric  ${(workerBudgetEnv.payload as Metric).name}" +
            "  worker  ${(workerBudgetEnv.payload as Metric).value}",
    )

    val workerCompleteId = MessageId.random()
    clientTransport.send(
        Envelope(
            id = workerCompleteId,
            sessionId = session.sessionId,
            jobId = workerJobId,
            payload = JobCompleted(result = JsonObject(mapOf("summary" to JsonPrimitive("worker done")))),
        ),
    )
    client.receive().first { (it.payload as? Ack)?.ackFor == workerCompleteId }
    println("[client] worker completed")

    // -----------------------------------------------------------------------
    // 4. Planner reports its own overhead, then completes
    // -----------------------------------------------------------------------
    val plannerSpend = simulateLlmWork("planner")
    clientTransport.send(
        Envelope(
            id = MessageId.random(),
            sessionId = session.sessionId,
            jobId = plannerJobId,
            payload =
                Metric(
                    name = StandardMetrics.COST_USD,
                    value = JsonPrimitive(plannerSpend),
                    unit = "USD",
                    dims = JsonObject(mapOf("agent" to JsonPrimitive("planner"))),
                ),
        ),
    )
    val plannerBudgetEnv =
        client.receive().first {
            (it.payload as? Metric)?.name == StandardMetrics.COST_BUDGET_REMAINING &&
                it.jobId == plannerJobId
        }
    println(
        "[client]  metric  ${(plannerBudgetEnv.payload as Metric).name}" +
            "  planner  ${(plannerBudgetEnv.payload as Metric).value}",
    )

    val plannerCompleteId = MessageId.random()
    clientTransport.send(
        Envelope(
            id = plannerCompleteId,
            sessionId = session.sessionId,
            jobId = plannerJobId,
            payload = JobCompleted(result = JsonObject(mapOf("status" to JsonPrimitive("all done")))),
        ),
    )
    client.receive().first { (it.payload as? Ack)?.ackFor == plannerCompleteId }
    println("[client] planner completed")
}
