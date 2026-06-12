package dev.arcp.runtime

import dev.arcp.credentials.CredentialProvisioner
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.json.arcpJson
import dev.arcp.lease.BudgetRegistry
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import dev.arcp.messages.AgentRef
import dev.arcp.messages.JobAccepted
import dev.arcp.messages.JobListEntry
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.SessionListJobs
import dev.arcp.transport.Transport
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Handles the client-driven job commands `session.list_jobs` and `job.submit`,
 * including agent resolution and §7.2 idempotency. Extracted from the runtime
 * facade so submission logic is cohesive and independently testable (#83).
 */
internal class JobCommandHandler(
    private val agentRegistry: AgentRegistry,
    private val jobInventory: JobInventory,
    private val budgets: BudgetRegistry,
    private val sessions: SessionRegistry,
    private val credentials: CredentialLifecycle,
    private val jobs: RuntimeJobs,
) {
    suspend fun listJobs(
        env: Envelope,
        request: SessionListJobs,
        transport: Transport,
    ) {
        val result =
            jobInventory.list(
                principal = sessions.principalFor(env.sessionId),
                requestId = env.id,
                filter = request.filter,
                limit = request.limit,
                cursor = request.cursor,
            )
        transport.send(reply(env, result))
    }

    suspend fun submit(
        env: Envelope,
        request: JobSubmit,
        transport: Transport,
    ) {
        if (replayIdempotent(env, request, transport)) return
        val resolved = resolveAgentOrNack(env, request, transport) ?: return
        completeSubmit(env, request, resolved, transport)
    }

    private suspend fun completeSubmit(
        env: Envelope,
        request: JobSubmit,
        resolved: AgentRef,
        transport: Transport,
    ) {
        val principal = sessions.principalFor(env.sessionId)
        val draft =
            SubmitDraft(
                jobId = JobId.random(),
                lease = parseCostBudget(request.leaseRequest),
                modelUse = parseModelUse(request.leaseRequest),
                expiresAt = parseExpiresAt(request.leaseConstraints),
                acceptedAt = Clock.System.now(),
            )
        draft.lease?.let { budgets.register(draft.jobId, it) }
        val issued = credentials.issue(issuanceContext(draft)).ifEmpty { null }
        val accepted = buildAccepted(draft, resolved, request, issued)
        recordJob(draft, principal)
        recordInventory(draft, resolved, principal, env)
        storeIdempotent(request, principal, draft, accepted)
        transport.send(acceptedEnvelope(env, draft.jobId, accepted))
    }

    private fun issuanceContext(draft: SubmitDraft): CredentialProvisioner.IssuanceContext =
        CredentialProvisioner.IssuanceContext(
            jobId = draft.jobId,
            parentJobId = null,
            lease = draft.lease,
            modelUse = draft.modelUse,
            expiresAt = draft.expiresAt,
        )

    private fun storeIdempotent(
        request: JobSubmit,
        principal: String,
        draft: SubmitDraft,
        accepted: JobAccepted,
    ) {
        val key = request.idempotencyKey ?: return
        jobs.idempotency[IdempotencyKey(principal, key)] =
            StoredSubmission(fingerprint(request), draft.jobId, accepted)
    }

    private fun acceptedEnvelope(
        env: Envelope,
        jobId: JobId,
        accepted: JobAccepted,
    ): Envelope = Envelope(
        id = MessageId.random(),
        sessionId = env.sessionId,
        jobId = jobId,
        traceId = env.traceId,
        correlationId = env.id,
        payload = accepted,
    )

    private fun buildAccepted(
        draft: SubmitDraft,
        resolved: AgentRef,
        request: JobSubmit,
        credentials: List<dev.arcp.credentials.Credential>?,
    ): JobAccepted = JobAccepted(
        jobId = draft.jobId,
        agent = resolved.render(),
        lease = leaseSummary(draft.lease, draft.modelUse, draft.expiresAt),
        leaseConstraints = request.leaseConstraints,
        budget = draft.lease?.budgets?.associate { it.currency.code to it.value.toPlainString() },
        acceptedAt = draft.acceptedAt,
        credentials = credentials,
    )

    private fun recordJob(
        draft: SubmitDraft,
        principal: String,
    ) {
        jobs.active[draft.jobId] =
            RuntimeJob(
                ownerPrincipal = principal,
                costBudget = draft.lease,
                modelUse = draft.modelUse,
                expiresAt = draft.expiresAt,
            )
    }

    private fun recordInventory(
        draft: SubmitDraft,
        resolved: AgentRef,
        principal: String,
        env: Envelope,
    ) {
        // The provisioned credential secret is surfaced only on job.accepted
        // (§9.8.2) and is intentionally NOT persisted in the introspection
        // inventory nor re-emitted on session.jobs (§14).
        jobInventory.record(
            JobListEntry(
                jobId = draft.jobId,
                agent = resolved.render(),
                status = "accepted",
                lease = leaseSummary(draft.lease, draft.modelUse, draft.expiresAt),
                parentJobId = null,
                createdAt = draft.acceptedAt,
                traceId = env.traceId,
            ),
            ownerPrincipal = principal,
        )
    }

    /**
     * Resolves the submitted agent reference, sending a correlated `Nack` and
     * returning `null` when the agent (or pinned version) is not available.
     */
    private suspend fun resolveAgentOrNack(
        env: Envelope,
        request: JobSubmit,
        transport: Transport,
    ): AgentRef? = try {
        agentRegistry.resolve(AgentRef.parse(request.agent))
    } catch (e: ARCPException.AgentVersionNotAvailable) {
        transport.send(nack(env, e))
        null
    } catch (e: ARCPException.NotFound) {
        transport.send(nack(env, e))
        null
    } catch (e: IllegalArgumentException) {
        transport.send(
            nack(env, ARCPException.InvalidArgument(e.message ?: "invalid agent", "agent")),
        )
        null
    }

    /**
     * §7.2 idempotency: when a prior outcome exists for `(principal, key)`,
     * replays the stored `job.accepted` for identical parameters or rejects
     * with `DUPLICATE_KEY` (mapped to `ALREADY_EXISTS`) for conflicting ones.
     * Returns `true` when the submission was fully handled here.
     */
    private suspend fun replayIdempotent(
        env: Envelope,
        request: JobSubmit,
        transport: Transport,
    ): Boolean {
        val key = request.idempotencyKey ?: return false
        val principal = sessions.principalFor(env.sessionId)
        val prior = jobs.idempotency[IdempotencyKey(principal, key)] ?: return false
        if (prior.fingerprint != fingerprint(request)) {
            transport.send(nack(env, duplicateKey(key)))
        } else {
            transport.send(acceptedEnvelope(env, prior.jobId, prior.accepted))
        }
        return true
    }

    private fun duplicateKey(key: String): ARCPException = ARCPException.AlreadyExists(
        "idempotency_key '$key' was reused with conflicting parameters (DUPLICATE_KEY)",
    )

    /**
     * Stable fingerprint of a submission's effective parameters, excluding the
     * idempotency key itself, used to detect conflicting reuse (§7.2).
     */
    private fun fingerprint(request: JobSubmit): String =
        arcpJson.encodeToString(JobSubmit.serializer(), request.copy(idempotencyKey = null))

    /** Derived values for a single submission, bundled to keep helpers small. */
    private data class SubmitDraft(
        val jobId: JobId,
        val lease: CostBudget?,
        val modelUse: ModelUseLease?,
        val expiresAt: Instant?,
        val acceptedAt: Instant,
    )
}
