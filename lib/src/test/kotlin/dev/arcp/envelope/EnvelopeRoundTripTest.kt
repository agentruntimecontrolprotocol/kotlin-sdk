package dev.arcp.envelope

import dev.arcp.ids.JobId
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.ids.TraceId
import dev.arcp.json.arcpJson
import dev.arcp.messages.Cancel
import dev.arcp.messages.CancelTarget
import dev.arcp.messages.Ping
import dev.arcp.messages.Pong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

class EnvelopeRoundTripTest :
    StringSpec({
        "ping envelope round-trips" {
            val original =
                Envelope(
                    id = MessageId("msg_001"),
                    timestamp = Instant.parse("2026-05-09T13:00:00Z"),
                    payload = Ping(nonce = "abc"),
                )
            val json = arcpJson.encodeToString(Envelope.serializer(), original)
            val decoded = arcpJson.decodeFromString(Envelope.serializer(), json)
            decoded shouldBe original
        }

        "envelope hoists discriminator to top-level type" {
            val env =
                Envelope(
                    id = MessageId("msg_002"),
                    timestamp = Instant.parse("2026-05-09T13:00:00Z"),
                    payload = Pong(nonce = "xyz"),
                )
            val json = arcpJson.encodeToString(Envelope.serializer(), env)
            json shouldContain "\"type\":\"pong\""
            json shouldContain "\"payload\":{\"nonce\":\"xyz\"}"
        }

        "envelope preserves all optional fields" {
            val env =
                Envelope(
                    id = MessageId("msg_003"),
                    timestamp = Instant.parse("2026-05-09T13:00:00Z"),
                    source = "client",
                    target = "runtime",
                    sessionId = SessionId("sess_aaa"),
                    jobId = JobId("job_bbb"),
                    traceId = TraceId("trace_ccc"),
                    correlationId = MessageId("msg_010"),
                    causationId = MessageId("msg_009"),
                    idempotencyKey = "refund-001",
                    priority = Priority.HIGH,
                    payload =
                        Cancel(
                            target = CancelTarget.JOB,
                            targetId = "job_bbb",
                            reason = "user_aborted",
                            deadlineMs = 5000,
                        ),
                )
            val json = arcpJson.encodeToString(Envelope.serializer(), env)
            val decoded = arcpJson.decodeFromString(Envelope.serializer(), json)
            decoded shouldBe env
        }

        "envelope omits default priority on the wire" {
            val env =
                Envelope(
                    id = MessageId("msg_004"),
                    timestamp = Instant.parse("2026-05-09T13:00:00Z"),
                    payload = Ping(),
                )
            val json = arcpJson.encodeToString(Envelope.serializer(), env)
            val parsed = arcpJson.parseToJsonElement(json).jsonObject
            (parsed["priority"]) shouldBe null
        }

        "decoding tolerates unknown payload fields (forward-compat with extensions)" {
            val rawJson =
                """
                {
                  "arcp": "1.1",
                  "id": "msg_005",
                  "type": "ping",
                  "timestamp": "2026-05-09T13:00:00Z",
                  "payload": { "nonce": "n", "future_field": 42 }
                }
                """.trimIndent()
            val decoded = arcpJson.decodeFromString(Envelope.serializer(), rawJson)
            decoded.payload shouldBe Ping(nonce = "n")
        }

        "decoding tolerates unknown extension envelope fields" {
            val rawJson =
                """
                {
                  "arcp": "1.1",
                  "id": "msg_006",
                  "type": "ping",
                  "timestamp": "2026-05-09T13:00:00Z",
                  "extensions": { "arcpx.acme.thing": "v" },
                  "payload": {}
                }
                """.trimIndent()
            val decoded = arcpJson.decodeFromString(Envelope.serializer(), rawJson)
            decoded.extensions["arcpx.acme.thing"] shouldBe JsonPrimitive("v")
        }

        "missing required envelope field is rejected" {
            val raw =
                """
                { "id": "x", "type": "ping", "timestamp": "2026-05-09T13:00:00Z", "payload": {} }
                """.trimIndent()
            val ex =
                runCatching {
                    arcpJson.decodeFromString(Envelope.serializer(), raw)
                }.exceptionOrNull()
            (ex != null) shouldBe true
            ex!!.message!!.shouldContain("arcp")
        }

        "wire type matches the @SerialName" {
            val env =
                Envelope(
                    id = MessageId("msg_007"),
                    payload = Ping(),
                )
            val json = arcpJson.encodeToString(Envelope.serializer(), env)
            val parsed = arcpJson.parseToJsonElement(json).jsonObject
            parsed["type"]!!.jsonPrimitive.content shouldBe "ping"
        }
    })
