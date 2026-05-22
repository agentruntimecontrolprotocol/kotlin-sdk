package dev.arcp.lease

/** Lease subsetting helpers for delegated jobs (RFC v1.1 §9.4 / §10). */
public object LeaseSubset {
    /** Returns true when [child] is within [parent] for every currency. */
    public fun subsumes(
        parent: CostBudget,
        child: CostBudget,
    ): Boolean = child.budgets.all { childAmount ->
        val parentAmount = parent.byCurrency(childAmount.currency) ?: return@all false
        childAmount.value <= parentAmount.value
    }

    /** Returns true when child budget and model leases are both within the parent. */
    public fun subsumes(
        parentBudget: CostBudget?,
        childBudget: CostBudget?,
        parentModelUse: ModelUseLease?,
        childModelUse: ModelUseLease?,
    ): Boolean {
        val budgetOk =
            childBudget == null || parentBudget?.let { subsumes(it, childBudget) } == true
        val modelOk = childModelUse == null ||
            parentModelUse?.let { ModelUseLease.subset(it, childModelUse) } == true
        return budgetOk && modelOk
    }
}
