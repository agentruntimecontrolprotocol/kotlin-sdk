# 07 — Test Strategy: kotlin-sdk for ARCP v1.1

Source spec: [`../../../spec/docs/draft-arcp-02.1.md`](../../../spec/docs/draft-arcp-02.1.md).
Audit baseline: [`02-current-audit.md`](./02-current-audit.md) — the existing
`tests/HandshakeTest.kt` + `HarnessFixture.kt` are discarded; the v1.1 SDK
is the first conformant implementation, so the test tree is built from
scratch against the post-Phase 4 module split (`:core`, `:client`,
`:runtime`, `:sdk`, `:middleware:*`).

Coverage floor: **87 % lines AND branches** measured by Kover.

---

## 1. Framework and posture

### 1.1 Kotest spec style — **`FunSpec`**

Decision: **`FunSpec`** everywhere except the layer-3 session/job state-machine
tests, which use **`BehaviorSpec`** (one `Given/When/Then` per transition row).

Argument:

- `FunSpec` is one block per assertion, which keeps the file scannable at the
  density a wire-protocol test suite needs (the `Envelope` round-trip alone
  produces ~40 cases). `BehaviorSpec`'s `given/when/then` nesting reads as
  prose for state machines but is noise for "decode this, expect that".
- The two state-machine layers (§6 session, §7.3 job lifecycle) are the only
  places where the narrative *is* the test — spec §6 prose is literally
  written in given/when/then language. Mirroring that one-for-one keeps the
  spec-to-test cross-reference legible.
- Mixing two styles is not free, but the cost is one paragraph in CONTRIBUTING
  vs. burying twenty round-trip assertions inside `When("decoding")`.

Coding rule: every spec class extends exactly one Kotest spec base; no
custom super-spec.

### 1.2 Property generators — `kotest-property`

Use `io.kotest:kotest-property` with custom `Arb<T>` instances for `Envelope`,
`Lease`, `AgentRef`, `BudgetAmount`. Shrinking is required (so `Arb.bind`, not
`Arb.create { gen() }`). Default sample count `PropertyTesting.defaultIterationCount = 200`;
the round-trip and monotonicity props bump to 1000 via `forAll(iterations = 1000) { … }`.

### 1.3 Coroutine tests — `kotlinx-coroutines-test`

- All `suspend` tests run inside `runTest { … }`. **No `runBlocking` in test
  code.** Lint rule (detekt) enforces.
- `Thread.sleep` is banned outside `samples/`; `delay(…)` is permitted only
  when invoked from a coroutine that the `TestScheduler` controls so virtual
  time advances. Real-time wall-clock waits exist only in the layer-7
  loopback WebSocket tests (CI-only, 30 s ceiling).
- Use `runTest(timeout = 5.seconds)` as the default — the `TestScope` virtual
  clock removes flake, but the wall-clock timeout catches deadlock.

### 1.4 Flow assertions — Turbine

Use `app.cash.turbine` for every `Flow` assertion:

```kotlin
flow.test {
    awaitItem() shouldBe …
    awaitComplete()
}
```

Why Turbine over `flow.toList()`:

1. `toList()` blocks until the flow terminates — many of our flows (event
   subscription, result chunks) terminate only on `unsubscribe()` or the
   final `more: false` chunk. Wrong-shape tests would hang.
2. Turbine's `expectNoEvents()` is the *only* way to assert "the flow did
   not drop an item" — `toList()` cannot distinguish drop vs. completion.
3. Turbine surfaces missing terminal events as test failures. `Flow.toList`
   on a never-terminating flow simply times out at the test framework
   level, with no signal about what was emitted.

### 1.5 Mocking — `mockk` is the last resort

**Default: hand-written fakes** that live next to the production class in
`:core:test-fakes` (and module equivalents). Concrete examples:

- `FakeClock` implementing `kotlinx.datetime.Clock` with a `var now: Instant`
  knob.
- `FakeTransport` — synchronous in-memory pipe for unit tests below the
  `MemoryTransport` layer.
- `FakeEventLog` — `MutableList<EnvelopeRecord>` with the same API contract
  as the SQLite-backed `EventLog`.
- `FakePrincipalStore`, `FakeAgentRegistry`.

`mockk` is reserved for one situation: a host-adapter test where constructing
a real `io.ktor.server.application.Application` or `org.springframework.web.reactive.socket.WebSocketSession`
would mean reimplementing the host. In `:middleware:ktor` and `:middleware:spring`
we accept `mockk` for the host context only; the ARCP types under test stay
real.

