# 02 — Current Audit: kotlin-sdk vs ARCP v1.0/v1.1

Goal: produce a sober gap map. This file's central finding determines
the migration shape — Phases 3–9 plan on top of it.

## A. Headline finding — this SDK is not v1.0 conformant

The current code targets a **pre-v1.0 internal draft** of ARCP, not the
published v1.0 in [`../spec/docs/draft-arcp-01.md`](../../../spec/docs/draft-arcp-01.md)
and not v1.1 in `draft-arcp-02.1.md`. Evidence:

- `Version.kt:13` sets `PROTOCOL_VERSION = "1.0"`. v1.0 §5.1 (and v1.1
  §5.1, unchanged) require the wire `arcp` field to be the literal
  string `"1"`. The current SDK ships an incorrect wire value.
- `README.md` cites RFC sections (`§6.1` envelope, `§7` capability,
  `§8` auth, `§9` session state, `§17.3.1` metrics, `§18` errors,
  `§19` resume, `§21` extensions) that exist in neither v1.0 nor v1.1
  of `draft-arcp-02.1.md`. The numbering is from an earlier draft.
- `lib/src/main/kotlin/dev/arcp/messages/Session.kt` defines the
  handshake as `session.open` / `session.challenge` /
  `session.authenticate` / `session.accepted` / `session.unauthenticated`
  / `session.rejected` / `session.refresh` / `session.evicted`. The
  published v1.0/v1.1 handshake is the two-message `session.hello` /
  `session.welcome` (with `session.error` on failure and `session.bye`
  for close).
- `messages/Execution.kt` declares job lifecycle as
  `job.accepted` / `job.started` / `job.progress` / `job.heartbeat` /
  `job.checkpoint` / `job.completed` / `job.failed` / `job.cancelled`.
  Published v1.0/v1.1 uses `job.submit` / `job.accepted` / `job.event`
  (kinds: `log`, `thought`, `tool_call`, `tool_result`, `status`,
  `metric`, `artifact_ref`, `delegate` [+ v1.1 `progress`,
  `result_chunk`]) / `job.result` / `job.error` / `job.cancel`. The
  two models don't overlap except at the name `job.accepted`.
- `messages/Subscriptions.kt` defines `subscribe` / `subscribe.accepted`
  / `subscribe.event` / `unsubscribe` / `subscribe.closed` — a
  session-level event-bus with a filter expression. v1.1 §7.6
  subscription is per-`job_id` re-attachment via `job.subscribe` /
  `job.subscribed` / `job.unsubscribe`. Different model, different
  authorization rules.
- `messages/Streaming.kt` defines a dedicated `stream.open` /
  `stream.chunk` / `stream.close` / `stream.error` channel. v1.0/v1.1
  has no `stream.*` envelope; chunks are `job.event` envelopes with
  `kind: result_chunk` (§8.4) or `kind: log/thought/tool_result`.
- `messages/Permissions.kt` defines `permission.request` / `grant` /
  `deny` and `lease.granted` / `refresh` / `extended` / `revoked` — a
  per-resource challenge-and-grant lease model. v1.0/v1.1 leases are
  static, immutable, granted on `job.accepted`, with no refresh or
  challenge (§9.1, §9.5). The v1.1 `LEASE_EXPIRED` code is unrelated
  to this SDK's `lease.revoked` flow.
- `messages/Control.kt` defines top-level `ping`, `pong`, `ack`, `nack`,
  `cancel`, `cancel.accepted`, `cancel.refused`, `interrupt`, `resume`,
  `backpressure`, `checkpoint.create`, `checkpoint.restore`. v1.1
  scopes heartbeat to `session.ping`/`session.pong` (§6.4), ack to
  `session.ack` (§6.5), and cancel to `job.cancel` — and has no
  `interrupt`, `checkpoint.*`, `ack`, or `nack` envelope types at all.
- The envelope (`envelope/Envelope.kt`) declares 18 top-level fields:
  `arcp`, `id`, `timestamp`, `source`, `target`, `session_id`, `job_id`,
  `stream_id`, `subscription_id`, `trace_id`, `span_id`, `parent_span_id`,
  `correlation_id`, `causation_id`, `idempotency_key`, `priority`,
  `extensions`, `payload` (+ derived `type`). v1.1 §5.1 envelope has 8:
  `arcp`, `id`, `type`, `session_id`, `trace_id`, `job_id`, `event_seq`,
  `payload`. There is no `event_seq` in the current envelope, which is
  a v1.0 normative requirement (§8.3 monotonic per-session sequence).
