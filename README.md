<h3 align="center">ARCP Kotlin SDK</h3>

<p align="center"><strong>Kotlin SDK for the Agent Runtime Control Protocol (ARCP) — submit, observe, and control long-running agent jobs from Kotlin.</strong></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/dev.arcp/arcp"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/dev.arcp/arcp.svg"></a>
  <a href="https://github.com/agentruntimecontrolprotocol/kotlin-sdk/actions/workflows/test.yml"><img alt="CI" src="https://github.com/agentruntimecontrolprotocol/kotlin-sdk/actions/workflows/test.yml/badge.svg"></a>
  <a href="https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md"><img alt="ARCP" src="https://img.shields.io/badge/ARCP-v1.1%20draft-blue"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-lightgrey"></a>
  <a href="https://coderabbit.ai"><img alt="CodeRabbit" src="https://img.shields.io/coderabbit/prs/github/agentruntimecontrolprotocol/kotlin-sdk?utm_source=oss&utm_medium=github&utm_campaign=agentruntimecontrolprotocol/kotlin-sdk&labelColor=171717&color=FF570A&label=CodeRabbit+Reviews"></a>
</p>

<p align="center">
  <a href="https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md">Specification</a> ·
  <a href="#concepts">Concepts</a> ·
  <a href="#installation">Install</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="docs/">Guides</a> ·
  <a href="docs/modules/arcp.md">API reference</a>
</p>

---

`dev.arcp:arcp` is the Kotlin reference implementation of [ARCP](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md), the Agent Runtime Control Protocol. It covers both sides of the wire — `dev.arcp.client.ARCPClient` for submitting and observing jobs, `dev.arcp.runtime.ARCPRuntime` for hosting agents — so either side can talk to any conformant peer in any language without hand-rolling the envelope, sequencing, or lease enforcement.

ARCP itself is a transport-agnostic wire protocol for long-running AI agent jobs. It owns the parts of agent infrastructure that don't change between products — sessions, durable event streams, capability leases, budgets, resume — and stays out of the parts that do. ARCP wraps the agent function; it does not define how agents are built, how tools are exposed (that's MCP), or how telemetry is exported (that's OpenTelemetry).

## Installation

Requires JDK 21 or newer. The library is published to Maven Central as `dev.arcp:arcp` and pulls in its Kotlin coroutines, serialization, and datetime runtimes transitively. Add it with Gradle (Kotlin DSL):

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.arcp:arcp:1.1.0")
}
```

For Maven users, the same coordinates apply (`groupId = dev.arcp`, `artifactId = arcp`, `version = 1.1.0`).

## Quick start

Connect to a runtime, submit a job, stream its events to completion:

```kotlin
import dev.arcp.client.ARCPClient
import dev.arcp.envelope.Envelope
import dev.arcp.messages.Capabilities
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobFailed
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.SessionClose
import dev.arcp.transport.MemoryTransport
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(): Unit = runBlocking {
    val (clientTransport, _) = MemoryTransport.pair() // swap for a networked transport
    ARCPClient(
        transport = clientTransport,
        auth = ARCPClient.bearer(System.getenv("ARCP_TOKEN")),
        client = ARCPClient.defaultClientInfo(principal = "quickstart"),
        capabilities = Capabilities(streaming = true),
    ).use { client ->
        val session = client.open()
        client.send(session.sessionId, JobSubmit(
            agent = "data-analyzer",
            input = buildJsonObject { put("dataset", "s3://example/sales.csv") },
        ))
        client.receive().takeWhile { env ->
            when (val p = env.payload) {
                is JobCompleted -> { println("final: ${p.result}"); false }
                is JobFailed -> { println("error: ${p.code} ${p.message}"); false }
                else -> { println("[${env.type}] ${env.payload}"); true }
            }
        }.collect {}
        client.send(session.sessionId, SessionClose())
    }
}
```

This is the whole shape of the SDK: open a session, submit work, consume an ordered event stream, get a terminal result or error. Everything below is detail on those four moves.

## Concepts

ARCP organizes everything around four concerns — **identity**, **durability**, **authority**, and **observability** — expressed through five core objects:

- **Session** — a connection between a client and a runtime. A session carries identity (a bearer token), negotiates a feature set in a `hello`/`welcome` handshake, and is *resumable*: if the transport drops, you reconnect with a resume token and the runtime replays buffered events. Jobs outlive the session that started them. See [§6](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Job** — one unit of agent work submitted into a session. A job has an identity, an optional idempotency key, a resolved agent version, and a lifecycle that ends in exactly one terminal state: `success`, `error`, `cancelled`, or `timed_out`. See [§7](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Event** — the ordered, session-scoped stream a job emits: logs, thoughts, tool calls and results, status, metrics, artifact references, progress, and streamed result chunks. Events carry strictly monotonic sequence numbers so the stream survives reconnects gap-free. See [§8](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Lease** — the authority a job runs under, expressed as capability grants (`fs.read`, `fs.write`, `net.fetch`, `tool.call`, `agent.delegate`, `cost.budget`, `model.use`). The runtime enforces the lease at every operation boundary; a job can never act outside it. Leases may carry a budget and an expiry, and may be subset and handed to sub-agents via delegation. See [§9](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).
- **Subscription** — read-only attachment to a job started elsewhere (e.g. a dashboard watching a job a CLI submitted). A subscriber observes the live event stream but cannot cancel or mutate the job. Distinct from *resume*, which continues the original session and carries cancel authority. See [§7.6](https://github.com/agentruntimecontrolprotocol/spec/blob/main/docs/draft-arcp-1.1.md).

The SDK models each of these as first-class objects; the rest of this README shows how.

## Guides

### Sessions and resume

Open a session, negotiate features, and reconnect transparently after a transport drop using the resume token — jobs keep running server-side while you're gone.

```kotlin
import dev.arcp.client.ARCPClient
import dev.arcp.messages.Capabilities
import dev.arcp.transport.MemoryTransport

