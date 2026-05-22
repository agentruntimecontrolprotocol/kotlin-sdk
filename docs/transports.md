# Transports

A `Transport` is the bidirectional channel over which `Envelope` frames flow
between client and runtime. The SDK ships one production-ready transport and
one for testing.

## Interface

```kotlin
package dev.arcp.transport

interface Transport {
    suspend fun send(envelope: Envelope)
    fun receive(): Flow<Envelope>
    fun close()
}
```

Implementors agree to:

- **Ordering** — frames are delivered in send order (per direction).
- **Backpressure** — `send` suspends when the receiver is slow; it does not drop.
- **Cancellation** — `close()` terminates both the outbound and inbound flows.

---

## MemoryTransport

`MemoryTransport` pairs two in-process channels. It is the transport used by
all integration tests and the `samples/` programs.

### Construction

```kotlin
val (clientTransport, serverTransport) = MemoryTransport.pair()
// or with a custom channel capacity:
val (c, s) = MemoryTransport.pair(capacity = 128)
```

`pair()` returns `Pair<MemoryTransport, MemoryTransport>`. The first element
is the client side, the second is the server (runtime) side. Each side's
`send` writes to the other's `receive` flow.

**Default capacity** is `64` envelopes per direction
(`MemoryTransport.DEFAULT_CAPACITY`). When the channel is full the sender
suspends, so real backpressure propagates even in tests.

### Use case

```kotlin
val (ct, rt) = MemoryTransport.pair()
val runtime = ARCPRuntime(supportedCapabilities = Capabilities(), agentRegistry = registry)
runtime.accept(rt)

val client = ARCPClient(
    transport = ct,
    auth = ARCPClient.bearer("my-token"),
    client = ARCPClient.defaultClientInfo(),
    capabilities = Capabilities(),
)
val session = client.open()
```

---

## WebSocketTransport (v0.2)

WebSocket support ships in SDK v0.2. The transport class will live in
`dev.arcp.transport.WebSocketTransport` and wrap a Ktor `DefaultClientWebSocketSession`.

Expected API (subject to change before release):

```kotlin
// Client side
val client = ARCPClient(
    transport = WebSocketTransport.connect("wss://runtime.example.com/arcp"),
    auth = ARCPClient.bearer(token),
    client = ARCPClient.defaultClientInfo(),
    capabilities = Capabilities(streaming = true),
)
```

---

## StdioTransport (v0.2)

Standard-input/output transport for subprocess-based runtimes ships in
SDK v0.2. It will live in `dev.arcp.transport.StdioTransport`.

---

## Writing a custom transport

Implement the `Transport` interface and inject it at construction time:

```kotlin
class MyCustomTransport : Transport {
    override suspend fun send(envelope: Envelope) { /* ... */ }
    override fun receive(): Flow<Envelope> = /* cold Flow<Envelope> */ TODO()
    override fun close() { /* ... */ }
}

val client = ARCPClient(
    transport = MyCustomTransport(),
    auth = ARCPClient.bearer("token"),
    client = ARCPClient.defaultClientInfo(),
    capabilities = Capabilities(),
)
```

The `receive()` flow should be cold (one consumer activates it). The flow
completes normally when the connection closes and throws on transport errors;
`ARCPClient`/`ARCPRuntime` will propagate those errors as `ARCPException`
subclasses.
