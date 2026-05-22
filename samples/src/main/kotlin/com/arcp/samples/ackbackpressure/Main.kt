package com.arcp.samples.ackbackpressure

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.ids.JobId
import dev.arcp.ids.StreamId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates `Ack`, `Nack`, and `Backpressure` flow control (RFC §§5, 14).
 *
 * A consumer subscribes to a high-throughput stream of sensor readings.
 * - `Ack` confirms durable processing of each chunk.
 * - `Backpressure` slows the producer when the local buffer fills.
 * - `Nack` rejects malformed chunks the runtime cannot decode.
 *
 * Run:
 * ```
 * ./gradlew :samples:run --args="ack-backpressure"
 * ```
 */

private const val LOW_WATER_BYTES: Int = 4 * 1024
private const val HIGH_WATER_BYTES: Int = 64 * 1024
private const val DESIRED_RATE_PER_SECOND: Int = 10

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()

    val streamId = StreamId.random()
    var bufferBytes = 0
    var lastAckedSeq = -1
    var throttled = false

    // Ask the runtime to open the sensor feed stream.
    client.request(
        envelope = client.envelope(
            type = "stream.open",
            streamId = streamId,
            payload = mapOf(
                "kind" to "metric",
                "content_type" to "application/x-sensor-readings",
            ),
        ),
        timeoutMs = 10_000,
    )

    client.events().collect { env ->
        when (env.type) {
            "stream.chunk" -> {
                val seq = env.payloadMap()["sequence"]?.toString()?.toIntOrNull() ?: -1
                val data = env.payloadMap()["data"]

                // Validate chunk shape.
                if (data == null || seq < 0) {
                    // Nack with INVALID_ARGUMENT — sender should fix and resend.
                    client.dispatch(
                        client.envelope(
                            type = "nack",
                            correlationId = env.id,
                            payload = mapOf(
                                "code" to "INVALID_ARGUMENT",
                                "message" to "chunk missing sequence or data",
                                "retryable" to false,
                            ),
                        ),
                    )
                    return@collect
                }

                // Simulate buffer fill.
                val chunkSize = data.toString().length
                bufferBytes += chunkSize

                // Ack durable receipt so the runtime can advance its send window.
                client.dispatch(
                    client.envelope(
                        type = "ack",
                        correlationId = env.id,
                        payload = mapOf("sequence" to seq),
                    ),
                )
                lastAckedSeq = seq

                // Apply backpressure when buffer crosses high-water mark.
                if (bufferBytes >= HIGH_WATER_BYTES && !throttled) {
                    client.dispatch(
                        client.envelope(
                            type = "backpressure",
                            streamId = streamId,
                            payload = mapOf(
                                "desired_rate_per_second" to DESIRED_RATE_PER_SECOND,
                                "buffer_remaining_bytes" to (HIGH_WATER_BYTES - bufferBytes),
                                "reason" to "consumer buffer near capacity",
                            ),
                        ),
                    )
                    throttled = true
                }

                // Drain simulated processing.
                processChunk(data)
                bufferBytes -= chunkSize

                // Lift backpressure once drained past low-water mark.
                if (throttled && bufferBytes <= LOW_WATER_BYTES) {
                    client.dispatch(
                        client.envelope(
                            type = "backpressure",
                            streamId = streamId,
                            payload = mapOf(
                                "desired_rate_per_second" to -1, // -1 signals "no limit"
                                "buffer_remaining_bytes" to HIGH_WATER_BYTES,
                                "reason" to "consumer drained",
                            ),
                        ),
                    )
                    throttled = false
                }
            }

            "stream.close" -> {
                println("stream closed; last acked seq=$lastAckedSeq")
                return@collect
            }

            "stream.error" -> {
                val code = env.payloadMap()["code"]
                val msg = env.payloadMap()["message"]
                println("stream error: $code — $msg")
                return@collect
            }

            "nack" -> {
                // The runtime nacked one of our outbound messages.
                val code = env.payloadMap()["code"]
                val retryable = env.payloadMap()["retryable"] as? Boolean ?: false
                println("our message nacked: code=$code retryable=$retryable")
            }
        }
    }

    client.close()
}

@Suppress("UNUSED_PARAMETER")
private fun processChunk(data: Any?) {
    // illustrative stub — real implementation would process the sensor data
}
