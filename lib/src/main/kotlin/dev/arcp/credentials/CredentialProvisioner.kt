package dev.arcp.credentials

import dev.arcp.ids.JobId
import dev.arcp.lease.CostBudget
import dev.arcp.lease.ModelUseLease
import kotlin.time.Instant

/** Issues and revokes lease-bound credentials for accepted jobs. */
public interface CredentialProvisioner {
    /** Issues credentials for an accepted job context. */
    public suspend fun issue(context: IssuanceContext): List<Credential>

    /** Revokes a previously issued credential. */
    public suspend fun revoke(credentialId: CredentialId)

    public data class IssuanceContext(
        val jobId: JobId,
        val parentJobId: JobId?,
        val lease: CostBudget?,
        val modelUse: ModelUseLease?,
        val expiresAt: Instant?,
    )
}
