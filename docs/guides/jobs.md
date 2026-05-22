# Jobs

A *job* is a discrete unit of work submitted to a registered agent. Jobs
progress through a well-defined lifecycle and produce a terminal result.

## Lifecycle

```
JobSubmit ──> JobAccepted ──> JobStarted ──> JobCompleted
                                        └──> JobFailed
                                        └──> JobCancelled
```

Intermediate events (`JobProgress`, `JobHeartbeat`, `JobStatusEvent`,
`JobResultChunk`) may arrive between `JobStarted` and the terminal event.
See [job-events.md](job-events.md) for details.

## Submitting a job

```kotlin
val msgId: MessageId = client.send(session.sessionId, JobSubmit(
    agent   = AgentRef.parse("summarise@1.0.0"),
    input   = buildJsonObject { put("text", "...") },
))
```

`JobSubmit` fields:

| Field | Type | Description |
|-------|------|-------------|
| `agent` | `AgentRef` | `name` or `name@version` |
| `input` | `JsonElement` | Agent-specific payload |
| `leaseRequest` | `JsonObject?` | Requested capabilities (e.g. `cost.budget`) |
| `leaseConstraints` | `JsonObject?` | Client-imposed constraints on sub-jobs |
| `idempotencyKey` | `String?` | Deduplicate resubmissions |
| `maxRuntimeSec` | `Long?` | Hard timeout in seconds |

## JobAccepted

The runtime immediately replies with `JobAccepted`, carrying the assigned
`jobId` and the negotiated `leaseId`:

```kotlin
// Receive loop (illustrative — real code uses Flow)
val accepted: JobAccepted = awaitMessage(msgId)
val jobId = accepted.jobId
```

If the agent or version is not registered the runtime replies with `Nack`
carrying `ErrorCode.AGENT_VERSION_NOT_AVAILABLE`.

## Registering agents

Agents must be registered before the runtime starts accepting connections:

```kotlin
val registry = AgentRegistry()
registry.register("summarise", listOf("1.0.0", "2.0.0"))

val runtime = ARCPRuntime(
    supportedCapabilities = Capabilities(),
    agentRegistry         = registry,
)
```

`AgentRef.parse("summarise@1.0.0")` parses the `name@version` wire form.
`AgentRef.parse("summarise")` references the agent without pinning a version;
the runtime selects the default.

## Awaiting completion

```kotlin
// Pseudocode — collect from the session's envelope flow
session.envelopes
    .filter { it.jobId == jobId }
    .collect { env ->
        when (val msg = env.payload) {
            is JobCompleted -> { println("result: ${msg.result}"); cancel() }
            is JobFailed    -> { throw RuntimeException(msg.error.message) }
            is JobCancelled -> { println("cancelled: ${msg.reason}") }
            else            -> { /* progress / heartbeat / chunk */ }
        }
    }
```

## Idempotency

Pass an `idempotencyKey` to ensure at-most-once dispatch:

```kotlin
client.send(session.sessionId, JobSubmit(
    agent           = AgentRef.parse("summarise"),
    input           = input,
    idempotencyKey  = "req-${requestId}",
))
```

If the runtime has already processed a `JobSubmit` with the same key in the
same session, it returns the stored `JobAccepted` without re-running the job.

## Job lifecycle states

`JobLifecycleState` is carried in `JobHeartbeat` and `JobStatusEvent`:

| State | Meaning |
|-------|---------|
| `ACCEPTED` | Runtime accepted the submit |
| `QUEUED` | Waiting for an executor slot |
| `RUNNING` | Agent is actively executing |
| `BLOCKED` | Waiting for a resource (lease, tool reply) |
| `PAUSED` | Interrupted; waiting for resume |
| `COMPLETED` | Terminal success |
| `FAILED` | Terminal failure |
| `CANCELLED` | Terminated by client cancel request |
