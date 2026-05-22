package dev.arcp.lease

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ModelUseTest :
    StringSpec({
        "matches glob patterns" {
            val lease = ModelUseLease(listOf("tier-fast/*"))
            lease.allows("tier-fast/foo") shouldBe true
            lease.allows("tier-slow/foo") shouldBe false
        }

        "subset accepts strict subset" {
            ModelUseLease.subset(
                parent = ModelUseLease(listOf("tier-fast/*")),
                child = ModelUseLease(listOf("tier-fast/foo")),
            ) shouldBe true
        }

        "subset rejects expansion" {
            ModelUseLease.subset(
                parent = ModelUseLease(listOf("tier-fast/foo")),
                child = ModelUseLease(listOf("tier-fast/*")),
            ) shouldBe false
        }

        "subset rejects new namespace" {
            ModelUseLease.subset(
                parent = ModelUseLease(listOf("tier-fast/*")),
                child = ModelUseLease(listOf("anthropic/*")),
            ) shouldBe false
        }
    })
