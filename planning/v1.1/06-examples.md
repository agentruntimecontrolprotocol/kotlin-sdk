# 06 — Examples: TypeScript → Kotlin Sample Tree

## 1. The rule

The TypeScript reference under
[`../../../typescript-sdk/examples/`](../../../typescript-sdk/examples/)
defines the scope. Every Kotlin sample MUST: (a) compile against
`:samples` with no extra source sets, (b) run via Gradle
(`./gradlew :samples:<task>`), (c) exit 0 on success and non-zero on
assertion failure, (d) demonstrate exactly ONE spec feature named in
[`../../../typescript-sdk/CONFORMANCE.md`](../../../typescript-sdk/CONFORMANCE.md)
§13, and (e) ship a sibling `README.md` naming the spec § exercised
and the wire frames produced. No `Thread.sleep`, no mocks, no
shared fixtures across samples. The Kotlin set MIRRORS the TS set; we
do not invent samples the TS reference does not have.

## 2. Mapping table

22 rows. Drops `bun/` (no JVM equivalent). Drops `fastify/` (folded
into the Ktor adapter sample). Adds nothing.

### v1.0 core (9)

| TS example           | Spec §       | Kotlin path                                              | Files                                  | Gradle task                | Kotlin idiom (named construct)                                                            | Δ vs TS (3–5 words)            |
| -------------------- | ------------ | -------------------------------------------------------- | -------------------------------------- | -------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------ |
| `submit-and-stream/` | §13.1, §8.2  | `samples/src/main/kotlin/io/arcp/samples/submitstream/` | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:submitAndStream`  | `client.submit(...).events.collect { ... }` over `Flow<JobEvent>`                          | Flow + Turbine in test         |
| `delegate/`          | §13.2, §10   | `samples/src/main/kotlin/io/arcp/samples/delegate/`     | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:delegate`         | `JobContext.delegate(...)` suspends; child `Job` inherits `CoroutineContext` + `trace_id` | trace_id via `CoroutineContext.Element` |
| `resume/`            | §13.3, §6.3  | `samples/src/main/kotlin/io/arcp/samples/resume/`       | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:resume`           | Transport drops; `client.connect(resumeToken = ...)` replays via `Flow<JobEvent>`         | Two `ProcessBuilder` JVMs      |
| `idempotent-retry/`  | §13.5, §7.2  | `samples/src/main/kotlin/io/arcp/samples/idempotent/`   | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:idempotentRetry`  | Same `idempotencyKey` → identical `JobHandle.jobId`; second submit returns from cache     | `kotlin.test.assertEquals`     |
| `lease-violation/`   | §13.4, §9.3  | `samples/src/main/kotlin/io/arcp/samples/leaseviolation/` | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:leaseViolation`   | Agent calls `validateLeaseOp(...)`; surfaces `PERMISSION_DENIED` in `ToolResult.body`     | Catches `PermissionDeniedException` |
| `cancel/`            | §7.4         | `samples/src/main/kotlin/io/arcp/samples/cancel/`       | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:cancel`           | `JobHandle.cancel(reason)`; agent observes `currentCoroutineContext().job.isActive`        | Structured `Job.cancel()`      |
| `stdio/`             | §4.2         | `samples/src/main/kotlin/io/arcp/samples/stdio/`        | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:stdio`            | `StdioTransport` over `ProcessBuilder.start()`; `Process.inputStream` bridges            | `ProcessBuilder`, single task  |
| `vendor-extensions/` | §8.2, §9.2, §15 | `samples/src/main/kotlin/io/arcp/samples/vendorext/` | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:vendorExtensions` | Emits `x-vendor.acme.progress`; client switches on `JobEvent.kind` (String, not enum)     | Open-string `kind` discriminator |
| `custom-auth/`       | §6.1         | `samples/src/main/kotlin/io/arcp/samples/customauth/`   | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:customAuth`       | Custom `BearerVerifier` SAM; HMAC via `javax.crypto.Mac` injected at server build         | SAM lambda for verifier        |

### v1.1 features (9)

| TS example           | Spec §       | Kotlin path                                              | Files                                  | Gradle task              | Kotlin idiom (named construct)                                                                 | Δ vs TS (3–5 words)         |
| -------------------- | ------------ | -------------------------------------------------------- | -------------------------------------- | ------------------------ | ---------------------------------------------------------------------------------------------- | --------------------------- |
| `heartbeat/`         | §6.4         | `samples/src/main/kotlin/io/arcp/samples/heartbeat/`    | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:heartbeat`      | Scheduled coroutine in `SupervisorJob`; `delay(interval)` + `withTimeoutOrNull` on pong        | `Dispatchers.Default` timer |
| `ack-backpressure/`  | §6.5, §8.2   | `samples/src/main/kotlin/io/arcp/samples/ackbackpressure/` | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:ackBackpressure` | `MutableStateFlow<Long>` for `lastProcessedSeq`; debounced collector emits `session.ack`        | `StateFlow` latest-wins     |
| `list-jobs/`         | §6.6         | `samples/src/main/kotlin/io/arcp/samples/listjobs/`     | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:listJobs`       | `suspend fun listJobs(filter, limit, cursor): JobsPage`; cursor loop in `while (page.nextCursor != null)` | Suspend, not `Flow`         |
| `subscribe/`         | §7.6, §6.6   | `samples/src/main/kotlin/io/arcp/samples/subscribe/`    | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:subscribe`      | Per-subscriber cold `Flow<JobEvent>`; runtime fan-out via `MutableSharedFlow(replay = 0)`      | Two-JVM via `ProcessBuilder` |
| `agent-versions/`    | §7.5, §12    | `samples/src/main/kotlin/io/arcp/samples/agentversions/` | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:agentVersions`  | Registry keyed by `Pair<String, String?>`; `AgentRef.parse("greet@2.0.0")` data class          | Sealed `AgentRef` data class |
| `lease-expires-at/`  | §9.5, §12    | `samples/src/main/kotlin/io/arcp/samples/leaseexpiry/`  | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:leaseExpiresAt` | `Instant` deadline + `System.nanoTime()` monotonic check inside `validateLeaseOp`              | Monotonic clock, not wall   |
| `cost-budget/`       | §9.6, §12    | `samples/src/main/kotlin/io/arcp/samples/costbudget/`   | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:costBudget`     | Per-currency `AtomicReference<BigDecimal>` CAS-loop on `metric` interceptor                    | `BigDecimal`, no `Double`   |
| `progress/`          | §8.2.1       | `samples/src/main/kotlin/io/arcp/samples/progress/`     | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:progress`       | `JobContext.progress(current, total)`; client `events.filterIsInstance<JobEvent.Progress>()`   | Sealed event hierarchy      |
| `result-chunk/`      | §8.4         | `samples/src/main/kotlin/io/arcp/samples/resultchunk/`  | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:resultChunk`    | `JobHandle.collectChunks()` returns `Flow<ResultChunk>`; client calls `.toList()` then joins   | `Flow.toList()` reassembly  |

