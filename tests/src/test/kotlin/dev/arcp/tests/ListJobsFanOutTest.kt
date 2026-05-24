package dev.arcp.tests

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobSubmit
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.runtime.AgentRegistry
import dev.arcp.transport.MemoryTransport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Regression: a concurrent `listJobs` call must not steal envelopes from
 * `client.receive()`. The client now mirrors the transport's inbound flow
 * through a single shared flow so every subscriber sees every envelope (#58).
 */
class ListJobsFanOutTest :
    StringSpec({
        "concurrent listJobs does not steal envelopes from receive() (#58)" {
            // Uses real dispatchers; the runtime collects from Dispatchers.Default,
            // which runTest's virtual time does not advance.
            runBlocking { withTimeout(10.seconds) { runFanOutScenario() } }
        }
    })

private suspend fun runFanOutScenario() {
    val (clientTransport, serverTransport) = MemoryTransport.pair()
    val agents = AgentRegistry().apply { register("a", "1.0.0", default = true) }
    val runtime =
        ARCPRuntime(
            supportedCapabilities = Capabilities(),
            agentRegistry = agents,
            bearerAuth = StaticBearerAuth(mapOf("t" to "u@x")),
        )
    runtime.accept(serverTransport)
    val client = buildClient(clientTransport)
    client.use { exerciseFanOut(client) }
    runtime.close()
}

private fun buildClient(transport: MemoryTransport): ARCPClient = ARCPClient(
    transport = transport,
    auth = ARCPClient.bearer("t"),
    client = ARCPClient.defaultClientInfo(),
    capabilities = Capabilities(),
)

private suspend fun exerciseFanOut(client: ARCPClient) {
    val session = client.open()
    coroutineScope {
        val accepted = async<Envelope> {
            client.receive().first { it.payload is JobAccepted }
        }
        client.send(session.sessionId, JobSubmit(agent = "a@1.0.0"))
        val list = client.listJobs(session.sessionId)
        list shouldNotBe null
        val acceptedEnv = accepted.await()
        (acceptedEnv.payload as JobAccepted).agent shouldBe "a@1.0.0"
    }
}
