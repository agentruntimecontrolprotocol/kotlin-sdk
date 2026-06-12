package dev.arcp.tests

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.credentials.InMemoryCredentialProvisioner
import dev.arcp.envelope.Envelope
import dev.arcp.ids.MessageId
import dev.arcp.ids.TraceId
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobSubmit
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.runtime.AgentRegistry
import dev.arcp.transport.MemoryTransport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class ProvisionedCredentialFlowTest :
    StringSpec({
        "job accepted carries credentials and completion revokes them" {
            runTest {
                val registry =
                    AgentRegistry().also { it.register("worker", "1.0.0", default = true) }
                val provisioner = InMemoryCredentialProvisioner()
                val (clientTransport, serverTransport) = MemoryTransport.pair()
                val runtime =
                    ARCPRuntime(
                        supportedCapabilities =
                            Capabilities(
                                durableJobs = true,
                                provisionedCredentials = true,
                                modelUse = true,
                            ),
                        bearerAuth = StaticBearerAuth(mapOf("good-token" to "user@example")),
                        agentRegistry = registry,
                        credentialProvisioner = provisioner,
                    )
                runtime.accept(serverTransport)
                val client =
                    ARCPClient(
                        transport = clientTransport,
                        auth = ARCPClient.bearer("good-token"),
                        client = ARCPClient.defaultClientInfo("tester"),
                        capabilities =
                            Capabilities(
                                durableJobs = true,
                                provisionedCredentials = true,
                                modelUse = true,
                            ),
                    )
                val session = client.open()

                val submitId =
                    client.send(
                        session.sessionId,
                        JobSubmit(
                            agent = "worker",
                            leaseRequest =
                                JsonObject(
                                    mapOf(
                                        "cost.budget" to
                                            JsonArray(listOf(JsonPrimitive("USD:1.00"))),
                                        "model.use" to
                                            JsonArray(listOf(JsonPrimitive("tier-fast/*"))),
                                    ),
                                ),
                        ),
                    )
                val acceptedEnvelope = client.receive().first { it.correlationId == submitId }
                val accepted = acceptedEnvelope.payload as JobAccepted
                val credential = accepted.credentials!!.single()
                credential.constraints!!.costBudget shouldBe listOf("USD:1.00")
                credential.constraints!!.modelUse shouldBe listOf("tier-fast/*")

                clientTransport.send(
                    Envelope(
                        id = MessageId.random(),
                        sessionId = session.sessionId,
                        jobId = accepted.jobId,
                        payload = JobCompleted(),
                    ),
                )
                client.receive().first { it.jobId == null && it.correlationId != null }
                provisioner.revoked.shouldHaveSize(1)
                provisioner.revoked.shouldContain(credential.id)

                runtime.close()
                client.close()
            }
        }

        "job.accepted echoes lease, budget, lease_constraints, and trace_id (§7.1)" {
            runTest {
                val registry =
                    AgentRegistry().also { it.register("worker", "1.0.0", default = true) }
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
                val session = client.open()

                val expiresAt = "2999-01-01T00:00:00Z"
                val submitId = MessageId.random()
                val traceId = TraceId("trace_echo_1")
                clientTransport.send(
                    Envelope(
                        id = submitId,
                        sessionId = session.sessionId,
                        traceId = traceId,
                        payload =
                            JobSubmit(
                                agent = "worker",
                                leaseRequest =
                                    JsonObject(
                                        mapOf(
                                            "cost.budget" to
                                                JsonArray(listOf(JsonPrimitive("USD:5.00"))),
                                        ),
                                    ),
                                leaseConstraints =
                                    JsonObject(
                                        mapOf("expires_at" to JsonPrimitive(expiresAt)),
                                    ),
                            ),
                    ),
                )

                val acceptedEnvelope = client.receive().first { it.correlationId == submitId }
                val accepted = acceptedEnvelope.payload as JobAccepted

                accepted.lease shouldNotBe null
                accepted.lease!!.capabilities["cost.budget"] shouldBe listOf("USD:5.00")
                accepted.lease!!.expiresAt.toString() shouldBe expiresAt
                accepted.budget shouldBe mapOf("USD" to "5.00")
                accepted.leaseConstraints
                    ?.get("expires_at")
                    ?.jsonPrimitive
                    ?.content shouldBe expiresAt
                acceptedEnvelope.traceId shouldBe traceId

                runtime.close()
                client.close()
            }
        }
    })
