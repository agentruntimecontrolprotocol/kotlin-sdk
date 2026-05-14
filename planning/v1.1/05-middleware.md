# 05 — Host Adapters (Middleware Modules)

Scope: per-host integration packages that bind `:runtime`'s
`ArcpServer` to a concrete WebSocket host and propagate W3C trace
context. Mirrors the six TypeScript packages under
`/Users/nficano/code/arpc/typescript-sdk/packages/middleware/`
(`node`, `express`, `fastify`, `hono`, `bun`, `otel`), collapsed to the
Kotlin/JVM hosts that have real consumers.

Inputs read: `01-spec-delta.md` §C (capability gating), `02-current-audit.md`
§C (JVM-only) and §E (`dev.arcp.middleware.otel` target package),
spec §4 (transport), §6.1 (auth), §11 (tracing attrs), §14
(host/origin defense), and the TS `node`/`otel` source above.

## Adapter slate

| TS package           | Kotlin module                  | Verdict     | Rationale                                                                                                                                       |
| -------------------- | ------------------------------ | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `node`               | `arcp-ktor-server`             | INCLUDE     | Ktor is the Kotlin-idiomatic WS server. `install(plugin)` covers what `node`'s `attachArcpUpgrade` covers (`Application.install(Arcp)`).        |
| `express` / `hono`   | (subsumed by Ktor)             | INCLUDE-AS  | Ktor's `Routing` plugin is the express-equivalent mount point; a separate `arcp-express` module would mean a Node bridge, which is out of scope. |
| `fastify`            | `arcp-spring-webflux`          | INCLUDE     | The "I already have a non-Ktor server" axis. In Kotlin that's overwhelmingly Spring Boot. Reactor `WebSocketHandler` is the WS upgrade seam.    |
| `bun`                | (no Kotlin analog)             | REJECT      | Bun is a JS runtime; nothing to bind on the JVM.                                                                                                |
| `otel`               | `arcp-otel`                    | INCLUDE     | Drop-in `Transport` wrapper, attribute-name parity with `packages/middleware/otel/src/index.ts`.                                                |
| —                    | `arcp-vertx`                   | REJECT      | See §4 below.                                                                                                                                   |
| —                    | `arcp-http4k`                  | REJECT      | See §5 below.                                                                                                                                   |
| —                    | Raw Servlet 6                  | REJECT      | Servlet's `WebSocketContainer` is Java-ese, exposes no Kotlin idiom Ktor/Spring don't already cover. Per Phase 5 prompt instruction.            |

---

## 1. `arcp-ktor-server`

### Module identity

- Gradle path: `:middleware:ktor-server`
- Maven: `io.arcp:arcp-ktor-server:1.1.0`
- Direct deps: `io.ktor:ktor-server-core`, `io.ktor:ktor-server-websockets`
  (host framework); `project(":core")`, `project(":runtime")` for
  `ArcpServer` and `Transport`; `org.slf4j:slf4j-api` only (per Phase 3).
- Version range: Ktor `3.0.x`–`3.x` (server WS API has been stable
  since 2.x but plugin DSL changed at 3.0; pin minimum to 3.0.0).

### WS upgrade attachment seam

- Mount point: a Ktor `ApplicationPlugin` named `Arcp` plus a routing
  extension `Route.arcp(path)`. The plugin captures auth/host config;
  the route call binds `webSocket(path) { ... }` from
  `ktor-server-websockets`.
- Per-connection lifecycle: inside `webSocket { }`, the adapter
  constructs a `WebSocketTransport` over the Ktor `DefaultWebSocketSession`
  (collect `incoming: ReceiveChannel<Frame>`, send via
  `outgoing.send(Frame.Text(...))`). It then calls
  `arcpServer.accept(transport)`. The `webSocket` block is the
  structured-concurrency scope; closing it cancels the transport
  coroutine cleanly.
- Auth injection point: bearer token is read from the **handshake
  request** before the upgrade, via `call.request.headers["Authorization"]`
  inside an `intercept(Plugins)` or `onCall` plugin phase. Spec §6.1
  still places the token in `session.hello.payload.auth.token`; the
  pre-upgrade header is an out-of-band fast-fail (HTTP 401 before WS)
  for deployments that wish to gate at the edge. The adapter MUST
  still surface the token from the hello payload to `:runtime`; the
  HTTP-header path is optional and additive.

### Host-header / DNS-rebind defense (§14)

- Read `call.request.headers["Host"]` and
  `call.request.headers["Origin"]`. The Ktor `ApplicationRequest`
  exposes these directly; no parsing of raw bytes.