- `ErrorCode.kt` is a gRPC-style taxonomy (`OK`, `INVALID_ARGUMENT`,
  `DEADLINE_EXCEEDED`, `NOT_FOUND`, `ALREADY_EXISTS`,
  `FAILED_PRECONDITION`, `ABORTED`, `OUT_OF_RANGE`, `INTERNAL`,
  `UNAVAILABLE`, `DATA_LOSS`, `RATE_LIMITED→RESOURCE_EXHAUSTED`,
  `BACKPRESSURE_OVERFLOW`, `LEASE_REVOKED`, etc.). v1.1 §12 specifies
  exactly 15 codes: `PERMISSION_DENIED`, `LEASE_SUBSET_VIOLATION`,
  `JOB_NOT_FOUND`, `DUPLICATE_KEY`, `AGENT_NOT_AVAILABLE`,
  `AGENT_VERSION_NOT_AVAILABLE`, `CANCELLED`, `TIMEOUT`,
  `RESUME_WINDOW_EXPIRED`, `HEARTBEAT_LOST`, `LEASE_EXPIRED`,
  `BUDGET_EXHAUSTED`, `INVALID_REQUEST`, `UNAUTHENTICATED`,
  `INTERNAL_ERROR`. Only `CANCELLED`, `PERMISSION_DENIED`,
  `UNAUTHENTICATED`, `HEARTBEAT_LOST`, `LEASE_EXPIRED` overlap by name
  — the rest are renamed or absent.
- `runtime/ARCPRuntime.kt` v0.1 only completes the handshake and NACKs
  every other message type (`handleEnvelope` returns
  `UNIMPLEMENTED` for everything except `Ping` and `SessionClose`).
  Despite the 14 sample directories, the runtime has no job
  submission, no job event emission, no lease enforcement, no resume,
  no real subscription delivery beyond the wiring in
  `SubscriptionManager.kt`.
- TypeScript `CONFORMANCE.md` reports v1.1 implemented across the
  entire feature set with file:line citations. The Kotlin
  `CONFORMANCE.md` is a 6-line stub deferring to README.

Implication: the migration to v1.1 is **not additive over an existing
v1.0 implementation**. It is the first conformant implementation, full
stop. The pre-v1.0 design will be retired. Reuse is limited to: the
Gradle multi-module skeleton, the `Json.kt` configuration approach,
the SQLite `EventLog` storage idea (schema needs replacement), the
`MemoryTransport` (already idiomatic Kotlin), the bearer/JWT auth
helpers, and the test/lint plugin choices.

## B. Module layout (current)

`settings.gradle.kts` declares four Gradle subprojects:

| Subproject  | Purpose (current)                                                                    | Status                                                       |
| ----------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------ |
| `:lib`      | Envelope, message catalog, runtime, client, MemoryTransport, EventLog, auth helpers. | Single module mixes wire/runtime/client/store. ~3,300 LOC.   |
| `:cli`      | `dev.arcp.cli.MainKt` — single file. Uses Clikt.                                     | Skeleton only.                                               |
| `:samples`  | 14 sample directories registered as `JavaExec` tasks in `samples/build.gradle.kts`.  | Compile against the lib's pre-v1.0 surface; non-conformant.  |
| `:tests`    | `dev/arcp/tests/HandshakeTest.kt` + `HarnessFixture.kt`. Two test files.             | Only exercises the pre-v1.0 handshake.                       |

Top-level `build.gradle.kts` already wires `ktlint`, `detekt`, `kover`,
`dokka`, `binary-compatibility-validator`. Toolchain is JDK 21
(`build.gradle.kts:11`). Kotlin DSL throughout. `gradle.properties`
enables `parallel` + `caching` but disables `configuration-cache`.

Compared to TypeScript's `@arcp/core`, `@arcp/client`, `@arcp/runtime`,
`@arcp/sdk`, plus six middleware packages, the Kotlin layout is
under-split. Phase 4 will resolve the right split for v1.1.

## C. KMP decision

The current SDK is **JVM-only**:

- `:lib` applies `org.jetbrains.kotlin.jvm`, not
  `org.jetbrains.kotlin.multiplatform` (`lib/build.gradle.kts:2`).
