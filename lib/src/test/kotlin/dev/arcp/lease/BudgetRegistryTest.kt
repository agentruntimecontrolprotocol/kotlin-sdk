package dev.arcp.lease

import dev.arcp.ids.JobId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

/**
 * Coverage for [BudgetRegistry]'s lifecycle: register/consume/terminate/remaining
 * plus the new [BudgetRegistry.Outcome.Unregistered] outcome (#66).
 */
class BudgetRegistryTest :
    StringSpec({
        val job = JobId("job_test")
        val usd = Currency("USD")

        "consume on an unregistered job returns Unregistered, not Ok (#66)" {
            val registry = BudgetRegistry()
            val outcome = registry.consume(job, BudgetAmount(usd, BigDecimal("0.10")))
            outcome shouldBe BudgetRegistry.Outcome.Unregistered
        }

        "consume after register returns Counted(Ok)" {
            val registry = BudgetRegistry()
            registry.register(job, CostBudget(listOf(BudgetAmount(usd, BigDecimal("1.00")))))
            val outcome = registry.consume(job, BudgetAmount(usd, BigDecimal("0.25")))
            outcome.shouldBeInstanceOf<BudgetRegistry.Outcome.Counted>()
            outcome.outcome shouldBe BudgetCounter.Outcome.Ok
        }

        "remaining reflects consumed amount" {
            val registry = BudgetRegistry()
            registry.register(job, CostBudget(listOf(BudgetAmount(usd, BigDecimal("1.00")))))
            registry.consume(job, BudgetAmount(usd, BigDecimal("0.30")))
            val remaining = registry.remaining(job)
            remaining shouldBe
                CostBudget(listOf(BudgetAmount(usd, BigDecimal("0.70"))))
        }

        "terminate drops both counter and initial state" {
            val registry = BudgetRegistry()
            registry.register(job, CostBudget(listOf(BudgetAmount(usd, BigDecimal("1.00")))))
            registry.terminate(job)
            registry.remaining(job) shouldBe null
            registry.consume(job, BudgetAmount(usd, BigDecimal("0.10"))) shouldBe
                BudgetRegistry.Outcome.Unregistered
        }
    })
