package dev.arcp.lease

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Mutable per-job counter for a [CostBudget].
 *
 * The counter has two distinct entry points for the two §9.6 concerns:
 *
 * - [consume] is the *cost-reporting* path driven by `metric` events. The
 *   spend has already happened, so the counter MUST always decrement by
 *   `value` (§9.6). A positive spend that exceeds the remaining balance
 *   drives the counter to zero and returns [Outcome.Exhausted] — it is
 *   never rejected. Once a currency is exhausted, subsequent positive
 *   spends keep reporting [Outcome.Exhausted].
 * - [tryReserve] is the *pre-authorization* path: a spend that would
 *   cross zero is rejected as [Outcome.Rejected] and the counter is *not*
 *   mutated, so a caller may retry with a smaller amount (#65).
 */
public class BudgetCounter(
    initial: CostBudget,
) {
    private val remaining: ConcurrentHashMap<Currency, BigDecimal> =
        ConcurrentHashMap(initial.budgets.associate { it.currency to it.value })

    /**
     * Records a *reported* spend of [amount] against the counter (§9.6 cost
     * metric path). Always decrements the matching counter by `value`,
     * clamping the floor at zero. Returns:
     * - [Outcome.Ok] when budget remains after the decrement.
     * - [Outcome.Exhausted] when the decrement reached or crossed zero.
     *
     * Negative values are rejected by the `require` guard and produce no
     * decrement (§9.6). Untracked currencies return [Outcome.Ok] for
     * backward compatibility: a counter only enforces the currencies the
     * initial [CostBudget] declared.
     */
    public fun consume(amount: BudgetAmount): Outcome {
        require(amount.value >= BigDecimal.ZERO) { "budget consumption must be non-negative" }
        val left = remaining.computeIfPresent(amount.currency) { _, current ->
            val next = current.subtract(amount.value)
            if (next < BigDecimal.ZERO) BigDecimal.ZERO else next
        } ?: return Outcome.Ok
        return if (left.compareTo(BigDecimal.ZERO) == 0) {
            Outcome.Exhausted(amount.currency)
        } else {
            Outcome.Ok
        }
    }

    /**
     * Attempts to *reserve* [amount] ahead of a spend (pre-authorization).
     * Returns:
     * - [Outcome.Ok] when the spend fit and budget remains.
     * - [Outcome.Exhausted] when the spend fit and remaining now equals zero.
     * - [Outcome.Rejected] when the spend would drop remaining below zero
     *   (the counter is *not* modified, so the caller may retry smaller).
     *
     * Untracked currencies return [Outcome.Ok] for backward compatibility.
     */
    public fun tryReserve(amount: BudgetAmount): Outcome {
        require(amount.value >= BigDecimal.ZERO) { "budget reservation must be non-negative" }
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
