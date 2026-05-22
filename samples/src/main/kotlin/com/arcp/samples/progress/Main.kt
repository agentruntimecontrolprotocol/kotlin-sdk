package com.arcp.samples.progress

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import dev.arcp.client.ARCPClient
import dev.arcp.ids.JobId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates `job.progress` and `job.status` reporting (RFC §8.3).
 *
 * The **worker** side emits granular `job.progress` envelopes as it
 * completes each named step.  The **client** side renders a simple
 * progress bar driven by the `percent` field.
 *
 * Steps run sequentially; each writes a `job.checkpoint` on completion
 * so a crashed job can resume from the last known-good step.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="progress"
 * ```
 */

// ---------------------------------------------------------------------------
// Worker side
// ---------------------------------------------------------------------------

private data class Step(val name: String, val weight: Int)

private val PIPELINE_STEPS = listOf(
    Step("fetch",     10),
    Step("parse",     20),
    Step("embed",     40),
    Step("index",     20),
    Step("finalise",  10),
)

internal suspend fun runPipeline(
    client: ARCPClient,
    jobId: JobId,
) {
    var cumulativePercent = 0.0
    val totalWeight = PIPELINE_STEPS.sumOf { it.weight }

    for (step in PIPELINE_STEPS) {
        // Emit "step starting" status.
        client.dispatch(
            client.envelope(
                type = "job.status",
                jobId = jobId,
                payload = mapOf(
                    "state" to "running",
                    "message" to "starting ${step.name}",
                ),
            ),
        )

        // Simulate work.
        doStep(step.name)

        cumulativePercent += (step.weight.toDouble() / totalWeight) * 100.0

        // Emit progress.
        client.dispatch(
            client.envelope(
                type = "job.progress",
                jobId = jobId,
                payload = mapOf(
                    "percent" to cumulativePercent.toInt().coerceAtMost(100),
                    "message" to "${step.name} complete",
                    "step" to step.name,
                ),
            ),
        )

        // Write checkpoint after each step (enables resume).
        client.dispatch(
            client.envelope(
                type = "job.checkpoint",
                jobId = jobId,
                payload = mapOf(
                    "checkpoint_id" to "chk_${step.name}_${jobId.value.takeLast(6)}",
                    "label" to step.name,
                ),
            ),
        )
    }

    client.dispatch(
        client.envelope(
            type = "job.completed",
            jobId = jobId,
            payload = mapOf("indexed_docs" to 1_234),
        ),
    )
}

// ---------------------------------------------------------------------------
// Client / observer side
// ---------------------------------------------------------------------------

internal fun CoroutineScope.watchProgress(
    client: ARCPClient,
    jobId: JobId,
): Job = launch {
    client.events().collect { env ->
        if (env.jobId != jobId) return@collect
        when (env.type) {
            "job.progress" -> {
                val pct = env.payloadMap()["percent"]?.toString()?.toIntOrNull() ?: 0
                val msg = env.payloadMap()["message"]?.toString() ?: ""
                renderBar(pct, msg)
            }
            "job.status" -> {
                println("  status: ${env.payloadMap()["message"]}")
            }
            "job.completed" -> {
                renderBar(100, "done")
                println("\njob completed: ${env.payloadMap()}")
            }
            "job.failed" -> {
                println("\njob failed: ${env.payloadMap()["message"]}")
            }
        }
    }
}

private fun renderBar(
    percent: Int,
    message: String,
) {
    val filled = (percent / 5).coerceIn(0, 20)
    val bar = "█".repeat(filled) + "░".repeat(20 - filled)
    print("\r[$bar] $percent%  $message    ")
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    val jobId = JobId.random()

    client.dispatch(
        client.envelope(
            type = "job.accepted",
            jobId = jobId,
            payload = mapOf("job_id" to jobId.value, "state" to "accepted"),
        ),
    )
    client.dispatch(
        client.envelope(
            type = "job.started",
            jobId = jobId,
            payload = mapOf("job_id" to jobId.value),
        ),
    )

    val watcher = watchProgress(client, jobId)
    runPipeline(client, jobId)
    watcher.join()

    client.close()
}

@Suppress("UNUSED_PARAMETER")
private fun doStep(name: String) {
    // illustrative stub — real implementation would execute the named pipeline step
}
