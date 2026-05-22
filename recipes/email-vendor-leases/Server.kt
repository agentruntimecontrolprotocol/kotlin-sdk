package com.arcp.recipes.emailvendorleases

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.credentials.InMemoryCredentialProvisioner
import dev.arcp.messages.Capabilities
import dev.arcp.runtime.AgentRegistry
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.transport.Transport

internal const val TOKEN = "demo-token"

fun runServer(serverTransport: Transport): ARCPRuntime {
    val registry =
        AgentRegistry().also {
            it.register("triage", "1.0.0", default = true)
        }
    val runtime =
        ARCPRuntime(
            supportedCapabilities = Capabilities(provisionedCredentials = true),
            bearerAuth = StaticBearerAuth(mapOf(TOKEN to "demo")),
            agentRegistry = registry,
            credentialProvisioner = InMemoryCredentialProvisioner(),
        )
    runtime.accept(serverTransport)
    return runtime
}
