package dev.fizzpop.arcp.samples

import dev.fizzpop.arcp.error.ARCPException

/** Sample 06 — relay HITL across channels. v0.2: requires HITL relay logic (RFC §12.3). */
public fun main(): Unit =
    throw ARCPException.Unimplemented(
        section = "12.3",
        detail = "relay HITL sample requires multi-channel resolution logic (v0.2)",
    )
