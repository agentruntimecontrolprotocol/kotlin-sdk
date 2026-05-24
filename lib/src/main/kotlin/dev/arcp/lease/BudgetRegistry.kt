package dev.arcp.lease

import dev.arcp.ids.JobId
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of active job budget counters.
 *
 * Callers that need to distinguish "no budget registered for this job"
 * from "spend fit within the budget" should match on the [Outcome]
 * sealed interface — [Outcome.Unregistered] is returned when no counter
 * exists, [BudgetCounter.Outcome] variants when one does.
 */
public class BudgetRegistry {
    private val counters: ConcurrentHashMap<JobId, BudgetCounter> = ConcurrentHashMap()
    private val initial: ConcurrentHashMap<JobId, CostBudget> = ConcurrentHashMap()

    /** Registers [budget] for [jobId]. */
    public fun register(
        jobId: JobId,
        budget: CostBudget,
    ) {
        counters[jobId] = BudgetCounter(budget)
        initial[jobId] = budget
    }

    /**
     * Consumes [amount] from the counter for [jobId]. Returns
     * [Outcome.Unregistered] when no counter has been registered (the
     * caller can then decide whether to log, drop the metric, or refuse
     * the operation); otherwise delegates to [BudgetCounter.consume].
     */
    public fun consume(
        jobId: JobId,
        amount: BudgetAmount,
    ): Outcome {
        val counter = counters[jobId] ?: return Outcome.Unregistered
        return Outcome.Counted(counter.consume(amount))
    }

    /** Removes budget state for a terminal job. */
    public fun terminate(jobId: JobId) {
        counters.remove(jobId)
        initial.remove(jobId)
    }

    /** Returns the remaining budget for [jobId], if registered. */
    public fun remaining(jobId: JobId): CostBudget? {
        val counter = counters[jobId] ?: return null
        val source = initial[jobId] ?: return null
        return CostBudget(
            source.budgets.mapNotNull { budget ->
                counter.remaining(budget.currency)?.let { BudgetAmount(budget.currency, it) }
            },
        )
    }

    /** Outcome of [consume] — disambiguates "no counter" from a real spend. */
    public sealed interface Outcome {
        /** No counter has been registered for the job id. */
        public data object Unregistered : Outcome

        /** A counter exists; [outcome] is the result of consuming against it. */
        public data class Counted(
            public val outcome: BudgetCounter.Outcome,
        ) : Outcome
    }
}
