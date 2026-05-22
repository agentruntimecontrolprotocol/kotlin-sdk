package com.arcp.samples.costbudget

import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.BudgetCounter
import dev.arcp.lease.CostBudget
import dev.arcp.lease.Currency

public fun main() {
    val counter = BudgetCounter(CostBudget(listOf(BudgetAmount.parse("USD:1.00"))))
    listOf("USD:0.35", "USD:0.40", "USD:0.30").forEach { raw ->
        val amount = BudgetAmount.parse(raw)
        val outcome = counter.consume(amount)
        println(
            "consumed ${amount.render()}, remaining USD:${counter.remaining(
                Currency("USD"),
            )}, outcome=$outcome",
        )
    }
}
