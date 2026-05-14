# 04 — Architecture: modules, types, coroutines

> **Phase 3 status.** `03-libraries.md` does not exist yet. This plan
> assumes the dependency set implied by Phase 2 §F and the current
> `lib/build.gradle.kts`: kotlinx-serialization-json (with
> `JsonClassDiscriminator` available — requires `>=1.6.0`),
> kotlinx-coroutines-core, kotlinx-datetime (JVM-bridged to
> `java.time`), sqlite-jdbc, Ktor client+server with CIO, slf4j-api +
> logback for tests. JOSE is dropped (bearer-only per v1.1 §6.1).
> `json-schema-validator` is dropped (Phase 2 §I.1). If Phase 3
> changes these, only §1 (Gradle deps) is affected; §2–§6 stand.

Spec references throughout are `draft-arcp-02.1.md`.

---

## 1. Gradle subprojects

The current `:lib` mixes wire, runtime, client, and store
(Phase 2 §B, ~3,300 LOC). The TS reference splits along the natural
seam — wire types and storage primitives are independently consumable
by both clients and runtimes — so the seams are real, not cosmetic.

**Decision: retire `:lib`. Add six new subprojects mirroring the TS
split**, plus keep `:cli`, `:samples`, `:tests` as siblings. The
six TS middleware packages collapse to two on the JVM because Express,
Fastify, Hono, Bun, Node are five flavors of the same Node-host
shim — there is no equivalent split in the JVM ecosystem.

| Subproject       | Coordinates             | Contents (one line)                                                                           |
| ---------------- | ----------------------- | --------------------------------------------------------------------------------------------- |
| `:core`          | `io.arcp:arcp-core`     | Envelope, sealed `Message` hierarchy, `Json.kt`, `ArcpException`, `Transport` interface, IDs. |
| `:store`         | `io.arcp:arcp-store`    | SQLite `EventLog` (append-only `(session_id, event_seq)` table), idempotency index.           |
| `:client`        | `io.arcp:arcp-client`   | `ArcpClient`, `JobHandle`, `AutoAck`, `SessionState`, pending-request registry.               |
| `:runtime`       | `io.arcp:arcp-runtime`  | `ArcpServer`, `JobManager`, `JobSubscriptionRegistry`, `LeaseGuard`, `BudgetMeter`, heartbeat.|
| `:transport-ktor`| `io.arcp:arcp-transport-ktor` | Ktor CIO WebSocket transport (client + server install) + stdio bridge.                   |
| `:middleware-otel` | `io.arcp:arcp-middleware-otel` | OpenTelemetry span/attribute adapter (§11).                                              |
| `:sdk`           | `io.arcp:arcp-sdk`      | Convenience facade re-exporting client+runtime+transport-ktor for one-stop consumers.         |
| `:cli`           | `io.arcp:arcp-cli`      | Clikt-based CLI (kept; rebuilt in Phase 6).                                                   |
| `:samples`       | _(not published)_       | Sample apps mirroring TS `examples/`.                                                         |
| `:tests`         | _(not published)_       | Cross-module integration + conformance harness.                                               |

Dep graph (only edges that exist):

```
                     :core
                    /  |  \
                :store :transport-ktor   :middleware-otel
                    \  |  /                |
                  :client  :runtime  ------+
                       \    /
                        :sdk
                           \
                          :cli, :samples, :tests  (also each may depend on lower layers directly)
```

Justification:

- **`:core` vs TS `@arcp/core`.** Same role. The TS package internally
  separates `transport/` and `store/` subfolders but ships them as one
  npm artifact. On the JVM, SQLite JDBC pulls a native dylib and
  Ktor pulls Netty — keeping `:store` and `:transport-ktor` as
  independently consumable artifacts lets a pure-test consumer (using
  only `MemoryTransport` from `:core`) avoid those dependencies. This
  is a split the TS package can't do because Node consumers always pay
  for the union; on the JVM, every transitive jar is a real cost.
