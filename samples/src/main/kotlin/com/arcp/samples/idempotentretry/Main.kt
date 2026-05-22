package com.arcp.samples.idempotentretry

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/**
 * Idempotent job submission with structured retry (RFC §6.4).
 *
 * The idempotency key is derived deterministically from the caller's
 * own request ID so any process restart re-submits the exact same key —
 * the runtime deduplicates and returns the already-accepted job instead
 * of spawning a duplicate.
 *
 * Retryable error codes (`DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`,
 * `UNAVAILABLE`) trigger an exponential back-off loop.  Non-retryable
 * codes re-throw immediately.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="idempotent-retry"
 * ```
 */

private const val MAX_ATTEMPTS: Int = 5
private const val BASE_DELAY_MS: Long = 200L

/**
 * Derive a stable idempotency key from a caller-controlled request ID.
 *
 * The key space is `"submit:<requestId>:<sha8>"` where `sha8` is the
 * first 8 hex digits of SHA-256(requestId).  The SHA prefix prevents
 * accidental collisions when two request IDs share a common prefix.
 */
internal fun idempotencyKey(requestId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val sha8 = digest.digest(requestId.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .substring(0, 8)
    return "submit:$requestId:$sha8"
}

/**
 * Submit a job, retrying on transient errors.
 *
 * Returns the accepted [JobId].  Throws on non-retryable errors or
 * after [MAX_ATTEMPTS] exhausted.
 */
internal suspend fun submitWithRetry(
    client: ARCPClient,
    requestId: String,
    agentName: String,
    input: Map<String, Any?>,
): JobId {
    val key = idempotencyKey(requestId)
    var lastException: Exception? = null

    repeat(MAX_ATTEMPTS) { attempt ->
        try {
            val submitted = client.dispatch(
                client.envelope(
                    type = "job.submit",
                    idempotencyKey = key,
                    payload = mapOf(
                        "agent" to agentName,
                        "input" to input,
                        "idempotency_key" to key,
                    ),
                ),
            )
            // Wait for job.accepted (first message correlated to our submit).
            val accepted = client.events().first { env ->
                env.type == "job.accepted" &&
                    env.payloadMap()["idempotency_key"] == key
            }

            val jobId = JobId(accepted.payloadMap()["job_id"].toString())
            val duplicate = accepted.payloadMap()["duplicate"] as? Boolean ?: false
            if (duplicate) {
                println("attempt $attempt: runtime returned existing job $jobId (idempotent)")
            } else {
                println("attempt $attempt: new job accepted → $jobId")
            }
            return jobId
        } catch (e: ARCPException) {
            if (!e.retryable || attempt == MAX_ATTEMPTS - 1) throw e
            lastException = e
            val delayMs = BASE_DELAY_MS * (1L shl attempt)   // 200, 400, 800, 1600 ms
            println("attempt $attempt failed (${e::class.simpleName}), retrying in ${delayMs}ms")
            delay(delayMs)
        }
    }
    throw lastException ?: error("unreachable")
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    // Use a stable request ID (e.g., from the caller's DB row PK).
    // Re-running main() with the same requestId will deduplicate.
    val requestId = System.getenv("REQUEST_ID") ?: "req_demo_001"

    val jobId = submitWithRetry(
        client,
        requestId = requestId,
        agentName = "summarise@1.0.0",
        input = mapOf(
            "text" to "ARCP is a protocol for agent runtime control.",
            "max_sentences" to 2,
        ),
    )

    println("job running: $jobId")

    // Collect result.
    client.events()
        .first { env ->
            env.jobId == jobId &&
                env.type in setOf("job.completed", "job.failed", "job.cancelled")
        }
        .also { terminal ->
            when (terminal.type) {
                "job.completed" -> println("result: ${terminal.payloadMap()["result"]}")
                "job.failed"    -> println("failed: ${terminal.payloadMap()["message"]}")
                else            -> println("cancelled")
            }
        }

    client.close()
}
