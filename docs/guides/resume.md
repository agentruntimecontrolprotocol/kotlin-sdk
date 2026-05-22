# Session Resume

ARCP supports resuming a session after a transport disconnect without
re-running jobs. The `EventLog` records every envelope so the runtime can
replay what the client missed (RFC §§6.3, 6.4, 19).

## EventLog

`EventLog` is an append-only SQLite-backed event store. Two factory
functions create instances:

```kotlin
// In-memory (tests and samples)
val log = EventLog.openInMemory()

// Persistent file
val log = EventLog.openFile(Path("sessions.db"))
```

### Appending

```kotlin
val rowId: Long = log.append(envelope)
```

`append` throws `ARCPException.AlreadyExists` if an envelope with the same
`message_id` is already in the log — enforcing idempotency automatically.

### Replaying

```kotlin
val envelopes: Flow<Envelope> = log.replay(
    sessionId      = sessionId,
    afterMessageId = lastReceivedMessageId,  // null → replay from start
)
envelopes.collect { env -> /* deliver to client */ }
```

`replay` runs on `Dispatchers.IO` via JDBC; the returned `Flow` is cold and
completes when all matching rows have been emitted.

If `afterMessageId` is not found in the log, `EventLog.replay` throws
`ARCPException.DataLoss`. Always pass a `MessageId` that was actually
received, or `null` to start from the beginning.

### Idempotent operations

`EventLog` also supports idempotency keys for non-envelope operations:

```kotlin
val existing: String? = log.lookupIdempotent(idempotencyKey)
if (existing == null) {
    log.recordIdempotent(idempotencyKey, resultJson)
}
```

## Resume message

The client sends a `Resume` message to replay past the last received
envelope:

```kotlin
client.send(sessionId, Resume(
    sessionId      = sessionId,
    afterMessageId = lastMessageId,
    jobId          = jobId,          // optional: scope replay to one job
    includeOpenStreams = true,       // re-open any streams still active
))
```

| Field | Purpose |
|-------|---------|
| `sessionId` | Which session to resume |
| `afterMessageId` | Only replay envelopes after this ID |
| `jobId` | Narrow replay to a specific job (optional) |
| `checkpointId` | Resume from a named checkpoint (optional) |
| `includeOpenStreams` | Re-deliver open stream frames (optional) |

## Full resume pattern

```kotlin
// 1. Persist the last message ID seen
var lastSeen: MessageId? = null
session.envelopes.collect { env ->
    lastSeen = env.id
    process(env)
}

// 2. Later, reconnect and replay
val newClient = ARCPClient(transport = newTransport, auth = bearer, client = info, capabilities = caps)
val newSession = newClient.open()

if (lastSeen != null) {
    newClient.send(newSession.sessionId, Resume(
        sessionId      = originalSessionId,
        afterMessageId = lastSeen,
    ))
}
```

## Checkpoints

A job can be asked to save a checkpoint at its next safe point:

```kotlin
client.send(sessionId, CheckpointCreate(jobId = jobId, label = "before-step-3"))
// The job emits a JobCheckpoint envelope when ready
```

To restore:

```kotlin
client.send(sessionId, CheckpointRestore(
    jobId        = jobId,
    checkpointId = "ckpt_abc123",
))
```
