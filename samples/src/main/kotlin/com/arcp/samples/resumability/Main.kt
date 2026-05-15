package com.arcp.samples.resumability

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.JobId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Durable research job with real crash and resume.
 *
 * ```
 * # First call: crash after `synthesize`. Prints the resume token.
 * CRASH_AFTER_STEP=synthesize ./gradlew :samples:run --args=...
 *
 * # Second call: pick up from the printed checkpoint.
 * RESUME_JOB_ID=... RESUME_AFTER_MSG_ID=... RESUME_CHECKPOINT_ID=... ./gradlew ...
 * ```
 */

private val STEPS = listOf("plan", "gather", "synthesize", "critique", "finalize")

/**
 * Deterministic per-step idempotency key (RFC §6.4). Re-issuing the
 * same step with the same input returns the prior outcome instead
 * of re-running the LLM.
 */
internal fun stepKey(
    jobId: JobId,
    step: String,
    salt: String,
): String {
    val h = MessageDigest.getInstance("SHA-256")
    listOf(jobId.value, step, salt).forEach {
        h.update(it.toByteArray())
        h.update(byteArrayOf(0))
    }
    val hex = h.digest().joinToString("") { "%02x".format(it) }.substring(0, 16)
    return "research:${jobId.value}:$step:$hex"
}

private suspend fun emitProgress(
    client: ARCPClient,
    jobId: JobId,
    step: String,
) {
    val pct = 100.0 * (STEPS.indexOf(step) + 1) / STEPS.size
    client.dispatch(
        client.envelope(
            type = "job.progress",
            jobId = jobId,
            payload = mapOf("percent" to pct, "message" to step),
        ),
    )
}

private suspend fun emitCheckpoint(
    client: ARCPClient,
    jobId: JobId,
    step: String,
): String {
    val chk = "chk_${step}_${jobId.value.takeLast(6)}"
    client.dispatch(
        client.envelope(
            type = "job.checkpoint",
            jobId = jobId,
            payload = mapOf("checkpoint_id" to chk, "label" to step),
        ),
    )
    return chk
}

private suspend fun executeSteps(
    client: ARCPClient,
    jobId: JobId,
    request: Any?,
    startingAt: String,
    crashAfter: String?,
): Any? {
    var output: Any? = request
    val startIdx = STEPS.indexOf(startingAt)
    for ((i, step) in STEPS.withIndex()) {
        if (i < startIdx) continue
        val key = stepKey(jobId, step, output.toString())
        emitProgress(client, jobId, step)
        output =
            runStep(
                client,
                jobId = jobId,
                step = step,
                inputs = mapOf("prior" to output, "idempotency_key" to key),
            )
        emitCheckpoint(client, jobId, step)
        if (crashAfter == step) {
            // The whole point of durable jobs: process death is fine.
            // Runtime kept every envelope; resume picks it up.
            println(
                "[crash after $step; resume with " +
                    "RESUME_JOB_ID=${jobId.value} " +
                    "RESUME_CHECKPOINT_ID=chk_${step}_${jobId.value.takeLast(6)} " +
                    "RESUME_AFTER_MSG_ID=<last id from your event log>]",
            )
            exitProcess(137)
        }
    }
    return output
}

/**
 * Replay envelopes; return the last checkpoint label, or null if the
 * job already terminated during replay.
 */
private suspend fun issueResume(
    client: ARCPClient,
    jobId: JobId,
    afterMessageId: String,
    checkpointId: String?,
): String? {
    val payload: MutableMap<String, Any?> =
        mutableMapOf(
            "after_message_id" to afterMessageId,
            "include_open_streams" to true,
        )
    if (checkpointId != null) payload["checkpoint_id"] = checkpointId
    client.dispatch(client.envelope(type = "resume", jobId = jobId, payload = payload))

    var last: String? = null
    client.events().collect { env ->
        if (env.jobId != jobId) return@collect
        if (env.type == "tool.error" &&
            env.payloadMap()["code"] == ErrorCode.DATA_LOSS.wire
        ) {
            throw ARCPException.DataLoss("retention expired")
        }
        when (env.type) {
            "job.checkpoint" -> last = env.payloadMap()["label"]?.toString()
            "job.completed", "job.failed", "job.cancelled" -> return@collect
            "event.emit" -> {
                if (env.payloadMap()["name"] == "subscription.backfill_complete") {
                    return@collect // replay window closed; we're now live
                }
            }
        }
    }
    return last
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    val rjId = System.getenv("RESUME_JOB_ID")
    val rjAfter = System.getenv("RESUME_AFTER_MSG_ID")
    if (rjId != null && rjAfter != null) {
        val jobId = JobId(rjId)
        val last =
            issueResume(
                client,
                jobId = jobId,
                afterMessageId = rjAfter,
                checkpointId = System.getenv("RESUME_CHECKPOINT_ID"),
            )
        if (last == null) {
            println("already terminal during replay")
        } else {
            val nextIdx = STEPS.indexOf(last) + 1
            if (nextIdx >= STEPS.size) {
                println("nothing to resume")
            } else {
                println("[resuming at ${STEPS[nextIdx]}]")
                val final =
                    executeSteps(
                        client,
                        jobId = jobId,
                        request = "<replayed>",
                        startingAt = STEPS[nextIdx],
                        crashAfter = null,
                    )
                client.dispatch(
                    client.envelope(
                        type = "job.completed",
                        jobId = jobId,
                        payload = mapOf("result" to final),
                    ),
                )
            }
        }
    } else {
        val jobId = JobId.random()
        val request = "Survey CRDT-based collaborative editing in 2026."
        client.dispatch(
            client.envelope(
                type = "workflow.start",
                jobId = jobId,
                payload =
                    mapOf(
                        "workflow" to "research.v1",
                        "arguments" to mapOf("request" to request),
                    ),
            ),
        )
        val final =
            executeSteps(
                client,
                jobId = jobId,
                request = request,
                startingAt = STEPS[0],
                crashAfter = System.getenv("CRASH_AFTER_STEP"),
            )
        client.dispatch(
            client.envelope(
                type = "job.completed",
                jobId = jobId,
                payload = mapOf("result" to final),
            ),
        )
        println("job_id=${jobId.value}\n$final")
    }
    client.close()
}
