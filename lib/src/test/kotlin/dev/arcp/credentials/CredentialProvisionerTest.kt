package dev.arcp.credentials

import dev.arcp.ids.JobId
import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class CredentialProvisionerTest :
    StringSpec({
        "issue returns credentials whose constraints reflect the lease" {
            val provisioner = InMemoryCredentialProvisioner()
            val issued =
                provisioner.issue(
                    CredentialProvisioner.IssuanceContext(
                        jobId = JobId("job_x"),
                        parentJobId = null,
                        lease = CostBudget(listOf(BudgetAmount.parse("USD:5"))),
                        modelUse = ModelUseLease(listOf("tier-fast/*")),
                        expiresAt = null,
                    ),
                )
            issued.single().constraints!!.costBudget shouldBe listOf("USD:5")
            issued.single().constraints!!.modelUse shouldBe listOf("tier-fast/*")
        }

        "revoke marks the credential as revoked in the in-memory provisioner" {
            val provisioner = InMemoryCredentialProvisioner()
            val credential =
                provisioner
                    .issue(
                        CredentialProvisioner.IssuanceContext(
                            jobId = JobId("job_x"),
                            parentJobId = null,
                            lease = null,
                            modelUse = ModelUseLease(listOf("tier-fast/*")),
                            expiresAt = null,
                        ),
                    ).single()
            provisioner.revoke(credential.id)
            provisioner.revoked.shouldContain(credential.id)
        }

        "Credential toString redacts value" {
            val credential =
                Credential(
                    id = CredentialId("cred_x"),
                    scheme = CredentialScheme.BEARER,
                    value = "secret-token",
                    endpoint = "https://example.invalid",
                )
            credential.toString().shouldNotContain("secret-token")
        }
    })