val (clientTransport, _) = MemoryTransport.pair()
ARCPClient(
    transport = clientTransport,
    auth = ARCPClient.bearer(System.getenv("ARCP_TOKEN")),
    client = ARCPClient.defaultClientInfo(principal = "resumable"),
    capabilities = Capabilities(
        streaming = true,
        heartbeatIntervalSeconds = 30,
    ),
).use { client ->
    val session = client.open()                 // four-message handshake (RFC §8.1)
    println("session id: ${session.sessionId}")
    println("negotiated caps: ${session.capabilities}")
    println("lease expires: ${session.lease?.expiresAt}")
    // The runtime tracks `last_event_seq` for this session in its EventLog; on a
    // transport drop, opening a new ARCPClient and replaying events from the
    // recorded sequence number is the resume path.
}
```

### Submitting jobs

Submit a job with an agent (optionally version-pinned as `name@version`), an input, and an optional lease request, idempotency key, and runtime limit.

```kotlin
import dev.arcp.messages.JobSubmit
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val requestId = client.send(
    session.sessionId,
    JobSubmit(
        agent = "weekly-report@2.1.0",
        input = buildJsonObject { put("week", "2026-W19") },
        leaseRequest = buildJsonObject {
            put("net.fetch", buildJsonArray { add("s3://reports/**") })
        },
        leaseConstraints = buildJsonObject {
            put("expires_at", "2026-05-22T12:00:00Z")
        },
        idempotencyKey = "weekly-report-2026-W19",
        maxRuntimeSec = 300,
    ),
)
println("submitted: $requestId — await job.accepted with this correlationId")
```

### Consuming events

Iterate the ordered event stream — `log`, `thought`, `tool_call`, `tool_result`, `status`, `metric`, `artifact_ref`, `progress`, `result_chunk` — and optionally acknowledge progress so the runtime can release buffered events early.

```kotlin
import dev.arcp.client.ResultChunkAssembler
import dev.arcp.messages.JobCompleted
import dev.arcp.messages.JobFailed
import dev.arcp.messages.JobProgress
import dev.arcp.messages.JobResultChunk
import dev.arcp.messages.JobStatusEvent
import dev.arcp.messages.Metric
import kotlinx.coroutines.flow.takeWhile

val chunks = ResultChunkAssembler()
client.receive().takeWhile { env ->
    when (val p = env.payload) {
        is JobStatusEvent  -> { println("status: ${p.phase} ${p.body}"); true }
        is JobProgress     -> { println("progress: ${p.percent}% ${p.message}"); true }
        is Metric          -> { println("metric: ${p.name}=${p.value} ${p.unit}"); true }
        is JobResultChunk  -> { chunks.accept(p); true }
        is JobCompleted    -> { println("result: ${p.result}"); false }
        is JobFailed       -> { println("failed: ${p.code} ${p.message}"); false }
        else               -> true
    }
}.collect {}
```

### Leases and budgets

Request capabilities, a budget, and an expiry; read budget-remaining metrics as they arrive; handle the runtime's enforcement decisions.

```kotlin
import dev.arcp.error.ARCPException
import dev.arcp.lease.BudgetAmount
import dev.arcp.lease.CostBudget
import dev.arcp.lease.Currency
import dev.arcp.messages.JobSubmit
import dev.arcp.messages.Metric
import dev.arcp.messages.StandardMetrics
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

