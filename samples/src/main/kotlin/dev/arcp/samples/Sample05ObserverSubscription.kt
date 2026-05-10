package dev.arcp.samples

import dev.arcp.error.ARCPException

/** Sample 05 — observer subscription. v0.2: requires SubscriptionManager (RFC §13). */
public fun main(): Unit =
    throw ARCPException.Unimplemented(
        section = "13",
        detail = "observer subscription sample requires SubscriptionManager (v0.2)",
    )
