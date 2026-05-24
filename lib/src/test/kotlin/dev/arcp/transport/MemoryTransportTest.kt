package dev.arcp.transport

import dev.arcp.envelope.Envelope
import dev.arcp.ids.MessageId
import dev.arcp.messages.Ping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Round-trip and backpressure coverage for [MemoryTransport]. The runtime,
 * client, and every sample depends on this transport, so its FIFO and
 * close-time semantics need direct tests (#70).
 */
class MemoryTransportTest :
    StringSpec({
        "client.send delivers to server.receive in FIFO order" {
            runTest {
                val (client, server) = MemoryTransport.pair()
                coroutineScope {
                    val received = async {
                        server.receive().take(3).toList()
                    }
                    client.send(env("a"))
                    client.send(env("b"))
                    client.send(env("c"))
                    val list = received.await()
                    list.map { it.id } shouldBe
                        listOf(MessageId("a"), MessageId("b"), MessageId("c"))
                }
            }
        }

        "send/receive is bidirectional" {
            runTest {
                val (client, server) = MemoryTransport.pair()
                coroutineScope {
                    launch { server.send(env("from-server")) }
                    val first = client.receive().first()
                    first.id shouldBe MessageId("from-server")
                }
            }
        }

        "close terminates the receive flow" {
            runTest {
                val (client, server) = MemoryTransport.pair()
                val collected = mutableListOf<Envelope>()
                coroutineScope {
                    val collector = launch {
                        server.receive().toList(collected)
                    }
                    client.send(env("only"))
                    client.close()
                    collector.join()
                }
                collected shouldHaveSize 1
            }
        }

        "backpressure suspends the sender once the channel is full" {
            runTest {
                // Capacity 1 channel — second send must suspend until the receiver drains.
                val (client, server) = MemoryTransport.pair(capacity = 1)
                client.send(env("first"))
                // No collector yet, so a second concurrent send would suspend; verify by
                // launching it and draining one frame.
                coroutineScope {
                    val sender = launch { client.send(env("second")) }
                    val drained = server.receive().take(2).toList()
                    drained.map { it.id } shouldBe listOf(MessageId("first"), MessageId("second"))
                    sender.join()
                }
            }
        }
    })

private fun env(id: String): Envelope = Envelope(
    id = MessageId(id),
    payload = Ping(nonce = id),
)
