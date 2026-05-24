package dev.arcp.runtime

import dev.arcp.messages.Capabilities
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class CapabilityNegotiationTest :
    StringSpec({
        "interrupt stays enabled when both peers use defaults" {
            val proposed = Capabilities()
            val supported = Capabilities()

            val result = negotiate(proposed, supported)

            result.negotiated.interrupt shouldBe true
        }

        // ---- #51 binary encoding negotiation ----

        "binary encoding intersects identical lists" {
            val result = negotiate(
                proposed = Capabilities(binaryEncoding = listOf("base64", "raw")),
                supported = Capabilities(binaryEncoding = listOf("base64", "raw")),
            )
            result.negotiated.binaryEncoding shouldContain "base64"
            result.negotiated.binaryEncoding shouldContain "raw"
        }

        "binary encoding intersection is empty when both sides advertise disjoint lists (#51)" {
            val result = negotiate(
                proposed = Capabilities(binaryEncoding = listOf("raw")),
                supported = Capabilities(binaryEncoding = listOf("base64")),
            )
            result.negotiated.binaryEncoding shouldBe emptyList()
        }

        "binary encoding defaults to base64 when both sides omit the field" {
            val result = negotiate(
                proposed = Capabilities(),
                supported = Capabilities(),
            )
            result.negotiated.binaryEncoding shouldBe listOf("base64")
        }

        // ---- #57 extension handling ----

        "unknown vendor extension is silently dropped (#57)" {
            val result = negotiate(
                proposed = Capabilities(extensions = listOf("arcpx.unknown.example.v1")),
                supported = Capabilities(),
            )
            result.negotiated.extensions shouldBe emptyList()
            result.unsupported shouldBe emptyList()
        }

        "matching vendor extension is intersected and kept" {
            val proposed = Capabilities(
                extensions = listOf("arcpx.acme.cache.v1", "arcpx.other.v1"),
            )
            val supported = Capabilities(extensions = listOf("arcpx.acme.cache.v1"))
            val result = negotiate(proposed, supported)
            result.negotiated.extensions shouldBe listOf("arcpx.acme.cache.v1")
            result.unsupported shouldBe emptyList()
        }

        "required boolean feature mismatch still appears in unsupported" {
            val result = negotiate(
                proposed = Capabilities(streaming = true),
                supported = Capabilities(streaming = false),
            )
            result.unsupported shouldContain "streaming"
        }

        "client features the runtime turns off do not appear in unsupported" {
            val result = negotiate(
                proposed = Capabilities(streaming = false),
                supported = Capabilities(streaming = true),
            )
            result.unsupported shouldNotContain "streaming"
            result.negotiated.streaming shouldBe false
        }
    })
