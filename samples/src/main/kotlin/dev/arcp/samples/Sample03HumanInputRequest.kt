package dev.arcp.samples

import dev.arcp.error.ARCPException

/** Sample 03 — human input request. v0.2: requires HITL handler wiring (RFC §12). */
public fun main(): Unit =
    throw ARCPException.Unimplemented(
        section = "12",
        detail = "human input sample requires HumanInputHandler runtime (v0.2)",
    )
