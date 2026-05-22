package dev.arcp.lease

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LeaseSubsetTest :
    StringSpec({
        "child budget within parent passes" {
            LeaseSubset.subsumes(
                CostBudget(listOf(BudgetAmount.parse("USD:5"))),
                CostBudget(listOf(BudgetAmount.parse("USD:2"))),
            ) shouldBe true
        }

        "child budget exceeding parent fails" {
            LeaseSubset.subsumes(
                CostBudget(listOf(BudgetAmount.parse("USD:5"))),
                CostBudget(listOf(BudgetAmount.parse("USD:10"))),
            ) shouldBe false
        }

        "child introducing new currency fails" {
            LeaseSubset.subsumes(
                CostBudget(listOf(BudgetAmount.parse("USD:5"))),
                CostBudget(listOf(BudgetAmount.parse("EUR:1"))),
            ) shouldBe false
        }
    })
