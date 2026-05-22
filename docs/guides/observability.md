# Observability

ARCP provides structured logging, metrics, traces, and generic events as
first-class protocol messages (RFC §§11, 17).

## Distributed tracing — W3C TraceContext

`TraceContext` propagates W3C trace context through coroutines as a
`CoroutineContext` element (RFC §17.1):

```kotlin
// Start a new root trace
withContext(TraceContext.newRoot()) {
    withSpan("session-open") {
        val session = client.open()

        withSpan("submit-job") {
            client.send(session.sessionId, JobSubmit(...))
        }
    }
}
```

### Accessing the current trace

```kotlin
val trace: TraceContext? = currentTrace()
println("trace=${trace?.traceId} span=${trace?.spanId} parent=${trace?.parentSpanId}")
```

### `withSpan`

`withSpan(name, block)` creates a child span that inherits `traceId` from
the ambient context and generates a fresh `spanId`:

```kotlin
withSpan("classify") {
    // currentTrace()?.traceId == parent trace ID
    // currentTrace()?.parentSpanId == parent span ID
    doWork()
}
```

### Wire representation

`TraceSpan` is the wire message sent when emitting a completed span:

```kotlin
client.send(sessionId, TraceSpan(
    name      = "classify",
    kind      = "CLIENT",
    startedAt = start,
    endedAt   = Instant.now(),
    attributes = buildJsonObject { put("model", "claude-3") },
))
```

## Structured logging

```kotlin
client.send(sessionId, Log(
    level      = LogLevel.INFO,
    message    = "Job started",
    attributes = buildJsonObject { put("job_id", jobId.value) },
))
```

`LogLevel` values (in order of severity): `TRACE`, `DEBUG`, `INFO`, `WARN`,
`ERROR`, `CRITICAL`.

## Metrics

```kotlin
client.send(sessionId, Metric(
    name  = StandardMetrics.TOKENS_USED,
    value = JsonPrimitive(1234),
    unit  = "tokens",
    dims  = buildJsonObject { put("kind", "output") },
))
```

### Standard metric names (RFC §17.3.1)

| Constant | Wire name | Unit |
|----------|-----------|------|
| `TOKENS_USED` | `tokens.used` | `tokens`; `dims.kind ∈ input,output,cache_read,cache_write` |
| `COST_USD` | `cost.usd` | `USD` (decimal, ≤ 6 fractional digits) |
| `COST_BUDGET_REMAINING` | `cost.budget.remaining` | per-currency budget |
| `GPU_SECONDS` | `gpu.seconds` | `s` |
| `TOOL_INVOCATIONS` | `tool.invocations` | count |
| `LATENCY_MS` | `latency.ms` | `ms`; `dims.phase ∈ queue,exec,total` |
| `BYTES_IN` | `bytes.in` | `bytes` |
| `BYTES_OUT` | `bytes.out` | `bytes` |
| `ERRORS_TOTAL` | `errors.total` | count; `dims.code` = canonical error code |

Non-standard metrics must be namespaced (e.g. `acme.model.cache_hits`).

## Generic events

`EventEmit` carries arbitrary structured events:

```kotlin
client.send(sessionId, EventEmit(
    eventType = "x-vendor.acme.email.parsed",
    data      = buildJsonObject {
        put("subject",  "Re: Q3 budget")
        put("from",     "alice@example.com")
        put("thread_id", "t_abc123")
    },
))
```

Event types must match the `arcpx.*` naming convention if they are
vendor-defined (see [vendor-extensions.md](vendor-extensions.md)).

## Backpressure

When consuming a stream that delivers faster than the receiver can process,
send `Backpressure` to request a slower rate:

```kotlin
client.send(sessionId, Backpressure(
    streamId             = streamId,
    desiredRatePerSecond = 5,
    bufferRemainingBytes = 1024,
    reason               = "downstream slow",
))
```