- **`:transport-ktor` not folded into `:runtime`.** A library author
  writing an HTTP-only embed (e.g., a Lambda) wants the runtime
  without Netty. TS doesn't have this problem because Node bundlers
  tree-shake; Gradle does not.
- **One `:middleware-otel`, no `:middleware-{express,fastify,hono,bun,node}`.**
  The five TS host shims are equivalent to a single Ktor-server
  install plus a generic `Transport` adapter. The Ktor install lives
  in `:transport-ktor`. There is no per-framework JVM middleware to
  write.
- **`:sdk` exists as a thin facade.** Matches TS `@arcp/sdk` (which is
  also a re-export). A consumer types `implementation("io.arcp:arcp-sdk:…")`
  and gets the common case. Phase 4 should NOT put real logic here.

**Path to take with `:lib`.** Delete it. Phase 2 §A established that
~nothing wire-level carries over. Reuse the survivors (`MemoryTransport`,
`Ids.kt`, `TraceContext.kt`, the SQLite scaffold, the top-level
`build.gradle.kts` plugin wiring) by moving the files into their new
homes (`:core`, `:store`). Renaming `:lib` to `:core` in place would
preserve git history for files that are being rewritten anyway — not
worth the package-rename churn for downstream consumers.

---

## 2. Type model — envelopes and messages

### 2.1 The envelope

Lives in `io.arcp.core.envelope` (`:core`). Direct mapping of §5.1.

```kotlin
@Serializable
public data class Envelope(
    public val arcp: String,
    public val id: String,
    public val type: String,
    @SerialName("session_id") public val sessionId: String? = null,
    @SerialName("trace_id")   public val traceId: String? = null,
    @SerialName("job_id")     public val jobId: String? = null,
    @SerialName("event_seq")  public val eventSeq: Long? = null,
    public val payload: JsonElement,
) {
    init { require(arcp == "1") { "envelope.arcp must be the literal \"1\" per §5.1" } }
}
```

Notes:

- **`arcp == "1"` literal.** Enforced in `init { require(...) }`. The
  current SDK ships `"1.0"` (Phase 2 §A), so the `require` doubles as
  a regression guard during the cutover. An `@SerialName("1")`
  literal-discriminator approach won't work because `arcp` is a
  value field, not a type discriminator.
- **`event_seq` is `Long`.** §8.3 says monotonic, gap-free,
  session-scoped. `Int` is too small for a long-running session at
  high event rates; the TS reference uses `number` (`Number.MAX_SAFE_INTEGER`
  = 2^53), so `Long` is strictly safer.
- **`payload: JsonElement`.** The envelope decoder can't know the
  payload's concrete type until it has read `type`. Two-pass
  decoding — first the envelope shell, then the payload via a
  per-type strategy — is the same pattern as the TS
  `RoundTripEnvelopeSchema` followed by `EnvelopeSchema.safeParse`
  (`packages/core/src/envelope.ts:181`).

### 2.2 `Message` sealed hierarchy

The TS reference puts the discriminator on the **envelope's** `type`
field, not inside the payload (see `messages/session.ts` building
`messageEnvelope("session.hello", SessionHelloPayloadSchema)`). The
Kotlin model has to mirror that. `kotlinx.serialization`'s built-in
`@JsonClassDiscriminator("type")` puts the discriminator **inside the
serialized object** — so if we used `@JsonClassDiscriminator` on
`sealed interface Message`, each message would serialize with `type`
inside it, but we want `type` on the **outer envelope** alongside
`arcp`, `id`, etc.

**Decision: write a custom envelope codec.** It is the only correct
shape. The codec:

1. **Encoding.** Take a `Message` instance. Look up its
   `@SerialName` to get the wire `type` string. Serialize the
   message to a `JsonObject` (via `Json.encodeToJsonElement`). Place
   that object as `payload` in the `Envelope`.
