package dev.arcp.runtime

import dev.arcp.messages.Capabilities

/**
 * Negotiates capabilities between [proposed] (client) and [supported]
 * (runtime) per RFC §7.
 *
 * For boolean fields the result is the AND of both sides — a feature is
 * negotiated only if both parties advertise it. Required client features
 * the runtime does not support are returned in [unsupported] so the runtime
 * can reject the session.
 *
 * Vendor extensions (`arcpx.*`) are explicitly *not* included in
 * [unsupported]: per RFC §21 unknown extensions are optional and the
 * negotiated extension set is the intersection of both sides. A peer
 * that *requires* an extension surface must request a corresponding
 * boolean capability or send the extension's required messages, which
 * will hit the `classifyUnknown`/`Nack` path at dispatch time.
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
    val merged = mergeCapabilities(proposed, supported, unsupported)
    return CapabilityNegotiation(merged, unsupported)
}

private fun mergeCapabilities(
    proposed: Capabilities,
    supported: Capabilities,
    unsupported: MutableList<String>,
): Capabilities {
    val bools = negotiateBooleanFlags(proposed, supported, unsupported)
    return Capabilities(
        streaming = bools.getValue("streaming"),
        durableJobs = bools.getValue("durable_jobs"),
        checkpoints = bools.getValue("checkpoints"),
        binaryStreams = bools.getValue("binary_streams"),
        agentHandoff = bools.getValue("agent_handoff"),
        artifacts = bools.getValue("artifacts"),
        subscriptions = bools.getValue("subscriptions"),
        scheduledJobs = bools.getValue("scheduled_jobs"),
        provisionedCredentials = bools.getValue("provisioned_credentials"),
        modelUse = bools.getValue("model.use"),
        anonymous = proposed.anonymous && supported.anonymous,
        interrupt = proposed.interrupt && supported.interrupt,
        heartbeatIntervalSeconds = minOf(
            proposed.heartbeatIntervalSeconds,
            supported.heartbeatIntervalSeconds,
        ),
        heartbeatRecovery = supported.heartbeatRecovery,
        binaryEncoding = negotiateBinaryEncoding(proposed, supported),
        extensions = negotiateExtensions(proposed, supported),
        agents = supported.agents,
    )
}

/**
 * Computes the negotiated `binary_encoding` list. The result is the
 * intersection of both sides; if either side omitted the field entirely
 * (i.e. carries the default `["base64"]`) the intersection naturally
 * includes `base64`. Two peers that explicitly advertise disjoint,
 * non-default lists return an empty list — the caller must then refuse
 * features that require an encoding.
 */
private fun negotiateBinaryEncoding(
    proposed: Capabilities,
    supported: Capabilities,
): List<String> {
    val intersection = supported.binaryEncoding.intersect(proposed.binaryEncoding.toSet())
    return intersection.toList()
}

private fun negotiateExtensions(
    proposed: Capabilities,
    supported: Capabilities,
): List<String> = supported.extensions.intersect(proposed.extensions.toSet()).toList()

private fun negotiateBooleanFlags(
    proposed: Capabilities,
    supported: Capabilities,
    unsupported: MutableList<String>,
): Map<String, Boolean> {
    val pairs =
        listOf(
            "streaming" to (proposed.streaming to supported.streaming),
            "durable_jobs" to (proposed.durableJobs to supported.durableJobs),
            "checkpoints" to (proposed.checkpoints to supported.checkpoints),
            "binary_streams" to (proposed.binaryStreams to supported.binaryStreams),
            "agent_handoff" to (proposed.agentHandoff to supported.agentHandoff),
            "artifacts" to (proposed.artifacts to supported.artifacts),
            "subscriptions" to (proposed.subscriptions to supported.subscriptions),
            "scheduled_jobs" to (proposed.scheduledJobs to supported.scheduledJobs),
            "provisioned_credentials" to
                (proposed.provisionedCredentials to supported.provisionedCredentials),
            "model.use" to (proposed.modelUse to supported.modelUse),
        )
    return pairs.associate { (name, ps) ->
        val (p, s) = ps
        if (p && !s) unsupported += name
        name to (p && s)
    }
}
