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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Regression coverage for #80 (§7.2): a repeated `idempotency_key` with
 * identical parameters returns the same `job.accepted` (one job), while a
 * reused key with conflicting parameters returns DUPLICATE_KEY.
 */
class IdempotentSubmitTest :
    StringSpec({
        fun fixture(): Pair<ARCPRuntime, ARCPClient> {
            val registry =
                AgentRegistry().also {
                    it.register("worker", "1.0.0", default = true)
                    it.register("other", "1.0.0", default = true)
                }
            val caps = Capabilities(durableJobs = true)
            val (clientTransport, serverTransport) = MemoryTransport.pair()
            val runtime =
                ARCPRuntime(
                    supportedCapabilities = caps,
                    bearerAuth = StaticBearerAuth(mapOf("good-token" to "user@example")),
                    agentRegistry = registry,
                    evictTerminalJobs = false,
                )
            runtime.accept(serverTransport)
            val client =
                ARCPClient(
                    transport = clientTransport,
                    auth = ARCPClient.bearer("good-token"),
                    client = ARCPClient.defaultClientInfo("tester"),
                    capabilities = caps,
                )
            return runtime to client
        }

        "repeated key with identical params yields one job and identical job.accepted (#80)" {
            runTest {
                val (runtime, client) = fixture()
                val submit =
                    JobSubmit(
                        agent = "worker",
                        input = JsonObject(mapOf("k" to JsonPrimitive("v"))),
                        idempotencyKey = "key-1",
                    )

                val session = client.open()
                val firstId = client.send(session.sessionId, submit)
                val first =
                    client.receive().first { it.correlationId == firstId }.payload as JobAccepted
                val secondId = client.send(session.sessionId, submit)
                val second =
                    client.receive().first { it.correlationId == secondId }.payload as JobAccepted

                second.jobId shouldBe first.jobId
                // Exactly one job recorded despite two submissions.
                client.listJobs(session.sessionId).jobs.shouldHaveSize(1)

                runtime.close()
                client.close()
            }
        }

        "reused key with conflicting params returns DUPLICATE_KEY (#80)" {
            runTest {
                val (runtime, client) = fixture()
                val session = client.open()

                val firstId =
                    client.send(
                        session.sessionId,
                        JobSubmit(agent = "worker", idempotencyKey = "key-2"),
                    )
                client.receive().first { it.correlationId == firstId }.payload as JobAccepted

                val secondId =
                    client.send(
                        session.sessionId,
                        JobSubmit(agent = "other", idempotencyKey = "key-2"),
                    )
                val reply = client.receive().first { it.correlationId == secondId }.payload
                (reply as Nack).code shouldBe ErrorCode.ALREADY_EXISTS

                // Still only the original job exists.
                client.listJobs(session.sessionId).jobs.shouldHaveSize(1)

                runtime.close()
                client.close()
            }
        }
    })