2. **Decoding.** Read the `Envelope`. Use `type` to select the
   `KSerializer<out Message>` for the payload. Decode `payload`
   into the concrete `Message` subtype.

This is exactly what the existing `EnvelopeSerializer` in
`lib/src/main/kotlin/dev/arcp/envelope/` does for the pre-v1.0 model
(Phase 2 §F notes the pattern survives even though the contents
don't). The codec lives in `io.arcp.core.envelope.EnvelopeCodec`
(`:core`).

**Why not the discriminator-hoist trick (move `type` to envelope,
keep `@JsonClassDiscriminator` on payload).** Because the payload
JSON literally does not contain `type` on the wire — see the v1.1
welcome example at §6.2 (`session_id` on the envelope, `payload`
contains `runtime`/`resume_token`/`capabilities` but **not** `type`).
A discriminator-hoist serializer would have to inject `type` at
decode time and strip it at encode time. That is doable but
strictly more code than the two-pass codec above, and it leaks the
discriminator into the payload schema for documentation purposes.
Two-pass codec wins.

### 2.3 `Json` policy

One singleton per process, in `io.arcp.core.json`:

```kotlin
public val ArcpJson: Json = Json {
    ignoreUnknownKeys = true     // §5.1: MUST ignore unknown top-level fields
    encodeDefaults = false        // omit `null` and default-valued fields
    explicitNulls = false         // do not serialize null as "x": null
    prettyPrint = false           // wire frames are minified
    classDiscriminator = "kind"   // for sealed JobEventBody only (§8.2 nested kind)
    coerceInputValues = false     // strict — do not silently coerce nulls
}
```

Notes:

- `classDiscriminator = "kind"` is set globally so the nested
  `JobEventBody` hierarchy (§8.2) serializes naturally with
  `@JsonClassDiscriminator("kind")` overrides where needed. The
  envelope-level `type` discriminator is handled by the custom codec
  in §2.2, so the global `classDiscriminator` does not collide.
- `ignoreUnknownKeys = true` covers v1.0/v1.1 forward-compat: a v1.0
  client receiving v1.1-only fields drops them silently (§5
  unchanged paragraph).
- `encodeDefaults = false` + `explicitNulls = false`: the wire format
  is "omit when absent." Setting both is belt-and-suspenders against
  `null`-vs-absent ambiguity, e.g. `lease_constraints` should never
  serialize as `"lease_constraints": null`.

### 2.4 Sealed hierarchy taxonomy

All in `io.arcp.core.messages`. Signatures only:

```kotlin
public sealed interface Message

public sealed interface SessionMessage : Message
@SerialName("session.hello")     public data class SessionHello(...) : SessionMessage
@SerialName("session.welcome")   public data class SessionWelcome(...) : SessionMessage
@SerialName("session.bye")       public data class SessionBye(...) : SessionMessage
@SerialName("session.error")     public data class SessionError(...) : SessionMessage
@SerialName("session.ping")      public data class SessionPing(...) : SessionMessage      // v1.1 §6.4
@SerialName("session.pong")      public data class SessionPong(...) : SessionMessage      // v1.1 §6.4
@SerialName("session.ack")       public data class SessionAck(...) : SessionMessage       // v1.1 §6.5
@SerialName("session.list_jobs") public data class SessionListJobs(...) : SessionMessage  // v1.1 §6.6
@SerialName("session.jobs")      public data class SessionJobs(...) : SessionMessage      // v1.1 §6.6

public sealed interface JobMessage : Message
@SerialName("job.submit")        public data class JobSubmit(...) : JobMessage
@SerialName("job.accepted")      public data class JobAccepted(...) : JobMessage
@SerialName("job.event")         public data class JobEvent(...) : JobMessage
@SerialName("job.result")        public data class JobResult(...) : JobMessage
@SerialName("job.error")         public data class JobError(...) : JobMessage
@SerialName("job.cancel")        public data class JobCancel(...) : JobMessage
@SerialName("job.subscribe")     public data class JobSubscribe(...) : JobMessage         // v1.1 §7.6
@SerialName("job.subscribed")    public data class JobSubscribed(...) : JobMessage        // v1.1 §7.6
@SerialName("job.unsubscribe")   public data class JobUnsubscribe(...) : JobMessage       // v1.1 §7.6

@Serializable
@JsonClassDiscriminator("kind")
public sealed interface JobEventBody
@SerialName("log")          public data class LogBody(...) : JobEventBody
@SerialName("thought")      public data class ThoughtBody(...) : JobEventBody
@SerialName("tool_call")    public data class ToolCallBody(...) : JobEventBody
@SerialName("tool_result")  public data class ToolResultBody(...) : JobEventBody
@SerialName("status")       public data class StatusBody(...) : JobEventBody
@SerialName("metric")       public data class MetricBody(...) : JobEventBody
@SerialName("artifact_ref") public data class ArtifactRefBody(...) : JobEventBody
@SerialName("delegate")     public data class DelegateBody(...) : JobEventBody
@SerialName("progress")     public data class ProgressBody(...) : JobEventBody             // v1.1 §8.2.1
@SerialName("result_chunk") public data class ResultChunkBody(...) : JobEventBody          // v1.1 §8.4
```

The `JobEventBody` discriminator is inside the body object (the §8.1
`{ kind, ts, body }` shape) — that's exactly what
`@JsonClassDiscriminator("kind")` produces, no custom serializer
needed. Contrast with `Message` above where the discriminator lives
on the envelope, not the payload.

### 2.5 Polymorphic `agents` (v1.1 §6.2)

`Welcome.capabilities.agents` is `List<String>` against a v1.0 runtime
and `List<AgentDescriptor>` against a v1.1 runtime. Element-shape
polymorphism — string-or-object — cannot use
`@JsonClassDiscriminator`, because there is no shared discriminator
field on a `JsonPrimitive`. Use a `JsonContentPolymorphicSerializer`:

```kotlin
public sealed interface AgentInventory {
    public data class Flat(public val names: List<String>) : AgentInventory     // v1.0
    public data class Rich(public val agents: List<AgentDescriptor>) : AgentInventory  // v1.1
}

internal object AgentInventorySerializer : JsonContentPolymorphicSerializer<AgentInventory>(...) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<AgentInventory>
}
```

The selector inspects the first element of the array: `JsonPrimitive`
→ `Flat`; `JsonObject` → `Rich`; empty array → `Rich` (safe default,
matches the v1.1 runtime advertising no agents). This is the
Kotlin-idiomatic way; the comparable TS code is a `z.union([...])`
with a discriminator predicate (no direct line, but the pattern is
implicit in `Capabilities` shape evolution).

---

## 3. Coroutine and concurrency model

### 3.1 Scope discipline

| Scope                            | Construct                                   | Why                                                                                                  |
| -------------------------------- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Session lifecycle (per session)  | `supervisorScope { }`                       | A misbehaving job MUST NOT kill the session. §6.7 lets either side close cleanly; an exception in one job's emitter is not that. |
| Job execution (per job)          | `coroutineScope { }`                        | The job's children (event emitter, timeout watchdog, tool sub-coroutines) share fate. If the emitter dies, the job dies. |
| Subscription fan-out (per job)   | `MutableSharedFlow` inside a supervisor     | Per Phase 2 §E v1.1 §7.6: many readers, one writer. Reader failure must not propagate to other readers or the producing job. |
| Server top-level                 | `CoroutineScope(SupervisorJob() + Dispatchers.Default)` | Stored on `ArcpServer`; cancelled in `close()`. Supervisor so one bad session does not unwind all sessions. |

### 3.2 Cancellation cooperation

§7.4 cancellation maps to `Job.cancel(CancellationException(reason))`.
Every long-running loop in the runtime calls `ensureActive()` at the
top of the iteration — specifically: the event-write loop in
`JobManager.run`, the subscriber fan-out loop in
`JobSubscriptionRegistry`, the heartbeat tick in
`HeartbeatLoop`. Mirrors TS `ctx.signal.aborted` checks in
`packages/runtime/src/job.ts`.

`runBlocking` is forbidden in library code (everything is `suspend`).
The only acceptable use is `:samples` and `:tests`, which both have
narrow entry points where blocking is fine.

### 3.3 Dispatcher boundaries

- CPU/parsing/serialization: caller's dispatcher (defaults to
  `Dispatchers.Default` when reached via the server scope).
