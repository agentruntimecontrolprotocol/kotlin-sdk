package dev.fizzpop.arcp.ids

import dev.fizzpop.arcp.json.arcpJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class IdsTest :
    StringSpec({
        "value classes round-trip as bare strings" {
            val id = MessageId("msg_xyz")
            val json = arcpJson.encodeToString(MessageId.serializer(), id)
            json shouldBe "\"msg_xyz\""
            arcpJson.decodeFromString(MessageId.serializer(), json) shouldBe id
        }

        "blank ids are rejected" {
            shouldThrow<IllegalArgumentException> { MessageId("") }
            shouldThrow<IllegalArgumentException> { SessionId("   ") }
            shouldThrow<IllegalArgumentException> { JobId("\t") }
        }

        "ULIDs are unique across many calls" {
            val ids = List(2_000) { Ulid.next("test") }.toSet()
            ids shouldHaveSize 2_000
        }

        "ULID prefix is preserved" {
            Ulid.next("session").shouldStartWith("session_")
        }

        "MessageId.random produces a msg_-prefixed id" {
            MessageId.random().value.shouldStartWith("msg_")
        }

        "any non-blank string is a valid id (prop)" {
            checkAll(Arb.string(minSize = 1, maxSize = 32)) { s ->
                if (s.isNotBlank()) {
                    MessageId(s).value shouldBe s
                }
            }
        }

        "two distinct id types with the same string are not equal at the type level" {
            // This is enforced at compile time but we can show the runtime values still differ:
            val msg = MessageId("foo")
            val sess = SessionId("foo")
            msg.value shouldBe sess.value
            @Suppress("EqualsBetweenInconvertibleTypes")
            (msg as Any) shouldNotBe (sess as Any)
        }
    })
