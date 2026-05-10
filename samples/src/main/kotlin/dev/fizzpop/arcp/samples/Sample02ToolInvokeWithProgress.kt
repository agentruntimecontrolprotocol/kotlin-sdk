package dev.fizzpop.arcp.samples

import dev.fizzpop.arcp.error.ARCPException

/**
 * Sample 02 — tool invocation with progress.
 *
 * Defers to v0.2 once `JobManager` and progress streaming come online. The
 * envelopes (`tool.invoke`, `job.progress`, `job.completed`) already
 * round-trip through the message catalog; this sample will wire the
 * runtime-side dispatch.
 */
public fun main(): Unit =
    throw ARCPException.Unimplemented(
        section = "10",
        detail = "tool invocation + progress sample requires JobManager (v0.2)",
    )
