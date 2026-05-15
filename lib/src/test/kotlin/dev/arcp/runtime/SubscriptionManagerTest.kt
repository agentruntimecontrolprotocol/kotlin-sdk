package dev.arcp.runtime

import dev.arcp.envelope.Envelope
import dev.arcp.envelope.Priority
import dev.arcp.ids.MessageId
import dev.arcp.ids.SessionId
import dev.arcp.ids.SubscriptionId
import dev.arcp.ids.TraceId
import dev.arcp.messages.EventEmit
import dev.arcp.messages.JobProgress
import dev.arcp.messages.Log
import dev.arcp.messages.LogLevel
import dev.arcp.messages.Ping
import dev.arcp.messages.SubscriptionFilter
import dev.arcp.store.EventLog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionManagerTest :
    StringSpec({
        val ts = Instant.parse("2026-05-09T13:00:00Z")
        val sessA = SessionId("sess_a")
        val traceA = TraceId("trace_a")

        fun env(
            id: String,
            sessionId: SessionId? = sessA,
            payload: dev.arcp.messages.MessageType = JobProgress(percent = 1),
            priority: Priority = Priority.NORMAL,
        ) = Envelope(
            id = MessageId(id),
            timestamp = ts,
            sessionId = sessionId,
            traceId = traceA,
            priority = priority,
            payload = payload,
        )

        "filter compiles to type-based predicate" {
            val mgr = SubscriptionManager()
            val pred = mgr.compile(SubscriptionFilter(types = listOf("job.progress", "log")))
            pred(env("a", payload = JobProgress(percent = 1))) shouldBe true
            pred(env("b", payload = Log(level = LogLevel.INFO, message = "x"))) shouldBe true
            pred(env("c", payload = Ping())) shouldBe false
        }

        "filter combines AND across fields and OR within arrays" {
            val mgr = SubscriptionManager()
            val pred =
                mgr.compile(
                    SubscriptionFilter(
                        sessionId = listOf(SessionId("sess_a"), SessionId("sess_b")),
                        types = listOf("job.progress"),
                    ),
                )
            pred(env("a", sessionId = SessionId("sess_a"))) shouldBe true
            pred(env("b", sessionId = SessionId("sess_b"))) shouldBe true
            pred(env("c", sessionId = SessionId("sess_other"))) shouldBe false
            pred(env("d", payload = Ping())) shouldBe false
        }

        "min_priority filters low-priority events" {
            val mgr = SubscriptionManager()
            val pred = mgr.compile(SubscriptionFilter(minPriority = Priority.HIGH))
            pred(env("a", priority = Priority.LOW)) shouldBe false
            pred(env("b", priority = Priority.NORMAL)) shouldBe false
            pred(env("c", priority = Priority.HIGH)) shouldBe true
            pred(env("d", priority = Priority.CRITICAL)) shouldBe true
        }

        "live publish reaches matching subscribers" {
            runTest(UnconfinedTestDispatcher()) {
                val mgr = SubscriptionManager()
                val received = mutableListOf<Envelope>()
                val collector =
                    launch {
                        mgr
                            .open(
                                SubscriptionId("sub_a"),
                                SubscriptionFilter(types = listOf("job.progress")),
                            ).take(2)
                            .toList(received)
                    }
                yield()
                mgr.publish(env("p1", payload = JobProgress(percent = 10)))
                mgr.publish(env("ignored", payload = Ping()))
                mgr.publish(env("p2", payload = JobProgress(percent = 20)))
                collector.join()
                received.map { it.id.value } shouldBe listOf("p1", "p2")
            }
        }

        "backfill emits historical events then a backfill_complete marker before live" {
            runTest(UnconfinedTestDispatcher()) {
                EventLog.openInMemory().use { logStore ->
                    val mgr = SubscriptionManager(eventLog = logStore)
                    val cursor = MessageId("msg_cursor")
                    logStore.append(env("msg_cursor"))
                    logStore.append(env("hist_1"))
                    logStore.append(env("hist_2"))

                    val received = mutableListOf<Envelope>()
                    val collector =
                        launch {
                            mgr
                                .open(
                                    SubscriptionId("sub_b"),
                                    SubscriptionFilter(sessionId = listOf(sessA)),
                                    afterMessageId = cursor,
                                ).take(4)
                                .toList(received)
                        }
                    yield()
                    yield()
                    mgr.publish(env("live_1"))
                    collector.join()

                    received.map { it.id.value } shouldBe
                        listOf(
                            "hist_1",
                            "hist_2",
                            received[2].id.value, // synthetic backfill_complete (random id)
                            "live_1",
                        )
                    received[2].payload.shouldBeInstanceOf<EventEmit>()
                    (received[2].payload as EventEmit).eventType shouldBe
                        "subscription.backfill_complete"
                }
            }
        }

        "toSubscribeEvent wraps an envelope in a subscribe.event envelope" {
            runTest {
                val mgr = SubscriptionManager()
                val original = env("orig")
                val wrapped = mgr.toSubscribeEvent(original, SubscriptionId("sub_x"))
                wrapped.subscriptionId?.value shouldBe "sub_x"
                wrapped.type shouldBe "subscribe.event"
            }
        }

        "subscription with no backfill goes straight to live tail" {
            runTest(UnconfinedTestDispatcher()) {
                val mgr = SubscriptionManager()
                val collector =
                    launch {
                        val first = mgr.open(SubscriptionId("sub_c"), SubscriptionFilter()).first()
                        first.id.value shouldBe "live_only"
                    }
                yield()
                mgr.publish(env("live_only"))
                collector.join()
            }
        }
    })
