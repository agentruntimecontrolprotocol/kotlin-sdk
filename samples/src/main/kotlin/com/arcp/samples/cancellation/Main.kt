package com.arcp.samples.cancellation

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/** Two scenarios over the §10.4 / §10.5 control surface. */

private const val CANCEL_DEADLINE_MS: Long = 5_000

private suspend fun startLongJob(client: ARCPClient): JobId {
    val accepted = client.request(
        envelope = client.envelope(
            type = "tool.invoke",
            payload = mapOf(
                "tool" to "demo.long_running",
                "arguments" to mapOf("work_seconds" to 600),
            ),
        ),
        timeoutMs = 10_000,
    )
    return JobId(accepted.payloadMap()["job_id"].toString())
}

/**
 * Cooperative cancel. Runtime drives target to a clean checkpoint
 * inside `deadlineMs` before terminating; escalates to ABORTED on
 * timeout (RFC §10.4).
 */
private suspend fun cancelJob(
    client: ARCPClient,
    jobId: JobId,
    reason: String,
    deadlineMs: Long,
): Envelope {
    val reply = client.request(
        envelope = client.envelope(
            type = "cancel",
            payload = mapOf(
                "target" to "job",
                "target_id" to jobId.value,
                "reason" to reason,
                "deadline_ms" to deadlineMs,
            ),
        ),
        timeoutMs = deadlineMs + 5_000,
    )
    if (reply.type == "cancel.refused") {
        throw ARCPException.FailedPrecondition(
            reply.payloadMap()["reason"]?.toString() ?: "cancel refused",
        )
    }
    return reply
}

/**
 * Distinct from cancel: pauses the job (`blocked`), runtime emits
 * `human.input.request`. Job is NOT terminated (RFC §10.5).
 */
private suspend fun interruptJob(client: ARCPClient, jobId: JobId, prompt: String) {
    client.dispatch(
        client.envelope(
            type = "interrupt",
            payload = mapOf(
                "target" to "job",
                "target_id" to jobId.value,
                "prompt" to prompt,
            ),
        ),
    )
}

private suspend fun awaitTerminal(client: ARCPClient, jobId: JobId): Envelope =
    client.events().firstOrNull { env ->
        env.jobId == jobId && env.type in setOf("job.completed", "job.failed", "job.cancelled")
    } ?: throw RuntimeException("event stream closed before terminal")

private suspend fun scenarioCancel() {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()
    try {
        val jobId = startLongJob(client)
        delay(2_000) // let the job actually start
        val ack = cancelJob(client, jobId, reason = "user_aborted", deadlineMs = CANCEL_DEADLINE_MS)
        println("cancel ack: ${ack.type}")
        val terminal = awaitTerminal(client, jobId)
        println("terminal: ${terminal.type} code=${terminal.payloadMap()["code"]}")
    } finally {
        client.close()
    }
}

private suspend fun scenarioInterrupt() {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()
    try {
        val jobId = startLongJob(client)
        delay(2_000)
        interruptJob(client, jobId, prompt = "Pause and ask before touching production tables.")
        // Runtime now emits human.input.request; answer via examples/human_input.
        val env = client.events().first { it.type == "human.input.request" && it.jobId == jobId }
        println("awaiting human: ${env.payloadMap()["prompt"]}")
    } finally {
        client.close()
    }
}

public fun main(args: Array<String>): Unit = runBlocking {
    val which = args.firstOrNull() ?: "cancel"
    when (which) {
        "cancel" -> scenarioCancel()
        "interrupt" -> scenarioInterrupt()
        else -> throw IllegalArgumentException("unknown scenario: $which")
    }
}
