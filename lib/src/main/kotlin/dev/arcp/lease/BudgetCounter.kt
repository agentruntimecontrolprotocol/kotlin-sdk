package dev.arcp.lease

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** Mutable per-job counter for a [CostBudget]. */
public class BudgetCounter(
    initial: CostBudget,
) {
    private val remaining: ConcurrentHashMap<Currency, BigDecimal> =
        ConcurrentHashMap(initial.budgets.associate { it.currency to it.value })

    /** Consumes [amount] and returns whether the budget is exhausted. */
    public fun consume(amount: BudgetAmount): Outcome {
        require(amount.value >= BigDecimal.ZERO) { "budget consumption must be non-negative" }
        val left = remaining.computeIfPresent(amount.currency) { _, current ->
            current.subtract(amount.value)
        } ?: return Outcome.Ok
        return if (left <= BigDecimal.ZERO) Outcome.Exhausted(amount.currency) else Outcome.Ok
    }

    /** Returns the remaining amount for [currency], if tracked. */
    public fun remaining(currency: Currency): BigDecimal? = remaining[currency]

    /** Returns true when any tracked currency has reached zero or below. */
    public fun isExhausted(): Boolean = remaining.values.any { it <= BigDecimal.ZERO }

    public sealed interface Outcome {
        public data object Ok : Outcome

        public data class Exhausted(
            public val currency: Currency,
        ) : Outcome
    }
}
