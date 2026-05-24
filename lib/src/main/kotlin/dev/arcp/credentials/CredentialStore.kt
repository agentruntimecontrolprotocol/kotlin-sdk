package dev.arcp.credentials

import dev.arcp.ids.JobId
import java.util.concurrent.ConcurrentHashMap

/** Store of outstanding credentials requiring terminal revocation. */
public interface CredentialStore {
    /** Records [credential] as outstanding for [jobId]. */
    public suspend fun record(
        jobId: JobId,
        credential: Credential,
    )

    /** Returns credentials that still need revocation for [jobId]. */
    public suspend fun outstanding(jobId: JobId): List<Credential>

    /** Removes [credentialId] from the outstanding set. */
    public suspend fun remove(credentialId: CredentialId)

    /** Returns all credentials that should be retried after restart. */
    public suspend fun pendingRevocations(): List<Credential>
}

/**
 * In-memory [CredentialStore] for tests and single-process runtimes.
 *
 * The map values are immutable [List]s updated through
 * [ConcurrentHashMap.compute], so iteration in [outstanding] /
 * [pendingRevocations] never observes a partially-mutated list and
 * concurrent [record] / [remove] calls on the same job id are atomic.
 */
public class InMemoryCredentialStore : CredentialStore {
    private val byJob: ConcurrentHashMap<JobId, List<Credential>> = ConcurrentHashMap()

    override suspend fun record(
        jobId: JobId,
        credential: Credential,
    ) {
        byJob.compute(jobId) { _, existing -> (existing ?: emptyList()) + credential }
    }

    override suspend fun outstanding(jobId: JobId): List<Credential> = byJob[jobId].orEmpty()

    override suspend fun remove(credentialId: CredentialId) {
        for (key in byJob.keys) {
            byJob.compute(key) { _, existing ->
                val filtered = existing?.filterNot { it.id == credentialId }
                if (filtered.isNullOrEmpty()) null else filtered
            }
        }
    }

    override suspend fun pendingRevocations(): List<Credential> = byJob.values.flatten()
}