- Hard JVM-only dependencies: `sqlite-jdbc`, `jose-jwt`,
  `json-schema-validator`, the Ktor server stack (`ktor-server-netty`),
  `slf4j-api`, `logback-classic` (`lib/build.gradle.kts:30-39`).
- `kotlin { jvmToolchain(21) }`.
- The `EventLog` uses `java.sql.*` directly.
- There are no `expect`/`actual` declarations, no `commonMain`
  directory, no Android/iOS/JS code paths.

**Decision for v1.1: remain JVM-only.** Justification: every named
consumer in the planning prompt (Ktor server, Spring Boot WebFlux,
Vert.x Kotlin, Http4k, OTel) is a JVM host; SQLite, JOSE, OkHttp,
Netty all assume JVM; no actual Android/iOS/JS consumer has been
proposed. The TypeScript reference is Node/Bun/Workers, none of which
benefits from a Kotlin/Multiplatform target. **No `kotlin-multiplatform`
plugin in any subproject** unless and until a real Android consumer
shows up. (Codifying this lets Phase 4 use `Dispatchers.IO`,
`java.time.*`-bridged `kotlinx-datetime`, and `java.sql.*` freely.)

Minimum baselines: **Kotlin 2.0+** (K2 stable; `@Serializable` sealed
hierarchies and value classes are reliable); **JDK 17** as the target
floor for library consumers (the SDK itself builds on toolchain 21 but
sets target 17 for `:lib` — see Phase 3 for the libs.versions.toml
update). JDK 21 only if a feature genuinely needs it (virtual threads
are an *interesting* but not *required* runtime choice; we will not
adopt them inside the library).

## D. v1.0 conformance against this SDK

A blunt port of the TypeScript v1.0 conformance table to Kotlin's
current state, restricted to v1.0 normative items:

| Spec §                                  | Requirement                                              | Current status                                                | Evidence                                                                                                       |
| --------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| §4 Transport                            | WebSocket mandatory; JSON text frames; stdio for in-proc | Missing                                                       | No `WebSocketTransport`; only `MemoryTransport`. Ktor WS deps are declared but unused in any Transport impl.   |
| §5.1 Envelope `arcp = "1"`              | Literal `"1"` required                                   | Wrong                                                         | `Version.kt:13` `"1.0"`                                                                                        |
| §5.1 Envelope shape                     | 8 fields, includes `event_seq`                           | Wrong                                                         | `envelope/Envelope.kt:39` has 18 fields; no `event_seq`                                                        |
| §5.1 Unknown top-level fields ignored   | MUST ignore                                              | Partial                                                       | `EnvelopeSerializer.deserialize` ignores by virtue of reading specific keys; not policy-tested                 |
| §6.1 Bearer auth on `session.hello`     | Token in `auth.token`                                    | Different shape                                               | `messages/Session.kt:80` `Auth(scheme, token, fingerprint)` is the pre-v1.0 multi-scheme model                 |
| §6.2 Hello/Welcome                      | Two-message handshake                                    | Wrong handshake                                               | Four-message `open/challenge/authenticate/accepted`                                                            |
| §6.2 `resume_token`                     | ≥128-bit entropy, rotated per welcome                    | Missing                                                       | No resume_token field anywhere in messages                                                                     |
| §6.3 Resume `last_event_seq`            | Replay events > seq                                      | Different mechanism                                           | `messages/Control.kt:107` uses `after_message_id` not `last_event_seq`                                         |
| §6.7 Clean close `session.bye`          | Either side sends                                        | Different name                                                | `session.close { reason }`                                                                                     |
| §7.1 `job.submit`                       | `agent/input/lease_request?/idempotency_key?/...`        | Missing                                                       | No `job.submit` exists                                                                                          |
| §7.1 `job.accepted` shape               | `job_id/lease/accepted_at/parent_job_id?/...`            | Wrong                                                         | `JobAccepted(jobId)` only                                                                                       |
| §7.2 Idempotency by `idempotency_key`   | Same key + principal → same job_id                       | Partial — at envelope, not job                                | `EventLog.recordIdempotent` stores per-principal outcome but not tied to `job.submit`                          |
| §7.3 Lifecycle states                   | `pending/running/success/error/cancelled/timed_out`      | Wrong                                                         | `JobLifecycleState` has `accepted/queued/running/blocked/paused/completed/failed/cancelled`                    |
| §7.4 Cancellation                       | `job.cancel { reason }`                                  | Different envelope                                            | `Cancel(target=JOB, target_id, reason, deadline_ms)` is generic-target                                         |
| §8.1 `job.event` envelope               | Single type, `{kind, ts, body}` payload                  | Missing                                                       | Events are individual top-level types                                                                          |
| §8.2 Eight reserved kinds               | `log/thought/tool_call/tool_result/status/metric/artifact_ref/delegate` | Partial — names only        | `Log`, `Metric`, `ToolInvoke/Result/Error` exist but as top-level message types not as `job.event` body shapes |
| §8.3 Session-scoped `event_seq`         | Monotonic, gap-free, replay rebases                      | Missing                                                       | No event_seq exists                                                                                              |
| §9 Leases                               | Static lease at `job.accepted`; glob patterns            | Wrong model                                                   | Permission/lease challenge model in `messages/Permissions.kt`                                                  |
| §10 Delegation                          | `kind: delegate` event with `delegate_id/agent/...`      | Missing                                                       | `AgentDelegate` is a top-level message, not a `job.event` body                                                  |
| §11 W3C trace context                   | `trace_id` 32-hex                                        | Present, looser                                               | `trace/TraceContext.kt`; envelope `trace_id` field                                                              |
| §12 Error taxonomy (12 codes)           | Exact set                                                | Wrong taxonomy                                                | `ErrorCode.kt` is gRPC-style + custom; doesn't match                                                            |

