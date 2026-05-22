package dev.arcp.messages

import dev.arcp.envelope.Envelope
import dev.arcp.ids.MessageId
import dev.arcp.json.arcpJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AgentRefTest :
    StringSpec({
        "parses bare name" {
            AgentRef.parse("code-refactor") shouldBe AgentRef("code-refactor")
        }

        "parses name at version" {
            AgentRef.parse("code-refactor@2.0.0") shouldBe
                AgentRef("code-refactor", "2.0.0")
        }

        "rejects invalid name" {
            shouldThrow<IllegalArgumentException> {
                AgentRef.parse("Bad/Name")
            }
        }

        "rejects empty version" {
            shouldThrow<IllegalArgumentException> {
                AgentRef.parse("foo@")
            }
        }

        "round-trips through arcpJson inside JobSubmit" {
            val original =
                Envelope(id = MessageId("msg_x"), payload = JobSubmit("code-refactor@2.0.0"))
            val encoded = arcpJson.encodeToString(Envelope.serializer(), original)
            arcpJson.decodeFromString(Envelope.serializer(), encoded) shouldBe original
        }
    })
