package dev.arcp.runtime

import dev.arcp.messages.Capabilities
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CapabilityNegotiationTest :
    StringSpec({
        "interrupt stays enabled when both peers use defaults" {
            val proposed = Capabilities()
            val supported = Capabilities()

            val result = negotiate(proposed, supported)

            result.negotiated.interrupt shouldBe true
        }
    })
