# 10 — Synthesis

Inputs: phases 01–09 in this directory. This file is the executive
read; it does not restate the phase plans, it resolves contradictions
between them and orders the work.

## A. Executive summary

The current `:lib` is **not v1.0 conformant** (see
[`02-current-audit.md`](./02-current-audit.md) §A). The migration to
ARCP v1.1 is therefore a green-field implementation of the published
spec [`../spec/docs/draft-arcp-02.1.md`](../../../spec/docs/draft-arcp-02.1.md),
not an additive layering on a working v1.0.

The target shape:

- **JVM-only, no KMP.** Kotlin 2.0+ K2, JDK 17 target, JDK 21
  toolchain. [`02-current-audit.md`](./02-current-audit.md) §C;
  [`03-libraries.md`](./03-libraries.md) "Hard rules".
- **Eight published Gradle modules**, replacing today's monolithic
  `:lib`: `:core`, `:store`, `:client`, `:runtime`, `:sdk`,
  `:middleware:ktor-server`, `:middleware:spring-webflux`,
  `:middleware:otel`. Plus `:cli`, `:samples`, `:tests`, `:docs`
  unpublished. ([`04-architecture.md`](./04-architecture.md) §1,
  [`05-middleware.md`](./05-middleware.md) §§1–3,
  [`09-diagrams.md`](./09-diagrams.md) §3.)
- **`@Serializable sealed interface Message`** with `@SerialName`,
  envelope-level `type` discriminator handled by a custom two-pass
  codec, `JobEventBody` discriminator on nested `kind` via
  `@JsonClassDiscriminator("kind")`. Polymorphic v1.1 `agents` via
  `JsonContentPolymorphicSerializer`. ([`04-architecture.md`](./04-architecture.md) §2,
  [`03-libraries.md`](./03-libraries.md) §1.)
- **Structured concurrency**: `SupervisorJob` per session,
  `coroutineScope` per job, `MutableSharedFlow(replay = 0,
  extraBufferCapacity = 256, SUSPEND)` for subscriber fan-out,
  `delay`-loop heartbeat using `System.nanoTime()` for the silence
  check. No `runBlocking` in library code. ([`03-libraries.md`](./03-libraries.md) §4,
  [`04-architecture.md`](./04-architecture.md) §3.)
- **15-code error taxonomy** with `retryable` on the enum constant;
  three new v1.1 codes (`AGENT_VERSION_NOT_AVAILABLE`,
  `LEASE_EXPIRED`, `BUDGET_EXHAUSTED`). All three are non-retryable.
  ([`01-spec-delta.md`](./01-spec-delta.md) §B,
  [`04-architecture.md`](./04-architecture.md) §4.)
- **`BigDecimal`-backed `cost.budget` counters in
  `AtomicReference<BigDecimal>` CAS loop.** `Double` is forbidden for
  any money math. ([`02-current-audit.md`](./02-current-audit.md) §E,
  [`04-architecture.md`](./04-architecture.md) §5.4.)
- **Tests on Kotest `FunSpec`** (with `BehaviorSpec` reserved for §6
  and §7.3 state machines), `kotlinx-coroutines-test` virtual clock,
  Turbine for `Flow`, fakes over `mockk`, Kover floor 87 % lines AND
  branches. ([`07-tests.md`](./07-tests.md) §§1, 5.)
- **Three host adapters**: Ktor server (collapses TS `node`/`express`/
  `hono`), Spring WebFlux, OTel. Vert.x, Http4k, raw Servlet, Bun
  rejected. ([`05-middleware.md`](./05-middleware.md) §§1–6.)
- **22 sample directories** mirroring the TS examples; 9 v1.0 core, 9
  v1.1 features, 2 host integrations (`tracing`, `ktor`). `fastify`
  and `bun` dropped. ([`06-examples.md`](./06-examples.md) §2.) Nine
  current sample directories MUST be deleted (handoff, human_input,
  reasoning_streams, permission_challenge, lease_revocation,
  checkpoint, extensions, mcp, capability_negotiation — out of v1.1
  scope, [`02-current-audit.md`](./02-current-audit.md) §G).
- **One canonical Graphviz stylesheet** reused from
  `typescript-sdk/diagrams/`. Six diagrams, paired light/dark, single
  `:docs:diagrams` Gradle task, `.svg` committed.
  ([`09-diagrams.md`](./09-diagrams.md) §§1–3.)

## B. Contradictions between phases — resolved

