# Job Events

Between `JobStarted` and the terminal event, a job may emit any number of
progress, heartbeat, chunk, and status events.

## JobStarted

Sent when the agent begins executing:

```kotlin
is JobStarted -> println("Job ${msg.jobId} is now running")
```

## JobProgress

Human-readable progress indication, useful for display:

```kotlin
is JobProgress -> println("[${msg.percent?.let { "$it%" } ?: "…"}] ${msg.message}")
```

`JobProgress` fields:

| Field | Type | Description |
|-------|------|-------------|
| `message` | `String` | Display text |
| `percent` | `Int?` | 0–100, optional |
| `data` | `JsonObject` | Structured progress data |

## JobHeartbeat

Agents send `JobHeartbeat` on the cadence negotiated in
`Capabilities.heartbeatIntervalSeconds` (default 30 s). The runtime marks
a job as dead if it misses consecutive beats.

```kotlin
is JobHeartbeat -> {
    println("Heartbeat seq ${msg.sequence}, state=${msg.state}, deadline=${msg.deadlineMs}ms")
}
```

`JobHeartbeat` fields:

| Field | Type | Description |
|-------|------|-------------|
| `sequence` | `Long` | Monotonically increasing beat number |
| `deadlineMs` | `Long` | Milliseconds until the next expected beat |
| `state` | `JobLifecycleState` | Current job state |

If the runtime detects missed beats it emits `ARCPException.HeartbeatLost`
with a `missedDeadlines` count. Set `Capabilities.heartbeatRecovery =
HeartbeatRecovery.BLOCK` to park the job rather than kill it.

## JobStatusEvent

General-purpose structured status update:

```kotlin
is JobStatusEvent -> println("Status: ${msg.status} phase=${msg.phase}")
```

## JobResultChunk — streaming results (RFC §8.4)

For large outputs the agent streams result fragments. Each chunk carries a
sequence number and a `more` flag:

```kotlin
val buffer = StringBuilder()
is JobResultChunk -> {
    buffer.append(msg.data)
    if (!msg.more) println("Full result: $buffer")
}
```

`JobResultChunk` fields:

| Field | Type | Description |
|-------|------|-------------|
| `resultId` | `String` | Groups chunks for the same result |
| `chunkSeq` | `Long` | Zero-based sequence within `resultId` |
| `data` | `String` | Payload fragment |
| `encoding` | `ResultChunkEncoding` | `UTF8` or `BASE64` |
| `more` | `Boolean` | `false` on the last chunk |

Enable streaming by advertising `Capabilities(streaming = true)` on both
sides.

### ResultChunkEncoding

| Value | Meaning |
|-------|---------|
| `UTF8` | `data` is plain UTF-8 text |
| `BASE64` | `data` is Base64-encoded binary |

## General streams (RFC §11)

For open-ended event, log, thought, or binary streams use the `stream.*`
envelope family:

```kotlin
is StreamOpen  -> println("Stream ${env.id} opened, kind=${msg.kind}")
is StreamChunk -> processChunk(msg.sequence, msg.data)
is StreamClose -> println("Stream closed after ${msg.totalChunks} chunks")
is StreamError -> throw RuntimeException("Stream error: ${msg.code}")
```

Use `Backpressure` to ask the sender to slow down:

```kotlin
client.send(sessionId, Backpressure(
    streamId              = streamId,
    desiredRatePerSecond  = 10,
))
```

## JobCompleted / JobFailed / JobCancelled

Terminal events end the job. Handle all three:

```kotlin
is JobCompleted -> {
    println("Completed in ${msg.runtimeMs}ms: ${msg.result}")
}
is JobFailed -> {
    println("Failed: code=${msg.error.code} ${msg.error.message}")
}
is JobCancelled -> {
    println("Cancelled: ${msg.reason}")
}
```
