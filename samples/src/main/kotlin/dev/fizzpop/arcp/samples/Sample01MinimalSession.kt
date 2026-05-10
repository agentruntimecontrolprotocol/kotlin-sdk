package dev.fizzpop.arcp.samples

import dev.fizzpop.arcp.auth.StaticBearerAuth
import dev.fizzpop.arcp.client.ARCPClient
import dev.fizzpop.arcp.messages.Capabilities
import dev.fizzpop.arcp.runtime.ARCPRuntime
import dev.fizzpop.arcp.transport.MemoryTransport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

/**
 * Sample 01 — minimal session.
 *
 * Spins up a runtime + client over an in-process [MemoryTransport], drives the
 * four-message handshake, and prints the negotiated capabilities. Every other
 * sample builds on this template.
 */
public fun main(): Unit =
    runBlocking {
        val (clientTransport, serverTransport) = MemoryTransport.pair()
        val runtime =
            ARCPRuntime(
                supportedCapabilities = Capabilities(streaming = true, durableJobs = true),
                bearerAuth = StaticBearerAuth(mapOf("demo" to "demo@example")),
            )
        runtime.accept(serverTransport)

        ARCPClient(
            transport = clientTransport,
            auth = ARCPClient.bearer("demo"),
            client = ARCPClient.defaultClientInfo(principal = "demo@example"),
            capabilities = Capabilities(streaming = true, durableJobs = true),
        ).use { client ->
            val accepted = client.open()
            log.info { "session opened: ${accepted.sessionId}" }
            log.info { "runtime: ${accepted.runtime.kind} ${accepted.runtime.version}" }
            log.info { "negotiated streaming=${accepted.capabilities.streaming}" }
        }
        runtime.close()
    }
