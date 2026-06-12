package com.arcp.samples.tracing

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.ids.JobId
import dev.arcp.trace.TraceContext
import dev.arcp.trace.currentTrace
import dev.arcp.trace.withSpan
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Demonstrates W3C TraceContext propagation through coroutines (RFC §17.1).
 *
 * A root trace is established with [TraceContext.newRoot]; nested
 * [withSpan] calls create child spans that inherit the `traceId` and
 * record their own `spanId` / `parentSpanId`.  Completed spans are
 * emitted as `trace.span` envelopes so an OTLP collector (Jaeger,
 * Grafana Tempo, etc.) can assemble the full trace tree.
 *
 * The same `traceId` is forwarded on every protocol message so the
 * runtime can correlate session events, job events, and custom spans
 * under one distributed trace.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="tracing"
 * ```
 */

// ---------------------------------------------------------------------------
// Span emission helper
// ---------------------------------------------------------------------------

/**
 * Run [block] inside a named child span.  On exit, emit a `trace.span`
 * envelope that includes timing and any [attributes] you supply.
 */
private suspend fun <T> ARCPClient.tracedSpan(
    name: String,
    kind: String = "INTERNAL",
    attributes: Map<String, String> = emptyMap(),
    block: suspend () -> T,
): T {
    val startedAt = Clock.System.now()
    val result: T

    result = withSpan(name) {
        block()
    }

    val endedAt = Clock.System.now()
    val trace = currentTrace() ?: return result

    dispatch(
        envelope(
            type = "trace.span",
            traceId = trace.traceId.value,
            payload = mapOf(
                "name" to name,
                "kind" to kind,
                "trace_id" to trace.traceId.value,
                "span_id" to trace.spanId.value,
                "parent_span_id" to trace.parentSpanId?.value,
                "started_at" to startedAt.toString(),
                "ended_at" to endedAt.toString(),
                "attributes" to attributes,
            ),
        ),
    )

    return result
}

// ---------------------------------------------------------------------------
// Traced workflow
// ---------------------------------------------------------------------------

private suspend fun runTracedWorkflow(client: ARCPClient) {
    val rootTrace = TraceContext.newRoot()

    withContext(rootTrace) {
        println("root trace: ${rootTrace.traceId.value}")

        // Span 1: session open.
        val session = client.tracedSpan("session-open", kind = "CLIENT") {
            client.open()
        }
        println("session: ${session.sessionId}")

        // Span 2: submit job (child of root).
        val accepted = client.tracedSpan(
            name = "job-submit",
            kind = "CLIENT",
            attributes = mapOf("agent" to "summarise@1.0.0"),
        ) {
            client.request(
                envelope = client.envelope(
                    type = "job.submit",
                    traceId = currentTrace()?.traceId?.value,
                    payload = mapOf(
                        "agent" to "summarise@1.0.0",
                        "input" to mapOf("text" to "ARCP tracing sample."),
                    ),
                ),
                timeoutMs = 10_000,
            )
        }
        val jobId = JobId(accepted.payloadMap()["job_id"].toString())
        println("job: $jobId")

        // Span 3: collect result (child of root).
        client.tracedSpan(
            name = "job-collect",
            kind = "CLIENT",
            attributes = mapOf("job_id" to jobId.value),
        ) {
            collectResult(client, jobId)
        }

        // Emit a custom application span with rich attributes.
        withSpan("post-process") {
            val trace = checkNotNull(currentTrace())
            client.dispatch(
                client.envelope(
                    type = "trace.span",
                    traceId = trace.traceId.value,
                    payload = mapOf(
                        "name" to "post-process",
                        "kind" to "INTERNAL",
                        "trace_id" to trace.traceId.value,
                        "span_id" to trace.spanId.value,
                        "parent_span_id" to trace.parentSpanId?.value,
                        "started_at" to Clock.System.now().toString(),
                        "ended_at" to Clock.System.now().toString(),
                        "attributes" to buildJsonObject {
                            put("component", "post_processor")
                            put("job_id", jobId.value)
                        },
                    ),
                ),
            )
            println("post-process span emitted; span=${trace.spanId.value}")
        }
    }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    runTracedWorkflow(client)
    client.close()
}

@Suppress("UNUSED_PARAMETER")
private suspend fun collectResult(client: ARCPClient, jobId: JobId) {
    // illustrative stub — real implementation would await job.completed
}
