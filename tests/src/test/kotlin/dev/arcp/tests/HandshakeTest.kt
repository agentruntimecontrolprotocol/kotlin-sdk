package dev.arcp.tests

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.error.ARCPException
import dev.arcp.messages.Auth
import dev.arcp.messages.AuthScheme
import dev.arcp.messages.Capabilities
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.transport.MemoryTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest

class HandshakeTest :
    StringSpec({
        "happy-path bearer handshake completes" {
            runTest {
                val h = harness()
                h.client.use {
                    val accepted = h.client.open()
                    accepted.sessionId shouldNotBe null
                    accepted.runtime.kind shouldBe "arcp-kotlin-sdk"
                }
                h.runtime.close()
            }
        }

        "invalid bearer token yields Unauthenticated" {
            runTest {
                val h = harness(bearerToken = "bad-token")
                h.client.use {
                    shouldThrow<ARCPException.Unauthenticated> { h.client.open() }
                }
                h.runtime.close()
            }
        }

        "anonymous (none) without negotiated capability yields Unauthenticated" {
            runTest {
                val (clientTransport, serverTransport) = MemoryTransport.pair()
                val runtime = ARCPRuntime(supportedCapabilities = Capabilities(anonymous = false))
                runtime.accept(serverTransport)
                val client =
                    ARCPClient(
                        transport = clientTransport,
                        auth = Auth(scheme = AuthScheme.NONE),
                        client = ARCPClient.defaultClientInfo("anon"),
                        capabilities = Capabilities(),
                    )
                client.use {
                    shouldThrow<ARCPException.Unauthenticated> { client.open() }
                }
                runtime.close()
            }
        }

        "anonymous (none) succeeds when capability negotiated" {
            runTest {
                val (clientTransport, serverTransport) = MemoryTransport.pair()
                val runtime = ARCPRuntime(supportedCapabilities = Capabilities(anonymous = true))
                runtime.accept(serverTransport)
                val client =
                    ARCPClient(
                        transport = clientTransport,
                        auth = Auth(scheme = AuthScheme.NONE),
                        client = ARCPClient.defaultClientInfo("anon"),
                        capabilities = Capabilities(anonymous = true),
                    )
                client.use {
                    val accepted = client.open()
                    accepted.runtime.kind shouldBe "arcp-kotlin-sdk"
                }
                runtime.close()
            }
        }

        "client advertising an unknown vendor extension is accepted (#57)" {
            // Per RFC §21 vendor extensions are optional unless required. The
            // runtime must drop unknown extensions from the negotiated set
            // rather than rejecting the entire session.
            runTest {
                val (clientTransport, serverTransport) = MemoryTransport.pair()
                val runtime =
                    ARCPRuntime(
                        supportedCapabilities = Capabilities(),
                        bearerAuth = StaticBearerAuth(mapOf("good" to "u@x")),
                    )
                runtime.accept(serverTransport)
                val client =
                    ARCPClient(
                        transport = clientTransport,
                        auth = ARCPClient.bearer("good"),
                        client = ARCPClient.defaultClientInfo(),
                        capabilities = Capabilities(extensions = listOf("arcpx.acme.cache.v1")),
                    )
                client.use {
                    val accepted = client.open()
                    accepted.sessionId shouldNotBe null
                    // The unknown extension is dropped from the negotiated set.
                    accepted.capabilities.extensions shouldBe emptyList()
                }
                runtime.close()
            }
        }
    })
