package com.arcp.samples.reasoningstreams

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import com.arcp.samples.sessionIdOrNull
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.SessionId
import dev.arcp.ids.StreamId
import dev.arcp.ids.SubscriptionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Primary emits reasoning; mirror peer subscribes, critiques back. */

private const val MAX_DEPTH = 3
private const val TOKEN_BUDGET = 8_000

// Primary side -----------------------------------------------------------

internal suspend fun runPrimary(
    client: ARCPClient,
    request: String,
    inboundCritiques: Channel<Map<String, Any?>>,
): String {
    val streamId = StreamId.random()
    client.dispatch(
        client.envelope(
            type = "stream.open",
            streamId = streamId,
            payload = mapOf("kind" to "thought"),
        ),
    )

    var last: Map<String, Any?>? = null
    var answer = ""
    for (step in 0 until MAX_DEPTH) {
        answer = primaryStep(request, last)
        client.dispatch(
            client.envelope(
                type = "stream.chunk",
                streamId = streamId,
                payload =
                    mapOf(
                        "sequence" to step,
                        "kind" to "thought",
                        "role" to "assistant_thought",
                        "content" to answer,
                    ),
            ),
        )
        last = withTimeoutOrNull(5_000) { inboundCritiques.receive() }
        if (last?.get("severity") == "halt") break
    }
    return answer
}

// Mirror side (a peer runtime, NOT a pure observer — it both reads
// the thought stream AND delegates critique events back) ---------------

private suspend fun subscribeThoughts(
    mirror: ARCPClient,
    target: SessionId,
): SubscriptionId {
    val accepted =
        mirror.request(
            envelope =
                mirror.envelope(
                    type = "subscribe",
                    payload =
                        mapOf(
                            "filter" to
                                mapOf(
                                    "session_id" to listOf(target.value),
                                    "types" to listOf("stream.chunk"),
                                ),
                        ),
                ),
            timeoutMs = 10_000,
        )
    return SubscriptionId(accepted.payloadMap()["subscription_id"].toString())
}

internal fun isThought(env: Envelope): Boolean = env.type == "stream.chunk" &&
    (env.payloadMap()["kind"] == "thought" || env.payloadMap()["role"] == "assistant_thought")

internal suspend fun runMirror(
    mirror: ARCPClient,
    target: SessionId,
) {
    val subId = subscribeThoughts(mirror, target)
    var spent = 0
    try {
        mirror.events().collect { env ->
            if (env.type != "subscribe.event") return@collect
            val inner = env.payloadMap()["event"] as? Envelope ?: return@collect
            if (!isThought(inner)) return@collect
            if (spent >= TOKEN_BUDGET) {
                // Tear down cleanly: runtime stops paying for events
                // we'll never act on.
                mirror.dispatch(
                    mirror.envelope(type = "unsubscribe", subscriptionId = subId),
                )
                return@collect
            }

            val (severity, summary, suggestion, consumed) =
                critiqueThought(inner.payloadMap()["content"]?.toString() ?: "")
            spent += consumed
            mirror.dispatch(
                mirror.envelope(
                    type = "agent.delegate",
                    target = target.value,
                    payload =
                        mapOf(
                            "target" to "primary",
                            "task" to "consume_critique",
                            "context" to
                                mapOf(
                                    "critique" to
                                        mapOf(
                                            "target_thought_sequence" to
                                                (inner.payloadMap()["sequence"] ?: 0),
                                            "severity" to severity,
                                            "summary" to summary,
                                            "suggestion" to suggestion,
                                            "consumed_tokens" to consumed,
                                        ),
                                ),
                        ),
                ),
            )
        }
    } finally {
        mirror.dispatch(mirror.envelope(type = "unsubscribe", subscriptionId = subId))
    }
}

public fun main(): Unit = runBlocking {
    val primary: ARCPClient = TODO("transport, identity, auth elided")
    val mirror: ARCPClient = TODO("transport, identity, auth elided")
    primary.open()
    mirror.open()

    val inbound: Channel<Map<String, Any?>> = Channel(Channel.UNLIMITED)

    coroutineScope {
        launch {
            primary.events().collect { env ->
                if (env.type != "agent.delegate") return@collect
                @Suppress("UNCHECKED_CAST")
                val context =
                    env.payloadMap()["context"] as? Map<String, Any?> ?: return@collect

                @Suppress("UNCHECKED_CAST")
                val critique = context["critique"] as? Map<String, Any?> ?: return@collect
                inbound.send(critique)
            }
        }
        launch {
            runMirror(mirror, primary.sessionIdOrNull() ?: SessionId.random())
        }

        val answer =
            runPrimary(
                primary,
                request = "Argue both sides: serializable vs snapshot iso?",
                inboundCritiques = inbound,
            )
        println(answer)
    }

    primary.close()
    mirror.close()
}