Rule, codified in `detekt.yml` via a custom config: import of `io.mockk` is
forbidden in `:core/src/test/**` and `:runtime/src/test/**`. Allowed in
`:middleware/*/src/test/**`.

---

## 2. Layered plan

Test pyramid: 1–5 are unit (≥70 % of total tests), 6 is integration on the
in-process transport (≥20 %), 7 is loopback over a real WebSocket (~5 %),
8 is conformance (~5 %).

### Layer 1 — Envelope (§5.1)

Module: `:core/src/test`.

| FQN | Coverage |
| --- | --- |
| `dev.arcp.core.envelope.EnvelopeRoundTripSpec` | `forAll(envelopeArb)` decode-encode-decode equality; assert byte-equal except for kotlinx JSON whitespace. |
| `dev.arcp.core.envelope.EnvelopeRejectionSpec` | Reject `arcp != "1"` with `InvalidRequestError` (§5.1). Reject malformed `id` (not ULID/UUIDv7). Reject envelopes missing required fields (`type`, `payload`). |
| `dev.arcp.core.envelope.UnknownFieldToleranceSpec` | Unknown top-level fields ignored on decode; round-trip preserves them via `JsonElement` passthrough container. |
| `dev.arcp.core.envelope.TraceIdValidationSpec` | `trace_id` MUST be 32 lowercase hex chars when present (§11); fuzz-reject anything else. |

### Layer 2 — Message catalog (§5, §6, §7, §8)

| FQN | Coverage |
| --- | --- |
| `dev.arcp.core.messages.WireTypeNamesSpec` | Table-driven: every `@SerialName` constant in the sealed `Message` hierarchy matches a row in `WireTypeTable.kt` (single source of truth). Adding a new message without adding a row is a build break. |
| `dev.arcp.core.messages.SessionMessagesSpec` | `session.hello/welcome/bye/ping/pong/ack/list_jobs/jobs/error` each round-trip and reject unknown discriminators. |
| `dev.arcp.core.messages.JobMessagesSpec` | `job.submit/accepted/event/result/error/cancel/subscribe/subscribed/unsubscribe` — same. |
| `dev.arcp.core.messages.EventBodySpec` | Eight v1.0 kinds (§8.2) + `progress` (§8.2.1) + `result_chunk` (§8.4) parse via `parseJobEventBody`; vendor `x-vendor.*` kinds preserved as opaque `JsonObject`; unknown un-prefixed kinds rejected. |
| `dev.arcp.core.messages.AgentRefSpec` | `parseAgentRef`: `name` and `name@version` accept; names match `[a-z0-9][a-z0-9._-]*`; versions match `[a-zA-Z0-9.+_-]+` (§7.5 BNF). `formatAgentRef` round-trips. |

### Layer 3 — Session state machine (§6) — `BehaviorSpec`

Module: `:runtime/src/test`.

| FQN | Coverage |
| --- | --- |
| `dev.arcp.runtime.session.HandshakeBehavior` | Given a fresh transport, when `session.hello` arrives, then `session.welcome` with resume_token (≥128 bits, §6.2) and `heartbeat_interval_sec` (§6.4) is emitted; capability intersection populates the session. |
| `dev.arcp.runtime.session.AuthFailureBehavior` | Missing/invalid bearer → `session.error` with `UNAUTHENTICATED`; transport closes (§6.1). |
| `dev.arcp.runtime.session.OutOfOrderBehavior` | A `job.submit` before `session.hello` → `INVALID_REQUEST`; a second `session.hello` on the same connection → `INVALID_REQUEST`. |
| `dev.arcp.runtime.session.ResumeBehavior` | Stale `resume_token` → `RESUME_WINDOW_EXPIRED` (§6.3); fresh token rotates per welcome; replay starts at `last_event_seq + 1`. |
| `dev.arcp.runtime.session.HeartbeatBehavior` | Drives `TestScheduler.advanceTimeBy(intervalMs)`; asserts `session.ping` emitted on idle, `session.pong` echoes nonce, two silent intervals → close with `HEARTBEAT_LOST` (§6.4). `session.ping`/`pong` MUST NOT increment `event_seq`. |
| `dev.arcp.runtime.session.AckBehavior` | `session.ack` with valid `last_processed_seq` recorded; client lag > `backPressureThreshold` (1000 default) emits a `status` event with `back_pressure: true` (§6.5). |

