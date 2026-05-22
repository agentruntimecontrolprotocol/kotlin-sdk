package com.arcp.samples.agentversions

import dev.arcp.error.ARCPException
import dev.arcp.messages.AgentRef
import dev.arcp.runtime.AgentRegistry

public fun main() {
    val registry = AgentRegistry()
    registry.register("code-refactor", "1.0.0")
    registry.register("code-refactor", "2.0.0", default = true)

    println("default -> ${registry.resolve(AgentRef.parse("code-refactor")).render()}")
    println("pinned -> ${registry.resolve(AgentRef.parse("code-refactor@1.0.0")).render()}")

    try {
        registry.resolve(AgentRef.parse("code-refactor@9.9.9"))
    } catch (e: ARCPException.AgentVersionNotAvailable) {
        println("${e.code.wire}: ${e.message}")
    }
}
