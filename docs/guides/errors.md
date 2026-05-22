# Error Handling

ARCP errors map 1:1 to typed `ARCPException` subclasses. Catch the specific
subclass to access structured fields.

## Catching typed exceptions

```kotlin
try {
    client.open()
} catch (e: ARCPException.Unauthenticated) {
    println("Auth failed: ${e.message}")
} catch (e: ARCPException.BudgetExhausted) {
    println("Budget exhausted: ${e.currency} on job ${e.jobId}")
} catch (e: ARCPException.DeadlineExceeded) {
    if (e.retryable) retry()
}
```

## Retryable exceptions

Check `ARCPException.retryable` (or `ErrorCode.retryableByDefault`) before
retrying:

```kotlin
fun <T> withRetry(block: () -> T): T {
    repeat(3) { attempt ->
        try {
            return block()
        } catch (e: ARCPException) {
            if (!e.retryable || attempt == 2) throw e
            delay(100L * (attempt + 1))
        }
    }
    error("unreachable")
}
```

Retryable codes by default: `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`,
`ABORTED`, `INTERNAL`, `UNAVAILABLE`, `HEARTBEAT_LOST`,
`BACKPRESSURE_OVERFLOW`.

**Note**: `ARCPException.BudgetExhausted` overrides `retryable = false`
regardless of the wire code's default.

## Nack — wire-level failures

Operations that do not throw may instead return a `Nack` envelope:

```kotlin
is Nack -> {
    val code = ErrorCode.fromWire(msg.code.wire)
    println("Failed: $code — ${msg.message}")
    if (msg.retryable == true) {
        // Nack.retryable overrides the code default when non-null
        retry()
    }
}
```

`ErrorCode.fromWire("RATE_LIMITED")` maps the alias to `RESOURCE_EXHAUSTED`.

## Exception taxonomy

| Exception | Code | Retryable | Notes |
|-----------|------|-----------|-------|
| `ARCPException.Cancelled` | `CANCELLED` | No | Client cancelled |
| `ARCPException.Unknown` | `UNKNOWN` | No | Unexpected server error |
| `ARCPException.InvalidArgument` | `INVALID_ARGUMENT` | No | Malformed request |
| `ARCPException.DeadlineExceeded` | `DEADLINE_EXCEEDED` | Yes | Timed out |
| `ARCPException.NotFound` | `NOT_FOUND` | No | Resource absent |
| `ARCPException.AlreadyExists` | `ALREADY_EXISTS` | No | Duplicate `message_id` |
| `ARCPException.PermissionDenied` | `PERMISSION_DENIED` | No | Missing lease |
| `ARCPException.ResourceExhausted` | `RESOURCE_EXHAUSTED` | Yes | Rate-limit; alias `RATE_LIMITED` |
| `ARCPException.FailedPrecondition` | `FAILED_PRECONDITION` | No | Invalid state |
| `ARCPException.Aborted` | `ABORTED` | Yes | Concurrency conflict |
| `ARCPException.OutOfRange` | `OUT_OF_RANGE` | No | Value out of bounds |
| `ARCPException.Unimplemented` | `UNIMPLEMENTED` | No | Feature not available |
| `ARCPException.Internal` | `INTERNAL` | Yes | SDK bug; please report |
| `ARCPException.Unavailable` | `UNAVAILABLE` | Yes | Runtime temporarily down |
| `ARCPException.DataLoss` | `DATA_LOSS` | No | Message missing from log |
| `ARCPException.Unauthenticated` | `UNAUTHENTICATED` | No | Bad/missing token |
| `ARCPException.HeartbeatLost` | `HEARTBEAT_LOST` | Yes | Missed heartbeat deadlines |
| `ARCPException.LeaseExpired` | `LEASE_EXPIRED` | No | Lease TTL elapsed |
| `ARCPException.LeaseRevoked` | `LEASE_REVOKED` | No | Grantor revoked |
| `ARCPException.LeaseSubsetViolation` | `LEASE_SUBSET_VIOLATION` | No | Child exceeds parent |
| `ARCPException.BudgetExhausted` | `BUDGET_EXHAUSTED` | **No** | Budget cap reached |
| `ARCPException.AgentVersionNotAvailable` | `AGENT_VERSION_NOT_AVAILABLE` | No | Agent not registered |
| `ARCPException.BackpressureOverflow` | `BACKPRESSURE_OVERFLOW` | Yes | Channel full |

## Typed exception fields

### `ARCPException.BudgetExhausted`

```kotlin
} catch (e: ARCPException.BudgetExhausted) {
    logger.warn { "Job ${e.jobId} exceeded ${e.currency} budget" }
}
```

### `ARCPException.HeartbeatLost`

```kotlin
} catch (e: ARCPException.HeartbeatLost) {
    logger.error { "Job missed ${e.missedDeadlines} consecutive heartbeat deadlines" }
}
```

### `ARCPException.Unimplemented`

```kotlin
} catch (e: ARCPException.Unimplemented) {
    // e.message == "unimplemented (RFC §${e.section}): ${e.detail}"
    logger.warn { e.message }
}
```

### `ARCPException.AgentVersionNotAvailable`

```kotlin
} catch (e: ARCPException.AgentVersionNotAvailable) {
    logger.error { "Agent ${e.agent}@${e.version} is not registered" }
}
```

## Translating wire codes

```kotlin
val code: ErrorCode = ErrorCode.fromWire("RATE_LIMITED")
// → ErrorCode.RESOURCE_EXHAUSTED (alias mapping)

println(code.wire)               // "RESOURCE_EXHAUSTED"
println(code.retryableByDefault) // true
```
