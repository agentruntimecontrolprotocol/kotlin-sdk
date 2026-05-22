# Cancellation & Delegation

## Cancellation

`Cancel` is a *cooperative* cancellation request — the target is asked to
stop, not killed (RFC §10.4). The runtime may accept or refuse.

```kotlin
// Cancel a job
client.send(sessionId, Cancel(
    target   = CancelTarget.JOB,
    targetId = jobId.value,
    reason   = "user aborted",
    deadlineMs = 5_000,           // give the job 5 s to clean up
))

// Handle the runtime's reply
is CancelAccepted -> println("Job ${msg.targetId} will be cancelled")
is CancelRefused  -> println("Refused: ${msg.reason}")
```

### Cancel targets

| `CancelTarget` | What is cancelled |
|----------------|------------------|
| `JOB` | A single job by `jobId` |
| `STREAM` | An open stream by `streamId` |
| `SESSION` | The entire session |

## Interrupt

`Interrupt` pauses a job and asks it a question — it is *not* a cancel
(RFC §10.5):

```kotlin
client.send(sessionId, Interrupt(
    target   = CancelTarget.JOB,
    targetId = jobId.value,
    prompt   = "Should I continue with the destructive step?",
))
```

The job transitions to `BLOCKED` and waits for the caller's answer. Resume
with a `JobSubmit` or `Cancel` as appropriate.

## Agent delegation

When a job needs to hand work to another agent it sends `AgentDelegate`
(RFC §10.3). The child job is automatically constrained to a subset of the
parent's lease:

```kotlin
// Inside an agent's execution context:
session.send(AgentDelegate(
    agent        = AgentRef.parse("classifier@1.0.0"),
    input        = classifyInput,
    leaseRequest = buildJsonObject { put("cost.budget", buildJsonArray { add("USD:0.50") }) },
    reason       = "delegating classification sub-task",
))
```

The runtime creates a child job, enforces the lease subset rule, and fans
out results back to the parent. `ARCPException.LeaseSubsetViolation` is
thrown if the child requests a broader budget than the parent's remaining
balance.

## Agent handoff

`AgentHandoff` terminates the current agent and transfers execution to
another:

```kotlin
session.send(AgentHandoff(
    agent  = AgentRef.parse("writer@1.0.0"),
    input  = writerInput,
    reason = "planning complete; handing off to writer",
))
```

Unlike delegation, handoff does not create a child — the current job ends
and a new one begins.

## Ping / Pong — liveness

Use `Ping`/`Pong` to check whether the remote end is still alive:

```kotlin
client.send(sessionId, Ping(nonce = "hello"))
is Pong -> println("Alive! nonce=${msg.nonce}")
```

## Ack / Nack

Every command receives either an `Ack` (success) or a `Nack` (failure):

```kotlin
is Ack  -> println("Command ${msg.ackFor} succeeded")
is Nack -> {
    println("Command ${msg.nackFor} failed: ${msg.code} — ${msg.message}")
    if (msg.retryable == true) retry()
}
```

`Nack.retryable` overrides the `ErrorCode.retryableByDefault` flag when set.
