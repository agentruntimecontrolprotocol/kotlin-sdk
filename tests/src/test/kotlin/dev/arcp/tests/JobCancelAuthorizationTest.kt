package dev.arcp.tests

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.credentials.InMemoryCredentialProvisioner
import dev.arcp.error.ErrorCode
import dev.arcp.messages.Cancel
import dev.arcp.messages.CancelTarget
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.Nack
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.runtime.AgentRegistry
import dev.arcp.transport.MemoryTransport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Regression coverage for #78 (§7.6/§14): only the submitting principal may
 * cancel a job. A different principal must be refused with PERMISSION_DENIED
 * and the job must not be terminated nor its credentials revoked.
 */
class JobCancelAuthorizationTest :
    StringSpec({
        "a foreign principal cannot cancel another principal's job (#78)" {
            runTest {
                val registry =
                    AgentRegistry().also { it.register("worker", "1.0.0", default = true) }
                val provisioner = InMemoryCredentialProvisioner()
                val caps =
                    Capabilities(durableJobs = true, provisionedCredentials = true)
                val runtime =
                    ARCPRuntime(
                        supportedCapabilities = caps,
                        bearerAuth =
                            StaticBearerAuth(
                                mapOf(
                                    "token-alice" to "alice@example",
                                    "token-bob" to "bob@example",
                                ),
                            ),
                        agentRegistry = registry,
                        credentialProvisioner = provisioner,
                    )

                val (aliceTransport, aliceServer) = MemoryTransport.pair()
                val (bobTransport, bobServer) = MemoryTransport.pair()
                runtime.accept(aliceServer)
                runtime.accept(bobServer)

                val alice =
                    ARCPClient(
                        transport = aliceTransport,
                        auth = ARCPClient.bearer("token-alice"),
                        client = ARCPClient.defaultClientInfo("alice"),
                        capabilities = caps,
                    )
                val bob =
                    ARCPClient(
                        transport = bobTransport,
                        auth = ARCPClient.bearer("token-bob"),
                        client = ARCPClient.defaultClientInfo("bob"),
                        capabilities = caps,
                    )
                val aliceSession = alice.open()
                val bobSession = bob.open()

                val submitId =
                    alice.send(
                        aliceSession.sessionId,
                        dev.arcp.messages.JobSubmit(
                            agent = "worker",
                            leaseRequest =
                                JsonObject(
                                    mapOf(
                                        "cost.budget" to
                                            JsonArray(listOf(JsonPrimitive("USD:1.00"))),
                                    ),
                                ),
                        ),
                    )
                val accepted =
                    alice.receive().first { it.correlationId == submitId }.payload as JobAccepted
                provisioner.issued.shouldHaveSize(1)

                // Bob attempts to cancel Alice's job.
                val cancelId =
                    bob.send(
                        bobSession.sessionId,
                        Cancel(
                            target = CancelTarget.JOB,
                            targetId = accepted.jobId.value,
                        ),
                    )
                val reply = bob.receive().first { it.correlationId == cancelId }.payload
                (reply as Nack).code shouldBe ErrorCode.PERMISSION_DENIED

                // The job was not terminated and its credentials were not revoked.
                provisioner.revoked.shouldBeEmpty()
                alice.listJobs(aliceSession.sessionId).jobs.shouldHaveSize(1)

                runtime.close()
                alice.close()
                bob.close()
            }
        }

        "the owning principal can still cancel its own job (#78)" {
            runTest {
                val registry =
                    AgentRegistry().also { it.register("worker", "1.0.0", default = true) }
                val caps = Capabilities(durableJobs = true)
                val (clientTransport, serverTransport) = MemoryTransport.pair()
                val runtime =
                    ARCPRuntime(
                        supportedCapabilities = caps,
                        bearerAuth = StaticBearerAuth(mapOf("token-alice" to "alice@example")),
                        agentRegistry = registry,
                        evictTerminalJobs = false,
                    )
                runtime.accept(serverTransport)
                val alice =
                    ARCPClient(
                        transport = clientTransport,
                        auth = ARCPClient.bearer("token-alice"),
                        client = ARCPClient.defaultClientInfo("alice"),
                        capabilities = caps,
                    )
                val session = alice.open()

                val submitId =
                    alice.send(session.sessionId, dev.arcp.messages.JobSubmit(agent = "worker"))
                val accepted =
                    alice.receive().first { it.correlationId == submitId }.payload as JobAccepted

                val cancelId =
                    alice.send(
                        session.sessionId,
                        Cancel(target = CancelTarget.JOB, targetId = accepted.jobId.value),
                    )
                val reply = alice.receive().first { it.correlationId == cancelId }.payload
                reply.shouldBeCancelAccepted(accepted.jobId.value)

                runtime.close()
                alice.close()
            }
        }
    })

private fun Any.shouldBeCancelAccepted(targetId: String) {
    check(this is dev.arcp.messages.CancelAccepted) { "expected CancelAccepted, got $this" }
    this.targetId shouldBe targetId
}