### Host integrations (4 in TS → 2 kept)

| TS example   | Spec § | Kotlin path                                              | Files                                  | Gradle task        | Kotlin idiom (named construct)                                                              | Δ vs TS (3–5 words)              |
| ------------ | ------ | -------------------------------------------------------- | -------------------------------------- | ------------------ | ------------------------------------------------------------------------------------------- | -------------------------------- |
| `tracing/`   | §11    | `samples/src/main/kotlin/io/arcp/samples/tracing/`      | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:tracing`  | OTel middleware module installs `Span` interceptor; `ConsoleSpanExporter` prints on shutdown | OTel JVM SDK, not Node OTel      |
| `express/`   | §4.1   | `samples/src/main/kotlin/io/arcp/samples/ktor/`         | `Server.kt`, `Client.kt`, `Main.kt`, `README.md` | `samples:ktor`     | One `embeddedServer(Netty)` with both `routing { get("/health") }` and `arcp("/arcp")`      | Ktor WS + routing on one port    |

Host-integration drops, with justification:

- `fastify/` — dropped. Fastify is a Node-only HTTP framework with no
  JVM analog; its distinguishing features (pino logger, per-request
  `req.id`) are subsumed by Ktor's `CallLogging` plugin and the OTel
  span attached in the `tracing/` sample. A second HTTP-framework
  sample would only repeat the wiring shown in `ktor/`.
- `bun/` — dropped entirely. Bun is a JavaScript runtime. No JVM
  equivalent; mirroring it in Kotlin would be a contradiction.

## 3. Common sample layout

Every sample directory has the same four-file shape. A reader who
opens any one of them knows what to expect:

```
samples/src/main/kotlin/io/arcp/samples/<name>/
├── Server.kt   // `fun buildServer(transport: Transport): ArcpServer` — runtime construction + agent registration; no `main`
├── Client.kt   // `suspend fun runClient(client: ArcpClient)` — submits/subscribes; ends with assertions
├── Main.kt     // `fun main()` — wires Server + Client in one JVM via MemoryTransport; calls `exitProcess(0)` or `exitProcess(1)`
└── README.md   // 1–2 paragraphs: what spec § this demonstrates, env vars (if any), wire frames printed
```

### Why MemoryTransport-in-one-JVM is the default

`MemoryTransport` is `:lib`'s test transport (Phase 2 §F keeps it).
It satisfies the SDK's `Transport` interface end-to-end without a
network. Choosing it as the default for samples is deliberate:

- No port allocation — CI parallelism cannot collide on `:8080`.
- No flake — no socket `accept` race, no TLS, no kernel buffer timing.
- No server-lifecycle plumbing — sample `main` returns when assertions
  pass; no shutdown hooks, no `signal` traps, no orphaned processes.
- The wire is real — `MemoryTransport` exchanges the same JSON
  `Envelope` frames over an in-process `Channel<String>` that
  `WebSocketTransport` exchanges over a socket. The samples exercise
  the same `Envelope` codec the WebSocket transport does.

### Three samples that must use two processes

| Sample        | Why MemoryTransport will not work                                                                                                                                   | Mechanism                                                                                                                                  |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `stdio/`      | The whole point of §4.2 is the parent/child stdin/stdout pipe; an in-process transport is the wrong demo.                                                            | `Main.kt` calls `ProcessBuilder(java, "-cp", classpath, "io.arcp.samples.stdio.ServerKt").start()`, then drives `StdioTransport` against the child's `InputStream`/`OutputStream`. Single Gradle task. |
| `resume/`     | The §6.3 demonstration is "drop the transport mid-stream and reconnect using the same `resume_token`". A `MemoryTransport.close()` followed by a second connection re-uses the same in-process server, but it cannot model network disconnect — the runtime's session still holds the buffered events because nothing severed.       | `Main.kt` forks the server into a child JVM via `ProcessBuilder`; client `WebSocketTransport` connects over loopback `wss://127.0.0.1:<ephemeral-port>`, then drops + reconnects. Ephemeral-port discovery via the server's first stdout line. |
| `subscribe/`  | §7.6 demonstrates TWO clients on the same principal — submitter + subscriber. A single-JVM main with one client cannot show cross-session interleaving.              | Same `ProcessBuilder` pattern as `resume/`: server forked; two `ArcpClient` instances opened against the same WS endpoint within `Main.kt`. The cross-session-cancel-denied assertion fires on the subscriber client. |