**v1.0 conformance score: 0 of 12 normative sections met.** The
non-trivial reuse is the trace context module, the `MemoryTransport`,
and the SQLite append-only log idea (the schema is wrong but the
storage pattern transfers).

## E. v1.1 gap matrix

For each v1.1 addition, status given the v1.0 floor is also missing.
"Missing v1.0 floor" means the feature can't even be planned until the
v1.0 surface is rewritten.

| §        | Feature                            | Status               | Target package (post-Phase 4)                                                  | Risk | Kotlin-specific reason                                                                                                                                                  |
| -------- | ---------------------------------- | -------------------- | ------------------------------------------------------------------------------ | ---- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 6.2      | `features` array + intersection    | Missing v1.0 floor   | `dev.arcp.core.session.Capabilities`                                            | M    | Polymorphic `agents` (string vs object) needs `JsonContentPolymorphicSerializer`; bare `kotlinx.serialization` cannot infer a discriminator that varies per element shape. |
| 6.2      | Rich `agents` inventory             | Missing v1.0 floor   | `dev.arcp.core.session.AgentDescriptor`                                         | M    | Same serializer issue.                                                                                                                                                  |
| 6.4      | `session.ping`/`pong` + interval    | Different mechanism  | `dev.arcp.runtime.session.Heartbeat`                                            | H    | The session-level heartbeat needs a coroutine scheduled task whose cancellation cooperates with session close; a naive `while (isActive) { delay(interval); ... }` will leak if the loop sits in `delay` when `Job` is cancelled (delay is cancellable so cancellation works — but the *ping-then-await-pong* pair needs `withTimeoutOrNull` to avoid starving structured cancellation). |
| 6.5      | `session.ack`                       | Missing v1.0 floor   | `dev.arcp.core.messages.SessionAck`; client coalesce in `dev.arcp.client.AutoAck` | M    | Coalescing needs a `MutableStateFlow<Long>` + a debounced collector; doing it with a `Channel` is the easy wrong answer (loses the latest-wins property).                |
| 6.6      | `session.list_jobs`/`jobs`          | Missing v1.0 floor   | `dev.arcp.runtime.session.JobInventory`                                         | M    | Suspend function returning a `JobsPage` data class with a `nextCursor: String?`; `Flow` is the wrong shape (paging is request/response, not streaming).                  |
| 7.5      | Agent versioning                    | Missing v1.0 floor   | `dev.arcp.core.agent.AgentRef`                                                  | L    | Parse via a single regex + small data class; `name@version` round-trip is mechanical. Registry keyed by `Pair<String, String?>`.                                       |
| 7.6      | Job subscribe/unsubscribe           | Different model      | `dev.arcp.runtime.subscription.JobSubscriptionRegistry`                         | H    | Multiple subscribers per job + each gets its own session-scoped `event_seq` rebase; the fan-out wants `MutableSharedFlow(replay=0, extraBufferCapacity=N, BufferOverflow.SUSPEND)` per subscriber. Cancel authority must not transfer — enforce at the cancel handler, not the message router. |
| 8.2.1    | `progress` event                    | Missing v1.0 floor   | `dev.arcp.core.events.ProgressBody`                                             | L    | One `data class`; pure additive.                                                                                                                                       |
| 8.4      | `result_chunk` + streamed `result`  | Missing v1.0 floor   | `dev.arcp.runtime.job.ResultStream`                                             | H    | Public client surface should be `Flow<ResultChunk>` collected into `ByteArray`/`String`; server side wants a `suspend fun writeChunk()` that increments `chunk_seq` server-internally so the agent can't desync the counter. Chunk-size cap enforced in the writer. |
| 9.5      | `lease_constraints.expires_at`      | Missing v1.0 floor   | `dev.arcp.runtime.lease.LeaseGuard`                                             | H    | Lease check on every authority-bearing op MUST consult a monotonic clock; using `kotlinx.datetime.Clock.System.now()` is wall-clock and can step. Use `System.nanoTime()` for the interval check and store `expiresAt` as the wall-clock value (for serialization) plus a `nanoDeadline`. |
| 9.6      | `cost.budget` counters              | Missing v1.0 floor   | `dev.arcp.runtime.lease.BudgetMeter`                                            | H    | Per-currency `AtomicReference<BigDecimal>` (NOT `Double` — `0.1 + 0.2` is the classic budget-leak); decrement must be CAS-loop, not naive read-then-write. Reject negatives in the metric interceptor. |
| 11       | `arcp.lease.expires_at`/`.budget.*` | Missing OTel adapter | `dev.arcp.middleware.otel`                                                      | L    | Two new attribute names; only added when the spans are produced.                                                                                                       |
| 12       | Three new error codes               | Missing v1.0 floor   | `dev.arcp.core.error.ArcpException`                                             | L    | Three subclasses; all `retryable = false`.                                                                                                                             |