- Default: deny by default if `allowedHosts` is non-null and the
  stripped-port host is not in the set. Match the TS `node`
  adapter's behavior at `packages/middleware/node/src/index.ts:81`
  (`hostHeaderAllowed`: split on `:`, exact-match against the
  allowlist). Reject with HTTP 403 via
  `call.respond(HttpStatusCode.Forbidden)` **before**
  `webSocketRaw`/`webSocket` upgrades the connection.
- Same treatment for `Origin` when the consumer opts in
  (`allowedOrigins`). Origin is browser-only, so default is
  unconfigured = not checked.

### API sketch

Plugin install (the Kotlin-idiomatic surface):

```kotlin
public class ArcpKtorConfig {
    public lateinit var server: ArcpServer
    public var path: String = "/arcp"
    public var allowedHosts: Set<String>? = null
    public var allowedOrigins: Set<String>? = null
}

public val Arcp: ApplicationPlugin<ArcpKtorConfig>
```

Used as:

```kotlin
install(Arcp) {
    server = arcpServer
    allowedHosts = setOf("localhost", "127.0.0.1")
}
```

Routing-DSL equivalent (for users who want explicit mount):

```kotlin
public fun Route.arcp(path: String = "/arcp", server: ArcpServer)
```

Used as `routing { arcp("/arcp", server = arcpServer) }`. Both modes
share the same internal `attach()` call; we ship both because Ktor
users idiomatically install plugins for app-wide concerns and use
`Route` extensions for path-local ones (Ktor docs:
"Custom plugins" + "Routing").

### What this adapter does NOT do