Phases 3–9 were authored in parallel and a few cells disagree. These
are the resolutions; treat this section as the source of truth for
implementation.

| # | Disagreement                                                                                                                                                                               | Resolution                                                                                                                                                                       |
| - | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | Phase 4 names the Ktor module `:transport-ktor` / `arcp-transport-ktor`. Phase 5 calls it `:middleware:ktor-server` / `arcp-ktor-server`. Phase 8 and Phase 9 use the Phase 5 spelling.    | **Phase 5/8/9 win.** Module: `:middleware:ktor-server`. Coordinates: `io.arcp:arcp-ktor-server`. Justification: it ships *both* the runtime-side WS upgrade plug-in AND the client-side WS transport (one Ktor dependency set); "middleware" is the right umbrella because per-host adapters live there, and `:middleware:ktor-server` reads correctly next to `:middleware:spring-webflux` and `:middleware:otel`. |
| 2 | Phase 4 picks Ktor `CIO` as the client engine; Phase 3 picks `OkHttp`.                                                                                                                      | **Phase 3 wins.** OkHttp engine on the client; Netty engine on the server. Phase 3 owns the dependency decision and argued the choice on `ConnectionPool` ergonomics and HTTP/2 maturity (which `:middleware:ktor-server` consumers will exercise). Phase 4's "CIO" was an assumed default before Phase 3 landed. |
| 3 | Phase 4 uses package prefix `io.arcp.*`; Phase 7 sometimes writes `dev.arcp.*` test FQNs. The current SDK is on `dev.arcp.*` (`build.gradle.kts:12`).                                       | **Migrate the group + packages to `io.arcp` as part of M0.** Sole reason: `io.arcp` is what the spec-aligned, consumer-facing coordinates use (Phase 4 §1, Phase 8 §4). Phase 7 test FQNs are renamed in the same milestone — they are net-new tests anyway (Phase 7 §0 discards the existing two test files). |
| 4 | Phase 4 says `:middleware-otel` (single dash). Phase 5 + 8 + 9 say `:middleware:otel` (Gradle colon path).                                                                                  | **Colon path wins.** `:middleware:otel`, `:middleware:spring-webflux`, `:middleware:ktor-server`. The colon path is Gradle's hierarchical convention and matches the directory layout `middleware/{ktor-server,spring-webflux,otel}/`.                                              |
| 5 | Sample task names: Phase 5 uses `runKtorServer`, `runSpringWebflux`, `runOtel`. Phase 6 uses `samples:ktor`, `samples:tracing`.                                                             | **Phase 6 wins.** All sample tasks are `samples:<lowerCamel>` (`samples:ktor`, `samples:tracing`, etc.); the `runX` prefix on the current SDK is dropped. The Ktor host-integration sample is `samples:ktor`; the OTel sample is `samples:tracing`; there is no separate `samples:otel`. Spring is not a sample in Phase 6's list. |
| 6 | Phase 7 §5.1 lists `:middleware:ktor` and `:middleware:spring` at 80 % coverage. Phase 5 names them `:middleware:ktor-server` and `:middleware:spring-webflux`.                              | **Phase 5 names win.** Update Phase 7's Kover floors table accordingly: `:middleware:ktor-server` 80 %, `:middleware:spring-webflux` 80 %, `:middleware:otel` 80 %.                                                                                                                |
| 7 | Phase 8 names guide pages `heartbeats.md` and `ack.md` separately. Phase 9 embeds the heartbeat-ack flow diagram in `docs/guides/heartbeats-and-ack.md`.                                    | **Phase 8 wins on page split.** The diagram embeds in `docs/guides/heartbeats.md` (the page that already covers §6.4 and one paragraph cross-link to `docs/guides/ack.md`).                                                                                                       |
| 8 | Phase 8 names the §8.4 page `result-streaming.md`; Phase 9 references `docs/guides/streaming-results.md`.                                                                                   | **Phase 8 wins.** `docs/guides/result-streaming.md`. Phase 9's embed table updates to match.                                                                                                                                                                                       |
| 9 | Phase 4 sketches `JobHandle.events: Flow<JobEventBody>`. Phase 4's own §6 "Open question 2" flags this. Phase 7 layer-6 tests assert against typed event variants.                          | **`Flow<JobEvent>`** at the public surface (where `JobEvent` is a sealed class wrapping `kind` + `ts` + typed `body`), not `Flow<JobEventBody>`. The `ts` and event-source `event_seq` ARE meaningful to client code and would otherwise be discarded. Update Phase 4 §5.1 sketch to use `Flow<JobEvent>`. |
| 10 | Phase 9 §1.1 says `subprojects.dot` has no spec §; Phase 8 §4.8 expects the same diagram in the README ASCII fallback. Phase 9 §3 says README ASCII is NOT in the Graphviz pipeline.        | **Both stand.** ASCII fallback in `README.md` is hand-written (per Phase 9 §3), the Graphviz `subprojects.svg` is embedded in `docs/index.md` via `<picture>` (per Phase 9 §4.2). The README does not embed the SVG.                                                              |
| 11 | Module coordinate group: current `build.gradle.kts:12` is `dev.arcp`; Phase 4 and Phase 8 use `io.arcp`.                                                                                    | **Change to `io.arcp` in M0.** Reserve the group on Sonatype OSSRH at the start of M0. The current `dev.arcp` releases (v0.1.0) are not consumer-facing and can be left orphaned.                                                                                                  |
| 12 | Phase 2 §H says `gradle.properties` has `configuration-cache=false`; Phase 3 §10 says enable it once the module split lands.                                                                | **Enable in M2** (after the split lands, before M3 starts).                                                                                                                                                                                                                       |