Risk legend: L = mechanical; M = needs care but no novel design; H =
needs explicit design decisions in Phase 4 (concurrency boundary,
clock source, or thread-safety semantics).

## F. What survives the rewrite

Concrete reuse (where Phase 4 should keep, not delete):

| Asset                                                                            | Verdict                                                                                                                                                                                                |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Top-level `build.gradle.kts` ktlint/detekt/kover/dokka wiring                    | Keep. Idiomatic Kotlin DSL multi-module scaffold; only the module list changes in Phase 4.                                                                                                              |
| `:lib/build.gradle.kts` `explicitApi()` + `allWarningsAsErrors`                  | Keep. Mandatory for a published library.                                                                                                                                                               |
| Toolchain JDK 21, target floor 17                                                | Keep, but set target 17 on `:lib` explicitly (currently inherits 21).                                                                                                                                  |
| `json/Json.kt` (existence; not shown above but implied by `arcpJson`)            | Keep the pattern; rewrite the policy (`ignoreUnknownKeys = true`, `encodeDefaults = false`, `classDiscriminator = "type"`) to match §5.1.                                                              |
| `transport/Transport.kt` interface (send/receive/close)                          | Keep the shape; receive returning `Flow<Envelope>` is idiomatic.                                                                                                                                       |
| `transport/MemoryTransport.kt`                                                   | Keep; rename to test-only namespace if Phase 4 splits packages.                                                                                                                                        |
| `auth/BearerAuth.kt`, `JwtAuth.kt`                                               | Keep BearerAuth — v1.1 §6.1 retains bearer-only. JwtAuth is v1.1 unspecified scope (only `scheme: bearer`); demote to an adapter or drop.                                                              |
| `store/EventLog.kt` + `schema.sql`                                               | Reuse the SQLite scaffold; redesign the table to be `(session_id, event_seq)` keyed instead of `(session_id, message_id)`. The `lookupIdempotent`/`recordIdempotent` pair maps to v1.0 §7.2 logical idempotency. |
| `ids/Ids.kt` (ULID/UUIDv7 helpers)                                               | Keep; v1.1 §5.1 keeps the `id` ULID/UUIDv7 requirement.                                                                                                                                                |
| `trace/TraceContext.kt`                                                          | Keep; §11 is unchanged in v1.1.                                                                                                                                                                        |
| `extensions/ExtensionRegistry.kt`                                                | Demote/redo: v1.0 §15/v1.1 §15 reserve `x-vendor.*` for vendor envelope types/event kinds. The registry shape in this SDK is broader than the spec calls for; trim.                                    |
| 14 sample directories                                                            | Discard. None of the wire surfaces match v1.1. Phase 6 will define a fresh `samples/` set mirroring TypeScript's 18 examples (9 v1.0 + 9 v1.1, of which TypeScript has 18 mapped under `examples/`). The Kotlin samples currently include scenarios v1.1 explicitly defers (handoff, human_input, reasoning_streams, checkpoints, lease_revocation) — those MUST be cut. |
| `tests/HarnessFixture.kt` + `HandshakeTest.kt`                                   | Discard. The harness is keyed to the old handshake.                                                                                                                                                    |
| `cli/Main.kt`                                                                    | Keep as a placeholder; CLI surface needs rebuilding once jobs work.                                                                                                                                    |