### Layer 4 — Job state machine (§7.3)

| FQN | Coverage |
| --- | --- |
| `dev.arcp.runtime.job.JobLifecycleSpec` | Valid transitions: `pending → running → success | error | cancelled | timed_out`. Each terminal state emits the spec-mandated envelope (`job.result` for success, `job.error{final_status}` otherwise). |
| `dev.arcp.runtime.job.IllegalTransitionSpec` | Internal API: calling `Job.markRunning()` twice or `Job.markSuccess()` from `pending` throws `IllegalStateException`. **Asserts the exception never crosses the wire** — the dispatcher catches it, emits `INTERNAL_ERROR`, and closes the job. |
| `dev.arcp.runtime.job.CancellationSpec` | `job.cancel` from owning session → `job.error{final_status:"cancelled"}` within 30 s grace; cancel from a subscriber → `PERMISSION_DENIED` (§7.6). |
| `dev.arcp.runtime.job.IdempotencySpec` | Same `idempotency_key` + same principal → same `job_id`. Same key, different `(agent, input)` → `DUPLICATE_KEY` (§7.2). Different principal, same key → fresh job. |
| `dev.arcp.runtime.job.ResultChunkSpec` | `streamResult { writeChunk … }` emits chunks with monotonic `chunk_seq`; terminal chunk has `more:false`; final `job.result` carries `result_id`, `result_size`. Attempting `emitResult(inline)` after a chunk emits → `IllegalStateException` (§8.4 "MUST NOT mix"). |
| `dev.arcp.runtime.job.ProgressSpec` | `progress(current = -1)` rejected; `progress` events are advisory and do not advance lifecycle (§8.2.1). |

### Layer 5 — Lease enforcement (§9)

| FQN | Coverage |
| --- | --- |
| `dev.arcp.runtime.lease.ReservedNamespaceSpec` | Every namespace in `RESERVED_CAPABILITY_NAMES` (§9.2: `fs.read`, `fs.write`, `net.fetch`, `tool.call`, `agent.delegate`, `cost.budget`) parses, validates, and enforces. Unknown un-prefixed name → `INVALID_REQUEST`. |
| `dev.arcp.runtime.lease.GlobMatchSpec` | `*` single-segment, `**` zero-plus segments; anchored; canonicalization of paths/URLs (§14) before match. |
| `dev.arcp.runtime.lease.ExpiresAtSpec` | `expires_at` in the past at submit → `INVALID_REQUEST` (§9.5); op at or after `expires_at` → `LEASE_EXPIRED`. Uses `FakeClock` + `TestScheduler` to advance virtual time across the deadline. **Internal interval check uses `System.nanoTime()` (audit risk-H mitigation); wall-clock `expires_at` is for the wire only.** |
| `dev.arcp.runtime.lease.BudgetMeterSpec` | Per-currency decrement on `metric` events whose `name` starts with `cost.`; negative values rejected (no decrement); counter ≤ 0 → `BUDGET_EXHAUSTED` on next op (§9.6). `AtomicReference<BigDecimal>` CAS loop verified by concurrent decrement of 1000 metrics from 8 coroutines (`launch { … }` on `Dispatchers.Default`) — final balance must equal `initial - sum`, never less. |
| `dev.arcp.runtime.lease.SubsetSpec` | `assertLeaseSubset(child, parent)` accepts proper subsets; rejects expansions; `cost.budget` child ≤ parent's *remaining* (§9.4); child `expires_at` ≤ parent's. Transitivity property: see §4 below. |

### Layer 6 — Integration over `MemoryTransport`

Module: `:sdk/src/test/integration`. Full client ↔ runtime in one process,
real `MemoryTransport` (the only Transport in the unit tier).

