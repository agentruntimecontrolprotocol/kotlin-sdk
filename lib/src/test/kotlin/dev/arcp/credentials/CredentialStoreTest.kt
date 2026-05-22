package dev.arcp.credentials

import dev.arcp.ids.JobId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class CredentialStoreTest :
    StringSpec({
        "record and outstanding returns recorded credentials" {
            val store = InMemoryCredentialStore()
            val credential =
                Credential(
                    id = CredentialId("cred_x"),
                    scheme = CredentialScheme.BEARER,
                    value = "secret",
                    endpoint = "https://example.invalid",
                )
            store.record(JobId("job_x"), credential)
            store.outstanding(JobId("job_x")) shouldBe listOf(credential)
            store.pendingRevocations().shouldContain(credential)
        }

        "remove clears credential from pending revocations" {
            val store = InMemoryCredentialStore()
            val credential =
                Credential(
                    id = CredentialId("cred_x"),
                    scheme = CredentialScheme.BEARER,
                    value = "secret",
                    endpoint = "https://example.invalid",
                )
            store.record(JobId("job_x"), credential)
            store.remove(credential.id)
            store.pendingRevocations() shouldBe emptyList()
        }
    })
