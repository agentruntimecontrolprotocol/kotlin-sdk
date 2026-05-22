package com.arcp.recipes.mcpskill

import dev.arcp.messages.Capabilities
import dev.arcp.runtime.AgentRegistry
import dev.arcp.runtime.ARCPRuntime
import dev.arcp.transport.Transport

// ---------------------------------------------------------------------------
// ARCP runtime for the mcp-skill recipe
// ---------------------------------------------------------------------------

/**
 * Starts an [ARCPRuntime] with the `planner@1.0.0` agent registered.
 *
 * No authentication is required — this recipe focuses on the bridge pattern,
 * not auth flows.  The runtime handles the `job.submit` → `job.accepted`
 * handshake and `job.completed` → Ack lifecycle.
 *
 * @return the running [ARCPRuntime]; call [ARCPRuntime.close] to shut down.
 */
public fun runServer(serverTransport: Transport): ARCPRuntime {
    val registry =
        AgentRegistry().also {
            it.register("planner", "1.0.0", default = true)
        }
    val runtime =
        ARCPRuntime(
            supportedCapabilities = Capabilities(),
            agentRegistry = registry,
        )
    runtime.accept(serverTransport)
    return runtime
}
