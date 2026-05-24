package dev.arcp.lease

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

/**
 * Direct coverage for the [BudgetCounter.consume] post-fix contract: a spend
 * that would drop remaining below zero must return [BudgetCounter.Outcome.Rejected]
 * without mutating the counter, so a caller can retry with a smaller amount
 * (#65).
 */
class BudgetCounterRejectedTest :
    StringSpec({
        "rejects an over-spend without mutating the counter (#65)" {
            val counter = BudgetCounter(
                CostBudget(listOf(BudgetAmount(Currency("USD"), BigDecimal("1.00")))),
            )
            val outcome = counter.consume(BudgetAmount(Currency("USD"), BigDecimal("1.50")))
            outcome.shouldBeInstanceOf<BudgetCounter.Outcome.Rejected>()
            counter.remaining(Currency("USD")) shouldBe BigDecimal("1.00")
        }

        "a smaller follow-up spend still succeeds after a rejected over-spend" {
            val counter = BudgetCounter(
                CostBudget(listOf(BudgetAmount(Currency("USD"), BigDecimal("1.00")))),
            )
            counter.consume(BudgetAmount(Currency("USD"), BigDecimal("2.00")))
            val ok = counter.consume(BudgetAmount(Currency("USD"), BigDecimal("0.40")))
            ok shouldBe BudgetCounter.Outcome.Ok
            counter.remaining(Currency("USD")) shouldBe BigDecimal("0.60")
        }

        "exact-zero spend returns Exhausted, not Rejected" {
            val counter = BudgetCounter(
                CostBudget(listOf(BudgetAmount(Currency("USD"), BigDecimal("0.25")))),
            )
            val outcome = counter.consume(BudgetAmount(Currency("USD"), BigDecimal("0.25")))
            outcome.shouldBeInstanceOf<BudgetCounter.Outcome.Exhausted>()
            counter.remaining(Currency("USD")) shouldBe BigDecimal("0.00")
        }
    })
