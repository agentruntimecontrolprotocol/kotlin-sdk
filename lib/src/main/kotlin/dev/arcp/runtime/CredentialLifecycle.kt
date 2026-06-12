package dev.arcp.runtime

import dev.arcp.credentials.Credential
import dev.arcp.credentials.CredentialId
import dev.arcp.credentials.CredentialProvisioner
import dev.arcp.credentials.CredentialStore
import dev.arcp.envelope.Envelope
import dev.arcp.error.ARCPException
import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.transport.Transport
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

private val log = KotlinLogging.logger {}

/**
 * Owns credential issuance, revocation (with retry/back-off), and rotation
 * for provisioned-credential jobs (RFC §9.8). Extracted from the runtime
 * facade so credential concerns live in one place (#83).
 */
internal class CredentialLifecycle(
    private val provisioner: CredentialProvisioner?,
    private val store: CredentialStore,
) {
    /** Revokes any credentials left outstanding from a prior process (§9.8). */
    suspend fun drainPending() {
        provisioner ?: return
        store.pendingRevocations().forEach { revokeWithRetry(it) }
    }

    /** Issues credentials for a job when a provisioner and a lease are present. */
    suspend fun issue(context: CredentialProvisioner.IssuanceContext): List<Credential> {
        val provisioner = provisioner ?: return emptyList()
        if (context.lease == null && context.modelUse == null) return emptyList()
        val issued = provisioner.issue(context)
        issued.forEach { store.record(context.jobId, it) }
        return issued
    }

    /** Revokes every credential still outstanding for [jobId]. */
    suspend fun revokeOutstanding(jobId: JobId) {
        provisioner ?: return
        store.outstanding(jobId).forEach { revokeWithRetry(it) }
    }

    private suspend fun revokeWithRetry(credential: Credential) {
        val provisioner = provisioner ?: return
        repeat(REVOKE_ATTEMPTS) { attempt ->
            if (tryRevoke(provisioner, credential, attempt)) return
        }
    }

    private suspend fun tryRevoke(
        provisioner: CredentialProvisioner,
        credential: Credential,
        attempt: Int,
    ): Boolean = try {
        provisioner.revoke(credential.id)
        store.remove(credential.id)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        handleRevokeFailure(e, credential, attempt)
        false
    }

    private suspend fun handleRevokeFailure(
        e: Exception,
        credential: Credential,
        attempt: Int,
    ) {
        if (attempt == REVOKE_ATTEMPTS - 1) {
            log.warn(e) {
                "credential revocation failed for ${credential.id.value} after " +
                    "${REVOKE_ATTEMPTS} attempts"
            }
            return
        }
        log.warn(e) {
            "credential revocation attempt ${attempt + 1}/$REVOKE_ATTEMPTS " +
                "failed for ${credential.id.value}"
        }
        val backoffMs = min(
            REVOKE_BACKOFF_INITIAL_MS * REVOKE_BACKOFF_BASE.pow(attempt).toLong(),
            REVOKE_BACKOFF_CAP_MS,
        )
        delay(backoffMs.milliseconds)
    }

    /** Reissues one outstanding credential, optionally emitting a rotation event. */
    suspend fun rotate(
        jobId: JobId,
        job: RuntimeJob?,
        credentialId: CredentialId,
        transport: Transport?,
    ): Credential {
        val provisioner =
            provisioner
                ?: throw ARCPException.FailedPrecondition(
                    "credential provisioner is not configured",
                )
        val active = job ?: throw ARCPException.NotFound("job $jobId is not active")
        val old = store.outstanding(jobId).firstOrNull { it.id == credentialId }
            ?: throw ARCPException.NotFound("credential $credentialId is not outstanding")
        val newCredential =
            provisioner
                .issue(
                    CredentialProvisioner.IssuanceContext(
                        jobId = jobId,
                        parentJobId = active.parentJobId,
                        lease = active.costBudget,
                        modelUse = active.modelUse,
                        expiresAt = active.expiresAt,
                    ),
                ).first()
        store.record(jobId, newCredential)
        revokeWithRetry(old)
        transport?.send(rotationEvent(jobId, newCredential))
        return newCredential
    }

    private fun rotationEvent(
        jobId: JobId,
        credential: Credential,
    ): Envelope = Envelope(
        id = MessageId.random(),
        jobId = jobId,
        payload =
            dev.arcp.messages.JobStatusEvent(
                phase = "credential_rotated",
                body =
                    buildJsonObject {
                        put("id", credential.id.value)
                        put("value", credential.value)
                    },
            ),
    )

    private companion object {
        const val REVOKE_ATTEMPTS: Int = 3
        const val REVOKE_BACKOFF_INITIAL_MS: Long = 250L
        const val REVOKE_BACKOFF_BASE: Double = 2.0
        const val REVOKE_BACKOFF_CAP_MS: Long = 5_000L
    }
}