val budget = CostBudget(listOf(BudgetAmount(Currency("USD"), BigDecimal("1.00"))))

client.send(
    session.sessionId,
    JobSubmit(
        agent = "web-research",
        input = buildJsonObject { put("iterations", 8) },
        leaseRequest = buildJsonObject {
            put("tool.call", buildJsonArray { add("search.*"); add("fetch.*") })
            put("cost.budget", buildJsonArray {
                budget.budgets.forEach { add(it.render()) }
            })
        },
        leaseConstraints = buildJsonObject {
            put("expires_at", "2026-05-22T13:00:00Z")
        },
    ),
)

try {
    client.receive().collect { env ->
        val m = env.payload as? Metric ?: return@collect
        if (m.name == StandardMetrics.COST_BUDGET_REMAINING) {
            println("budget remaining: ${m.value} ${m.unit}")
        }
    }
} catch (e: ARCPException.BudgetExhausted) {
    // Never retryable — request a fresh budget on a new submit instead.
    println("budget exhausted for ${e.currency}")
}
```

### Error handling

Catch the typed error taxonomy and respect the `retryable` flag — `LEASE_EXPIRED` and `BUDGET_EXHAUSTED` are never retryable; a naive retry fails identically.

```kotlin
import dev.arcp.error.ARCPException
import dev.arcp.error.ErrorCode

try {
    val session = client.open()
    // ... submit, collect events ...
} catch (e: ARCPException) {
    when (e.code) {
        ErrorCode.LEASE_EXPIRED,
        ErrorCode.BUDGET_EXHAUSTED -> throw e   // resubmit with fresh lease / budget
        ErrorCode.UNAUTHENTICATED -> throw e   // refresh credentials, then reopen
        else -> if (e.retryable) {
            // safe to retry with backoff (e.g. UNAVAILABLE, INTERNAL)
        } else {
            throw e
        }
    }
}
```

## Feature support

ARCP features this SDK negotiates during the `hello`/`welcome` handshake:

| Feature flag | Status |
|---|---|
| `heartbeat` | Supported |
| `ack` | Partial |
| `list_jobs` | Supported |
| `subscribe` | Partial |
| `lease_expires_at` | Supported |
| `cost.budget` | Supported |
| `model.use` | Supported |
| `provisioned_credentials` | Supported |
| `progress` | Supported |
| `result_chunk` | Supported |
| `agent_versions` | Supported |

`ack` and `subscribe` are wired into the runtime and message catalog (envelopes round-trip, the runtime dispatches them), but `ARCPClient` does not yet expose convenience methods for either — they're driven via `client.send(...)` with the raw `Ack` / `Subscribe` message types.

## Transport

ARCP is transport-agnostic. This SDK ships an in-memory transport (`MemoryTransport`) used by the integration test suite and for in-process embedding of a runtime; the WebSocket and stdio transports defined in the spec are planned and not yet on the public API surface. Construct one with `MemoryTransport.pair()` for a connected (client, runtime) pair, and pass the appropriate end into the `ARCPClient(transport = ...)` or `ARCPRuntime.accept(transport)` call.

## API reference

Full API reference — every type, method, and event payload — is in [`docs/`](docs/) and as Dokka-generated module pages under [`docs/modules/`](docs/modules/).

## Versioning and compatibility

This SDK speaks **ARCP v1.1 (draft)**. The SDK follows semantic versioning independently of the protocol; the protocol version it negotiates is shown above and in `session.hello`. A runtime advertising a different ARCP MAJOR is not guaranteed compatible. Feature mismatches degrade gracefully: the effective feature set is the intersection of what the client and runtime advertise, and the SDK will not use a feature outside it.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Protocol questions and proposed changes belong in the [spec repository](https://github.com/agentruntimecontrolprotocol/spec); SDK bugs and feature requests belong here.

## License

Apache-2.0 — see [`LICENSE`](LICENSE).