## C. PR-sized milestones, ordered

Each milestone is one PR (or a small stack). Each names the files
touched (using the post-split paths) and the spec §s exercised. The
sequence guarantees `:check` is green at every milestone boundary —
no half-built modules.

### M0 — Repo scaffold and rename (no protocol change)

Goal: get the new Gradle layout green with the OLD wire surface still
working, so subsequent milestones replace one subsystem at a time.

- Reserve the `io.arcp` group on Sonatype.
- `settings.gradle.kts`: add `:core`, `:store`, `:client`,
  `:runtime`, `:sdk`, `:middleware:ktor-server`,
  `:middleware:spring-webflux`, `:middleware:otel`, `:docs`. Keep
  `:lib`, `:cli`, `:samples`, `:tests` for the transition.
- `gradle/libs.versions.toml`: bump per [`03-libraries.md`](./03-libraries.md)
  (Kotlin 2.0+, Ktor 3.5.0, Kotest 6.0.7, Kover 0.9.8, ktlint 14.2.0,
  detekt 1.23.8, sqlite-jdbc 3.53.1.0). Remove `nimbus-jose-jwt`,
  `json-schema-validator`. Add `ktor-client-okhttp`, `opentelemetry-api`,
  `turbine-jvm`, `mockk-jvm` (per-module scope).
- Move `Ids.kt`, `TraceContext.kt`, `MemoryTransport`, `Json.kt`,
  `EventLog` scaffold (NOT the schema), and `BearerAuth.kt` into
  `:core` (and `:store` where applicable). `JwtAuth.kt` deleted
  ([`02-current-audit.md`](./02-current-audit.md) §F).
- Migrate `group = "dev.arcp"` → `group = "io.arcp"` in
  `build.gradle.kts:12` and the package roots (mass rename of
  `dev.arcp.*` → `io.arcp.*` under the files moved above).
- Keep `:lib` compiling against its pre-v1.0 message catalog so the
  14 existing samples and the `HandshakeTest` still pass. They get
  deleted in M3, not here.

