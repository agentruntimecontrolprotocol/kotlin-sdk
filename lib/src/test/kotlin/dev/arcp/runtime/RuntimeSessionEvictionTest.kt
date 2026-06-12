package dev.arcp.runtime

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.client.ARCPClient
import dev.arcp.messages.Capabilities
import dev.arcp.transport.MemoryTransport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_MS = 5_000L
private const val POLL_MS = 10L
private const val SESSION_COUNT = 10

/**
 * Regression coverage for #79: an authenticated session must be removed from
 * the runtime's internal map whenever its transport ends, not only on an
 * explicit `session.close`.
 */
class RuntimeSessionEvictionTest :
    StringSpec({
        fun runtime() = ARCPRuntime(
            supportedCapabilities = Capabilities(durableJobs = true),
            bearerAuth = StaticBearerAuth(mapOf("good-token" to "user@example")),
        )

        fun client(transport: MemoryTransport) = ARCPClient(
            transport = transport,
            auth = ARCPClient.bearer("good-token"),
            client = ARCPClient.defaultClientInfo("tester"),
            capabilities = Capabilities(durableJobs = true),
        )

        suspend fun ARCPRuntime.awaitSessionCount(expected: Int) {
            withTimeout(TIMEOUT_MS) {
                while (activeSessionCount != expected) delay(POLL_MS)
            }
            activeSessionCount shouldBe expected
        }

        "removes the session entry when the transport drops (#79)" {
            runBlocking {
                val runtime = runtime()
                runtime.activeSessionCount shouldBe 0

                val (clientTransport, serverTransport) = MemoryTransport.pair()
                runtime.accept(serverTransport)
                val client = client(clientTransport)
                client.open()
                runtime.awaitSessionCount(1)

                // Drop the connection without a clean session.close.
                clientTransport.close()
                runtime.awaitSessionCount(0)

                client.close()
                runtime.close()
            }
        }

        "map returns to baseline after many dropped sessions (#79)" {
            runBlocking {
                val runtime = runtime()
                repeat(SESSION_COUNT) {
                    val (clientTransport, serverTransport) = MemoryTransport.pair()
                    runtime.accept(serverTransport)
                    val client = client(clientTransport)
                    client.open()
                    clientTransport.close()
                    client.close()
                }
                runtime.awaitSessionCount(0)
                runtime.close()
            }
        }
    })
