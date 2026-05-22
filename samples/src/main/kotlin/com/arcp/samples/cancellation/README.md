# cancellation

One scenario that exercises the §10.4 cooperative-cancel control surface
that distinguishes ARCP from "agent over plain HTTP":

- `cancel`: cooperative termination with a deadline.

## Before ARCP

Cancellation usually means closing the socket or trying to kill the
process. The agent's tool was already mid-network call, so it
either completes anyway (silent waste of money) or leaves a
half-applied side effect. There's no notion of "stop cleanly"; the
only knob is "stop".

## With ARCP

```kotlin
// Stop the job; the runtime drives it to a clean checkpoint
// inside `deadlineMs` before terminating.
val ack = cancelJob(client, jobId, reason = "user_aborted", deadlineMs = 5_000)
val terminal = awaitTerminal(client, jobId)             // job.cancelled
```

## ARCP primitives

- `cancel` cooperative contract — RFC §10.4 (`cancel.accepted` /
  `cancel.refused`, `deadline_ms`, escalation to `ABORTED`).

## File tour

- `Main.kt` — cancel scenario driven by `args[0]` (`cancel`).

## Variations

- Send `cancel` against a `stream_id` instead of a `job_id` to
  terminate just one stream — terminal is a `stream.error` with
  `code: CANCELLED` (§10.4).
- Race many peers, cancel the slowest once N succeed.
