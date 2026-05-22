package com.arcp.samples.provisionedcredentials

import dev.arcp.credentials.CredentialProvisioner
import dev.arcp.credentials.InMemoryCredentialProvisioner
import dev.arcp.ids.JobId
import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import kotlinx.coroutines.runBlocking

public fun main(): Unit = runBlocking {
    val provisioner = InMemoryCredentialProvisioner()
    val issued =
        provisioner
            .issue(
                CredentialProvisioner.IssuanceContext(
                    jobId = JobId("job_demo"),
                    parentJobId = null,
                    lease = CostBudget(listOf(BudgetAmount.parse("USD:1.00"))),
                    modelUse = ModelUseLease(listOf("tier-fast/*")),
                    expiresAt = null,
                ),
            ).single()

    println("issued ${issued.id} for ${issued.constraints?.modelUse.orEmpty().joinToString()}")
    provisioner.revoke(issued.id)
    println("revoked ${provisioner.revoked.size} credential")
}
