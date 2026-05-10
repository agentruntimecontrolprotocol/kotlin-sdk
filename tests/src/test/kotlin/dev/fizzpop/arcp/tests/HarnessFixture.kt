package dev.fizzpop.arcp.tests

import dev.fizzpop.arcp.auth.StaticBearerAuth
import dev.fizzpop.arcp.client.ARCPClient
import dev.fizzpop.arcp.messages.Capabilities
import dev.fizzpop.arcp.runtime.ARCPRuntime
import dev.fizzpop.arcp.transport.MemoryTransport

/** Test fixture that wires a runtime + client together over a memory transport. */
internal class HarnessFixture(
    val runtime: ARCPRuntime,
    val client: ARCPClient,
)

/** Builds a server runtime with the given supported capabilities and bearer-token allow-list. */
internal fun harness(
    runtimeCaps: Capabilities = Capabilities(streaming = true, durableJobs = true),
    clientCaps: Capabilities = Capabilities(streaming = true, durableJobs = true),
    tokens: Map<String, String> = mapOf("good-token" to "user@example"),
    bearerToken: String = "good-token",
): HarnessFixture {
    val (clientTransport, serverTransport) = MemoryTransport.pair()
    val runtime =
        ARCPRuntime(
            supportedCapabilities = runtimeCaps,
            bearerAuth = StaticBearerAuth(tokens),
        )
    runtime.accept(serverTransport)
    val client =
        ARCPClient(
            transport = clientTransport,
            auth = ARCPClient.bearer(bearerToken),
            client = ARCPClient.defaultClientInfo(principal = "tester"),
            capabilities = clientCaps,
        )
    return HarnessFixture(runtime, client)
}