## G. Out-of-spec scope in the current SDK (must be cut)

Features the present codebase implements that v1.1 either doesn't
mention or explicitly defers. Removing these is part of v1.1
conformance because their wire types collide with reserved namespaces
or set incorrect expectations:

- `workflow.start` / `workflow.complete` — no analog in v1.1.
- `agent.delegate` / `agent.handoff` as top-level envelopes — v1.1
  delegation is a `job.event` `kind: delegate` body; handoff isn't in
  v1.1 (spec "Not in v1.1" defers federation).
- `human.input.request` / `.response` / `.choice.*` / `.cancelled` — v1.1
  is silent on HITL; the spec explicitly leaves "how HITL is surfaced"
  out of scope (§1.2). Sample MUST be cut.
- `checkpoint.create` / `.restore`, `JobCheckpoint` — v1.1 has no
  checkpoint surface. Sample MUST be cut.
- `interrupt`, `Backpressure` (top-level), `permission.*`,
  `lease.refresh` / `.extended` / `.revoked` — v1.1 leases are static
  and non-renewable (§9.5 "Renewal is NOT supported").
- `stream.open` / `.chunk` / `.close` / `.error` — v1.1 uses
  `job.event` `kind: result_chunk` (§8.4) and other kinds for text
  streaming; standalone stream envelopes are not in the spec.
- `JobLifecycleState.{accepted,queued,blocked,paused}` — v1.1 states
  are `pending/running/success/error/cancelled/timed_out` (§7.3); the
  others MUST be cut from the public model. (Internal scheduler states
  are fine, but they MUST NOT leak to the wire.)
- `priority` envelope field — not in v1.1 envelope (§5.1).
- `event_type` on `event.emit`, `subscribe.backfill_complete` synthetic
  event — these don't exist in v1.1; the subscribe model is different.

## H. Gradle/tooling deltas to plan in Phase 3/4

- `:lib` currently bundles wire + runtime + client + storage + Ktor
  server/client + auth. Phase 4 will propose a split (likely
  `core` / `client` / `runtime` / `sdk` plus middleware modules,
  mirroring TS).
- `binary-compatibility-validator` is wired in `:lib` — preserve it
  across the split and run `apiDump` once the v1.1 surface lands.
- The `samples/build.gradle.kts` task table is the right shape but the
  list of names must be redone in Phase 6 to mirror v1.1 examples.
- `gradle.properties` has `org.gradle.configuration-cache=false` —
  enable it in Phase 3 once the build is otherwise stable; configuration
  cache catches accidental project-state mutations at task creation.

## I. Two questions Phase 3 needs an answer to

1. **Drop `json-schema-validator`?** v1.1 schemas are inline JSON in
   the spec, not a separate JSON Schema artifact. The library was
   added under the pre-v1.0 design. Phase 3 should drop it unless an
   external `lease_request` schema validator becomes needed.
2. **Ktor client AND OkHttp engine, or Ktor `CIO`?** The current `:lib`
   pulls `ktor-client-cio`. Phase 3 should defend the engine choice
   (CIO is the Kotlin-native answer for a non-Android JVM SDK and
   keeps the dependency set Kotlin-first; OkHttp brings a richer
   connection pool and HTTP/2 maturity at the cost of a large
   transitive set).
