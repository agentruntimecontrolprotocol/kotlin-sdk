package dev.arcp.lease

import dev.arcp.ids.JobId
import java.util.concurrent.ConcurrentHashMap

/** Registry of active job budget counters. */
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

    /** Consumes [amount] from the counter for [jobId]. */
    public fun consume(
        jobId: JobId,
        amount: BudgetAmount,
    ): BudgetCounter.Outcome = counters[jobId]?.consume(amount) ?: BudgetCounter.Outcome.Ok

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
}
