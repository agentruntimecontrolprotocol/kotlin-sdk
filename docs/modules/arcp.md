# Module: arcp (`dev.arcp:arcp`)

The `:lib` Gradle module is the publishable ARCP protocol library. All
public API lives here.

**Maven coordinates**: `dev.arcp:arcp:1.1.0`

---

## dev.arcp.envelope

### `Envelope`

The canonical wire container for every ARCP message (RFC §6.1).

| Field | Type | Description |
|-------|------|-------------|
| `id` | `MessageId` | Unique per-message identifier |
| `type` | `String` | Wire discriminator (e.g. `"session.open"`) |
| `timestamp` | `Instant` | ISO 8601 send time |
| `sessionId` | `SessionId?` | Owning session |
| `jobId` | `JobId?` | Owning job (optional) |
| `correlationId` | `MessageId?` | Request/response correlation |
| `causationId` | `MessageId?` | Causal predecessor |
| `traceId` | `String?` | W3C trace ID |
| `priority` | `String` | `"normal"` or `"high"` |
| `payload` | `MessageType` | Polymorphic message body |

The custom serializer hoists the `type` discriminator from `payload` to the
envelope root, matching the RFC §6.1 wire layout.

---

## dev.arcp.messages

All RFC §6.2 message types as `@Serializable @SerialName` data classes
implementing `MessageType`.

### Session messages

| Class | Wire type | Direction |
|-------|-----------|-----------|
| `SessionOpen` | `session.open` | C → R |
| `SessionChallenge` | `session.challenge` | R → C |
| `SessionAuthenticate` | `session.authenticate` | C → R |
| `SessionAccepted` | `session.accepted` | R → C |
| `SessionUnauthenticated` | `session.unauthenticated` | R → C |
| `SessionRejected` | `session.rejected` | R → C |
| `SessionRefresh` | `session.refresh` | either |
| `SessionEvicted` | `session.evicted` | R → C |
| `SessionClose` | `session.close` | either |
| `SessionListJobs` | `session.list_jobs` | C → R |
| `SessionJobs` | `session.jobs` | R → C |

Key types on `SessionAccepted`: `sessionId: SessionId`, `capabilities:
Capabilities`, `runtime: RuntimeIdentity`, `trustLevel: TrustLevel`.

`TrustLevel`: `UNTRUSTED`, `CONSTRAINED`, `TRUSTED`, `PRIVILEGED`.

### Execution messages

| Class | Wire type |
|-------|-----------|
| `JobSubmit` | `job.submit` |
| `JobAccepted` | `job.accepted` |
| `JobStarted` | `job.started` |
| `JobProgress` | `job.progress` |
| `JobHeartbeat` | `job.heartbeat` |
| `JobStatusEvent` | `job.status` |
| `JobResultChunk` | `job.result_chunk` |
| `JobResult` | `job.result` |
| `JobCompleted` | `job.completed` |
| `JobFailed` | `job.failed` |
| `JobCancelled` | `job.cancelled` |
| `JobCheckpoint` | `job.checkpoint` |
| `ToolInvoke` | `tool.invoke` |
| `ToolResult` | `tool.result` |
| `ToolError` | `tool.error` |

`ResultChunkEncoding`: `UTF8`, `BASE64`.  
`JobLifecycleState`: `ACCEPTED`, `QUEUED`, `RUNNING`, `BLOCKED`, `PAUSED`,
`COMPLETED`, `FAILED`, `CANCELLED`.

### Control messages

| Class | Wire type |
|-------|-----------|
| `Ping` | `ping` |
| `Pong` | `pong` |
| `Ack` | `ack` |
| `Nack` | `nack` |
| `Cancel` | `cancel` |
| `CancelAccepted` | `cancel.accepted` |
| `CancelRefused` | `cancel.refused` |
| `Interrupt` | `interrupt` |
| `Resume` | `resume` |
| `Backpressure` | `backpressure` |
| `CheckpointCreate` | `checkpoint.create` |
| `CheckpointRestore` | `checkpoint.restore` |

`CancelTarget`: `JOB`, `STREAM`, `SESSION`.

### Permission / lease messages

| Class | Wire type |
|-------|-----------|
| `PermissionRequest` | `permission.request` |
| `PermissionGrant` | `permission.grant` |
| `PermissionDeny` | `permission.deny` |
| `LeaseGranted` | `lease.granted` |
| `LeaseRefresh` | `lease.refresh` |
| `LeaseExtended` | `lease.extended` |
| `LeaseRevoked` | `lease.revoked` |

### Streaming messages

| Class | Wire type |
|-------|-----------|
| `StreamOpen` | `stream.open` |
| `StreamChunk` | `stream.chunk` |
| `StreamClose` | `stream.close` |
| `StreamError` | `stream.error` |

`StreamKind`: `TEXT`, `BINARY`, `EVENT`, `LOG`, `METRIC`, `THOUGHT`.

### Telemetry messages

| Class | Wire type |
|-------|-----------|
| `EventEmit` | `event.emit` |
| `Log` | `log` |
| `Metric` | `metric` |
| `TraceSpan` | `trace.span` |

`LogLevel`: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `CRITICAL`.

Standard metric name constants are in `StandardMetrics`.

### Agent messages

| Class | Description |
|-------|-------------|
| `AgentRef` | `name` or `name@version` reference; `AgentRef.parse(wire)` |
| `AgentDescriptor` | Versions advertised by a runtime |

---

## dev.arcp.client

### `ARCPClient`

```kotlin
ARCPClient(
    transport    : Transport,
    auth         : BearerAuth,
    client       : ClientInfo,
    capabilities : Capabilities,
)
```

| Method | Returns | Description |
|--------|---------|-------------|
| `open()` | `SessionAccepted` | Authenticate and negotiate session |
| `send(sessionId, payload)` | `MessageId` | Send a message |

