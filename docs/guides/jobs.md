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
    agent = "summarise@1.0.0",
    input = buildJsonObject { put("text", "...") },
))
```

`msgId` is the id of the `job.submit` envelope (i.e. the command id). The
runtime-assigned `JobId` arrives on the correlated `JobAccepted` reply.

`JobSubmit` fields:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `agent` | `String` | required | Wire-form `name` or `name@version` |
| `input` | `JsonObject` | `{}` | Agent-specific payload |
| `leaseRequest` | `JsonObject` | `{}` | Requested capabilities (e.g. `cost.budget`) |
| `leaseConstraints` | `JsonObject?` | `null` | Client-imposed constraints on sub-jobs |
| `idempotencyKey` | `String?` | `null` | Deduplicate resubmissions |
| `maxRuntimeSec` | `Long?` | `null` | Hard timeout in seconds |

`AgentRef.parse("summarise@1.0.0")` is available for callers that need a
typed reference (e.g. to read `.name`/`.version`); the wire field on
`JobSubmit` is the rendered string.

## JobAccepted

The runtime replies with `JobAccepted` carrying the assigned `jobId`, the
resolved `agent@version`, and any provisioned `credentials`:

```kotlin
// Receive loop (illustrative — real code uses Flow)
val accepted: JobAccepted = awaitMessage(msgId)
val jobId = accepted.jobId
val agent = accepted.agent              // resolved name@version, may be null
val creds = accepted.credentials        // null unless a provisioner is configured
```

If the agent or version is not registered the runtime replies with `Nack`
carrying `ErrorCode.AGENT_VERSION_NOT_AVAILABLE`.

## Registering agents

Agents must be registered before the runtime starts accepting connections.
`register` takes one version at a time; mark exactly one version as the
default if you want bare-name references to resolve:

```kotlin
val registry = AgentRegistry()
registry.register("summarise", "1.0.0")
registry.register("summarise", "2.0.0", default = true)

val runtime = ARCPRuntime(
    supportedCapabilities = Capabilities(),
    agentRegistry         = registry,
)
```

`AgentRef.parse("summarise@1.0.0")` parses the `name@version` wire form.
`AgentRef.parse("summarise")` references the agent without pinning a version;
the runtime resolves to the default (or the first registered version if
none is marked default).

## Awaiting completion

```kotlin
// Pseudocode — collect from the client's envelope flow
client.receive()
    .filter { it.jobId == jobId }
    .collect { env ->
        when (val msg = env.payload) {
            is JobCompleted -> { println("result: ${msg.result}"); cancel() }
            is JobFailed    -> { throw RuntimeException("${msg.code}: ${msg.message}") }
            is JobCancelled -> { println("cancelled: ${msg.reason}") }
            else            -> { /* progress / heartbeat / chunk */ }
        }
    }
```

## Idempotency

Pass an `idempotencyKey` to ensure at-most-once dispatch:

```kotlin
client.send(session.sessionId, JobSubmit(
    agent          = "summarise",
    input          = input,
    idempotencyKey = "req-${requestId}",
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
