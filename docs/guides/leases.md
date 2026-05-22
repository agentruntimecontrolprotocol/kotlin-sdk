# Leases

ARCP v1.1 adds runtime-enforced lease capabilities that can be used directly by the Kotlin SDK or delegated to an upstream provider through provisioned credentials.

## `cost.budget`

`cost.budget` values use the wire form `currency:decimal`, for example `USD:5.00` or `credits:100`. The SDK parses these into `BudgetAmount` values and tracks them per job with `BudgetCounter`.

When a cost metric such as `cost.inference` arrives on a job envelope, the runtime decrements the matching currency counter. Once the remaining value reaches zero, the runtime reports `BUDGET_EXHAUSTED` with `retryable = false`.

Child jobs may only request budgets that are less than or equal to the parent's remaining budget, and they may not introduce a new currency.

## `model.use`

`model.use` constrains which model identifiers a job may use. Patterns are segment-aware globs:

- `tier-fast/*` matches `tier-fast/haiku` but not `tier-slow/haiku`.
- `provider/**` matches all models below `provider/`.

Delegated jobs must request a subset of the parent lease. A literal model such as `tier-fast/haiku` is a subset of `tier-fast/*`; broadening from a literal to `tier-fast/*` is rejected.

## Provisioned Credentials

When a `CredentialProvisioner` is configured, the runtime issues credentials after job lease finalization and attaches them to `job.accepted.credentials`. Credentials use this vendor-neutral shape:

```json
{
  "id": "cred_...",
  "scheme": "bearer",
  "value": "secret",
  "endpoint": "https://provider.example/v1",
  "constraints": {
    "cost.budget": ["USD:1.00"],
    "model.use": ["tier-fast/*"],
    "expires_at": "2026-05-09T13:00:00Z"
  }
}
```

The `value` field is treated as a secret. `Credential.toString()` redacts it, and job introspection should only expose credentials to the submitting principal.

On terminal job states (`completed`, `failed`, `cancelled`, or timeout), the runtime revokes outstanding credentials with retry. `CredentialStore.pendingRevocations()` is the durability hook used to retry revocation after restart.

Credential rotation is exposed through `ARCPRuntime.rotateCredential(...)`. It issues a replacement, revokes the prior credential, and can emit a `status` event with `phase = "credential_rotated"`.
