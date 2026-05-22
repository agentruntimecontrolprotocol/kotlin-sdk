package dev.arcp.tests

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.error.ErrorCode
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.Nack
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.runtime.AgentRegistry
import dev.arcp.transport.MemoryTransport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AgentVersioningTest :
    StringSpec({
        "runtime accepts pinned agent version and rejects missing version" {
            runTest {
                val registry = AgentRegistry()
                registry.register("code-refactor", "1.0.0")
                registry.register("code-refactor", "2.0.0", default = true)
                val (clientTransport, serverTransport) = MemoryTransport.pair()
                val runtime =
                    ARCPRuntime(
                        supportedCapabilities = Capabilities(durableJobs = true),
                        bearerAuth = StaticBearerAuth(mapOf("good-token" to "user@example")),
                        agentRegistry = registry,
                    )
                runtime.accept(serverTransport)
                val client =
                    ARCPClient(
                        transport = clientTransport,
                        auth = ARCPClient.bearer("good-token"),
                        client = ARCPClient.defaultClientInfo("tester"),
                        capabilities = Capabilities(durableJobs = true),
                    )
                val accepted = client.open()
                accepted.capabilities.agents
                    .single()
                    .default shouldBe "2.0.0"

                val okId =
                    client.send(
                        accepted.sessionId,
                        JobSubmit(agent = "code-refactor@1.0.0"),
                    )
                val ok =
                    client
                        .receive()
                        .first { it.correlationId == okId }
                        .payload as JobAccepted
                ok.agent shouldBe "code-refactor@1.0.0"

                val badId = client.send(
                    accepted.sessionId,
                    JobSubmit(agent = "code-refactor@9.9.9"),
                )
                val bad =
                    client
                        .receive()
                        .first { it.correlationId == badId }
                        .payload as Nack
                bad.code shouldBe ErrorCode.AGENT_VERSION_NOT_AVAILABLE

                runtime.close()
                client.close()
            }
        }
    })
