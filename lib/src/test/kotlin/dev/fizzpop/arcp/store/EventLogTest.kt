package dev.fizzpop.arcp.store

import dev.fizzpop.arcp.envelope.Envelope
import dev.fizzpop.arcp.error.ARCPException
import dev.fizzpop.arcp.ids.MessageId
import dev.fizzpop.arcp.ids.SessionId
import dev.fizzpop.arcp.messages.Ping
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.hours

class EventLogTest :
    StringSpec({
        "append assigns monotonically increasing seq" {
            runTest {
                EventLog.openInMemory().use { log ->
                    val sess = SessionId("sess_a")
                    val a = log.append(envelope("msg_a", sess))
                    val b = log.append(envelope("msg_b", sess))
                    val c = log.append(envelope("msg_c", sess))
                    (a < b) shouldBe true
                    (b < c) shouldBe true
                    log.lastSeq() shouldBe c
                }
            }
        }

        "duplicate (session, id) pair is rejected with AlreadyExists" {
            runTest {
                EventLog.openInMemory().use { log ->
                    val sess = SessionId("sess_b")
                    log.append(envelope("msg_dup", sess))
                    shouldThrow<ARCPException.AlreadyExists> {
                        log.append(envelope("msg_dup", sess))
                    }
                }
            }
        }

        "replay returns envelopes in append order" {
            runTest {
                EventLog.openInMemory().use { log ->
                    val sess = SessionId("sess_c")
                    val one = envelope("msg_1", sess)
                    val two = envelope("msg_2", sess)
                    val three = envelope("msg_3", sess)
                    log.append(one)
                    log.append(two)
                    log.append(three)

                    val replayed = log.replay(sess).toList()
                    replayed.map { it.id } shouldBe listOf(one.id, two.id, three.id)
                }
            }
        }

        "replay after_message_id skips through-and-including the cursor" {
            runTest {
                EventLog.openInMemory().use { log ->
                    val sess = SessionId("sess_d")
                    val a = envelope("msg_a", sess)
                    val b = envelope("msg_b", sess)
                    val c = envelope("msg_c", sess)
                    log.append(a)
                    log.append(b)
                    log.append(c)
                    log.replay(sess, afterMessageId = a.id).toList().map { it.id } shouldBe
                        listOf(b.id, c.id)
                }
            }
        }

        "replay with non-existent cursor raises DataLoss" {
            runTest {
                EventLog.openInMemory().use { log ->
                    val sess = SessionId("sess_e")
                    shouldThrow<ARCPException.DataLoss> {
                        log.replay(sess, afterMessageId = MessageId("msg_unknown")).toList()
                    }
                }
            }
        }

        "idempotent recall returns prior outcome" {
            runTest {
                EventLog.openInMemory().use { log ->
                    val outcome = JsonPrimitive("done")
                    log.recordIdempotent(
                        principal = "user@example",
                        idempotencyKey = "refund-1",
                        outcome = outcome,
                        expiresAt = Clock.System.now().plus(1.hours),
                    )
                    log.lookupIdempotent("user@example", "refund-1") shouldBe outcome
                }
            }
        }

        "idempotent recall returns null after expiry" {
            runTest {
                EventLog.openInMemory().use { log ->
                    log.recordIdempotent(
                        principal = "p",
                        idempotencyKey = "k",
                        outcome = JsonPrimitive(1),
                        expiresAt = Instant.parse("2000-01-01T00:00:00Z"),
                    )
                    log.lookupIdempotent("p", "k") shouldBe null
                }
            }
        }

        "isolated databases stay isolated" {
            runTest {
                EventLog.openInMemory().use { a ->
                    EventLog.openInMemory().use { b ->
                        a.append(envelope("msg_x", SessionId("sess_z")))
                        b.replay(SessionId("sess_z")).toList() shouldHaveSize 0
                    }
                }
            }
        }
    })

private fun envelope(
    id: String,
    sessionId: SessionId,
): Envelope =
    Envelope(
        id = MessageId(id),
        sessionId = sessionId,
        timestamp = Instant.parse("2026-05-09T13:00:00Z"),
        payload = Ping(nonce = id),
    )
