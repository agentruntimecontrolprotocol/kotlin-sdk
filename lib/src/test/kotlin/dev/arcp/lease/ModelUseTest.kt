package dev.arcp.lease

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class ModelUseTest :
    StringSpec({
        "matches glob patterns" {
            val lease = ModelUseLease(listOf("tier-fast/*"))
            lease.allows("tier-fast/foo") shouldBe true
            lease.allows("tier-slow/foo") shouldBe false
        }

        "reuses one compiled regex per pattern across repeated checks (#84)" {
            val lease = ModelUseLease(listOf("tier-fast/*", "anthropic/**"))
            repeat(5) {
                lease.allows("tier-fast/foo") shouldBe true
                lease.allows("anthropic/claude") shouldBe true
            }
            // Repeated lookups return the same cached Regex instance rather
            // than recompiling the glob on every authorization check.
            lease.regexFor("tier-fast/*") shouldBeSameInstanceAs lease.regexFor("tier-fast/*")
            lease.regexFor("anthropic/**") shouldBeSameInstanceAs lease.regexFor("anthropic/**")
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
