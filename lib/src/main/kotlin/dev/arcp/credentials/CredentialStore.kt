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

/** In-memory [CredentialStore] for tests and single-process runtimes. */
public class InMemoryCredentialStore : CredentialStore {
    private val byJob: ConcurrentHashMap<JobId, MutableList<Credential>> = ConcurrentHashMap()

    override suspend fun record(
        jobId: JobId,
        credential: Credential,
    ) {
        byJob.compute(jobId) { _, existing ->
            (existing ?: mutableListOf()).also { it += credential }
        }
    }

    override suspend fun outstanding(jobId: JobId): List<Credential> =
        byJob[jobId]?.toList().orEmpty()

    override suspend fun remove(credentialId: CredentialId) {
        byJob.entries.forEach { (_, credentials) ->
            credentials.removeIf { it.id == credentialId }
        }
        byJob.entries.removeIf { it.value.isEmpty() }
    }

    override suspend fun pendingRevocations(): List<Credential> =
        byJob.values.flatMap { it.toList() }
}
