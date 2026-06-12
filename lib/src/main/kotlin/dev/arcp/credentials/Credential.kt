package dev.arcp.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Provisioned credential scheme. */
@Serializable
public enum class CredentialScheme {
    @SerialName("bearer")
    BEARER,
}

/** Lease constraints baked into a provisioned credential. */
@Serializable
public data class CredentialConstraints(
    @SerialName("cost.budget")
    val costBudget: List<String> = emptyList(),
    @SerialName("model.use")
    val modelUse: List<String> = emptyList(),
    @SerialName("expires_at")
    val expiresAt: Instant? = null,
)

/** Lease-bound provisioned credential. The secret [value] is redacted in logs. */
@Serializable
public data class Credential(
    val id: CredentialId,
    val scheme: CredentialScheme,
    val value: String,
    val endpoint: String,
    val profile: String? = null,
    val constraints: CredentialConstraints? = null,
) {
    override fun toString(): String =
        "Credential(id=$id, scheme=$scheme, endpoint=$endpoint, profile=$profile, value=<redacted>)"
}
