package com.arcp.samples.human_input

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Fan `human.input.request` across channels; resolve on first. */

private val DESTINATIONS = listOf("ntfy:phone", "email:oncall", "slack:ops")

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun fanOut(client: ARCPClient, request: Envelope) {
    val payload = request.payloadMap()
    @Suppress("UNCHECKED_CAST")
    val schema = (payload["response_schema"] as? Map<String, Any?>) ?: emptyMap()
    val prompt = payload["prompt"]?.toString() ?: ""
    val expiresAt = Instant.parse(payload["expires_at"].toString())
    val timeoutMs = maxOf(0L, expiresAt.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds())

    coroutineScope {
        val tasks: Map<Deferred<Pair<String, Map<String, Any?>>>, String> =
            DESTINATIONS.associate { dest ->
                val d = async { dest to REGISTRY.getValue(dest)(prompt, schema) }
                d to dest
            }
        try {
            val winner: Deferred<Pair<String, Map<String, Any?>>>? =
                withTimeoutOrNull(timeoutMs) {
                    select<Deferred<Pair<String, Map<String, Any?>>>> {
                        for (d in tasks.keys) d.onAwait { d }
                    }
                }

            if (winner == null) {
                // Deadline elapsed; translate timeout into the cancelled-input
                // shape (RFC §12.4).
                client.dispatch(
                    client.envelope(
                        type = "human.input.cancelled",
                        correlationId = request.id,
                        payload = mapOf(
                            "code" to "DEADLINE_EXCEEDED",
                            "message" to "no channel responded before expires_at",
                        ),
                    ),
                )
                return@coroutineScope
            }

            val (respondedBy, value) = winner.await()
            client.dispatch(
                client.envelope(
                    type = "human.input.response",
                    correlationId = request.id,
                    payload = mapOf(
                        "value" to value,
                        "responded_by" to respondedBy,
                        "responded_at" to Clock.System.now().toString(),
                    ),
                ),
            )
            // Tell the losing destinations the question is settled.
            val losers = tasks.entries.filter { it.key !== winner }.map { it.value }
            if (losers.isNotEmpty()) {
                client.dispatch(
                    client.envelope(
                        type = "human.input.cancelled",
                        correlationId = request.id,
                        payload = mapOf(
                            "code" to "OK",
                            "message" to "answered elsewhere",
                            "channels" to losers,
                        ),
                    ),
                )
            }
        } finally {
            tasks.keys.forEach { if (!it.isCompleted) it.cancel() }
        }
    }
}

public fun main(): Unit = runBlocking {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()
    coroutineScope {
        client.events().collect { env ->
            if (env.type == "human.input.request") {
                launch { fanOut(client, env) }
            }
        }
    }
    client.close()
}