- Does not pull `ktor-server-netty` / `ktor-server-cio` (engine
  choice belongs to the consumer's app module).
- Does not pull a logging binding (slf4j-api only).
- Does not register OTel spans — that's `arcp-otel`'s job.
- Does not parse `auth.token` from JSON; that lives in `:runtime`.

---

## 2. `arcp-spring-webflux`

### Module identity

- Gradle path: `:middleware:spring-webflux`
- Maven: `io.arcp:arcp-spring-webflux:1.1.0`
- Direct deps: `org.springframework:spring-webflux`,
  `org.springframework:spring-context` (for `@Configuration`),
  `io.projectreactor.kotlin:reactor-kotlin-extensions` (for
  `awaitSingle`/`asFlow` between Reactor and coroutines),
  `kotlinx-coroutines-reactor`; `project(":core")`, `project(":runtime")`.
- Version range: Spring Framework `6.1.x`–`6.x`, Spring Boot
  `3.2`–`3.x`. Spring 6 / Boot 3 is the JDK 17 baseline; older
  Spring 5 / Boot 2 (`javax.*`) is out of scope.

### Consumer evidence

JVM enterprise shops standardized on Spring Boot in 2018–2020. The
ones who adopted Kotlin (Square, Allegro, Expedia, JetBrains'
internal services) kept Boot underneath. A Ktor-only SDK forces a
runtime swap; a `WebSocketHandler` adapter doesn't.

### WS upgrade attachment seam

- Spring WebFlux's WS upgrade is a `WebSocketHandler` bean wired by
  a `SimpleUrlHandlerMapping` (or
  `WebSocketHandlerMapping`). Path `/arcp` maps to an
  `ArcpWebSocketHandler` whose `handle(session: WebSocketSession): Mono<Void>`
  bridges Reactor's `Flux<WebSocketMessage>` (inbound) and
  `Sink<WebSocketMessage>` (outbound) onto our `Transport`. Use
  `kotlinx-coroutines-reactor`'s `asFlow()` / `asPublisher()` so
  `Transport.receive(): Flow<Envelope>` integrates without a thread
  hop.
- Per-connection lifecycle: the `Mono<Void>` returned by `handle`
  completes when the WS closes; we tie the `ArcpServer.accept(...)`
  coroutine scope to that completion via
  `mono { coroutineScope { ... } }`.
- Auth injection: bearer token is read from the WS handshake via
  `session.handshakeInfo.headers.getFirst("Authorization")`. As with
  Ktor, the runtime still consumes the token from
  `session.hello.payload.auth.token`; the HTTP-header path is an
  early reject hook.

### Host-header / DNS-rebind defense (§14)

- `session.handshakeInfo.uri.host` for the requested authority,
  `session.handshakeInfo.headers.getFirst("Host")` for the literal
  header, `session.handshakeInfo.headers.getFirst("Origin")` for the
  Origin check.
- Default: deny if `allowedHosts` configured and host not in set.
  Reject inside `handle()` by returning
  `session.close(CloseStatus.POLICY_VIOLATION)`. Note that WebFlux's
  upgrade machinery runs the handshake before our handler, so we
  cannot return HTTP 403; closing the WS immediately with policy-
  violation is the spec-aligned reject (`§14`).

### API sketch

```kotlin
@Configuration
public open class ArcpAutoConfiguration {

    @Bean
    public open fun arcpWebSocketHandler(
        server: ArcpServer,
        config: ArcpSpringConfig,
    ): WebSocketHandler

    @Bean
    public open fun arcpHandlerMapping(
        handler: WebSocketHandler,
        config: ArcpSpringConfig,
    ): HandlerMapping

    @Bean
    public open fun webSocketHandlerAdapter(): WebSocketHandlerAdapter
}

public data class ArcpSpringConfig(
    public val path: String = "/arcp",
    public val allowedHosts: Set<String>? = null,
    public val allowedOrigins: Set<String>? = null,
)
```

The consumer provides their own `ArcpServer` bean and an
`ArcpSpringConfig` bean (or relies on defaults). The
`HandlerMapping` is a `SimpleUrlHandlerMapping` registering
`config.path` → the WS handler with order `Ordered.HIGHEST_PRECEDENCE`
so it wins over `RequestMappingHandlerMapping` for the WS path.

### What this adapter does NOT do

- Does **not** ship a Spring Boot Starter (no
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  no `spring-boot-autoconfigure` dep). Consumers import the
  `@Configuration` class manually (`@Import(ArcpAutoConfiguration::class)`).
  Reason: starters tie us to Spring Boot's lifecycle and pull a
  larger transitive set; the bean class is one `@Import` away from
  parity. If consumer demand for a starter materializes, ship
  `arcp-spring-boot-starter` as a separate v1.2 module.
- Does not pull a logging binding (slf4j-api only).
- Does not depend on Spring Boot at all — only Spring Framework
  WebFlux. Boot is the consumer's choice.
- Does not handle `Servlet` / Spring MVC. Blocking MVC's WS API
  (`WebSocketHandler` in `spring-websocket`) is a separate adapter
  that v1.1 does not ship; coroutines + blocking servlet threads is
  a thread-pool hazard.

---

## 3. `arcp-otel`

### Module identity

- Gradle path: `:middleware:otel`
- Maven: `io.arcp:arcp-otel:1.1.0`
- Direct deps: `io.opentelemetry:opentelemetry-api`,
  `io.opentelemetry:opentelemetry-context` (already a transitive of
  `-api` but pin explicitly); `project(":core")`. **No
  `opentelemetry-sdk`, no `opentelemetry-exporter-*`**.
- Version range: OpenTelemetry API `1.40.x`–`1.x`. The API module is
  semver-stable; exporters and SDK are not, and not our concern.

### Attachment seam

Wraps `Transport` rather than the host. Same shape as the TS package
at `packages/middleware/otel/src/index.ts:57` (`withTracing(inner, { tracer })`).
The Kotlin entry point:

```kotlin
public fun Transport.withTracing(tracer: Tracer): Transport
```

Used either client-side (`client.connect(transport.withTracing(tracer))`)
or server-side inside a host adapter
(`server.accept(transport.withTracing(tracer))`). For Ktor, ship an
auxiliary plugin so consumers do not write the wrap by hand:

```kotlin
public class ArcpOtelConfig { public lateinit var tracer: Tracer }
public val ArcpOtel: ApplicationPlugin<ArcpOtelConfig>
// Used: install(ArcpOtel) { tracer = openTelemetry.getTracer("arcp") }
// Must be installed AFTER Arcp.
```

### Attribute naming — line-by-line parity with TS

The TS extractor at `packages/middleware/otel/src/index.ts:139–184`
sets the following attribute keys; Kotlin MUST match exactly. New
v1.1 attrs flagged per spec §11:

| TS attr key                 | Source                                     | Required? | v1.1 add? |
| --------------------------- | ------------------------------------------ | --------- | --------- |
| `arcp.direction`            | hardcoded `"in"` / `"out"`                 | always    | no        |
| `arcp.type`                 | envelope `type`                            | when string | no       |
| `arcp.id`                   | envelope `id`                              | when string | no       |
| `arcp.session_id`           | envelope `session_id`                      | when string | no       |
| `arcp.job_id`               | envelope `job_id`                          | when string | no       |
| `arcp.trace_id`             | envelope `trace_id`                        | when string | no       |
| `arcp.event_seq`            | envelope `event_seq`                       | when number | no       |
| `arcp.agent`                | `payload.agent`                            | on `job.submit`/`job.accepted` | no |
| `arcp.lease.capabilities`   | `Object.keys(payload.lease | lease_request).join(",")` | when present | no |
| `arcp.lease.expires_at`     | `payload.lease_constraints.expires_at`     | when present | **yes (§11)** |
| `arcp.budget.remaining`     | `JSON.stringify(payload.budget)`           | when present | **yes (§11)** |

Implementation: a single `extractAttributes(envelope, direction): Attributes`
that mirrors the TS function shape (one branch per key). For
`arcp.budget.remaining` we serialize the budget JSON via
`kotlinx.serialization.json.Json.encodeToString(JsonElement)` rather
than `toString()` — preserves per-currency totals as the TS code
intends.

### Trace context propagation

TS injects/extracts under the envelope extension key
`x-vendor.opentelemetry.tracecontext`
(`packages/middleware/otel/src/index.ts:48`). Kotlin uses the same
constant. Inject via
`GlobalOpenTelemetry.getPropagators().textMapPropagator.inject(Context.current(), carrier, setter)`,
extract via the corresponding `.extract(...)`. The setter/getter
treat the carrier as a `MutableMap<String, String>` written to / read
from the envelope's `extensions[OTEL_EXTENSION_NAME]` map.

### Span shape

- Outbound: `tracer.spanBuilder("arcp.send <type>").setSpanKind(PRODUCER).startSpan()`.
- Inbound: `tracer.spanBuilder("arcp.recv <type>").setSpanKind(CONSUMER).setParent(extractedContext).startSpan()`.
- Status: on exception, `span.recordException(t)` + `span.setStatus(StatusCode.ERROR, message)`.
- Lifetime: span ends in `finally` block of the wrap. Use
  `withContext(span.makeCurrent().asContextElement())` from
  `opentelemetry-extension-kotlin` — but we cannot depend on that
  module (it's not in `-api`). Instead, use try-with-resources
  pattern via `Scope` (manual close in `finally`) which `-api` ships.

### What this adapter does NOT do

- Does **not** depend on `opentelemetry-sdk`. The consumer wires the
  SDK + exporters in their app module; our seam is `Tracer` from
  `-api`.
- Does not auto-discover `GlobalOpenTelemetry`. Consumer passes the
  `Tracer` explicitly; this matches `packages/middleware/otel/src/index.ts:23`.
- Does not emit semantic-conventions HTTP attributes — those belong
  to the host (Ktor / Spring) instrumentation.

---

## 4. `arcp-vertx` — REJECT

Argument:

- Vert.x Kotlin (`vertx-lang-kotlin-coroutines`) exists and the
  `HttpServer.webSocketHandler { ws -> ... }` API is small enough to
  bind. The technical work is on the order of a day.
- No real consumer demand on this side: every ARPC-adjacent
  TypeScript example uses Node/Bun/Workers, never an event-loop-
  router framework. The JVM consumers we have evidence for (per
  §02-current-audit Phase 5 prompt: enterprise Spring shops, Kotlin
  apps) are Ktor- or Spring-based.
- A shipped adapter we don't maintain becomes a CVE liability
  (`hostHeaderAllowed`-equivalent regressing without a Vert.x user
  noticing). The bar for shipping a host adapter is "we will fix it
  on report" — without a consumer, that bar is not met.

**Decision: not shipped in v1.1.** Re-evaluate if an ARCP adopter
publishes a Vert.x Kotlin runtime. Until then, the documented
escape hatch is: wrap a Vert.x `ServerWebSocket` as a `Transport`
manually (transports are an interface in `:core`; the public
contract is `send(Envelope)` / `receive(): Flow<Envelope>` /
`close(reason)`).

## 5. `arcp-http4k` — REJECT

Argument:

- Http4k has a server-WS API
  (`org.http4k.server.websocket.WebsocketHandler`) and a JVM-Kotlin
  user base. The framework's "server as a function" philosophy
  could plausibly support an `arcp-http4k` module.
- The consumer base for Http4k is small relative to Ktor (Http4k's
  GitHub stars ≈ 2.6k vs Ktor's ≈ 13k as of 2026-Q2) and the
  Http4k WS layer routes through `PolyHandler` which is awkward to
  bridge to a long-lived bidirectional `Transport`. The Kotlin-
  idiomatic value over Ktor is nil.
- Same maintenance-cost argument as Vert.x.

**Decision: not shipped in v1.1.** Http4k users self-implement the
`Transport` over `WsHandler`; the surface is small.

## 6. Raw Servlet 6 — REJECT

Per Phase 5 prompt. `jakarta.websocket.server.ServerEndpoint`
forces an annotation-driven, container-managed lifecycle that
fights coroutine cancellation (the endpoint instance is constructed
by the container, not by us, so we can't carry a `CoroutineScope`
naturally). Spring WebFlux already covers any servlet container
that ships Tomcat 10+; Ktor covers Netty/CIO. There is no
remaining Kotlin user who is on a raw Servlet container and not
behind Spring. Out of scope.

---

## Cross-cutting requirements

- Every adapter module applies `explicitApi()` (Phase 3) and
  `binary-compatibility-validator` (`apiDump` task wired). API
  diffs against the previous release must be a PR-review gate.
- Every adapter pins its host framework to the version range stated
  in its identity section. Version pins live in
  `gradle/libs.versions.toml`; downstream consumers can override.
- Every adapter has at least one sample under `samples/`. The
  current `samples/build.gradle.kts` `sampleClasses` map (see
  `samples/build.gradle.kts:27`) extends to:
  - `runKtorServer` → `com.arcp.samples.ktor_server.MainKt`
  - `runSpringWebflux` → `com.arcp.samples.spring_webflux.MainKt`
  - `runOtel` → `com.arcp.samples.otel.MainKt` (wraps the Ktor
    sample's transport with `withTracing`)

  Cross-reference: Phase 6 owns sample contents; this phase only
  reserves the slots.
- slf4j-api only across all three; no `logback-classic` /
  `slf4j-simple` runtime dep. Consumer brings the binding (matches
  `02-current-audit.md` reuse note on logging).
- All three modules ship a `module-info.java` (no — we are JVM but
  not JPMS; explicit non-goal) and a `META-INF/MANIFEST.MF`
  carrying `Automatic-Module-Name: io.arcp.middleware.<host>` for
  consumers who do use JPMS.

## Risks (Kotlin/framework-specific)

| Risk                                                                                                                                                                                | Mitigation                                                                                                                                                          |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ktor's `webSocket { }` block's coroutine scope is the WS session itself; calling `arcpServer.accept(transport)` and returning from the block closes the WS prematurely.            | Pattern: `coroutineScope { val tx = ...; arcpServer.accept(tx); tx.awaitClose() }`. `awaitClose` suspends until the transport's `Job` completes.                    |
| WebFlux's `Mono<Void>` from `handle()` must complete only when the WS closes; bridging coroutines to Reactor via `mono { }` is correct but `mono { ... arcpServer.accept(...) }` returns immediately if accept is non-suspending. | Make `ArcpServer.accept(transport: Transport): Job` return a `kotlinx.coroutines.Job`; the handler awaits it via `job.join()` inside the `mono { }` builder.        |
| OTel's `Scope` from `span.makeCurrent()` must close on the same thread that opened it; coroutine dispatch reassigns threads.                                                       | Use `Context.current().with(Span.wrap(...))` + manual `Context.with(...)` blocks; never rely on `makeCurrent()` across suspension points. Test with `runTest` + thread-confined dispatcher. |
| DNS-rebind defense must run before WS upgrade; both Ktor and WebFlux run the upgrade before the application handler in some configurations.                                        | Ktor: install the host check at `Plugins` phase (`onCall`) — runs pre-routing. WebFlux: WS upgrade happens at the `HandlerAdapter` level, so reject *inside* the handler by closing with `CloseStatus.POLICY_VIOLATION`; document that the TLS-terminated request has already happened but no application traffic has flowed. |
| Capability negotiation (`01-spec-delta.md` §C) is per-session; the OTel adapter's `arcp.lease.expires_at` / `arcp.budget.remaining` attrs MUST not appear if the features are not negotiated. | The OTel extractor reads from envelope payload, not session state. The attrs only appear when the runtime emits them, which it only does when the features were negotiated. No additional gate needed; document this invariant. |

## Out of scope for Phase 5

- A `arcp-spring-boot-starter` module (auto-config + properties
  binding). Revisit in v1.2 if consumers ask.
- A blocking `arcp-spring-mvc` adapter using `spring-websocket`'s
  `WebSocketHandler`. Coroutines on servlet threads is a hazard;
  not shipped without a consumer.
- `arcp-vertx`, `arcp-http4k`, raw Servlet 6 — see §4, §5, §6.
- Client-side host adapters. The client uses `:client`'s
  `WebSocketTransport` (Ktor client `CIO` engine, per
  `02-current-audit.md` §I); there is no per-host client wrapper to
  ship.
