package com.arcp.samples.recipes.litellm

import dev.arcp.credentials.CredentialProvisioner

public fun main() {
    println(
        """
        LiteLLM provisioner recipe

        Implement CredentialProvisioner.issue by calling POST /key/generate with:
        - max_budget from cost.budget
        - allowed_models from model.use
        - duration from lease_constraints.expires_at

        Implement CredentialProvisioner.revoke by calling POST /key/delete with the credential id.
        Keep the implementation outside core so vendor behavior remains pluggable.
        """.trimIndent(),
    )
    println("Interface: ${CredentialProvisioner::class.qualifiedName}")
}