Companion factories:

```kotlin
ARCPClient.bearer(token: String): BearerAuth
ARCPClient.defaultClientInfo(): ClientInfo
```

---

## dev.arcp.runtime

### `ARCPRuntime`

```kotlin
ARCPRuntime(
    supportedCapabilities : Capabilities,
    bearerAuth            : BearerAuth? = null,
    jwtAuth               : JwtAuth? = null,
    agentRegistry         : AgentRegistry = AgentRegistry(),
    budgetRegistry        : BudgetRegistry = BudgetRegistry(),
    eventLog              : EventLog? = null,
    credentialProvisioner : CredentialProvisioner? = null,
    extensionRegistry     : ExtensionRegistry = ExtensionRegistry(),
)
```

| Method | Description |
|--------|-------------|
| `accept(transport)` | Start the server coroutine (non-blocking) |
| `rotateCredential(jobId)` | Issue replacement credential mid-job |

### `AgentRegistry`

```kotlin
val registry = AgentRegistry()
registry.register("summarise", listOf("1.0.0", "2.0.0"))
```

---

## dev.arcp.transport

### `Transport` interface

```kotlin
interface Transport {
    suspend fun send(envelope: Envelope)
    fun receive(): Flow<Envelope>
    fun close()
}
```

### `MemoryTransport`

```kotlin
val (clientTransport, serverTransport) = MemoryTransport.pair()
// or:
val (c, s) = MemoryTransport.pair(capacity = 128)
```

`DEFAULT_CAPACITY = 64`. The channel uses `BufferOverflow.SUSPEND` so
real backpressure propagates in tests.

---

## dev.arcp.auth

### `BearerAuth` (fun interface)

```kotlin
fun interface BearerAuth {
    fun verify(token: String): String  // returns principal name
}
```

### `StaticBearerAuth`

```kotlin
StaticBearerAuth(tokens: Map<String, String>)
// key=token, value=principal
```

### `JwtAuth`

```kotlin
JwtAuth(verifier: JWSVerifier, expectedAudience: String)
// Companion:
JwtAuth.hmac(secret: ByteArray, audience: String): JwtAuth
```

---

## dev.arcp.credentials

| Class | Description |
|-------|-------------|
| `Credential` | Wire credential (id, scheme, value, endpoint, constraints); `toString()` redacts `value` |
| `CredentialStore` | In-memory store; `issue(jobId, cred)`, `revoke(credId)`, `pendingRevocations()` |
| `CredentialProvisioner` | Interface: `provision(jobId, lease): Credential` |

---

## dev.arcp.lease

| Class | Description |
|-------|-------------|
| `Currency` | Value class wrapping a currency string |
| `BudgetAmount` | `(currency, value: BigDecimal)`; `BudgetAmount.parse("USD:5.00")` |
| `CostBudget` | `(budgets: List<BudgetAmount>)` — lease constraint container |
| `BudgetRegistry` | Per-job counters; `register`, `consume`, `terminate`, `remaining` |
| `BudgetCounter` | Single-job counter; `consume(amount): Outcome` (`Ok` or `Exhausted`) |
| `ModelUseLease` | `(patterns: List<String>)`; `allows(modelId)`, `subset(parent, child)` |
| `LeaseSubset` | Static helpers for subset validation |

---

## dev.arcp.store

### `EventLog`

```kotlin
EventLog.openInMemory(): EventLog
EventLog.openFile(path: Path): EventLog
```

| Method | Description |
|--------|-------------|
| `append(envelope): Long` | Append; throws `AlreadyExists` on dup `message_id` |
| `replay(sessionId, afterMessageId?): Flow<Envelope>` | Replay envelopes |
| `lookupIdempotent(key): String?` | Check idempotency key |
| `recordIdempotent(key, value)` | Record idempotency result |

---

## dev.arcp.trace

### `TraceContext`

```kotlin
data class TraceContext(
    val traceId:      TraceId,
    val spanId:       SpanId,
    val parentSpanId: SpanId? = null,
) : AbstractCoroutineContextElement(Key)
```

| Function | Description |
|----------|-------------|
| `TraceContext.newRoot()` | Create root span (random traceId + spanId) |
| `currentTrace()` | Get ambient `TraceContext` from coroutine context |
| `withSpan(name, block)` | Run block in child span; returns block result |

---

## dev.arcp.extensions

### `ExtensionRegistry`

```kotlin
val ext = ExtensionRegistry()
ext.advertise("arcpx.acme.email.v1")
ext.acceptsType(wireType): Boolean
ext.classifyUnknown(wireType, optional, advertised): UnknownAction
```

`UnknownAction`: `Drop`, `Nack`.

---

## dev.arcp.ids

Typed ID wrappers (all are `@JvmInline value class` wrapping `String`):

`SessionId`, `JobId`, `MessageId`, `LeaseId`, `StreamId`,
`PermissionName`, `TraceId`, `SpanId`.

---

## dev.arcp.error

### `ErrorCode` enum

24 codes; `wire: String`, `retryableByDefault: Boolean`.

```kotlin
ErrorCode.fromWire("RATE_LIMITED")   // → RESOURCE_EXHAUSTED
```

### `ARCPException` sealed class

24 subclasses. Common pattern:

```kotlin
try {
    client.open()
} catch (e: ARCPException) {
    if (e.retryable) retry()
}
```

---

## dev.arcp.json

### `arcpJson`

Pre-configured `kotlinx.serialization` `Json` instance:

```kotlin
val json = arcpJson   // lenient, ignores unknown keys, custom serializers registered
```

Use `arcpJson` to parse raw ARCP JSON strings:

```kotlin
val envelope = arcpJson.decodeFromString<Envelope>(rawJson)
```
