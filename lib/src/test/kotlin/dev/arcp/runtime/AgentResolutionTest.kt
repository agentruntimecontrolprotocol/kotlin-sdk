package dev.arcp.runtime

import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode
import dev.arcp.messages.AgentRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class AgentResolutionTest :
    StringSpec({
        "bare name resolves to default" {
            val registry = AgentRegistry()
            registry.register("code-refactor", "1.0.0")
            registry.register("code-refactor", "2.0.0", default = true)
            registry.resolve(AgentRef("code-refactor")) shouldBe AgentRef("code-refactor", "2.0.0")
        }

        "bare name without default falls back to a registered version" {
            val registry = AgentRegistry()
            registry.register("code-refactor", "1.0.0")
            registry
                .descriptors()
                .single()
                .versions
                .shouldContain("1.0.0")
            registry.resolve(AgentRef("code-refactor")) shouldBe AgentRef("code-refactor", "1.0.0")
        }

        "exact version returns that version" {
            val registry = AgentRegistry()
            registry.register("code-refactor", "1.0.0")
            registry.resolve(AgentRef("code-refactor", "1.0.0")) shouldBe
                AgentRef("code-refactor", "1.0.0")
        }

        "missing version throws AgentVersionNotAvailable" {
            val registry = AgentRegistry()
            registry.register("code-refactor", "1.0.0")
            val ex =
                shouldThrow<ARCPException.AgentVersionNotAvailable> {
                    registry.resolve(AgentRef("code-refactor", "9.9.9"))
                }
            ex.code shouldBe ErrorCode.AGENT_VERSION_NOT_AVAILABLE
        }
    })
