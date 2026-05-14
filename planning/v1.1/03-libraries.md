# 03 — Libraries

Constraints carried from Phase 2 (`02-current-audit.md` §C, §H): JVM-only, JDK 17 target floor, toolchain JDK 21, Kotlin 2.0+ K2, no Multiplatform. Every pick below assumes those.

## 1. Serialization — `kotlinx-serialization-json`

Pick over Jackson `jackson-module-kotlin` because the v1.1 envelope (`draft-arcp-02.1.md` §5.1) is a tagged union keyed on `type`, and `kotlinx.serialization`'s `@SerialName` + sealed-class discriminator round-trips that shape from a compile-time schema (no reflection at message-dispatch time, no `@JsonTypeInfo`/`@JsonSubTypes` registration table to keep in sync). Pick over Moshi because Moshi's polymorphic adapter requires a runtime-registered `PolymorphicJsonAdapterFactory` per sealed hierarchy and has no value-class support, which the §5.1 `id: ULID` and §6.3 `resume_token` wrappers want.

The §6.2 `agents` field (string-or-object per element, per `01-spec-delta.md` §A row "6.2") is handled with a `JsonContentPolymorphicSerializer<AgentDescriptor>` whose `selectDeserializer(element)` branches on `element is JsonPrimitive` → `AgentDescriptor.Bare.serializer()` vs `element is JsonObject` → `AgentDescriptor.Rich.serializer()`. This is the same idiom used for shape-polymorphic JSON in `kotlinx.serialization` and avoids a custom `KSerializer<List<AgentDescriptor>>` wrapper. Jackson's equivalent is a `JsonDeserializer<AgentDescriptor>` that inspects `JsonNode.isTextual()` — viable but reflective; Moshi has no first-class equivalent.

Json instance policy (from `02-current-audit.md` §F "json/Json.kt"): `ignoreUnknownKeys = true` (§5.1 unknown-fields rule), `encodeDefaults = false` (keep wire frames small), `classDiscriminator = "type"` (matches §5.1 envelope), `explicitNulls = false`, `prettyPrint = false`.

Coordinates: `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` (released 2026-04).

## 2. WebSocket

**Client: Ktor `client-websockets` plugin over the engine chosen in §3.** Pick over OkHttp's `WebSocketListener` because OkHttp gives a callback API and we want a `Flow<Frame>` from `DefaultClientWebSocketSession.incoming.consumeAsFlow()` to feed the §8.3 `event_seq` reorder buffer without adapter glue. Pick over `java.net.http.WebSocket` (JDK built-in) because the JDK type delivers fragmented messages through a `Listener.onText(part, last)` callback that the consumer must reassemble, and there is no per-connection ping interval — both are configured on Ktor as flags (`pingInterval`, `maxFrameSize`).

**Server: Ktor `server-websockets` (Netty engine).** Pick over a hand-rolled `WebSocketServlet` because the v1.1 transport (§4) is a single endpoint `WS /arcp` with mandatory framing; Ktor's `webSocket("/arcp") { ... }` gives the `Flow`-shaped read side identical to the client side and the Netty engine handles TLS without a Tomcat/Jetty layer. The TS reference uses `ws` (`typescript-sdk/packages/core/package.json` → `"ws": "^8.18.0"`); Ktor server-ws is the equivalent Kotlin posture.

Coordinates: `io.ktor:ktor-client-websockets:3.5.0`, `io.ktor:ktor-server-websockets:3.5.0`, `io.ktor:ktor-server-netty:3.5.0` (3.5.0 released 2026-05; upgrade from current pin `2.3.12` in `lib/build.gradle.kts:34-39`).

## 3. HTTP — Ktor client with the OkHttp engine

The library's only non-WS HTTP call is the bearer-auth side-channel for token rotation (§6.1) and any out-of-band agent metadata fetch a host wires in. The engine choice is about transitive footprint, HTTP/2 maturity, and connection-pool behavior across long-lived process lifetimes.

