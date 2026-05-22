package dev.arcp.credentials

import dev.arcp.ids.Ulid
import kotlinx.serialization.Serializable

/** Identifier for a provisioned credential. */
@JvmInline
@Serializable
public value class CredentialId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "CredentialId must not be blank" }
    }

    override fun toString(): String = value

    public companion object {
        /** Returns a freshly minted credential id. */
        public fun random(): CredentialId = CredentialId(Ulid.next("cred"))
    }
}
