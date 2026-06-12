package dev.arcp.runtime

import dev.arcp.ids.JobId
import dev.arcp.ids.SessionId
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import dev.arcp.messages.JobAccepted
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** Active (non-terminal) job state retained by the runtime. */
internal data class RuntimeJob(
    val ownerPrincipal: String,
    val parentJobId: JobId? = null,
    val costBudget: CostBudget? = null,
    val modelUse: ModelUseLease? = null,
    val expiresAt: Instant? = null,
)

/** Composite idempotency key scoped per principal (§7.2). */
internal data class IdempotencyKey(
    val principal: String,
    val key: String,
)

/** Stored outcome for a prior idempotent submission (§7.2). */
internal data class StoredSubmission(
    val fingerprint: String,
    val jobId: JobId,
    val accepted: JobAccepted,
)

/**
 * Thread-safe store of active jobs and idempotency outcomes, shared by the
 * submit and lifecycle handlers so both observe the same job set.
 */
internal class RuntimeJobs {
    val active: ConcurrentHashMap<JobId, RuntimeJob> = ConcurrentHashMap()
    val idempotency: ConcurrentHashMap<IdempotencyKey, StoredSubmission> = ConcurrentHashMap()
}

/**
 * Registry of authenticated sessions with principal resolution (§8/§14).
 * Entries are added on a successful handshake and removed whenever the
 * connection ends, by any cause (#79).
 */
internal class SessionRegistry {
    private val sessions: ConcurrentHashMap<SessionId, SessionState.Authenticated> =
        ConcurrentHashMap()

    /** Number of authenticated sessions currently retained. */
    val size: Int get() = sessions.size

    fun register(session: SessionState.Authenticated) {
        sessions[session.sessionId] = session
    }

    fun remove(sessionId: SessionId) {
        sessions.remove(sessionId)
    }

    fun principalFor(sessionId: SessionId?): String =
        sessionId?.let { sessions[it]?.principal } ?: ANONYMOUS

    private companion object {
        const val ANONYMOUS: String = "anonymous"
    }
}