- SQLite, file I/O, network frames: explicit `withContext(Dispatchers.IO)`.
  JDBC is blocking; the rule is to wrap **at the call site** in the
  `:store` package — never trust callers to remember.
- Public `suspend` API: no implicit dispatcher switch. The client
  caller's dispatcher is preserved, switching only at the
  `Transport.send`/`receive` and `EventLog` boundaries.

### 3.4 `subscribe(jobId)` and back-pressure

Per the v1.1 §7.6 architecture (Phase 2 §E "subscriptions, H risk"),
the runtime maintains a fan-out per job:

```kotlin
internal class JobBroadcaster(jobId: String) {
    private val sink = MutableSharedFlow<JobEventBody>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    public fun events(): Flow<JobEventBody> = sink.asSharedFlow()
    internal suspend fun emit(body: JobEventBody)
}
```

`onBufferOverflow = SUSPEND` is the §6.5 contract: when the slowest
subscriber falls behind, the producer slows down. The alternative
(`DROP_OLDEST`) silently violates §8.3 ordering. The 256 buffer is
Phase 4 default; configurable per server option.

Client `subscribe(jobId): Flow<JobEventBody>` returns a **cold**
flow. Each call opens a session-scoped collection over the inbound
event stream filtered by `job_id`. No `shareIn` on the client side —
the use case is "one consumer per subscribe call." This matches the
TS `subscribe(...)` returning a `JobSubscription` whose events arrive
via the same handler bus (`packages/client/src/client.ts:476`).

