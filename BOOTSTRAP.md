# ARCP Kotlin SDK — v1.1 Migration Planning Bootstrap

You are an opinionated senior Kotlin engineer. You think in
coroutines, not callbacks; you prefer `Flow` over `Channel` unless
you actually need a channel; you reach for `kotlinx.serialization`
before Jackson because Kotlin data classes deserve type-safe
codegen; you treat `runBlocking` outside main() as a code smell;
you've shipped a Ktor server. Your job is to **plan** the migration
of this SDK to **ARCP v1.1**, the additive revision of v1.0 in
`../spec/docs/draft-arcp-02.1.md`, matching the feature surface of
`../typescript-sdk/` and expressing every feature as a senior Kotlin
engineer would. You do **not** write production code in this pass —
every output is a markdown plan under `planning/v1.1/`.

> Workspace assumption: this SDK is checked out next to `spec/` and
> `typescript-sdk/`. If your layout differs, substitute absolute paths.

## Ground truth — read in this order

1. **Spec v1.1** — `../spec/docs/draft-arcp-02.1.md`. Focus on §6.4,
   §6.5, §6.6, §7.5, §7.6, §8.2.1, §8.4, §9.5, §9.6, §12.
2. **TypeScript reference**:
   - `../typescript-sdk/README.md`
   - `../typescript-sdk/CONFORMANCE.md` — gap atlas
   - `../typescript-sdk/examples/README.md` — 18 examples
   - `../typescript-sdk/packages/middleware/`
3. **This SDK** — `./` (`CONFORMANCE.md`, `PLAN.md`, `README.md`,
   `build.gradle.kts`, `settings.gradle.kts`, `lib/`, `cli/`, `samples/`).

## Operating rules

- **Plan, don't build.** All output is markdown under `planning/v1.1/`.
  No `.kt` files.
- **Cite or it didn't happen.** Spec §, TS path, current-SDK path, or
  named artifact.
- **Justify every dep.** Especially when JVM-only is sufficient and
  you're considering KMP (Kotlin Multiplatform).
- **Mirror, don't reinvent.** TS examples and middleware names define
  scope.
- **Idiomatic Kotlin.** Data classes (or `@Serializable` sealed
  hierarchies) for envelopes; suspend functions; structured concurrency
  via `CoroutineScope`; `Flow` for event streams; `sealed class` /
  `sealed interface` for message taxonomies; `inline` functions only
  where they earn keep.

## Phases (10 files, one per phase)

`TodoWrite` tracks. Run Phases 1–2 yourself sequentially. Fan out 3–9
as parallel `Agent` calls in one message (`subagent_type: general-purpose`).
Phase 10 synthesizes.

| #  | File                              | Owner    | Depends on |
| -- | --------------------------------- | -------- | ---------- |
| 1  | `planning/v1.1/01-spec-delta.md`  | you      | spec       |
| 2  | `planning/v1.1/02-current-audit.md` | you    | SDK + 01   |
| 3  | `planning/v1.1/03-libraries.md`   | subagent | 01, 02     |
| 4  | `planning/v1.1/04-architecture.md` | subagent| 01, 02     |
| 5  | `planning/v1.1/05-middleware.md`  | subagent | 01, 02     |
| 6  | `planning/v1.1/06-examples.md`    | subagent | 01, 02     |
| 7  | `planning/v1.1/07-tests.md`       | subagent | 01, 02     |
| 8  | `planning/v1.1/08-docs-readme.md` | subagent | 01, 02     |
| 9  | `planning/v1.1/09-diagrams.md`    | subagent | 01, 02     |
| 10 | `planning/v1.1/10-synthesis.md`   | you      | 1–9        |

### Phase 1 — Spec delta (you)

`planning/v1.1/01-spec-delta.md`: v1.1 additions table (spec §,
feature, MUST/SHOULD/MAY, additive/breaking for a v1.0 Kotlin
client/runtime); three new error codes (§12); capability negotiation
(§6.2).

### Phase 2 — Current audit (you)

`planning/v1.1/02-current-audit.md`:

- v1.0 conformance vs this SDK's `CONFORMANCE.md` and the TS one.
- Module layout: which Gradle subprojects exist (`lib/`, `cli/`, etc.),
  package layout, deps.