| FQN | Coverage |
| --- | --- |
| `dev.arcp.sdk.integration.SubmitAndStreamIntegration` | `submit → accepted → event(log, thought, tool_call, tool_result) → result`. |
| `dev.arcp.sdk.integration.DelegateIntegration` | Parent emits `delegate` event; runtime creates child job; child lease ⊆ parent. |
| `dev.arcp.sdk.integration.ResumeIntegration` | Disconnect mid-stream; reconnect with `resume_token` + `last_event_seq`; replay continues; new `resume_token` issued. |
| `dev.arcp.sdk.integration.HeartbeatIntegration` | End-to-end ping/pong over `runTest`'s virtual clock. |
| `dev.arcp.sdk.integration.AckBackpressureIntegration` | Client `autoAck` coalesces (32 events / 250 ms default); runtime emits `back_pressure` status when lag exceeds threshold. |
| `dev.arcp.sdk.integration.ListJobsIntegration` | Paging via `cursor`; same-principal scoping; filter by `status`, `agent`, `created_after/before` (§6.6). |
| `dev.arcp.sdk.integration.SubscribeIntegration` | Two subscribers + owning session; each sees the same events in *their own* `event_seq` rebase (§7.6); subscriber cancel → `PERMISSION_DENIED`. |
| `dev.arcp.sdk.integration.AgentVersionsIntegration` | Bare name → default; `name@version` exact match; unknown version → `AGENT_VERSION_NOT_AVAILABLE`. |
| `dev.arcp.sdk.integration.LeaseExpiresAtIntegration` | Mid-job lease elapse → `job.error{code:LEASE_EXPIRED, final_status:error}`. |
| `dev.arcp.sdk.integration.CostBudgetIntegration` | `metric { name: "cost.tokens", unit: "USD", value: N }` decrements; `BUDGET_EXHAUSTED` surfaced as `tool_result.body.error` first (§9.6 SHOULD), then on next authority op as `job.error`. |
| `dev.arcp.sdk.integration.ProgressIntegration` | Progress events flow through subscribers. |
| `dev.arcp.sdk.integration.ResultChunkIntegration` | `JobHandle.collectChunks()` assembles by `result_id`; final `job.result` carries `result_size`. |

### Layer 7 — Loopback over `WebSocketTransport`

Module: `:middleware:ktor/src/test/loopback`. Ktor `embeddedServer(CIO)` on
`localhost:0`; Ktor client `CIO` engine connecting via `ws://`. CI-only
(annotated `@Tag("loopback")`, excluded from local `:check` by default).

One test class per v1.1 feature, mirroring layer-6 names with the suffix
`LoopbackTest`. Each:

- Uses real network I/O (no `MemoryTransport`).
- Wall-clock `runBlocking(timeout = 30.seconds)` (the *only* place
  `runBlocking` is permitted; documented in CONTRIBUTING).
- Asserts the same observable behavior as the layer-6 sibling. If the
  layer-6 test passes and the loopback test fails, the failure is in the
  Ktor adapter, not the core/runtime.

### Layer 8 — Conformance harness

Module: `:tests/conformance`.

Kotlin `CONFORMANCE.md` will mirror the TypeScript file row-for-row, with
Kotlin `file:line` citations. A single parameterized spec
`dev.arcp.conformance.ConformanceCitationSpec` reads the Markdown table
(via a tiny resource-loaded parser, **not** runtime reflection), then for
each `(spec §, file, line, symbol)` row:

1. Asserts the file exists.
2. Asserts the cited line contains a non-comment, non-blank token matching
   `symbol` (regex `\b<symbol>\b`).
3. Asserts the symbol resolves at compile time (a generated
   `ConformanceCitations.kt` is part of the module and references every
   symbol — `git diff` on this file catches rename drift).

This is the same shape the TypeScript SDK uses to keep `CONFORMANCE.md`
honest against the implementation. It catches `file:line` rot at every
CI run.

---

## 3. Coroutine testing rules

1. **`runTest` only.** `runBlocking` is banned outside layer 7. Detekt
   custom rule `NoRunBlockingInTests`.
2. **Heartbeat (§6.4)**: drive with `testScope.advanceTimeBy(intervalMs)`.
   Assert the ping was sent by reading `FakeTransport.sent`. Never use
   `delay(intervalMs)` in the test body — only in the production code
   under test.
3. **Ack back-pressure (§6.5)**: build the runtime with
   `backPressureThreshold = 4`, feed 5 events with no ack via the test
   transport, then `advanceUntilIdle()`. Watch the runtime's
   `MutableSharedFlow<StatusEvent>.subscriptionCount` to confirm at least
   one subscriber (the per-session emitter) is collecting; assert the
   subsequent `back_pressure: true` status event is emitted exactly once
   per crossing of the threshold (deduped — re-emit only on `down → up`
   transition, mirroring TypeScript `emitBackPressureStatus`).
4. **`Flow` cancellation**: with Turbine, call `cancel()` to stop the
   collector. Then assert the producer's `Job.isCancelled == true` (the
   producing scope is captured in the test via a `CoroutineScope` field
   on the runtime fixture). Also assert `expectNoEvents()` after the
   cancel — no late items leak.
