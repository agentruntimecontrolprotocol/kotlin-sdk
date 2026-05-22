package dev.arcp.lease

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class BudgetCounterTest :
    StringSpec({
        "decrements remaining on consume" {
            val counter = BudgetCounter(CostBudget(listOf(BudgetAmount.parse("USD:1.00"))))
            counter.consume(BudgetAmount.parse("USD:0.30")) shouldBe BudgetCounter.Outcome.Ok
            counter.remaining(Currency("USD")) shouldBe BigDecimal("0.70")
        }

        "returns exhausted at zero" {
            val counter = BudgetCounter(CostBudget(listOf(BudgetAmount.parse("USD:0.10"))))
            counter.consume(BudgetAmount.parse("USD:0.10")) shouldBe
                BudgetCounter.Outcome.Exhausted(Currency("USD"))
        }

        "rejects negative amount" {
            val counter = BudgetCounter(CostBudget(listOf(BudgetAmount.parse("USD:1.00"))))
            shouldThrow<IllegalArgumentException> {
                counter.consume(BudgetAmount.parse("USD:-0.10"))
            }
        }

        "tracks multiple currencies independently" {
            val counter =
                BudgetCounter(
                    CostBudget(
                        listOf(
                            BudgetAmount.parse("USD:1.00"),
                            BudgetAmount.parse("EUR:2.00"),
                        ),
                    ),
                )
            counter.consume(BudgetAmount.parse("USD:0.25"))
            counter.remaining(Currency("EUR")) shouldBe BigDecimal("2.00")
        }
    })