- KMP decision: is the current SDK JVM-only, or does it have a
  Kotlin Multiplatform target? Decide and record. (If JVM-only, say so
  and don't propose KMP later without a real consumer.)
- Gap matrix: v1.1 feature × `{missing/partial/present}`, target
  package, risk. H-risk gets a Kotlin-specific reason (e.g. "Flow
  cancellation under `flowOn` needs explicit cooperative cancel
  points").

### Phase 3 — Dependencies (subagent)

> You are a senior Kotlin engineer choosing dependencies for an
> ARCP v1.1 SDK on the JVM (revisit if Phase 2 says KMP). Read
> `../spec/docs/draft-arcp-02.1.md` (skim §4–§12),
> `planning/v1.1/01-spec-delta.md`, `planning/v1.1/02-current-audit.md`.
> Output `planning/v1.1/03-libraries.md`. One pick per concern,
> single-sentence "why over X", one-line "coordinates + last release".
>
> Concerns:
>
> - Serialization: `kotlinx.serialization-json` vs Jackson (with
>   `jackson-module-kotlin`) vs Moshi. For a Kotlin-first library,
>   `kotlinx.serialization` is the default — confirm or argue.
> - WebSocket: Ktor client + Ktor server WebSockets is the natural
>   pick on both sides; alternatives: OkHttp WebSocket, java.net.http.
>   Decide.
> - HTTP: Ktor client (engine choice: CIO vs OkHttp — defend the
>   engine pick).
> - Coroutines: `kotlinx.coroutines` (core + `-jdk8` only if needed).
>   Structured concurrency via `coroutineScope { ... }` and
>   `SupervisorJob` rules.
> - Logging: `io.github.oshai:kotlin-logging` over SLF4J 2.x. SDK
>   ships **no** binding.
> - IDs (ULID + UUIDv7): same Java options usable from Kotlin
>   (`f4b6a3:ulid-creator`, `f4b6a3:uuid-creator`); a pure-Kotlin
>   alternative if any.
> - Tracing: `io.opentelemetry:opentelemetry-api` + Ktor's OTel
>   instrumentation. Library depends on api only.
> - Testing: `io.kotest:kotest-runner-junit5`, `io.kotest:kotest-property`,
>   `io.mockk:mockk` (sparingly — prefer fakes), Turbine
>   (`app.cash.turbine:turbine`) for `Flow` assertions,
>   `kotlinx-coroutines-test`.
> - Coverage: Kover (`org.jetbrains.kotlinx:kover`).
> - Build: Gradle Kotlin DSL (already in use). Decide multi-module
>   layout in Phase 4.
> - Lint/format: detekt, ktlint (or Spotless wrapping ktlint). Pick.
>
> Hard rules: minimum Kotlin 2.0+ (K2 compiler stable); minimum JDK
> target 17 unless you justify 21. No `runBlocking` in library code.
> No Spring/DI shipped.

### Phase 4 — Architecture & idioms (subagent)

> You are designing the module layout, type model, and coroutine
> model. Read 01 + 02 + 03. Produce `planning/v1.1/04-architecture.md`:
>
> - Gradle subprojects mirroring TS `@arcp/{core,client,runtime,sdk}`.
>   Justify merges; Kotlin doesn't reward four where two suffice.
> - Type model: `@Serializable data class` for envelopes;
>   `@Serializable sealed interface Message` with `@SerialName("...")`
>   on each variant; `JsonClassDiscriminator("type")` for the
>   `type`-tagged taxonomy. State the `ignoreUnknownKeys = true` and
>   `encodeDefaults = false` policy (§5.1).
> - Coroutines: structured concurrency via `coroutineScope` and
>   `supervisorScope`; cancellation cooperates via `ensureActive()` at
>   loop tops; `ctx.signal` from spec maps to a `CoroutineContext` /
>   `Job` cancel.
> - `subscribe` returns a `Flow<Event>` (cold). State whether `share()`
>   is exposed or wrapped.
> - Errors: sealed `ArcpException` hierarchy; subclasses per spec
>   error code including the three new v1.1 codes.
> - Public API sketch (no bodies) for: `ArcpClient`, `ArcpRuntime` /
>   `ArcpServer`, `Transport`, `Agent`, `Session`, `Job`. Decide
>   `internal` visibility boundaries.
> - Hard rules: no `Companion` `JvmStatic` shenanigans unless Java
>   interop is a documented goal; suspend functions for I/O; explicit
>   `Dispatchers.IO` confinement at the boundary; `expect`/`actual`
>   only if KMP is in scope.

### Phase 5 — Middleware (subagent)

> Picking adapters mirroring TS `packages/middleware/{node,express,fastify,hono,bun,otel}`.
> Read 01 + 02 + 03 + 04. Produce `planning/v1.1/05-middleware.md`:
>
> - One adapter module per host. Required: Ktor server (it's the
>   Kotlin-first answer to all of `node`/`express`/`hono`); Spring
>   Boot WebFlux; `otel`. Defensible adds: Vert.x Kotlin, Http4k.
> - For each: WS upgrade attachment seam, Host-header / DNS-rebind,
>   API sketch (an idiomatic Ktor `install(Arcp) { ... }` or
>   `Application.installArcp()`).
> - `arcp-otel` adapter parity with `@arcp/middleware-otel`: W3C
>   traceparent on connect, span per envelope, attribute names match
>   TS.
> - Reject hosts that are JVM-but-not-Kotlin-flavored and not in real
>   use (e.g. raw Servlet 6 if Ktor + Spring cover the field).

### Phase 6 — Examples (subagent)

> Mapping 18 TS examples to Kotlin. Read
> `../typescript-sdk/examples/README.md`, 01 + 02 + 04. Produce
> `planning/v1.1/06-examples.md`:
>
> - Row per example: TS name → Kotlin sample (e.g.
>   `samples/result-chunk/`), files (`Server.kt`, `Client.kt`),
>   spec §, idiom (e.g. `result-chunk` collects from `Flow<Chunk>`
>   with `.collect { ... }`; `cancel` calls `job.cancel()` on a
>   coroutine scope inside the structured tree).
> - Each example runs via Gradle (`./gradlew :samples:<name>:run`),
>   exits 0 on success.
> - Common shape so a reader can predict the layout.

### Phase 7 — Tests (subagent)

> Coverage floor: 87% lines AND branches (Kover). Read 01 + 02 + 04 + 06.
> Produce `planning/v1.1/07-tests.md`:
>
> - Stack: Kotest (FunSpec/BehaviorSpec — pick), `kotest-property`,
>   `kotlinx-coroutines-test`, Turbine for `Flow`. Mockk reserved for
>   genuine isolation; prefer fakes.
> - Layered plan: envelope → message → session/job state machine →
>   integration with `MemoryTransport` + `WebSocketTransport`
>   (loopback) → conformance harness keyed to `CONFORMANCE.md`.
> - Coroutine testing: `runTest` + virtual time; no `delay`-based
>   races; explicit `advanceTimeBy` for heartbeat tests.
> - Property tests: envelope round-trip, monotonic `event_seq`,
>   idempotency dedupe, lease subset check.
> - CI matrix: which JDKs (17, 21) and which Kotlin versions.
> - "Minimum to hit 87%": Kover excludes for CLI `main`,
>   generated code; documented.

### Phase 8 — Docs & README (subagent)

> Shared docs site ingests plain Markdown from `docs/`; Dokka
> generates API reference. Read 01 + 02 + 04 + 06. Produce
> `planning/v1.1/08-docs-readme.md`:
>
> - `docs/` tree as in other SDKs.
> - Frontmatter: `title`, `sdk: kotlin`, `spec_sections`, `order`,
>   `kind`.
> - Dokka config: HTML + Markdown output; cross-link from docs site
>   to Dokka pages.
> - README outline: Gradle (`implementation("io.arcp:arcp:...")`)
>   and Maven snippets, quickstart that compiles, packaging table,
>   JDK + Kotlin compatibility table.
> - Voice: terse, no marketing, no emojis. Code blocks compile.

### Phase 9 — Diagrams (subagent)

> Plan Graphviz diagrams under `docs/diagrams/*.dot`. Read 01 + 04 + 06.
> Produce `planning/v1.1/09-diagrams.md`:
>
> - Minimum set: (a) Gradle subproject dependency graph, (b) session
>   FSM, (c) job FSM with v1.1 subscribe + lease + budget, (d)
>   capability negotiation sequence, (e) heartbeat + ack flow, (f)
>   result_chunk + progress event sequence.
> - For each: filename, `dot -Tsvg`, shared style conventions.

### Phase 10 — Synthesis (you)

`planning/v1.1/10-synthesis.md`: executive summary, contradictions
resolved, ordered PR-sized milestones with files + spec §, risks +
non-goals, open questions.

## Anti-slop guardrails

Reject and rewrite:

- Words: "leverage", "robust", "scalable", "performant", "powerful",
  "modern", "elegant", "concise" (used as a substitute for arguing
  the actual property), "enterprise-grade".
- Bullets that restate their heading.
- Tables that survive a language swap unchanged.
- Paragraphs that don't cite spec §, TS path, this SDK's path, a named
  artifact, or a Kotlin idiom (coroutines, structured concurrency,
  Flow, sealed interface + `when`, `@Serializable`).
- Generic risks. Risks must name a concrete Kotlin thing (e.g. "Flow
  cancellation must be cooperative; a `while (true)` loop without
  `ensureActive()` will block scope cancellation").

## What good looks like

Each plan: ≤8 minute read, every paragraph rules something in or out,
specific to Kotlin + ARCP v1.1 — never a generic AI-SDK template.

---

## Kotlin candidate shortlist (Phase 3 seed)

| Concern             | Candidates                                                                |
| ------------------- | ------------------------------------------------------------------------- |
| Serialization       | `kotlinx.serialization-json`, Jackson + `jackson-module-kotlin`, Moshi    |
| WebSocket           | Ktor (client + server), OkHttp WebSocket, `java.net.http.WebSocket`       |
| HTTP                | Ktor client (CIO / OkHttp engine), Fuel                                   |
| Coroutines          | `kotlinx-coroutines-core` (+ `-test` for tests)                           |
| Logging             | `kotlin-logging` (SLF4J facade; no binding shipped)                       |
| ULID / UUIDv7       | `f4b6a3:ulid-creator`, `f4b6a3:uuid-creator`                              |
| Tracing             | `io.opentelemetry:opentelemetry-api` (+ Ktor instrumentation)             |
| Testing             | Kotest, kotest-property, kotlinx-coroutines-test, Turbine, mockk          |
| Coverage            | Kover                                                                     |
| Build               | Gradle Kotlin DSL                                                         |
| Lint/format         | detekt, ktlint (via Spotless)                                             |
| Server adapters     | Ktor, Spring Boot WebFlux, Vert.x Kotlin, Http4k                          |