Pick `ktor-client-okhttp` over `ktor-client-cio` because CIO's HTTP/2 path is still flagged experimental in `io.ktor.client.engine.cio` (no ALPN auto-fallback when the server downgrades mid-handshake) and OkHttp ships a battle-tested `ConnectionPool` with idle eviction we'd otherwise re-implement. Pick over `ktor-client-java` (JDK `HttpClient`) because the JDK client allocates a dedicated `ExecutorService` per `HttpClient` instance and does not surface a connection-pool API for size/eviction tuning. Phase 2 §J explicitly excludes Android, so OkHttp's Android-readiness is not a selling point — it's the connection-pool maturity that justifies it.

Coordinates: `io.ktor:ktor-client-okhttp:3.5.0` (pulls in `com.squareup.okhttp3:okhttp:4.12.x` transitively).

## 4. Coroutines — `kotlinx-coroutines-core`

Drop `-jdk8` — every API it adds (`future`, `asCompletableFuture`) is already in `-core` since 1.7 (`02-current-audit.md` does not show jdk8 as a current dep, so this is "confirm don't add"). No `-reactor` / `-rx*`: the library exposes `Flow` and `suspend fun` only, leaving interop to consumers.

Structured-concurrency posture, applied to the v1.1 shapes flagged H-risk in `01-spec-delta.md`:
- **Session lifecycle:** `SupervisorJob` owned by `Session`; child failures (one collector crashing) MUST NOT tear down the WebSocket read loop. Sibling event-emit jobs are independently cancellable.
- **Per-request scopes:** `coroutineScope { }` around `submit() → awaitAccepted()` so a child failure cancels siblings and propagates.
- **Per-job event fan-out (§7.6):** `MutableSharedFlow(replay = 0, extraBufferCapacity = N, BufferOverflow.SUSPEND)` per subscriber, collection scope created with `supervisorScope { }` so one slow subscriber doesn't cancel the others (`02-current-audit.md` §E row 7.6).
- **Heartbeat (§6.4):** dedicated child `Job` of the session supervisor; the `delay(interval)` → `send(ping)` → `withTimeoutOrNull(2*interval)` loop relies on `delay` being a cancellation point.
- **No `runBlocking` in library code** (hard rule below).