Files: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`,
`{core,store,client,runtime,sdk,middleware/*}/build.gradle.kts`,
`core/src/main/kotlin/io/arcp/core/**`, `store/src/main/kotlin/io/arcp/store/**`.

Spec §: none directly — pure scaffolding.

### M1 — Envelope + Message catalog (`:core`)

- `Envelope` data class with the v1.1 §5.1 field set;
  `init { require(arcp == "1") }`; `event_seq: Long`.
- `EnvelopeCodec` (two-pass custom serializer per
  [`04-architecture.md`](./04-architecture.md) §2.2).
- `ArcpJson` singleton with the §5.1 policy.
- Sealed `Message` hierarchy: every v1.1 envelope type
  (`SessionHello`/`SessionWelcome`/`SessionBye`/`SessionError`/`SessionPing`/
  `SessionPong`/`SessionAck`/`SessionListJobs`/`SessionJobs`,
  `JobSubmit`/`JobAccepted`/`JobEvent`/`JobResult`/`JobError`/`JobCancel`/
  `JobSubscribe`/`JobSubscribed`/`JobUnsubscribe`).
- Sealed `JobEventBody` with all 10 v1.1 body kinds.
- `JsonContentPolymorphicSerializer` for `AgentInventory`.
- `ErrorCode` enum (15 codes) and `ArcpException` sealed hierarchy.
- Layer-1 and Layer-2 tests from [`07-tests.md`](./07-tests.md) §2.
- `:core:apiDump` generated and checked in.

Files: `core/src/main/kotlin/io/arcp/core/{envelope,messages,json,error,ids,trace}/**`,
`core/src/test/kotlin/io/arcp/core/**`, `core/api/core.api`.

Spec §: §5.1 envelope; §6 message names; §7 message names; §8.1
event envelope; §8.2 reserved kinds; §8.2.1 `progress` body; §8.4
`result_chunk` body; §12 error taxonomy.

### M2 — Transport interface + `:store` `EventLog`

- `Transport` interface in `:core` with `send`/`receive: Flow<Envelope>`/`close`.
- `MemoryTransport` from the current SDK, moved + renamed package.
- New SQLite schema for `:store` keyed on `(session_id, event_seq)`
  per [`02-current-audit.md`](./02-current-audit.md) §F (the existing
  schema is `(session_id, message_id)`; that's the wrong invariant
  for §6.3 resume). Append-only.
- Idempotency table for §7.2 (carried over from the current SDK's
  `arcp_idempotency` table).
- Enable `org.gradle.configuration-cache=true` in
  `gradle.properties` (resolves Contradiction #12).

Files: `core/src/main/kotlin/io/arcp/core/transport/**`,
`store/src/main/kotlin/io/arcp/store/**`,
`store/src/main/resources/io/arcp/store/schema.sql`,
`gradle.properties`.

Spec §: §6.3 resume mechanics; §7.2 idempotency; §8.3 sequence numbers.

### M3 — Client `:client` and runtime `:runtime` (v1.0 floor)

This is the biggest PR; it can split into M3a (client) and M3b
(runtime) if review demands. The two halves are useless without each
other so they ship together.

- `ArcpClient.connect(transport, hello): Session` and `Session` public
  surface from [`04-architecture.md`](./04-architecture.md) §5.1
  *minus* the v1.1-only methods (`subscribe`, `listJobs`, `ack`).
- `JobHandle` with `events: Flow<JobEvent>`, `await()`, `cancel()`
  (no `collectChunks` yet — that lands in M5).
- `ArcpServer` from [`04-architecture.md`](./04-architecture.md) §5.2
  *minus* `registerAgentVersion`/`setDefaultAgentVersion` (those land
  in M4).
- `JobManager`, `SessionContext`, `LeaseGuard` (no `expires_at` yet),
  `BudgetMeter` shell (no enforcement yet).
- Two-message handshake (`session.hello`/`session.welcome`), bye,
  resume, cancel.
- Heartbeat coroutine (the wire is v1.1-only but the *loop* is the
  same as v1.0 would have looked like; only triggered when
  `heartbeat` feature is negotiated — which it won't be yet because
  the feature negotiation gate lands in M4).
- **Delete `:lib` and the 14 current sample directories.** Phase 2
  §F survivors are now homed in `:core`. Phase 6 §5 lists the 9 to
  cut outright; the other 5 (heartbeat, cancellation, subscriptions,
  resumability, leases) are replaced by M3+M4 samples.
- Layer-3, Layer-4, Layer-5 (v1.0 subset), Layer-6 (v1.0 subset)
  tests.

Files: `client/src/main/kotlin/io/arcp/client/**`,
`runtime/src/main/kotlin/io/arcp/runtime/**`,
`{client,runtime}/src/test/kotlin/io/arcp/**`,
`{client,runtime}/api/*.api`, `samples/` (delete 14 directories, add
`submit-and-stream/`, `cancel/`, `resume/`, `idempotent-retry/`,
`lease-violation/`, `custom-auth/`, `vendor-extensions/`).

Spec §: §6.1, §6.2, §6.3, §6.7, §7.1, §7.2, §7.3, §7.4, §8.1, §8.2,
§8.3, §9.1, §9.2, §9.3, §9.4, §11, §12 (v1.0 codes), §14.

### M4 — Capability negotiation + v1.1 session features

- Feature-flag enum and `intersectFeatures(a, b)` helper.
- Session-level `negotiatedFeatures: Set<Feature>` and `hasFeature(name)`.
- `session.ping`/`session.pong` wire, gated by `heartbeat`.
- `session.ack` wire, client `autoAck` coalescer
  (`MutableStateFlow<Long>` debounced collector — Phase 2 §E
  guidance), runtime back-pressure status emit (§6.5 threshold default
  1000).
- `session.list_jobs`/`session.jobs` with paging cursor; runtime
  scopes by authenticated principal; default `jobAuthorizationPolicy`.
- Agent versioning: `AgentRef.parse`/`format`, agent registry keyed
  by `(name, version?)`, `setDefaultAgentVersion`, runtime emits
  `AGENT_VERSION_NOT_AVAILABLE` per §7.5.
- Layer-3 (Ack/List/Version), Layer-6 (v1.1 session features) tests.

Files: `core/src/main/kotlin/io/arcp/core/{messages,agent}/**`,
`client/src/main/kotlin/io/arcp/client/**`,
`runtime/src/main/kotlin/io/arcp/runtime/**`, `samples/heartbeat/`,
`samples/ack-backpressure/`, `samples/list-jobs/`,
`samples/agent-versions/`.

Spec §: §6.2 features, §6.4 heartbeats, §6.5 ack, §6.6 list_jobs,
§7.5 agent versioning, §12 `AGENT_VERSION_NOT_AVAILABLE`.

### M5 — Job subscription + result streaming + progress

- `job.subscribe`/`job.subscribed`/`job.unsubscribe` envelope types.
- `JobSubscriptionRegistry` in `:runtime`: per-job `MutableSharedFlow(replay=0,
  extraBufferCapacity=256, BufferOverflow.SUSPEND)`; each subscriber
  has its own `event_seq` rebase.
- `ArcpClient.subscribe(jobId, history, fromEventSeq): JobHandle` —
  no cancel authority for subscribers
  ([`02-current-audit.md`](./02-current-audit.md) §E,
  [`04-architecture.md`](./04-architecture.md) §3.4).
- `ProgressBody` and `ResultChunkBody` body kinds (already declared
  in M1; this milestone adds the runtime emit + client receive
  surface).
- `JobContext.progress(current, total?, units?, message?)` helper.
- `JobContext.streamResult { writeChunk(...) }` writer + auto
  `chunk_seq` increment + terminal `more:false` + final `job.result
  { result_id, result_size }`. Enforce "MUST NOT mix inline +
  chunked" (§8.4).
- `JobHandle.collectChunks(): ByteArray` (or `Flow<ByteArray>`,
  TBD — see open question 3 below).
- Layer-3 `ResultChunkSpec`, `ProgressSpec`, Layer-6 `SubscribeIntegration`,
  `ProgressIntegration`, `ResultChunkIntegration`.

Files: `core/src/main/kotlin/io/arcp/core/messages/**`,
`runtime/src/main/kotlin/io/arcp/runtime/{job,subscription}/**`,
`client/src/main/kotlin/io/arcp/client/JobHandle.kt`,
`samples/{subscribe,progress,result-chunk}/`.

Spec §: §7.6 subscription, §8.2.1 progress, §8.4 result streaming.

### M6 — Lease constraints + budget enforcement

- `LeaseConstraints { expiresAt: Instant? }` on `job.submit` /
  `job.accepted`.
- `LeaseGuard.authorize(capability, target, now)` enforces
  `expires_at` against a wall-clock OR `nanoTimeSource` per Phase 2
  §E. Production code uses `System.nanoTime()` for the interval
  check; `expires_at` is wire-serialized as `Instant`.
- `BudgetMeter` enforced: per-currency `AtomicReference<BigDecimal>`
  CAS loop on `metric` events whose `name` starts with `cost.`;
  reject negatives; emit `BUDGET_EXHAUSTED` on the next authority op
  (§9.6 prefers `tool_result.body.error` form).
- Lease subsetting v1.1 additions: child `cost.budget` ≤ parent's
  *remaining*; child `expires_at` ≤ parent's (§9.4).
- New error codes wired: `LEASE_EXPIRED`, `BUDGET_EXHAUSTED`.
- Layer-5 `ExpiresAtSpec`, `BudgetMeterSpec`, `SubsetSpec`;
  Layer-6 `LeaseExpiresAtIntegration`, `CostBudgetIntegration`.

Files: `core/src/main/kotlin/io/arcp/core/messages/Lease.kt`,
`runtime/src/main/kotlin/io/arcp/runtime/lease/**`,
`samples/{lease-expires-at,cost-budget}/`.

Spec §: §9.4 (additions), §9.5 lease expiration, §9.6 budget,
§12 `LEASE_EXPIRED`/`BUDGET_EXHAUSTED`.

### M7 — Ktor server adapter + WebSocket transport

- `:middleware:ktor-server`: `Arcp` `ApplicationPlugin` +
  `Route.arcp(...)` extension; host-header / Origin defense; bearer
  pre-upgrade extraction (§14).
- WebSocket `Transport` implementation for client side (Ktor client
  with OkHttp engine).
- Stdio transport for §4.2 in-process child agents (the `stdio/`
  sample needs this).
- Layer-7 loopback tests, `@Tag("loopback")`, CI-only.

Files: `middleware/ktor-server/src/main/kotlin/io/arcp/middleware/ktor/**`,
`core/src/main/kotlin/io/arcp/core/transport/{websocket,stdio}/**`,
`samples/{stdio,ktor}/`, `tests/loopback/**`.

Spec §: §4.1 WebSocket, §4.2 stdio, §6.1 auth at the host edge,
§14 security.

### M8 — Spring WebFlux + OTel adapters

- `:middleware:spring-webflux`: `@Configuration ArcpAutoConfiguration`,
  `WebSocketHandler`, `SimpleUrlHandlerMapping` per
  [`05-middleware.md`](./05-middleware.md) §2.
- `:middleware:otel`: `Transport.withTracing(tracer)` extension; Ktor
  `ArcpOtel` plugin; W3C trace context extension key
  `x-vendor.opentelemetry.tracecontext`; attribute parity with TS
  including the two v1.1 attributes `arcp.lease.expires_at` and
  `arcp.budget.remaining` (§11).
- `samples/tracing/` end-to-end with a `ConsoleSpanExporter`.

Files: `middleware/spring-webflux/src/main/kotlin/io/arcp/middleware/spring/**`,
`middleware/otel/src/main/kotlin/io/arcp/middleware/otel/**`,
`samples/tracing/`.

Spec §: §11 trace propagation + new attributes, §14.

### M9 — Docs, diagrams, CLI rebuild, release

- `:docs:diagrams` Gradle task; six `.dot` files + light/dark SVGs.
- `docs/` tree per [`08-docs-readme.md`](./08-docs-readme.md) §1
  with frontmatter on every page.
- README rewrite per [`08-docs-readme.md`](./08-docs-readme.md) §4.
- Dokka multi-module aggregation; `dokkaHtmlMultiModule` wired to
  the docs site URL scheme.
- CLI (`:cli`) rebuilt with Clikt commands `submit`, `subscribe`,
  `list-jobs`, `cancel`, `version`. (Phase 6 §5 left the CLI as a
  stub; this milestone fills it.)
- `CONFORMANCE.md` rewritten with v1.1 file:line citations matching
  the TS shape; conformance harness (Layer-8) reads the table.
- `CHANGELOG.md` 1.1.0 release entry.
- Doctest task (Phase 7 deliverable) wired into `:check`.
- Cut release `1.1.0`.

Files: `docs/**`, `README.md`, `CHANGELOG.md`, `CONFORMANCE.md`,
`cli/src/main/kotlin/io/arcp/cli/**`, `tests/conformance/**`,
`docs/diagrams/**`.

Spec §: §13 examples, §15 IANA / extension namespace.

### Sequencing rationale

- **M0 → M1 → M2** is mechanical: scaffolding, the wire types, the
  storage primitive. No protocol decisions land here.
- **M3** is the inflection point: it deletes the pre-v1.0 surface
  and replaces it with the v1.0 surface, sample-for-sample. The 14
  current samples must be deleted before they bit-rot under the new
  message catalog.
- **M4–M6** add the three orthogonal v1.1 feature clusters: session
  features, job-stream features, lease features. They are
  independent enough that M5 and M6 could in principle ship in
  parallel — but reviewing them serially is cheaper.
- **M7–M8** are host bindings, after the wire surface is frozen.
- **M9** is documentation, CLI, release. Doc work that starts before
  M3 will need to be rewritten; starting after M8 means examples are
  stable.

## D. Risks (Kotlin-specific, named constructs)

| # | Risk                                                                                                                                       | Concrete construct at risk                                                                                                                                          | Mitigation                                                                                                                                                       |
| - | ------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | Custom `EnvelopeCodec` hand-decodes the two-pass shape; bugs are silent message-drops on the consumer side.                                | `JsonDecoder.decodeJsonElement` + manual `decodeFromJsonElement(Message.serializer(byType), payload)`.                                                              | Layer-1 round-trip property at 1000 iterations + Layer-2 table-driven name match + a fuzz test that injects unknown `type` strings.                              |
| 2 | Heartbeat falsely fires `HEARTBEAT_LOST` after a wall-clock step.                                                                          | `kotlinx-datetime`'s `Clock.System.now()` vs `System.nanoTime()`.                                                                                                   | Production `HeartbeatLoop` uses `System.nanoTime()` for the silence check; `expires_at` (wire serialization) uses `Instant`. Tests inject both clocks separately. |
| 3 | `MutableSharedFlow(replay=0, SUSPEND)` deadlocks if the same coroutine emits and collects (subscriber fan-out).                            | `JobBroadcaster.emit` running on the producing job's coroutine.                                                                                                     | Emit on a dedicated `supervisorScope`; never collect on the emitter's coroutine. Layer-3 test seeds a 2-subscriber + 1-emitter scenario and asserts no deadlock.   |
| 4 | `BigDecimal` budget debits race on a naïve `var counter`.                                                                                  | `AtomicReference<BigDecimal>.updateAndGet { it - amount }`.                                                                                                         | Layer-5 `BudgetMeterSpec` runs 8 coroutines × 1000 debits concurrently; final balance must equal `initial - sum`.                                                  |
| 5 | `JsonContentPolymorphicSerializer.selectDeserializer` mis-selects on `[]` (no element to inspect).                                          | Empty-array branch in `selectDeserializer`.                                                                                                                          | Explicit Layer-2 unit test for the empty case (selects `Rich` — safe default per [`04-architecture.md`](./04-architecture.md) §2.5).                              |
| 6 | `explicitApi()` strict rejects the existing `:lib` symbols on rename.                                                                      | `public` modifier required on every top-level declaration.                                                                                                          | M0 includes a `:lib`-only relaxation; M1 onwards turns `explicitApi()` strict on the new modules. The old `:lib` is deleted in M3 so the relaxation is short-lived. |
| 7 | Gradle configuration cache breaks against the `publishing { }` block.                                                                       | Mid-task project-state mutation.                                                                                                                                    | Enable CC in M2 after the split lands; if breakage, isolate publish tasks via `pluginManagement` rather than disabling CC repo-wide.                              |
| 8 | OkHttp 4 vs OkHttp 5 transitive skew on the consumer's classpath.                                                                          | Consumer pins `okhttp:5.x`; we pull via Ktor 3.5 → OkHttp 4.12 transitively.                                                                                        | Our code uses Ktor's engine abstraction, not OkHttp directly. Document the version range and ship a smoke test against both OkHttp 4.12 and 5.x on CI.            |
| 9 | The Ktor `webSocket { }` block's coroutine scope IS the WS session; returning from `arcpServer.accept(transport)` closes the WS prematurely. | `ArcpServer.accept(transport): Job` returning a `Job` to `join()` inside the block.                                                                                  | M7 design must make `accept` return a `Job`; the `webSocket` body calls `accept(tx).join()`. Layer-7 loopback test catches a premature-close regression.          |
| 10 | The OTel `Scope` from `span.makeCurrent()` is thread-confined; coroutine dispatch reassigns threads.                                       | `Span.makeCurrent()` paired with a `withContext` that switches dispatcher.                                                                                          | Use `Context.current().with(Span.wrap(...))` + manual `Context.with(...)` blocks; never call `makeCurrent()` across a suspension point. Documented in M8.        |
| 11 | Conformance harness's file:line citations rot when symbols are renamed.                                                                    | `ConformanceCitationSpec` reads `CONFORMANCE.md` and asserts each cited symbol exists.                                                                              | Layer-8 generates a `ConformanceCitations.kt` that references every cited symbol; rename drift fails compile, not test.                                          |
| 12 | Group-id migration `dev.arcp` → `io.arcp` strands the v0.1.0 release for any consumer who depended on it.                                  | Maven Central artifact coordinates are immutable.                                                                                                                   | The v0.1.0 surface was never consumer-grade (it's the pre-v1.0 wire; [`02-current-audit.md`](./02-current-audit.md) §A). Mark `dev.arcp:arcp:0.1.0` as deprecated in its README before release of `io.arcp:arcp-sdk:1.1.0`. |

## E. Non-goals (explicit)

Carried forward from spec "Not in v1.1" + this project's scoping:

- Job pause/unpause. (Spec.)
- Job priority and scheduling hints. (Spec.)
- Federation across runtimes. (Spec.)
- Streaming-token surface for LLM outputs. (Spec — those go through
  `kind: thought` / `kind: result_chunk`.)
- Kotlin Multiplatform. ([`02-current-audit.md`](./02-current-audit.md) §C.)
- Java interop niceties: no `@JvmStatic`/`@JvmOverloads`/`@file:JvmName`
  unless a real Java consumer is documented. ([`04-architecture.md`](./04-architecture.md) §6.)
- Vert.x, Http4k, raw Servlet adapters. ([`05-middleware.md`](./05-middleware.md) §§4–6.)
- Bun and Fastify samples — no JVM analog / fold into Ktor.
  ([`06-examples.md`](./06-examples.md) §2.)
- A Spring Boot starter — bean class only, no
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
  ([`05-middleware.md`](./05-middleware.md) §2.)
- Persistent idempotency store — in-memory map with 24-hour TTL only
  (matches TypeScript SDK posture).
- Sandboxed lease enforcement — agents call `validateLeaseOp`; the SDK
  does not run agents in a syscall sandbox.
- Renewal of `expires_at` leases. (Spec §9.5 "Renewal is NOT
  supported in v1.1".)
- Animated SVG, per-sample diagrams, runtime-internal call-graph
  diagrams. ([`09-diagrams.md`](./09-diagrams.md) §6.)

## F. Open questions

These were left explicitly open by the upstream phases or surfaced
during synthesis; they need answers before M5 or M9.

1. **`JobHandle.collectChunks(): ByteArray` vs `Flow<ByteArray>`.**
   Phase 4 §5.1 picks `ByteArray` (assembled). Phase 7 layer-6
   `ResultChunkIntegration` assumes assembly is done client-side. For
   a 31 MB result, materializing as a single `ByteArray` is 31 MB of
   heap; a `Flow<ByteArray>` lets the caller stream to a file. **My
   recommendation: ship `collectChunks(): Flow<ByteArray>` as the
   primitive and `awaitFullResult(): ByteArray` as the convenience
   that calls `collectChunks().toList().reduce(ByteArray::plus)`.**
   Decide in M5.

2. **`ArcpServer.close()` is synchronous (`AutoCloseable`); cleanup
   is suspend.** Phase 4 §"Open questions" flagged this. Options: (a)
   block in `close()` via `runBlocking { closeAsync() }`; (b) add
   `closeAsync(): Deferred<Unit>` and document that `close()` only
   does best-effort sync cleanup; (c) make `ArcpServer` not
   `AutoCloseable` and force consumers to call `closeAsync()`. **My
   recommendation: (b).** Decide in M3.

3. **`negotiatedFeatures` exposure on the server.** Phase 4 §"Open
   questions" flagged this. TS exposes both per-session and a
   server-wide advertised set. **My recommendation: match TS.**
   Decide in M4.

4. **Group-id rollback.** If `io.arcp` cannot be reserved on
   Sonatype, fall back to `dev.arcp.v1_1` (or similar). Decide
   before M0 closes.

5. **CLI scope at M9.** Minimum: `submit`, `subscribe`, `list-jobs`,
   `cancel`, `version`. Should it also include `agent register` for
   server-side test setups? **My recommendation: defer; the
   `:samples` set covers it.** Decide in M9.

## G. What good looks like at v1.1.0 cut

- `./gradlew :check` green on JDK 17 and JDK 21, on Ubuntu and macOS.
- `./gradlew :samples:check` runs 22 samples, exit 0 on every one,
  inside a 30-second per-sample timeout.
- `./gradlew :middleware:ktor-server:loopbackTest` green.
- Kover repo-wide ≥ 87 % lines AND branches; per-module floors met.
- `CONFORMANCE.md` rows resolve to live file:line references;
  Layer-8 conformance harness green.
- Public-API dumps (`*/api/*.api`) committed and unchanged for one
  consecutive PR (= API has stabilized).
- Banned-word pre-commit hook green on `planning/`, `*.md`,
  `src/**/*.kt`.
- A reader who opens `docs/index.md` sees the SVG architecture
  diagram, gets to the quickstart, copy-pastes the snippet, and the
  snippet compiles via the doctest task.