The fork mechanism is uniformly `ProcessBuilder` (not Gradle
multi-task chaining): keeps the sample self-contained, keeps the
exit-code contract `Main.kt`-owned, and lets the sample propagate
the child's stderr to the parent on assertion failure. Gradle task
chaining adds two failure modes (child cleanup, exit-code
propagation) that `ProcessBuilder` plus a `try { ... } finally {
proc.destroyForcibly() }` handles inline.

## 4. Gradle task wiring

Replace the current `sampleClasses` map in
[`samples/build.gradle.kts`](../../samples/build.gradle.kts) with the
following convention:

- Task name = `samples:<lowerCamel>` (Gradle subproject syntax;
  the old `runX` prefix goes away).
- Main class FQN = `io.arcp.samples.<dirname>.MainKt`.
- Each task: `group = "samples"` and
  `description = "Run sample <name> — demonstrates spec §X"`.
  `./gradlew :samples:tasks` becomes the authoritative index of what
  every sample covers (the description is the only place we duplicate
  the spec-§ tag outside this table — pick one source of truth and
  cite it from CI logs).
- Each task: `timeout.set(Duration.ofSeconds(30))`. Per §6 below.

The dirname-to-task-name mapping (the 22 rows above) lives in a single
`samplesIndex` map in `samples/build.gradle.kts`, registered in a
`forEach` block. No per-sample DSL boilerplate.

