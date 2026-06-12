package dev.arcp.runtime

import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.BudgetCounter
import dev.arcp.lease.BudgetRegistry
import dev.arcp.lease.Currency
import dev.arcp.messages.Ack
import dev.arcp.messages.Cancel
import dev.arcp.messages.CancelAccepted
import dev.arcp.messages.CancelTarget
import dev.arcp.messages.Metric
import dev.arcp.messages.StandardMetrics
import dev.arcp.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive

private val log = KotlinLogging.logger {}

/**
 * Handles job-lifecycle messages — cost metrics, `job.cancel`, and the
 * terminal `job.completed/failed/cancelled` events — and enforces that every
 * job-scoped operation is authorized against the owning principal (§7.6/§14).
 * Extracted from the runtime facade for cohesion (#83).
 */
internal class JobLifecycleHandler(
    private val jobInventory: JobInventory,
    private val budgets: BudgetRegistry,
    private val credentials: CredentialLifecycle,
    private val sessions: SessionRegistry,
    private val jobs: RuntimeJobs,
    private val evictTerminalJobs: Boolean,
) {
    suspend fun metric(
        env: Envelope,
        metric: Metric,
        transport: Transport,
    ) {
        val jobId = env.jobId ?: return
        // A metric must not poison another principal's budget (§14): only the
        // job's owner may report cost against it.
        if (!authorizedForJob(env, jobId)) {
            transport.send(nack(env, permissionDenied("job.metric", jobId.value)))
            return
        }
        if (!metric.name.startsWith("cost.") ||
            metric.name == StandardMetrics.COST_BUDGET_REMAINING
        ) {
            return
        }
        applyCostMetric(env, jobId, metric, transport)
    }

    private suspend fun applyCostMetric(
        env: Envelope,
        jobId: JobId,
        metric: Metric,
        transport: Transport,
    ) {
        val amount = BudgetAmount(Currency(metric.unit), metric.value.asBigDecimal())
        val outcome = budgets.consume(jobId, amount)
        if (outcome !is BudgetRegistry.Outcome.Counted) {
            log.info { "cost metric for unregistered job ${jobId.value}; ignoring" }
            return
        }
        emitRemainingBudget(env, jobId, amount, transport)
        budgetError(outcome.outcome, jobId)?.let { transport.send(nack(env, it)) }
    }

    private fun budgetError(
        counted: BudgetCounter.Outcome,
        jobId: JobId,
    ): ARCPException? {
        val currency =
            when (counted) {
                BudgetCounter.Outcome.Ok -> return null
                is BudgetCounter.Outcome.Exhausted -> counted.currency.code
                is BudgetCounter.Outcome.Rejected -> counted.currency.code
            }
        return ARCPException.BudgetExhausted(currency, jobId)
    }

    suspend fun cancel(
        env: Envelope,
        request: Cancel,
        transport: Transport,
    ) {
        if (request.target != CancelTarget.JOB) {
            transport.send(
                nack(
                    env,
                    ARCPException.Unimplemented("10.4", "only job cancellation is implemented"),
                ),
            )
            return
        }
        val jobId = JobId(request.targetId)
        // §7.6/§14: cancellation is reserved for the submitting principal; a
        // state subscription MUST NOT confer cancel authority. Reject any
        // other principal (and unknown jobs) without terminating the job or
        // revoking its credentials, and without leaking job existence.
        if (jobs.active[jobId]?.ownerPrincipal != sessions.principalFor(env.sessionId)) {
            transport.send(nack(env, permissionDenied("job.cancel", request.targetId)))
            return
        }
        terminalCleanup(jobId, "cancelled")
        transport.send(reply(env, CancelAccepted(targetId = request.targetId)))
    }

    suspend fun terminal(
        env: Envelope,
        status: String,
        transport: Transport,
    ) {
        val jobId = env.jobId
        if (jobId != null) {
            // Only the owning principal may drive a job to a terminal state
            // (§14); reject foreign terminal events without mutating the job.
            if (!authorizedForJob(env, jobId)) {
                transport.send(nack(env, permissionDenied("job.$status", jobId.value)))
                return
            }
            terminalCleanup(jobId, status)
        }
        transport.send(reply(env, Ack(ackFor = env.id)))
    }

    /**
     * Authorizes a job-scoped operation against the session principal (§14).
     * An active job is accessible only to its owner; a job that is not (or no
     * longer) active is treated as accessible so idempotent terminal/metric
     * cleanup for the owner is preserved.
     */
    private fun authorizedForJob(
        env: Envelope,
        jobId: JobId,
    ): Boolean {
        val job = jobs.active[jobId] ?: return true
        return job.ownerPrincipal == sessions.principalFor(env.sessionId)
    }

    private suspend fun terminalCleanup(
        jobId: JobId,
        status: String,
    ) {
        jobInventory.updateStatus(jobId, status)
        budgets.terminate(jobId)
        jobs.active.remove(jobId)
        credentials.revokeOutstanding(jobId)
        // Drop the inventory record once events have drained — long-lived
        // runtimes that accept many short jobs cannot afford an unbounded
        // in-memory inventory (#60). Implementations that want to retain
        // terminal jobs for replay should override JobInventory.evict.
        if (evictTerminalJobs) jobInventory.evict(jobId)
    }

    private suspend fun emitRemainingBudget(
        env: Envelope,
        jobId: JobId,
        amount: BudgetAmount,
        transport: Transport,
    ) {
        val remaining = budgets.remaining(jobId)?.byCurrency(amount.currency)?.value ?: return
        transport.send(
            Envelope(
                id = MessageId.random(),
                sessionId = env.sessionId,
                jobId = jobId,
                causationId = env.id,
                payload =
                    Metric(
                        name = StandardMetrics.COST_BUDGET_REMAINING,
                        value = JsonPrimitive(remaining.toPlainString()),
                        unit = amount.currency.code,
                    ),
            ),
        )
    }
}