### 3.5 Heartbeats (§6.4)

The session has a `HeartbeatLoop` coroutine launched in the
session's `supervisorScope`. Sketch:

```kotlin
internal class HeartbeatLoop(
    private val intervalSec: Int,
    private val sendPing: suspend () -> Unit,
    private val onLost: suspend () -> Unit,
)
internal suspend fun HeartbeatLoop.run(): Nothing
```

Implementation rules (no code in the plan, but the design must hold):

- Use a `delay(interval)` loop guarded by `ensureActive()` — `delay`
  is cancellable, so session-close cancels the loop within one tick.
- Track last-inbound-activity timestamp on a `MutableStateFlow<Instant>`
  that the receive loop updates on every frame. The heartbeat loop
  reads it; if `now - lastInbound > 2 × interval`, raise
  `HEARTBEAT_LOST` (close transport, surface error). This is the
  "2× silent intervals" rule in §6.4.
- **Use `System.nanoTime()` for the interval check**, not `Clock.System.now()`.
  Wall-clock can step; the heartbeat MUST NOT false-fire when the
  host clock jumps. Phase 2 §E flagged this for `LeaseGuard`; same
  rule applies here.

---

## 4. Errors

In `io.arcp.core.error` (`:core`). One sealed hierarchy.

```kotlin
public enum class ErrorCode(public val wire: String, public val retryable: Boolean) {
    PERMISSION_DENIED("PERMISSION_DENIED", retryable = false),
    LEASE_SUBSET_VIOLATION("LEASE_SUBSET_VIOLATION", retryable = false),
    JOB_NOT_FOUND("JOB_NOT_FOUND", retryable = false),
    DUPLICATE_KEY("DUPLICATE_KEY", retryable = false),
    AGENT_NOT_AVAILABLE("AGENT_NOT_AVAILABLE", retryable = false),
    AGENT_VERSION_NOT_AVAILABLE("AGENT_VERSION_NOT_AVAILABLE", retryable = false),  // v1.1
    CANCELLED("CANCELLED", retryable = false),
    TIMEOUT("TIMEOUT", retryable = true),
    RESUME_WINDOW_EXPIRED("RESUME_WINDOW_EXPIRED", retryable = false),
    HEARTBEAT_LOST("HEARTBEAT_LOST", retryable = true),
    LEASE_EXPIRED("LEASE_EXPIRED", retryable = false),                              // v1.1
    BUDGET_EXHAUSTED("BUDGET_EXHAUSTED", retryable = false),                        // v1.1
    INVALID_REQUEST("INVALID_REQUEST", retryable = false),
    UNAUTHENTICATED("UNAUTHENTICATED", retryable = false),
    INTERNAL_ERROR("INTERNAL_ERROR", retryable = true),
}

public sealed class ArcpException(
    public val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    public val retryable: Boolean get() = code.retryable
    public companion object {
        public fun fromCode(code: ErrorCode, message: String, cause: Throwable? = null): ArcpException
        public fun fromPayload(payload: ErrorPayload): ArcpException
    }
}

public class PermissionDenied(message: String, cause: Throwable? = null)        : ArcpException(ErrorCode.PERMISSION_DENIED, message, cause)
public class LeaseSubsetViolation(message: String, cause: Throwable? = null)    : ArcpException(ErrorCode.LEASE_SUBSET_VIOLATION, message, cause)
public class JobNotFound(message: String, cause: Throwable? = null)             : ArcpException(ErrorCode.JOB_NOT_FOUND, message, cause)
public class DuplicateKey(message: String, cause: Throwable? = null)            : ArcpException(ErrorCode.DUPLICATE_KEY, message, cause)
public class AgentNotAvailable(message: String, cause: Throwable? = null)       : ArcpException(ErrorCode.AGENT_NOT_AVAILABLE, message, cause)
public class AgentVersionNotAvailable(message: String, cause: Throwable? = null): ArcpException(ErrorCode.AGENT_VERSION_NOT_AVAILABLE, message, cause)
public class Cancelled(message: String, cause: Throwable? = null)               : ArcpException(ErrorCode.CANCELLED, message, cause)
public class Timeout(message: String, cause: Throwable? = null)                 : ArcpException(ErrorCode.TIMEOUT, message, cause)
public class ResumeWindowExpired(message: String, cause: Throwable? = null)     : ArcpException(ErrorCode.RESUME_WINDOW_EXPIRED, message, cause)
public class HeartbeatLost(message: String, cause: Throwable? = null)           : ArcpException(ErrorCode.HEARTBEAT_LOST, message, cause)
public class LeaseExpired(message: String, cause: Throwable? = null)            : ArcpException(ErrorCode.LEASE_EXPIRED, message, cause)
public class BudgetExhausted(message: String, cause: Throwable? = null)         : ArcpException(ErrorCode.BUDGET_EXHAUSTED, message, cause)
public class InvalidRequest(message: String, cause: Throwable? = null)          : ArcpException(ErrorCode.INVALID_REQUEST, message, cause)
public class Unauthenticated(message: String, cause: Throwable? = null)         : ArcpException(ErrorCode.UNAUTHENTICATED, message, cause)
public class InternalError(message: String, cause: Throwable? = null)           : ArcpException(ErrorCode.INTERNAL_ERROR, message, cause)
```

