package dev.fizzpop.arcp.runtime

import dev.fizzpop.arcp.messages.Capabilities

/**
 * Negotiates capabilities between [proposed] (client) and [supported]
 * (runtime) per RFC §7.
 *
 * For boolean fields the result is the AND of both sides — a feature is
 * negotiated only if both parties advertise it. Required client features
 * the runtime does not support are returned in [unsupported] so the runtime
 * can reject the session.
 */
public data class CapabilityNegotiation(
    val negotiated: Capabilities,
    val unsupported: List<String>,
)

/**
 * Computes the negotiated capability set from client- and runtime-side
 * capability blocks. The intersection is conservative — a `true` flag on
 * either side does not imply the negotiated value is `true`.
 */
public fun negotiate(
    proposed: Capabilities,
    supported: Capabilities,
): CapabilityNegotiation {
    val unsupported = mutableListOf<String>()

    fun negotiateBool(
        name: String,
        p: Boolean,
        s: Boolean,
    ): Boolean {
        val v = p && s
        if (p && !s) unsupported += name
        return v
    }

    val merged =
        Capabilities(
            streaming = negotiateBool("streaming", proposed.streaming, supported.streaming),
            durableJobs = negotiateBool("durable_jobs", proposed.durableJobs, supported.durableJobs),
            checkpoints = negotiateBool("checkpoints", proposed.checkpoints, supported.checkpoints),
            binaryStreams = negotiateBool("binary_streams", proposed.binaryStreams, supported.binaryStreams),
            agentHandoff = negotiateBool("agent_handoff", proposed.agentHandoff, supported.agentHandoff),
            humanInput = negotiateBool("human_input", proposed.humanInput, supported.humanInput),
            artifacts = negotiateBool("artifacts", proposed.artifacts, supported.artifacts),
            subscriptions = negotiateBool("subscriptions", proposed.subscriptions, supported.subscriptions),
            scheduledJobs = negotiateBool("scheduled_jobs", proposed.scheduledJobs, supported.scheduledJobs),
            anonymous = proposed.anonymous && supported.anonymous,
            interrupt = proposed.interrupt && supported.interrupt,
            heartbeatIntervalSeconds =
                minOf(proposed.heartbeatIntervalSeconds, supported.heartbeatIntervalSeconds),
            heartbeatRecovery = supported.heartbeatRecovery,
            binaryEncoding =
                supported.binaryEncoding
                    .intersect(proposed.binaryEncoding.toSet())
                    .toList()
                    .ifEmpty { listOf("base64") },
            extensions = supported.extensions.intersect(proposed.extensions.toSet()).toList(),
        )

    val unsupportedExtensions = proposed.extensions - supported.extensions.toSet()
    unsupported += unsupportedExtensions

    return CapabilityNegotiation(merged, unsupported)
}
