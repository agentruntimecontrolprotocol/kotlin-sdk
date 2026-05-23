# Leases & Budgets

Leases are runtime-enforced capability grants. They limit what a job may
do — which models it may call, how much it may spend, which tools it may
invoke — and can be delegated to sub-jobs as equal or narrower subsets
(RFC §9).

## Permission request / grant flow

```
runtime ─── PermissionRequest ──> client
        <── PermissionGrant ───── (or PermissionDeny)
        ─── LeaseGranted ──────> client
```

```kotlin
is PermissionRequest -> {
    println("Runtime requests ${msg.permission} on ${msg.resource}")
    // Approve:
    client.send(sessionId, PermissionGrant(
        permission   = msg.permission,
        resource     = msg.resource,
        leaseSeconds = 300,
    ))
    // Or deny:
    // client.send(sessionId, PermissionDeny(msg.permission, msg.resource, "not allowed"))
}

is LeaseGranted -> {
    println("Lease ${msg.leaseId} granted, expires ${msg.expiresAt}")
}
```

## Lease refresh

A job can extend its lease before it expires:

```kotlin
client.send(sessionId, LeaseRefresh(
    leaseId                  = leaseId,
    requestedExtensionSeconds = 120,
))

is LeaseExtended -> println("Lease extended to ${msg.expiresAt}")
```

If the grantor revokes the lease before expiry:

```kotlin
is LeaseRevoked -> throw ARCPException.LeaseRevoked("Lease ${msg.leaseId}: ${msg.reason}")
```

## cost.budget

`cost.budget` values use the wire form `currency:decimal` (e.g. `USD:5.00`,
`credits:100`).

```kotlin
val budget = CostBudget(
    budgets = listOf(BudgetAmount.parse("USD:10.00")),
)
```

Include the budget in the job's `leaseRequest`:

```kotlin
client.send(sessionId, JobSubmit(
    agent        = "summarise@1.0.0",
    input        = input,
    leaseRequest = buildJsonObject {
        put("cost.budget", buildJsonArray { add("USD:5.00") })
    },
))
```

The runtime tracks spending per job with `BudgetRegistry`. When the counter
reaches zero it emits `ARCPException.BudgetExhausted`:

```kotlin
} catch (e: ARCPException.BudgetExhausted) {
    logger.warn { "Job ${e.jobId} exceeded ${e.currency} budget" }
}
```

### Delegation subset rule

A child job may only request a budget ≤ the parent's *remaining* balance:

```
parent budget: USD:5.00 (spent: USD:3.00, remaining: USD:2.00)
child request: USD:1.50  ✅
child request: USD:2.50  ❌ LEASE_SUBSET_VIOLATION
```

The runtime enforces this automatically; `ARCPException.LeaseSubsetViolation`
is thrown if the child's request exceeds the parent's remaining budget.

## model.use

`model.use` limits which model IDs a job may call. Patterns are
segment-aware globs:

| Pattern | Matches | Does not match |
|---------|---------|----------------|
| `tier-fast/*` | `tier-fast/haiku` | `tier-slow/haiku` |
| `provider/**` | `provider/v1/chat` | `other/v1/chat` |
| `**` | anything | — |

```kotlin
val lease = ModelUseLease(patterns = listOf("anthropic/*"))
lease.allows("anthropic/claude-3")   // true
lease.allows("openai/gpt-4o")        // false
```

Subset check:

```kotlin
ModelUseLease.subset(
    parent = ModelUseLease(listOf("tier-fast/**")),
    child  = ModelUseLease(listOf("tier-fast/haiku")),
)  // true — literal is subset of glob
```

## Provisioned credentials

When a `CredentialProvisioner` is configured, the runtime issues per-job
credentials after lease finalization. They arrive on `JobAccepted` as a
`List<Credential>?` and each entry's secret is redacted in `toString()`:

```kotlin
is JobAccepted -> {
    accepted.credentials.orEmpty().forEach { cred ->
        // cred.value is the actual secret — Credential.toString() redacts it
        println(cred)   // "Credential(id=..., scheme=bearer, endpoint=..., value=<redacted>)"
    }
}
```

Credential shape:

```json
{
  "id":       "cred_...",
  "scheme":   "bearer",
  "value":    "...",
  "endpoint": "https://provider.example/v1",
  "profile":  "default",
  "constraints": {
    "cost.budget": ["USD:1.00"],
    "model.use":   ["tier-fast/*"],
    "expires_at":  "2026-05-09T13:00:00Z"
  }
}
```

Credentials are automatically revoked on job termination. To rotate one
mid-job, pass the credential id (and optionally a transport on which the
runtime should emit a rotation status event):

```kotlin
runtime.rotateCredential(jobId, credentialId)                 // re-issue only
runtime.rotateCredential(jobId, credentialId, transport)      // re-issue + notify peer
```