Notes:

- All 15 codes per §12 (12 from v1.0 + 3 from v1.1).
- `retryable` lives on `ErrorCode`, not duplicated per subclass. The
  spec table at §12 fixes the retryability per code; making it an
  enum constant prevents the subclass-by-subclass drift that the
  TS `ARCPError` chain has to maintain manually.
- `ErrorPayload` is a separate `@Serializable data class` in
  `io.arcp.core.messages` — it's the wire shape carried inside
  `session.error.payload` and `job.error.payload`. The exception is
  what application code catches; the payload is what serializes.
  `fromPayload(payload)` bridges the two on the receive side.
  `toPayload(): ErrorPayload` would do the inverse on the send side
  (member function on `ArcpException`).

---

## 5. Public API sketches

Visibility per Phase 2 §C: `explicitApi()` strict, default to
`internal`. Functions below are `public`.

### 5.1 Client — `:client`, package `io.arcp.client`

```kotlin
public class ArcpClient(public val options: ArcpClientOptions) {
    public suspend fun connect(transport: Transport, hello: SessionHello): Session
    public suspend fun resume(transport: Transport, resume: SessionResume): Session
}

public interface Session : AutoCloseable {
    public val id: String
    public val negotiatedFeatures: Set<Feature>
    public fun hasFeature(name: Feature): Boolean
    public suspend fun submit(request: JobSubmit): JobHandle
    public suspend fun subscribe(jobId: String, history: Boolean = false, fromEventSeq: Long? = null): JobHandle
    public suspend fun listJobs(filter: JobsFilter? = null, limit: Int? = null, cursor: String? = null): JobsPage
    public suspend fun ack(seq: Long)
    public suspend fun close(reason: String? = null)
}

public interface JobHandle {
    public val jobId: String
    public val lease: Lease
    public val events: Flow<JobEventBody>
    public suspend fun await(): JobResult
    public suspend fun cancel(reason: String? = null)
    public suspend fun collectChunks(): ByteArray
}
```

