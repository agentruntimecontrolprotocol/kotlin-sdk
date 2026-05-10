package dev.fizzpop.arcp.tests

import dev.fizzpop.arcp.auth.StaticBearerAuth
import dev.fizzpop.arcp.client.ARCPClient
import dev.fizzpop.arcp.error.ARCPException
import dev.fizzpop.arcp.messages.Auth
import dev.fizzpop.arcp.messages.AuthScheme
import dev.fizzpop.arcp.messages.Capabilities
import dev.fizzpop.arcp.runtime.ARCPRuntime
import dev.fizzpop.arcp.transport.MemoryTransport
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

        "client requesting unsupported extension is rejected" {
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
                    shouldThrow<ARCPException.Unimplemented> { client.open() }
                }
                runtime.close()
            }
        }
    })