## 5. What we are NOT shipping

Phase 2 §G enumerates the wire surfaces the current SDK ships that
v1.1 does not. The corresponding samples MUST be deleted in Phase 6,
not ported:

- `samples/.../handoff` — §1.2 explicitly defers federation; no TS
  reference.
- `samples/.../human_input` — §1.2 explicitly leaves HITL out of v1.1
  scope; no TS reference.
- `samples/.../reasoning_streams` — v1.1 streams reasoning as
  `job.event { kind: thought }` (§8.2); standalone `stream.*`
  envelopes are out of spec. Folded into `submit-and-stream/`.
- `samples/.../permission_challenge` — §9.5 "Renewal is NOT
  supported"; v1.1 leases are static. Out of scope.
- `samples/.../lease_revocation` — same reason. Lease lifecycle is
  static-then-expired; no revoke channel.
- `samples/.../checkpoint` — no checkpoint surface in v1.1; no TS
  reference.
- `samples/.../extensions` — the current SDK's bespoke extension
  registry over-reaches §15. The `x-vendor.*` story is covered by
  `vendor-extensions/` as a v1.0 sample; that's all v1.1 calls for.
- `samples/.../mcp` — ARCP composes with MCP (per spec §1.1
  positioning) but does not ship it; no TS sample exists. Out of
  scope.
- `samples/.../capability_negotiation` — feature negotiation is the
  cross-cutting mechanism (§6.2). It does not get its own sample; it
  is *exercised by* every v1.1 sample's hello/welcome exchange. The
  client/runtime negotiation assertion lives in `:tests`, not
  `:samples`.

That cuts 9 out-of-spec sample directories from the current tree, in
exchange for 13 new ones (the 22 mirrored above minus the 9 v1.0 core
that have no current analog).

## 6. CI gate

Every task in the `samplesIndex` map is run in CI with a 30-second
timeout. Exit code 0 = pass; anything else = fail (`Main.kt` uses
`exitProcess(1)` for assertion failure, and `ProcessBuilder`-forked
children propagate non-zero exit upward). The CI job runs the
22 tasks in parallel where Gradle's worker pool allows. The matrix
choice (JDK 17 vs 21, OS variants) belongs to Phase 7; cross-link
when that file lands.

The minimum CI invocation:

```sh
./gradlew :samples:tasks --group=samples   # sanity: descriptions present
./gradlew $(./gradlew -q :samples:tasks --group=samples | awk '/^samples:/ {print ":samples:"$1}')
```

Phase 7 will replace the shell loop with a Gradle aggregator task
(`runAllSamples`) that depends on every entry in `samplesIndex`.