### 5.2 Runtime — `:runtime`, package `io.arcp.runtime`

```kotlin
public class ArcpServer(public val options: ArcpServerOptions) : AutoCloseable {
    public fun registerAgent(name: String, handler: AgentHandler)
    public fun registerAgentVersion(name: String, version: String, handler: AgentHandler)
    public fun setDefaultAgentVersion(name: String, version: String)
    public suspend fun accept(transport: Transport): SessionContext
    public override fun close()
}

public class SessionContext internal constructor(...) {
    public val negotiatedFeatures: Set<Feature>
    public fun hasFeature(name: Feature): Boolean
}

public fun interface AgentHandler {
    public suspend fun handle(ctx: JobContext, input: JsonElement): JobResult
}
```

### 5.3 Transport — `:core`, package `io.arcp.core.transport`

```kotlin
public interface Transport : AutoCloseable {
    public suspend fun send(envelope: Envelope)
    public fun receive(): Flow<Envelope>
    public suspend fun close(reason: String? = null)
}
```

Implementations live in `:transport-ktor` (WebSocket via Ktor CIO,
both client and server install) and `:core` (`MemoryTransport` for
tests, `StdioTransport` for in-proc).

### 5.4 Leases — `:runtime`, package `io.arcp.runtime.lease`

```kotlin
public class LeaseGuard(internal val lease: Lease, internal val constraints: LeaseConstraints?) {
    public fun authorize(capability: String, target: String, now: Instant = Clock.System.now())
    // throws PermissionDenied | LeaseExpired | BudgetExhausted
}

public class BudgetMeter(internal val initial: Map<String, BigDecimal>) {
    public fun debit(currency: String, amount: BigDecimal)   // throws BudgetExhausted
    public fun remaining(currency: String): BigDecimal
}
```

Phase 2 §E §9.6: counters MUST be `BigDecimal`, not `Double`. The
debit must be CAS-loop or `synchronized`; an `AtomicReference<BigDecimal>`
with `updateAndGet` is the idiomatic Kotlin path.

### 5.5 Module map