5. **Lease-expiry timer**: prefer a single `Job` per lease (`launch {
   delay(remaining); fireExpiry() }`); the test advances the scheduler
   past the deadline and asserts `job.error{LEASE_EXPIRED}` is on the
   wire. The production code's `System.nanoTime()` interval check is
   driven indirectly by the same `TestScheduler` because
   `kotlinx-coroutines-test` patches the dispatcher's time source —
   audit risk-H: the test must explicitly construct the runtime with
   `Clock = FakeClock` (wall) and `nanoTimeSource = testNanoTime` (interval).

---

## 4. Property-based tests

Five concrete properties, each cited to a spec section:

1. **Envelope round-trip (§5.1).** Domain: `envelopeArb = Arb.bind(idArb,
   typeArb, sessionIdArb, traceIdArb, jobIdArb, eventSeqArb, payloadArb,
   ::Envelope)`. Property: `forAll(envelopeArb) { e ->
   arcpJson.decodeFromString<Envelope>(arcpJson.encodeToString(e)) == e }`.
   Encodes via `kotlinx.serialization.json.Json` configured with
   `ignoreUnknownKeys = true, encodeDefaults = false`. 1000 iterations.

2. **Monotonic `event_seq` (§8.3).** Domain: `Arb.list(jobEventArb, 0..256)`
   feeding a single `SessionContext`. Property: for the resulting list of
   emitted envelopes, `envelopes.map { it.eventSeq!! }` is strictly
   increasing and gap-free (`zipWithNext { a, b -> b == a + 1 }`). Property
   also asserts that `session.ping/pong/ack` envelopes interleaved in the
   input do NOT consume sequence numbers (§6.4, §6.5).

3. **Idempotency dedupe (§7.2).** Domain: `Arb.pair(principalArb,
   idempotencyKeyArb)` plus `Arb.pair(agentArb, inputArb)`. Property: for
   any `(p, k)`, two `job.submit` calls with the same `(agent, input)`
   return the same `job_id`; same `(p, k)` with *different* `(agent, input)`
   raises `DuplicateKeyError`. Different `p` with same `k` is independent.
   Implementation uses `kotest-property`'s `checkAll` with explicit
   `iterations = 200` (the runtime store has side effects, so we reset it
   in `beforeEach`).

4. **Lease subset transitivity (§9.4).** Domain: `leaseArb` with bounded
   capability count. Property: `assertLeaseSubset(c, b) &&
   assertLeaseSubset(b, a)` ⟹ `assertLeaseSubset(c, a)`. Covers globs,
   `expires_at` ordering, and `cost.budget` remaining-currency comparison.

5. **`cost.budget` invariant (§9.6).** Domain: `Arb.list(metricEventArb,
   0..512)` with `value` drawn from `Arb.bigDecimal(0..1000, scale = 6)`.
   Property: after applying the list to a `BudgetMeter(initial = X)`, sum
   of decrements ≤ `X`; once the counter reaches `≤ 0`, the next
   `validateLeaseOp(capability = "cost.budget", ...)` throws
   `BudgetExhaustedError`. Negative metric values must be rejected before
   they are summed (§9.6).

---

## 5. Coverage policy (Kover)

`build.gradle.kts` plugin: `org.jetbrains.kotlinx.kover` (v0.8+).

### 5.1 Floors

- **Repository-wide: 87 % lines AND branches.** Both gates enforced via
  `kover { verify { rule { bound { minValue = 87; aggregation = COVERED_LINES } … } } }` and a parallel rule for `COVERED_BRANCHES`.
- **Per-module**:
  - `:core` — **90 %** (pure wire/parse code; high coverage is cheap).
  - `:runtime` — **87 %** (concurrent code with rare-path branches).
  - `:client` — **87 %**.
  - `:middleware:ktor`, `:middleware:spring` — **80 %** (real-host glue;
    the layer-7 loopback tests count, but some host paths are CI-only).
  - `:sdk` — inherits **87 %** (facade module; most lines are aggregator).

### 5.2 Exclusions — `kover.exclusions.gradle`

```
- dev.arcp.cli.MainKt                          # CLI entry; covered by sample runs
- dev.arcp.**.*$$serializer                    # kotlinx.serialization synthetics
- dev.arcp.**.ComponentRegistrar               # ksp / kotlinx generated
- samples/**
- tests/**                                     # the test harness itself
- **/build/generated/**
```

