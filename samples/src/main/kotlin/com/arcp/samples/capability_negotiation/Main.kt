package com.arcp.samples.capability_negotiation

import com.arcp.samples.envelope
import com.arcp.samples.events
import com.arcp.samples.payloadMap
import com.arcp.samples.request
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.ids.TraceId
import dev.arcp.messages.Capabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Capability-driven peer routing with ordered fallback + cost rollup. */

private val PEERS = listOf(
    "anthropic-haiku",
    "anthropic-sonnet",
    "openai-4o",
    "groq-llama",
)
private val FALLBACK_CHAINS: Map<String, List<String>> = mapOf(
    "cheap_fast" to listOf("groq-llama", "anthropic-haiku", "openai-4o"),
    "balanced" to listOf("anthropic-sonnet", "openai-4o", "anthropic-haiku"),
    "deep" to listOf("anthropic-sonnet"),
)
private const val COST_CEILING_USD_PER_MTOK: Double = 8.0
private const val LATENCY_CEILING_MS: Int = 800
private val RETRYABLE = setOf(
    ErrorCode.RESOURCE_EXHAUSTED,
    ErrorCode.UNAVAILABLE,
    ErrorCode.DEADLINE_EXCEEDED,
    ErrorCode.ABORTED,
)

internal data class Profile(
    val costPerMtok: Double,
    val p50LatencyMs: Int,
    val modelClass: String,
)

internal fun profileFrom(caps: Capabilities): Profile {
    // Capabilities is sealed in the Kotlin SDK; the namespaced fields would
    // travel under the runtime's `extensions` list per RFC §21.2. NOTE:
    // §21 covers extension *messages* but not extension *capability values*
    // — load-bearing convention here.
    val tagged = caps.extensions.associate { ext ->
        ext.substringBefore('=') to ext.substringAfter('=', missingDelimiterValue = "")
    }
    return Profile(
        costPerMtok = tagged["arcpx.market.cost_per_mtok.v1"]?.toDoubleOrNull() ?: 0.0,
        p50LatencyMs = tagged["arcpx.market.p50_latency_ms.v1"]?.toIntOrNull() ?: 0,
        modelClass = tagged["arcpx.market.model_class.v1"] ?: "unknown",
    )
}

internal fun candidateChain(
    profiles: Map<String, Profile>,
    requestClass: String,
): List<String> =
    FALLBACK_CHAINS[requestClass].orEmpty().filter { name ->
        val p = profiles[name] ?: return@filter false
        p.costPerMtok <= COST_CEILING_USD_PER_MTOK && p.p50LatencyMs <= LATENCY_CEILING_MS
    }

/** Walk the chain. Retryable error → next peer; otherwise raise. */
private suspend fun invokeWithFallback(
    clients: Map<String, ARCPClient>,
    chain: List<String>,
    tool: String,
    arguments: Map<String, Any?>,
    traceId: TraceId,
): Envelope {
    var last: ARCPException? = null
    for (name in chain) {
        val client = clients[name] ?: continue
        val reply: Envelope =
            try {
                client.request(
                    envelope = client.envelope(
                        type = "tool.invoke",
                        traceId = traceId.value,
                        extensions = mapOf("arcpx.market.peer.v1" to name),
                        payload = mapOf("tool" to tool, "arguments" to arguments),
                    ),
                    timeoutMs = 30_000,
                )
            } catch (e: ARCPException) {
                last = e
                if (e.code in RETRYABLE) continue
                throw e
            }
        if (reply.type != "tool.error") return reply
        val code = ErrorCode.fromWire(reply.payloadMap()["code"]?.toString() ?: "UNKNOWN")
        last = wrap(code, reply.payloadMap()["message"]?.toString() ?: "")
        if (code in RETRYABLE) continue
        throw last
    }
    throw last ?: ARCPException.Unavailable("no peers available")
}

private fun wrap(code: ErrorCode, message: String): ARCPException = when (code) {
    ErrorCode.RESOURCE_EXHAUSTED -> ARCPException.ResourceExhausted(message)
    ErrorCode.UNAVAILABLE -> ARCPException.Unavailable(message)
    ErrorCode.DEADLINE_EXCEEDED -> ARCPException.DeadlineExceeded(message)
    ErrorCode.ABORTED -> ARCPException.Aborted(message)
    else -> ARCPException.Internal("$code: $message")
}

internal data class Usage(
    var tokensIn: Long = 0,
    var tokensOut: Long = 0,
    var costUsd: Double = 0.0,
    val byPeer: MutableMap<String, Double> = mutableMapOf(),
)

internal fun consumeMetric(env: Envelope, totals: MutableMap<String, Usage>) {
    if (env.type != "metric") return
    val p = env.payloadMap()
    @Suppress("UNCHECKED_CAST")
    val dims = (p["dims"] as? Map<String, Any?>) ?: emptyMap()
    val name = p["name"]?.toString()
    val value = (p["value"] as? Number)?.toDouble() ?: return
    val u = totals.getOrPut(dims["tenant"]?.toString() ?: "unknown") { Usage() }
    when (name) {
        "tokens.used" -> when (dims["kind"]) {
            "input" -> u.tokensIn += value.toLong()
            "output" -> u.tokensOut += value.toLong()
        }
        "cost.usd" -> {
            u.costUsd += value
            val peer = dims["peer"]?.toString() ?: "unknown"
            u.byPeer[peer] = (u.byPeer[peer] ?: 0.0) + value
        }
    }
}

private fun CoroutineScope.meter(c: ARCPClient, totals: MutableMap<String, Usage>): Job =
    launch {
        c.events().collect { env -> consumeMetric(env, totals) }
    }

public fun main(): Unit = runBlocking {
    val clients: MutableMap<String, ARCPClient> = mutableMapOf()
    val profiles: MutableMap<String, Profile> = mutableMapOf()
    for (name in PEERS) {
        val c: ARCPClient = TODO("transport per peer URL, identity, auth elided ($name)")
        val accepted = c.open()
        clients[name] = c
        // Marketplace fields ride on the negotiated capabilities;
        // no extra round trip to learn cost / latency / class.
        profiles[name] = profileFrom(accepted.capabilities)
    }

    val totals: MutableMap<String, Usage> = mutableMapOf()

    coroutineScope {
        val drains = clients.values.map { meter(it, totals) }

        val chain = candidateChain(profiles, "balanced")
        val reply = invokeWithFallback(
            clients = clients,
            chain = chain,
            tool = "chat.completion",
            arguments = mapOf("prompt" to "Hello", "tenant" to "acme-corp"),
            traceId = TraceId.random(),
        )
        println("chosen= ${reply.extensions["arcpx.market.peer.v1"]}")
        println("usage= $totals")

        drains.forEach { it.cancel() }
    }
    clients.values.forEach { it.close() }
}
