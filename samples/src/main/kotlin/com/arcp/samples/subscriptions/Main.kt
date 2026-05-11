package com.arcp.samples.subscriptions

import com.arcp.samples.dispatch
import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.ids.SessionId
import dev.arcp.ids.SubscriptionId
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Boot three Observer clients on a single producing session. */

private val STDOUT_TYPES = listOf(
    "log",
    "job.started",
    "job.progress",
    "job.completed",
    "job.failed",
    "tool.error",
)
private val OTLP_TYPES = listOf("metric", "trace.span")

private suspend fun subscribe(
    client: ARCPClient,
    sessionId: SessionId,
    types: List<String>?,
): SubscriptionId {
    val filter: MutableMap<String, Any> = mutableMapOf("session_id" to listOf(sessionId.value))
    if (types != null) filter["types"] = types
    val accepted = client.request(
        client.envelope(type = "subscribe", payload = mapOf("filter" to filter)),
    )
    return SubscriptionId(accepted.payloadMap()["subscription_id"].toString())
}

private suspend fun unsubscribe(client: ARCPClient, id: SubscriptionId) {
    client.dispatch(client.envelope(type = "unsubscribe", subscriptionId = id))
}

/** Strip the `subscribe.event` wrapper; return the inner envelope or null. */
public fun unwrapEvent(envelope: Envelope): Envelope? {
    if (envelope.type != "subscribe.event") return null
    return envelope.payloadMap()["event"] as? Envelope
}

private suspend fun attach(
    types: List<String>?,
    handler: suspend (Envelope) -> Unit,
) {
    val client: ARCPClient = TODO("transport, identity, auth elided")
    client.open()
    val target: SessionId = TODO("target session id elided")
    val subId = subscribe(client, target, types)
    try {
        client.events().collect { env ->
            val inner = unwrapEvent(env)
            if (inner != null) handler(inner)
        }
    } finally {
        unsubscribe(client, subId)
        client.close()
    }
}

public fun main(): Unit = runBlocking {
    val stdout = StdoutSink()
    val otlp = OtlpSink(endpoint = "...")
    SqliteSink(path = "replay.sqlite").use { sqlite ->
        coroutineScope {
            launch { attach(STDOUT_TYPES, stdout::handle) }
            launch { attach(null, sqlite::handle) }
            launch { attach(OTLP_TYPES, otlp::handle) }
        }
    }
}