### 5.3 Minimum to hit 87 %

What drives coverage on each module:

| Module | Classes that pay the rent |
| --- | --- |
| `:core` | `Envelope` + every `Message` subclass (round-trip property covers ~80 %); `AgentRef.parse`; `parseBudgetAmount`; `parseJobEventBody`; error subclasses. |
| `:runtime` | `SessionContext` (heartbeat, ack, list_jobs, subscribe handlers); `Job` (lifecycle, applyCostMetric, emitResult); `Lease` (`validateLeaseOp`, `isLeaseSubset`, `validateLeaseConstraints`). |
| `:client` | `ARCPClient` (connect, submit, listJobs, subscribe, ack, autoAck); `JobHandle.collectChunks`. |
| `:middleware:ktor` | The `Application.installArcp(...)` extension + the WebSocket route handler. Loopback layer-7 tests cover. |

87 % is a floor, not a ceiling. The PR template asks for the kover delta;
a drop below floor on any module fails CI hard (`koverVerify`).

---

## 6. CI matrix

GitHub Actions workflow `.github/workflows/ci.yml`.

| Axis | Values | Why |
| --- | --- | --- |
| **JDK** | 17, 21 | 17 is the library's target floor (`:lib` compiles to JDK 17 bytecode). 21 is the toolchain (build host JDK and the version we run integration tests on for the future virtual-thread story). Both gates required green. |
| **Kotlin compiler** | **2.0+** (single version, currently 2.0.21) | A library does not need to validate against every consumer's compiler. We track one stable Kotlin and update it deliberately. Running a Kotlin matrix would more than double CI time for a class of failure the consumer hits first anyway. |
| **OS** | `ubuntu-latest`, `macos-latest` | One OS would compile, but the SQLite `EventLog` (the `:runtime` store) hits case-sensitivity differently on APFS (default case-insensitive on macOS) vs ext4 (case-sensitive). The kotlin-sdk's resume tests touch the same store key under different casings; the macOS leg is the canary. Windows is **not** in the matrix — no Kotlin-on-Windows consumer has been named, and Ktor's Windows WebSocket story is shaky enough that supporting it would mean writing Windows-specific tests we can't justify. |

| Task | What it runs |
| --- | --- |
| `./gradlew :check` | All `:*:test` (unit + integration on `MemoryTransport`) + ktlint + detekt + dokka HTML build (catches KDoc breakage). |
| `./gradlew :middleware:ktor:loopbackTest` | Layer 7 only, gated by `@Tag("loopback")`; per-test timeout 30 s via Kotest `timeout = 30.seconds`. |
| `./gradlew :kover:koverXmlReport :kover:koverVerify` | Aggregated XML + per-module + repo-wide floor gate. XML uploaded as workflow artifact and posted to Codecov. |
| `./gradlew :samples:check` | Runs every sample via `JavaExec` with `--continue` (so one broken sample doesn't mask the others) and a 30 s per-task timeout. The 18-sample set (Phase 6) is the consumer-facing smoke test. |
| `./gradlew :apiCheck` | `binary-compatibility-validator`. `apiDump` is committed; PRs that change the public API without updating the dump fail this task. |

Concurrency: matrix legs run in parallel (`max-parallel: 4`). Caches:
Gradle cache keyed on `**/*.gradle.kts` + `gradle/libs.versions.toml`;
Kotest results cached on `src/**` hash to skip unchanged modules under
`--build-cache`.

---

## 7. Test-writing rules (enforced in CONTRIBUTING.md)

- Every test class header has a `// §X.Y` comment naming the spec section
  it exercises. Conformance harness greps for it.
- Test names use Kotest backtick-string syntax: `\`rejects envelope when
  arcp field is not "1" (§5.1)\``. The `(§...)` suffix is required for
  layer 1–5 tests.
- No magic timeouts. Every `withTimeout` uses a named `kotlin.time.Duration`
  constant declared at file top.
- No `assert` from `kotlin.test`; use Kotest matchers (`shouldBe`,
  `shouldThrow<>`, `shouldContainExactly`) for the failure-message
  quality and shrinking integration.

---

## Appendix A — Banned word ledger

The following terms do not appear in this plan, in test names, or in
CONTRIBUTING.md: leverage, robust, scalable, performant, powerful,
modern, elegant, enterprise-grade. A pre-commit hook (`scripts/check-prose.sh`)
greps `planning/`, `*.md`, and `src/**/*.kt` for them.