| Symbol                    | Module           | Package                       |
| ------------------------- | ---------------- | ----------------------------- |
| `Envelope`                | `:core`          | `io.arcp.core.envelope`       |
| `Message`, `JobEventBody` | `:core`          | `io.arcp.core.messages`       |
| `ArcpException`           | `:core`          | `io.arcp.core.error`          |
| `Transport`               | `:core`          | `io.arcp.core.transport`      |
| `MemoryTransport`         | `:core`          | `io.arcp.core.transport`      |
| `EventLog`                | `:store`         | `io.arcp.store`               |
| `ArcpClient`, `Session`, `JobHandle` | `:client` | `io.arcp.client`        |
| `ArcpServer`, `SessionContext`, `AgentHandler` | `:runtime` | `io.arcp.runtime` |
| `LeaseGuard`, `BudgetMeter` | `:runtime`     | `io.arcp.runtime.lease`       |
| `KtorWebSocketTransport`  | `:transport-ktor`| `io.arcp.transport.ktor`      |
| `OtelInterceptor`         | `:middleware-otel`| `io.arcp.middleware.otel`    |

---

## 6. Visibility and Java interop policy

- **`explicitApi()` strict** in every published subproject's
  `kotlin { }` block. Already wired in the current `lib/build.gradle.kts:13` —
  carry it forward.
- **Default to `internal`.** Only the symbols listed in §5 are
  `public`. Phase 2 §F keeps the survivors (`Ids.kt`, `TraceContext.kt`,
  `MemoryTransport`); on the move into `:core` they should be
  re-audited and demoted to `internal` where the test surface is the
  only consumer.
- **Java interop is not a goal.** No `@JvmStatic`, no `@JvmOverloads`,
  no `@file:JvmName`, no top-level companion-object workarounds.
  Every consumer in the planning prompt (Ktor server, Spring Boot
  WebFlux, Vert.x Kotlin, Http4k) is Kotlin-first; spending
  binary-compat budget on Java callability is not justified. Phase 8
  binary-compat validation (`apiDump`) freezes the Kotlin metadata
  shape only.
- **`binary-compatibility-validator`** stays wired (current
  `lib/build.gradle.kts:7`). It moves to `:core`, `:client`,
  `:runtime`, `:transport-ktor`, `:middleware-otel`, `:sdk`.
  `:store` is `implementation` from `:runtime` and need not freeze
  its API; mark its functions `@PublishedApi internal` if any
  must cross the module boundary.

---

## Risks

| Risk                                                                                                | Specific Kotlin construct at risk                                              |
| --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| Custom envelope codec (§2.2) is hand-rolled; bugs land as silent message-drops.                     | `KSerializer<Envelope>` + `JsonDecoder.decodeJsonElement` pairing.             |
| Heartbeat 2× wall-clock check fires falsely on a clock step.                                        | `Clock.System.now()` vs `System.nanoTime()` — use the latter for intervals.    |
| `MutableSharedFlow` with `SUSPEND` deadlocks if the same coroutine emits and collects.              | `JobBroadcaster.emit` running on the same scope as the slowest collector.      |
| `BigDecimal` budget debits race on naive `var counter`.                                             | `AtomicReference<BigDecimal>.updateAndGet { it - amount }`.                    |
| `JsonContentPolymorphicSerializer` for `agents` mis-selects on `[]`.                                | Empty-array branch in `selectDeserializer` — must explicit-test.               |
| `explicitApi()` strict will reject the current `lib` after rename; surface the public set up front. | `public` modifier required on every top-level declaration in `:core`/`:client`.|

---

## Open questions for Phase 5

1. Does `ArcpServer` expose `negotiatedFeatures` per-session only, or
   also a server-wide "advertised set"? TS keeps both
   (`packages/runtime/src/server.ts:55` `V1_1_FEATURES` constant +
   per-session intersection); plan to match.
2. Should `JobHandle.events` be `Flow<JobEvent>` (envelope) or
   `Flow<JobEventBody>` (just the body)? Sketch above chose body
   because §8 events are body-shaped; envelope carries no info the
   handle doesn't already know. Confirm in Phase 5.
3. `ArcpServer.close()` is `AutoCloseable.close()` — non-suspend.
   Will need a `closeAsync(): Deferred<Unit>` or a `runBlocking`
   bridge for cleanup-on-JVM-shutdown. Phase 5 decides.
