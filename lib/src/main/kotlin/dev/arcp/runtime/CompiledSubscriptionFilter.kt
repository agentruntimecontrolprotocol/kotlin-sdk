package dev.arcp.runtime

import dev.arcp.envelope.Envelope
import dev.arcp.envelope.Priority
import dev.arcp.ids.JobId
import dev.arcp.ids.SessionId
import dev.arcp.ids.StreamId
import dev.arcp.ids.TraceId
import dev.arcp.messages.SubscriptionFilter

/**
 * Compiled, lookup-friendly form of [SubscriptionFilter].
 *
 * Equality on the wire filter is by list, but match-time evaluation wants
 * set lookups. Computing the sets once at compile time keeps the per-event
 * matching path branch-free aside from the early-exit short-circuits.
 */
internal class CompiledSubscriptionFilter(
    private val sessionIds: Set<SessionId>,
    private val traceIds: Set<TraceId>,
    private val jobIds: Set<JobId>,
    private val streamIds: Set<StreamId>,
    private val types: Set<String>,
    private val minPriorityOrdinal: Int,
) {
    fun matches(env: Envelope): Boolean = memberOrEmpty(env.sessionId, sessionIds) &&
        memberOrEmpty(env.traceId, traceIds) &&
        memberOrEmpty(env.jobId, jobIds) &&
        memberOrEmpty(env.streamId, streamIds) &&
        memberOrEmpty(env.type, types) &&
        env.priority.ordinal >= minPriorityOrdinal

    private fun <T> memberOrEmpty(
        candidate: T?,
        allowed: Set<T>,
    ): Boolean = allowed.isEmpty() || candidate in allowed

    companion object {
        fun from(filter: SubscriptionFilter): CompiledSubscriptionFilter =
            CompiledSubscriptionFilter(
                sessionIds = filter.sessionId.toSet(),
                traceIds = filter.traceId.toSet(),
                jobIds = filter.jobId.toSet(),
                streamIds = filter.streamId.toSet(),
                types = filter.types.toSet(),
                minPriorityOrdinal =
                    filter.minPriority?.ordinal
                        ?: Priority.LOW.ordinal,
            )
    }
}
