package dev.arcp.lease

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Mutable per-job counter for a [CostBudget].
 *
 * The counter enforces `consumed-to-date ≤ initial` per currency. Spends
 * that would cross zero are rejected as [Outcome.Rejected]; the counter
 * is *not* mutated, so a caller may retry with a smaller amount. A spend
 * that lands exactly on zero returns [Outcome.Exhausted]; subsequent
 * non-zero spends return [Outcome.Rejected].
 */
public class BudgetCounter(
    initial: CostBudget,
) {
    private val remaining: ConcurrentHashMap<Currency, BigDecimal> =
        ConcurrentHashMap(initial.budgets.associate { it.currency to it.value })

    /**
     * Attempts to consume [amount]. Returns:
     * - [Outcome.Ok] when the spend fit and budget remains.
     * - [Outcome.Exhausted] when the spend fit and remaining now equals zero.
     * - [Outcome.Rejected] when the spend would drop remaining below zero
     *   (the counter is *not* modified).
     *
     * Untracked currencies return [Outcome.Ok] for backward compatibility:
     * a counter only enforces the currencies the initial [CostBudget]
     * declared.
     */
    public fun consume(amount: BudgetAmount): Outcome {
        require(amount.value >= BigDecimal.ZERO) { "budget consumption must be non-negative" }
        var rejected: Outcome.Rejected? = null
        val left = remaining.computeIfPresent(amount.currency) { _, current ->
            val next = current.subtract(amount.value)
            if (next < BigDecimal.ZERO) {
                rejected = Outcome.Rejected(amount.currency, amount.value, current)
                current
            } else {
                next
            }
        } ?: return Outcome.Ok
        rejected?.let { return it }
        return if (left.compareTo(BigDecimal.ZERO) == 0) {
            Outcome.Exhausted(amount.currency)
        } else {
            Outcome.Ok
        }
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

        /**
         * The requested spend would have pushed the remaining balance
         * negative; the counter was not modified. Callers may retry with
         * a smaller [requested] amount or surface the rejection to the
         * agent.
         */
        public data class Rejected(
            public val currency: Currency,
            public val requested: BigDecimal,
            public val available: BigDecimal,
        ) : Outcome
    }
}
