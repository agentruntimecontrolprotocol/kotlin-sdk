package dev.arcp.credentials

import java.util.concurrent.ConcurrentHashMap

/** Deterministic in-memory provisioner for tests and samples. */
public class InMemoryCredentialProvisioner(
    private val endpoint: String = "https://example.invalid/v1",
) : CredentialProvisioner {
    private val issuedById: ConcurrentHashMap<CredentialId, Credential> = ConcurrentHashMap()
    private val revokedIds: MutableSet<CredentialId> = ConcurrentHashMap.newKeySet()

    public val issued: List<Credential>
        get() = issuedById.values.toList()

    public val revoked: Set<CredentialId>
        get() = revokedIds.toSet()

    override suspend fun issue(context: CredentialProvisioner.IssuanceContext): List<Credential> {
        val id = CredentialId.random()
        val credential =
            Credential(
                id = id,
                scheme = CredentialScheme.BEARER,
                value = "test_${id.value}",
                endpoint = endpoint,
                constraints =
                    CredentialConstraints(
                        costBudget = context.lease
                            ?.budgets
                            ?.map { it.render() }
                            .orEmpty(),
                        modelUse = context.modelUse?.patterns.orEmpty(),
                        expiresAt = context.expiresAt,
                    ),
            )
        issuedById[id] = credential
        return listOf(credential)
    }

    override suspend fun revoke(credentialId: CredentialId) {
        revokedIds += credentialId
    }
}
