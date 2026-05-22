package com.arcp.recipes.multiagentbudget

import dev.arcp.auth.StaticBearerAuth
import dev.arcp.messages.Capabilities
import dev.arcp.runtime.AgentRegistry
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.transport.Transport

internal const val TOKEN = "demo-token"

fun runServer(serverTransport: Transport): ARCPRuntime {
    val registry =
        AgentRegistry().also {
            it.register("planner", "1.0.0", default = true)
            it.register("worker", "1.0.0", default = true)
        }
    val runtime =
        ARCPRuntime(
            supportedCapabilities = Capabilities(),
            bearerAuth = StaticBearerAuth(mapOf(TOKEN to "demo")),
            agentRegistry = registry,
        )
    runtime.accept(serverTransport)
    return runtime
}
