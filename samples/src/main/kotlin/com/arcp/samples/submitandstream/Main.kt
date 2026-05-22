package com.arcp.samples.submitandstream

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.ids.JobId
import dev.arcp.ids.StreamId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking

/**
 * Submit a job and collect the streaming result in real time (RFC §§7, 8).
 *
 * The agent writes its output as a sequence of `stream.chunk` envelopes
 * (`kind = text`).  The client reassembles the chunks in arrival order and
 * prints the full response when `stream.close` arrives, before waiting for
 * the terminal `job.completed` envelope.
 *
 * This pattern is the foundation of chat-style streaming interfaces.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="submit-and-stream"
 * ```
 */

private const val STREAM_KIND: String = "text"

/** Ordered buffer of received text chunks. */
private class StreamBuffer {
    private val chunks: MutableList<Pair<Int, String>> = mutableListOf()

    fun add(sequence: Int, text: String) {
        chunks += sequence to text
    }

    /** Return chunks assembled in sequence order. */
    fun assembled(): String =
        chunks.sortedBy { it.first }.joinToString("") { it.second }
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    // Submit the job.
    val accepted = client.request(
        envelope = client.envelope(
            type = "job.submit",
            payload = mapOf(
                "agent" to "writer@1.0.0",
                "input" to mapOf(
                    "prompt" to "Explain the ARCP streaming model in three sentences.",
                    "stream" to true,
                ),
            ),
        ),
        timeoutMs = 10_000,
    )

    val jobId = JobId(accepted.payloadMap()["job_id"].toString())
    println("job accepted: $jobId")

    val buffer = StreamBuffer()
    var streamId: StreamId? = null
    var jobDone = false

    // Collect stream chunks and terminal events.
    client.events()
        .takeWhile { !jobDone }
        .collect { env ->
            if (env.jobId != jobId) return@collect

            when (env.type) {
                "stream.open" -> {
                    streamId = env.payloadMap()["stream_id"]?.toString()?.let(::StreamId)
                    println("stream opened (kind=${env.payloadMap()["kind"]})")
                }

                "stream.chunk" -> {
                    val seq = env.payloadMap()["sequence"]?.toString()?.toIntOrNull() ?: 0
                    val text = env.payloadMap()["content"]?.toString()
                        ?: env.payloadMap()["data"]?.toString()
                        ?: ""
                    buffer.add(seq, text)
                    print(text)   // stream to console as chunks arrive
                }

                "stream.close" -> {
                    val total = env.payloadMap()["total_chunks"]?.toString()?.toIntOrNull()
                    println("\n[stream closed; total_chunks=$total]")
                    println("assembled:\n${buffer.assembled()}")
                }

                "stream.error" -> {
                    val code = env.payloadMap()["code"]
                    val msg  = env.payloadMap()["message"]
                    System.err.println("stream error: $code — $msg")
                    jobDone = true
                }

                "job.result" -> {
                    // Full result payload (may accompany or follow the stream).
                    println("[job.result] ${env.payloadMap()["result"]}")
                }

                "job.completed" -> {
                    println("[job.completed]")
                    jobDone = true
                }

                "job.failed" -> {
                    System.err.println("[job.failed] ${env.payloadMap()["message"]}")
                    jobDone = true
                }

                "job.cancelled" -> {
                    println("[job.cancelled]")
                    jobDone = true
                }
            }
        }

    client.close()
}
