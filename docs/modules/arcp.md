# Module: arcp

The `:lib` Gradle module is the publishable ARCP protocol library. All
public API lives here.

**Maven coordinates**: `dev.arcp:arcp:1.1.0` (published to Maven Central
via the root project's `nmcpAggregation` configuration).

---

## dev.arcp.envelope

### `Envelope`

The canonical wire container for every ARCP message (RFC §6.1).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `arcp` | `String` | `Version.PROTOCOL_VERSION` | Wire protocol version |
| `id` | `MessageId` | required | Unique per-message identifier |
| `timestamp` | `Instant` | `Clock.System.now()` | ISO 8601 send time |
| `source` | `String?` | `null` | Logical sender id |
| `target` | `String?` | `null` | Logical recipient id |
| `sessionId` | `SessionId?` | `null` | Owning session |
| `jobId` | `JobId?` | `null` | Owning job |
| `streamId` | `StreamId?` | `null` | Owning stream |
| `subscriptionId` | `SubscriptionId?` | `null` | Owning subscription |
| `traceId` | `TraceId?` | `null` | W3C trace ID |
| `spanId` | `SpanId?` | `null` | Current span id |
| `parentSpanId` | `SpanId?` | `null` | Parent span id |
| `correlationId` | `MessageId?` | `null` | Request/response correlation |
| `causationId` | `MessageId?` | `null` | Causal predecessor |
| `idempotencyKey` | `String?` | `null` | Logical command intent (RFC §6.4) |
| `priority` | `Priority` | `NORMAL` | `LOW`/`NORMAL`/`HIGH`/`CRITICAL` |
| `extensions` | `Map<String, JsonElement>` | `{}` | Namespaced extension fields (RFC §21) |
| `payload` | `MessageType` | required | Polymorphic message body |

`type: String` is exposed as a computed property that returns the wire
discriminator of `payload`. The custom `EnvelopeSerializer` hoists this
discriminator to the envelope root on encode, matching the RFC §6.1 wire
layout, and re-attaches it on decode.

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

Fields on `SessionAccepted`: `sessionId: SessionId`, `runtime:
RuntimeIdentity`, `capabilities: Capabilities`, `lease: SessionLease?`.
Trust level is on the embedded `runtime.trustLevel`.

`TrustLevel`: `UNTRUSTED`, `CONSTRAINED`, `TRUSTED`, `PRIVILEGED`.

### Execution messages

| Class | Wire type |
|-------|-----------|
| `JobSubmit` | `job.submit` |
| `JobAccepted` | `job.accepted` |
| `JobStarted` | `job.started` |
| `JobProgress` | `job.progress` |
| `JobHeartbeat` | `job.heartbeat` |
| `JobStatusEvent` | `status` |
| `JobResultChunk` | `result_chunk` |
| `JobResult` | `job.result` |
| `JobCompleted` | `job.completed` |
| `JobFailed` | `job.failed` |
| `JobCancelled` | `job.cancelled` |
| `JobCheckpoint` | `job.checkpoint` |
| `JobSchedule` | `job.schedule` *(v0.1 NACKs with `UNIMPLEMENTED`)* |
| `WorkflowStart` | `workflow.start` *(v0.1 deferred)* |
| `WorkflowComplete` | `workflow.complete` *(v0.1 deferred)* |
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
| `AgentRef` | `name` or `name@version` reference; `AgentRef.parse(wire)` / `.render()` |
| `AgentDescriptor` | Versions advertised by a runtime (`name`, `versions`, `default`) |
| `AgentDelegate` | `target: String`, `task: String`, `context: JsonObject` |
| `AgentHandoff` | `target: String`, `sessionId/jobId: String?`, `receivingRuntime: RuntimeIdentity`, `handoffFor: MessageId?` |

> `AgentDelegate` / `AgentHandoff` envelopes round-trip through the wire
> layer but lease subset enforcement and child-job spawning are deferred to
> a later milestone (the runtime currently Nacks with `UNIMPLEMENTED`).

---

## dev.arcp.client

### `ARCPClient`

```kotlin
ARCPClient(
    transport    : Transport,
    auth         : Auth,                  // dev.arcp.messages.Auth
    client       : ClientInfo,
    capabilities : Capabilities,
) : AutoCloseable
```

| Method | Returns | Description |
|--------|---------|-------------|
| `open()` | `SessionAccepted` | Authenticate and negotiate the session |
| `send(sessionId, payload)` | `MessageId` | Send a message; returns the assigned envelope id |
| `receive()` | `Flow<Envelope>` | Underlying transport's incoming envelope flow |
| `listJobs(sessionId, filter, limit, cursor)` | `SessionJobs` | Send `session.list_jobs` and await the correlated reply |
| `close()` | `Unit` | Close the underlying transport |

Companion factories:

```kotlin
ARCPClient.bearer(token: String): Auth                           // Auth(scheme = BEARER, token = ...)
ARCPClient.defaultClientInfo(principal: String? = null): ClientInfo
```

---

## dev.arcp.runtime

### `ARCPRuntime`

```kotlin
ARCPRuntime(
    supportedCapabilities : Capabilities,
    identity              : RuntimeIdentity = RuntimeIdentity(
        kind = Version.SDK_KIND,
        version = Version.SDK_VERSION,
        trustLevel = TrustLevel.TRUSTED,
    ),
    bearerAuth            : BearerAuth = StaticBearerAuth(emptyMap()),
    jwtAuth               : JwtAuth? = null,
    sessionLeaseDuration  : Duration = ARCPRuntime.DEFAULT_SESSION_LEASE,
    agentRegistry         : AgentRegistry = AgentRegistry(),
    jobInventory          : JobInventory = InMemoryJobInventory(),
    budgets               : BudgetRegistry = BudgetRegistry(),
    credentialProvisioner : CredentialProvisioner? = null,
    credentialStore       : CredentialStore = InMemoryCredentialStore(),
) : AutoCloseable
```

> The runtime does not own an `EventLog` or an `ExtensionRegistry` directly
> in this release; they are wired into specific message handlers when
> needed. The `JobInventory` interface backs `session.list_jobs`.

| Method | Description |
|--------|-------------|
| `accept(transport): Job` | Drive the handshake on `transport` then dispatch incoming envelopes |
| `rotateCredential(jobId, credentialId, transport?): Credential` | Re-issue an outstanding credential and optionally emit a rotation event |
| `close()` | Cancel the runtime's coroutine scope |

### `AgentRegistry`

```kotlin
val registry = AgentRegistry()
registry.register("summarise", "1.0.0")
registry.register("summarise", "2.0.0", default = true)
```

Methods: `register(name, version, default = false)`, `resolve(ref)`,
`descriptors(): List<AgentDescriptor>`.

---

## dev.arcp.transport

### `Transport` interface

```kotlin
interface Transport : AutoCloseable {
    suspend fun send(envelope: Envelope)
    fun receive(): Flow<Envelope>
    override fun close()
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
| `Credential` | Wire credential (`id`, `scheme`, `value`, `endpoint`, `profile?`, `constraints?`); `toString()` redacts `value` |
| `CredentialConstraints` | `costBudget: List<String>`, `modelUse: List<String>`, `expiresAt: Instant?` |
| `CredentialScheme` | `BEARER` (only scheme defined in v0.1) |
| `CredentialStore` / `InMemoryCredentialStore` | Per-job credential storage; `issue`, `revoke`, `outstanding(jobId)`, `pendingRevocations()` |
| `CredentialProvisioner` | Interface: `suspend fun issue(ctx: IssuanceContext): List<Credential>` and `suspend fun revoke(credentialId: CredentialId)` |

---

## dev.arcp.lease

| Class | Description |
|-------|-------------|
| `Currency` | `@JvmInline value class Currency(code: String)` |
| `BudgetAmount` | `(currency, value: BigDecimal)`; `BudgetAmount.parse("USD:5.00")`, `render()` |
| `CostBudget` | `(budgets: List<BudgetAmount>)` — lease constraint container; `byCurrency(c)` |
| `BudgetRegistry` | Per-job counters; `register`, `consume`, `terminate`, `remaining` |
| `BudgetCounter` | Single-job counter; `consume(amount): Outcome` (`Ok` or `Exhausted`) |
| `ModelUseLease` | `(patterns: List<String>)`; `allows(modelId)`, `ModelUseLease.subset(parent, child)` |
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
| `suspend append(envelope): Long` | Append; throws `AlreadyExists` on dup `(session_id, message_id)` |
| `replay(sessionId, afterMessageId?): Flow<Envelope>` | Cold flow; throws `DataLoss` if `afterMessageId` is missing |
| `suspend lookupIdempotent(principal, key): JsonElement?` | Returns prior outcome if present and unexpired |
| `suspend recordIdempotent(principal, key, outcome, expiresAt)` | Persist a per-principal idempotency outcome |
| `suspend lastSeq(): Long` | Highest assigned sequence number |
| `close()` | Close the underlying JDBC connection |

---

## dev.arcp.trace

### `TraceContext`

```kotlin
data class TraceContext(
    val traceId:      TraceId,
    val spanId:       SpanId,
    val parentSpanId: SpanId? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceContext> {
        fun newRoot(): TraceContext
    }
}
```

Top-level helpers (in `dev.arcp.trace`):

| Function | Description |
|----------|-------------|
| `suspend currentTrace(): TraceContext?` | Return the ambient `TraceContext`, if any |
| `suspend withSpan(name, block: suspend (TraceContext) -> T): T` | Run `block` in a fresh child span keyed to `name` |

---

## dev.arcp.extensions

### `ExtensionRegistry`

```kotlin
val ext = ExtensionRegistry()
ext.advertise("arcpx.acme.email.v1")
ext.acceptsType(wireType): Boolean        // prefix match against advertised namespaces
ext.advertised: Set<String>               // snapshot of accepted namespaces
ExtensionRegistry.isValidName(name): Boolean
```

`classifyUnknown` is a top-level function (not a member of `ExtensionRegistry`):

```kotlin
fun classifyUnknown(
    wireType: String,
    optional: Boolean,
    advertisedExtensions: Set<String>,
): UnknownAction
```

`UnknownAction`: `Drop`, `Nack` (both `data object`s in a sealed interface).

---

## dev.arcp.ids

Typed ID wrappers (all are `@JvmInline value class` wrapping `String`):

`MessageId`, `SessionId`, `JobId`, `StreamId`, `SubscriptionId`, `LeaseId`,
`ArtifactId`, `TraceId`, `SpanId`, `PermissionName`, `ToolName`,
`IdempotencyKey`.

Most carry a companion `random()` factory that generates a fresh id.

---

## dev.arcp.error

### `ErrorCode` enum

24 codes (including `OK`); each carries `wire: String` and
`retryableByDefault: Boolean`.

```kotlin
ErrorCode.fromWire("RATE_LIMITED")   // → RESOURCE_EXHAUSTED (alias accepted on decode only)
```

### `ARCPException` sealed class

23 concrete subclasses — every `ErrorCode` value except `OK` has a matching
exception. `ARCPException` carries `code: ErrorCode` and `retryable:
Boolean` (default `code.retryableByDefault`; `BudgetExhausted` overrides
to `false`).

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

Pre-configured `kotlinx.serialization` `Json` instance for ARCP wire JSON.
Settings (`Json.kt`):

- `classDiscriminator = "type"`
- `ignoreUnknownKeys = true` (forward-compatible with extensions)
- `encodeDefaults = false`
- `explicitNulls = false`
- `prettyPrint = false`

```kotlin
val envelope = arcpJson.decodeFromString<Envelope>(rawJson)
```

`buildArcpJson { ... }` returns a `Json` configured identically with an extra
`JsonBuilder` block — useful for callers that need to merge a custom
`SerializersModule`.
