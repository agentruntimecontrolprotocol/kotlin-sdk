# Sessions

A *session* is the top-level authenticated context between a client and a
runtime. All jobs, leases, and subscriptions are scoped to a session.

## Session lifecycle

```
client ──────────────────────────────────────── runtime
  │                                               │
  │── SessionOpen ────────────────────────────>  │  (1) open
  │<─ SessionChallenge ─────────────────────────  │  (2) auth challenge (optional)
  │── SessionAuthenticate ───────────────────>   │  (3) bearer / JWT
  │<─ SessionAccepted ──────────────────────────  │  (4) negotiated capabilities
  │                  ... session active ...           │
  │── SessionClose ──────────────────────────>   │  (5) graceful close
```

If the runtime is configured with `StaticBearerAuth` or `JwtAuth`, the
challenge/authenticate round-trip occurs; otherwise the runtime may skip
directly to `SessionAccepted`.

## Opening a session

```kotlin
val (clientTransport, serverTransport) = MemoryTransport.pair()

val runtime = ARCPRuntime(
    supportedCapabilities = Capabilities(streaming = true, durableJobs = true),
    bearerAuth = StaticBearerAuth(mapOf("my-token" to "alice")),
    agentRegistry = AgentRegistry().also { it.register("summarise", "1.0.0", default = true) },
)
runtime.accept(serverTransport)    // launches coroutine; returns a Job

val client = ARCPClient(
    transport = clientTransport,
    auth = ARCPClient.bearer("my-token"),
    client = ARCPClient.defaultClientInfo(),
    capabilities = Capabilities(streaming = true),
)

val session: SessionAccepted = client.open()
println("Session ${session.sessionId} negotiated")
```

`client.open()` returns only after `SessionAccepted` is received; it throws
`ARCPException.Unauthenticated` if the token is rejected or
`ARCPException.FailedPrecondition` if the runtime is not ready.

## Capability negotiation

`SessionAccepted.capabilities` contains the *intersection* of what the
client advertised and what the runtime supports. Inspect it before using
optional features:

```kotlin
val caps = session.capabilities
if (caps.streaming) {
    // safe to submit jobs that use result_chunk streaming
}
if (caps.durableJobs) {
    // safe to use EventLog-backed resume
}
```

### Advertised capabilities

The full set of `Capabilities` fields:

| Field | Default | Purpose |
|-------|---------|---------|
| `streaming` | `false` | `result_chunk` streaming (RFC §8.4) |
| `durableJobs` | `false` | durable event log / resume |
| `checkpoints` | `false` | mid-job checkpoint / restore |
| `binaryStreams` | `false` | binary stream frames |
| `agentHandoff` | `false` | agent delegation |
| `artifacts` | `false` | file artifact transfer |
| `subscriptions` | `false` | push subscriptions |
| `scheduledJobs` | `false` | future-scheduled job dispatch |
| `provisionedCredentials` | `false` | per-job credential issue |
| `modelUse` | `false` | `model.use` lease enforcement |
| `anonymous` | `false` | unauthenticated clients allowed |
| `interrupt` | `true` | cooperative interrupt signal |
| `heartbeatIntervalSeconds` | `30` | expected heartbeat cadence |
| `heartbeatRecovery` | `FAIL` | `FAIL` or `BLOCK` on missed beats |
| `binaryEncoding` | `["base64"]` | supported envelope-binary encodings (`List<String>`) |
| `extensions` | `[]` | vendor extension names (`arcpx.*`) |
| `agents` | `[]` | available agent descriptors |

`interrupt` is the only boolean that defaults to `true`; all others default
to `false` per RFC §7.

## Listing jobs

Use `session.list_jobs` to enumerate the principal's jobs. The
`JobListFilter` accepts wire-form status strings (`"accepted"`, `"running"`,
...) and an optional agent name plus created-at bounds:

```kotlin
client.send(session.sessionId, SessionListJobs(
    filter = JobListFilter(status = listOf("running")),
    limit  = 20,
    cursor = null,
))
// The runtime replies with a `session.jobs` envelope.
```

`ARCPClient.listJobs(sessionId, filter, limit, cursor)` is a convenience that
sends the request and awaits the correlated reply.

## Closing a session

Send `SessionClose` to perform a graceful shutdown; the runtime drains
in-flight jobs before tearing down:

```kotlin
client.send(session.sessionId, SessionClose(reason = "done"))
```

The transport's `close()` is called automatically by `ARCPClient` after the
close handshake completes.
