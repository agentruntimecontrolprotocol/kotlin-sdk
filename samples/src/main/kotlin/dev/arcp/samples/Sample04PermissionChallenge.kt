package dev.arcp.samples

import dev.arcp.error.ARCPException

/** Sample 04 — permission challenge. v0.2: requires LeaseManager (RFC §15). */
public fun main(): Unit =
    throw ARCPException.Unimplemented(
        section = "15",
        detail = "permission challenge sample requires LeaseManager runtime (v0.2)",
    )
