# Troubleshooting

## Error codes

Every ARCP error maps to an `ErrorCode` constant (RFC §18.2). Catch
`ARCPException` subclasses to react to them:

```kotlin
try {
    client.open()
} catch (e: ARCPException.Unauthenticated) {
    println("Auth failed: ${e.message}")
} catch (e: ARCPException.BudgetExhausted) {
    println("Budget exhausted (${e.currency}) on job ${e.jobId}")
}
```

### Full error taxonomy

| Wire code | Retryable? | Exception class | Common cause |
|-----------|-----------|-----------------|--------------|
| `OK` | — | — | Success (non-error) |
| `CANCELLED` | No | `ARCPException.Cancelled` | Client cancelled the operation |
| `UNKNOWN` | No | `ARCPException.Unknown` | Unexpected server-side error |
| `INVALID_ARGUMENT` | No | `ARCPException.InvalidArgument` | Malformed request field |
| `DEADLINE_EXCEEDED` | Yes | `ARCPException.DeadlineExceeded` | Operation timed out |
| `NOT_FOUND` | No | `ARCPException.NotFound` | Resource or agent does not exist |
| `ALREADY_EXISTS` | No | `ARCPException.AlreadyExists` | Duplicate `message_id` in event log |
| `PERMISSION_DENIED` | No | `ARCPException.PermissionDenied` | Missing lease or permission |
| `RESOURCE_EXHAUSTED` | Yes | `ARCPException.ResourceExhausted` | Rate-limit hit (wire alias: `RATE_LIMITED`) |
| `FAILED_PRECONDITION` | No | `ARCPException.FailedPrecondition` | Operation not valid in current state |
| `ABORTED` | Yes | `ARCPException.Aborted` | Concurrency conflict; retry |
| `OUT_OF_RANGE` | No | `ARCPException.OutOfRange` | Value exceeds valid bounds |
| `UNIMPLEMENTED` | No | `ARCPException.Unimplemented` | Feature not yet implemented |
| `INTERNAL` | Yes | `ARCPException.Internal` | Runtime bug; report it |
| `UNAVAILABLE` | Yes | `ARCPException.Unavailable` | Runtime temporarily unreachable |
| `DATA_LOSS` | No | `ARCPException.DataLoss` | Message missing from event log |
| `UNAUTHENTICATED` | No | `ARCPException.Unauthenticated` | Bad or missing bearer/JWT token |
| `HEARTBEAT_LOST` | Yes | `ARCPException.HeartbeatLost` | Job missed consecutive heartbeat deadlines |
| `LEASE_EXPIRED` | No | `ARCPException.LeaseExpired` | Lease TTL passed before use |
| `LEASE_REVOKED` | No | `ARCPException.LeaseRevoked` | Runtime revoked the lease |
| `LEASE_SUBSET_VIOLATION` | No | `ARCPException.LeaseSubsetViolation` | Requested capability exceeds granted subset |
| `BUDGET_EXHAUSTED` | No | `ARCPException.BudgetExhausted` | Cost budget ceiling reached |
| `AGENT_VERSION_NOT_AVAILABLE` | No | `ARCPException.AgentVersionNotAvailable` | No matching `agent@version` registered |
| `BACKPRESSURE_OVERFLOW` | Yes | `ARCPException.BackpressureOverflow` | Subscription channel full |

---

## Common failure modes

### `ARCPException.Unauthenticated` at `client.open()`

The bearer token was rejected. Check:
- Token is not empty or whitespace.
- Server's `StaticBearerAuth` map includes the exact token.
- For JWT: the `sub` claim is non-blank, `aud` matches the configured audience,
  and the token has not expired.

### `ARCPException.AgentVersionNotAvailable`

The client requested `agent@version` that is not registered:

```kotlin
// Runtime must register the agent + version before accepting connections
val registry = AgentRegistry()
registry.register("summarise", "1.0.0", default = true)
val runtime = ARCPRuntime(
    supportedCapabilities = Capabilities(),
    agentRegistry         = registry,
)
```

### `ARCPException.BudgetExhausted`

The job's provisioned credential contained a `cost.budget` cap that was
reached. The exception carries the `currency` and `jobId`:

```kotlin
} catch (e: ARCPException.BudgetExhausted) {
    logger.warn { "Job ${e.jobId} exceeded ${e.currency} budget" }
}
```

### `ARCPException.HeartbeatLost`

The runtime stopped receiving heartbeat acknowledgements from a job. The
`missedDeadlines` property shows how many consecutive beats were missed.
If the job is an external subprocess, check that it is calling
`JobHeartbeat` on schedule.

### `ARCPException.DataLoss` during resume

`EventLog.replay()` could not find `afterMessageId` in the log for the given
session. This usually means the log was deleted or the wrong session ID was
used. Verify the SQLite file path and the `session_id` value.

### Build error: "Unable to locate a Java Runtime"

The Gradle wrapper cannot find JDK 21. Fix:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew build
```

### Detekt violation in CI

Run detekt locally to see the specific rule:

```bash
./gradlew :lib:detekt
```

Common fixes:
- **FunctionTooLong** — extract helpers; target ≤15 lines, hard cap 30.
- **ComplexMethod** — extract branches into named functions or `when` tables.
- **MagicNumber** — move numeric literals to `const val`.
- **ForbiddenVoid** — use `Unit` instead of `void`-style expressions.