Coordinates: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0` (released 2026-05); `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0` for tests only.

## 5. Logging — `kotlin-logging` (oshai) on top of `slf4j-api`

Pick `io.github.oshai:kotlin-logging-jvm` over calling SLF4J 2.x directly because the lazy lambda form (`logger.debug { "msg ${expensive()}" }`) skips the message-construction allocation on level-disabled paths, which matters for `event_seq` debug logs (§8.3). The library uses the SLF4J 2.x fluent builder under the hood, so consumer SLF4J 2.x routing works unchanged.

Confirmed posture (matches `02-current-audit.md` §F's reuse stance): the library declares `api 'org.slf4j:slf4j-api:2.0.18'` and `api 'io.github.oshai:kotlin-logging-jvm:7.0.14'` and **NO binding** — no `logback-classic`, no `slf4j-simple`, no `log4j-slf4j2-impl`. Consumers pick their own. `logback-classic` stays `testRuntimeOnly` for our own test suite (see §12 misc).

Coordinates: `io.github.oshai:kotlin-logging-jvm:7.0.14`, `org.slf4j:slf4j-api:2.0.18`.

## 6. IDs — `com.github.f4b6a3:ulid-creator` + `:uuid-creator`

Pick the f4b6a3 libraries over a pure-Kotlin alternative because §5.1 mandates ULID or UUIDv7 for `id` and `resume_token` ≥128-bit entropy (§6.2); both f4b6a3 libraries source from `java.security.SecureRandom` by default and the ULID artifact is ~50 KB. A pure-Kotlin reimplementation would re-do the Crockford base32 alphabet and the timestamp/randomness packing — work for no payoff on a JVM-only library, and the f4b6a3 surface returns `String`/`UUID` which our value classes wrap directly.

`ids/Ids.kt` (`02-current-audit.md` §F) already uses these — confirming, not changing.

Coordinates: `com.github.f4b6a3:ulid-creator:5.2.3`, `com.github.f4b6a3:uuid-creator:6.1.1`.

## 7. Tracing — `opentelemetry-api` only

The library compiles against `io.opentelemetry:opentelemetry-api` and never against `opentelemetry-sdk` or any exporter. Rationale: §11 only requires that the SDK *produce* spans with the attributes named in §11 + `01-spec-delta.md` §A row 11 (`arcp.lease.expires_at`, `arcp.budget.remaining`); pulling in the SDK would force a global `OpenTelemetry` provider on every consumer (including those who don't trace at all). Consumers wire the SDK themselves; our code reads `GlobalOpenTelemetry.get()` lazily.

For Ktor instrumentation, the Ktor v3 `io.ktor:ktor-server-call-id` plus a thin `Tracer.spanBuilder()` wrapper in our middleware module is sufficient — we do not need the full `io.opentelemetry.instrumentation:opentelemetry-ktor-3.0` artifact in the library (only in the OTel middleware module added in Phase 4).

Coordinates: `io.opentelemetry:opentelemetry-api:1.62.0`.

## 8. Testing — Kotest FunSpec, kotest-property, kotlinx-coroutines-test, Turbine, mockk-as-last-resort

**FunSpec over BehaviorSpec.** The wire-conformance tests we'll write (envelope round-trip per §5.1, error-code coverage per §12, capability intersection per §6.2) are property-style assertions with one `test("…") { … }` per case, not Given/When/Then narratives. BehaviorSpec's three-level nesting buys nothing for "encode X, parse it, assert Y". FunSpec's flat structure also reads like the TS `describe(…, it(…))` blocks in `typescript-sdk/packages/core/test/**`, easing cross-reference.

`kotest-property` is the source of `Arb<Envelope>`, `Arb<Event>`, etc. for the round-trip generators.

`kotlinx-coroutines-test` provides `runTest` + `TestScope` for every suspend test. We never call `runBlocking { … }` in tests either — `runTest` skips `delay` calls deterministically, which the §6.4 heartbeat tests need.

`app.cash.turbine:turbine` is the assertion library for `Flow` tests (subscribe fan-out per §7.6, result_chunk streaming per §8.4). `flow.test { … }` with `awaitItem()` / `awaitComplete()` replaces hand-rolled `toList()` collectors and gives precise per-emission timeouts.

`mockk` is reserved for cases where a real fake costs more than its keep. Default posture: fakes implemented as ordinary Kotlin classes in `src/test/kotlin/.../fakes/` (a `FakeTransport`, `FakeClock`, `FakeBudgetMeter`). Justification: fakes are themselves tested by the suite that uses them, while mocks duplicate verification structure and rot on signature changes. Recorded so reviewers can reject `mockk { every { … } returns … }` boilerplate when a fake is the right answer.

Coordinates: `io.kotest:kotest-runner-junit5:6.0.7`, `io.kotest:kotest-assertions-core:6.0.7`, `io.kotest:kotest-property:6.0.7`, `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0`, `app.cash.turbine:turbine-jvm:1.2.1`, `io.mockk:mockk-jvm:1.14.9` (only `testImplementation`, never `api`/`implementation`).

## 9. Coverage — `kover`

No alternative argued: Kover is the only JVM coverage tool that instruments K2-compiled Kotlin bytecode without the JaCoCo `synthetic-method` false positives on inline functions and value classes (relevant because §5.1 envelope wrappers will be `value class Ulid(...)`). The current pin (`gradle/libs.versions.toml:17` = 0.8.3) is two minors behind.

Coordinates: `org.jetbrains.kotlinx:kover:0.9.8` (plugin id `org.jetbrains.kotlinx.kover`, released 2026-03).

## 10. Build — Gradle Kotlin DSL, enable configuration cache

DSL already in use (`02-current-audit.md` §H). Enable `org.gradle.configuration-cache=true` in `gradle.properties` once the Phase 4 module split is settled — `02-current-audit.md` §H flags it as currently `false`. Reasoning is in §H itself ("catches accidental project-state mutations at task creation"); the only `lib/build.gradle.kts` thing that risks a CC-incompatibility is the `publishing { … }` block, which works correctly under CC in Gradle 8.7+.

Coordinates: Gradle wrapper 8.10+ (matches Kotlin 2.0+ requirement).

## 11. Lint/format — keep `org.jlleitschuh.gradle.ktlint`, add `detekt`, do NOT switch to Spotless

Pick the standalone `org.jlleitschuh.gradle.ktlint` plugin (already wired, `02-current-audit.md` §H) over Spotless-wrapping-ktlint because Spotless adds an `apply plugin: 'com.diffplug.spotless'` configuration layer that itself rotates ktlint versions independently of the user's pin, and we want the ktlint version in `libs.versions.toml` to be the single source of truth. Spotless's "format any file" generality is unused here (no YAML, no Markdown formatting requirements in the SDK).

`detekt` runs alongside for type-resolved rules (forbidden imports, complexity ceilings) that ktlint doesn't cover. Coordinates of both:

- `org.jlleitschuh.gradle.ktlint:14.2.0` (plugin portal; ktlint engine 1.5.x by default).
- `io.gitlab.arturbosch.detekt:1.23.8` (plugin; current pin `1.23.7` in `libs.versions.toml:16` is one patch behind).

## 12. Misc carryovers from Phase 2

- **`sqlite-jdbc` — KEEP.** Backs the §6.3 resume buffer (the redesigned `(session_id, event_seq)` schema from `02-current-audit.md` §F). Coordinates: `org.xerial:sqlite-jdbc:3.53.1.0` (current pin `3.46.1.3` in `libs.versions.toml:7` is several minors behind).
- **`nimbus-jose-jwt` — REVOKE.** §6.1 is bearer-only (verified in `spec/docs/draft-arcp-02.1.md:235` "Bearer token in `session.hello.payload.auth.token`"); JWT validation is the consumer's concern, not the SDK's. `auth/BearerAuth.kt` stays (a `BearerToken(value: String)` value class + header injection); `auth/JwtAuth.kt` is removed per `02-current-audit.md` §F. Drop the dep entirely.
- **`json-schema-validator` (networknt) — REVOKE.** v1.1 schemas are inline JSON Schema fragments in the spec (`lease_request` body, `agent.input_schema`, `agent.output_schema`), but the SDK does not perform structural validation of those — agents do, on submission. There is no v1.1 code path that needs `JsonSchema.validate(node)`. Drop. (Answer to Phase 2 open question 1 below.)
- **`logback-classic` — testRuntime only, CONFIRM.** Posture is correct: library ships no binding (§5 above), `logback-classic` is `testRuntimeOnly` so `kotest`'s logger output goes somewhere during the test run. Coordinates: `ch.qos.logback:logback-classic:1.5.18`.

## Answers to Phase 2 open questions

1. **Drop `json-schema-validator`?** Yes. No code path in the v1.1 wire surface needs structural JSON Schema validation — `lease_request` and agent schemas are negotiated content, not SDK-validated content. Saves ~1.4 MB transitive (the networknt artifact pulls `jackson-databind`, `slf4j-api`, `ethlo-itu`).
2. **Ktor CIO vs OkHttp engine?** OkHttp, per §3 above. The HTTP/2 maturity and `ConnectionPool` knobs justify the larger transitive set on a JVM-only library where Phase 2 §J has ruled out Android (CIO's main pitch is JVM-and-native parity, which we do not need).

## Hard rules (restated)

- Kotlin 2.0+ K2; do not pin Kotlin below 2.0.21.
- JDK 17 target floor (`kotlin { jvmTarget = JvmTarget.JVM_17 }`); toolchain JDK 21.
- No `runBlocking` anywhere in `:lib` (library code). `runBlocking` may appear only in `:cli` `main()` and in test `@JvmStatic main` fixtures.
- No Spring, no DI framework dependency in any library module.
- No Kotlin Multiplatform plugin in any subproject (Phase 2 §C decision).
- Library depends on `slf4j-api` only — NO `logback-classic`/`slf4j-simple`/`log4j-slf4j2-impl` shipped from `:lib`. Bindings are testRuntime only.
- Library depends on `opentelemetry-api` only — NO `opentelemetry-sdk`, NO exporter artifacts in `:lib`. SDK wiring happens in consumer code or a separate `:middleware-otel` module.

## Transitive footprint

Direct dependencies proposed, with sizes called out only where a single dep crosses 5 MB transitively or introduces a known classpath conflict:

| Dep | Transitive size | Conflict notes |
| --- | --- | --- |
| `kotlinx-coroutines-core:1.11.0` | ~1.7 MB (incl. `kotlinx-coroutines-bom`) | None. |
| `kotlinx-serialization-json:1.11.0` | ~900 KB | None. |
| `kotlinx-datetime:0.6.2` | ~600 KB | Pulls `kotlinx-serialization-core` — already in graph. |
| `kotlin-logging-jvm:7.0.14` | ~300 KB | Pulls `slf4j-api:2.0.x` — matches our explicit pin. |
| `slf4j-api:2.0.18` | ~70 KB | None (API only). |
| `sqlite-jdbc:3.53.1.0` | **~12 MB** | The native binaries for all OS/arch combos are bundled in the jar; this is the single largest dep. No alternative: pure-Java SQLite implementations don't exist, and switching to an external `sqlite` process is out of scope. Document the size in `:lib` README. |
| `opentelemetry-api:1.62.0` | ~400 KB | Pulls `opentelemetry-context` — fine. |
| `ulid-creator:5.2.3` | ~50 KB | None. |
| `uuid-creator:6.1.1` | ~170 KB | None. |
| `ktor-client-core:3.5.0` + `ktor-client-okhttp:3.5.0` + `ktor-client-websockets:3.5.0` | ~3.2 MB (incl. `okhttp:4.12.x`, `okio:3.x`) | OkHttp + Okio are pulled transitively. **Potential conflict:** consumer apps that pin OkHttp 5.x will resolve the higher version; our code uses Ktor's engine abstraction, not OkHttp directly, so this is benign. |
| `ktor-server-core:3.5.0` + `ktor-server-netty:3.5.0` + `ktor-server-websockets:3.5.0` | **~7 MB** (Netty pulls `netty-codec`, `netty-handler`, `netty-transport`) | Netty's transitive set is large. Mitigation: split server-side Ktor into a separate `:runtime` module in Phase 4 so `:core` and `:client` consumers don't pay this cost. |

Test-only (not shipped to consumers):

| Dep | Transitive size |
| --- | --- |
| `kotest-runner-junit5:6.0.7` + assertions-core + property | ~3 MB total |
| `kotlinx-coroutines-test:1.11.0` | ~300 KB |
| `turbine-jvm:1.2.1` | ~80 KB |
| `mockk-jvm:1.14.9` | ~2.5 MB (pulls Byte Buddy + objenesis) — used sparingly per §8 |
| `logback-classic:1.5.18` | ~900 KB |

**Removed from current `lib/build.gradle.kts` (transitive savings):**

- `nimbus-jose-jwt:9.40` → ~1 MB saved (pulls `bcprov-jdk18on` optional but present in many resolutions).
- `json-schema-validator:1.5.2` (networknt) → ~1.4 MB saved (pulls `jackson-databind`, `jackson-core`, `jackson-annotations`, `ethlo-itu`). Removing this also keeps Jackson off our classpath entirely — relevant because consumers using Jackson for their own JSON will not see version skew through us.

Net change vs current `lib/build.gradle.kts`: +OkHttp (~1.5 MB), +OTel API (~400 KB), +ULID/UUID (~220 KB, already present), -JOSE (~1 MB), -networknt+Jackson (~1.4 MB). Roughly net-neutral on `:core`/`:client` and a clear win once the Netty-bearing `:runtime` is split out.
