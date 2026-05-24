# Conformance

This document maps ARCP v1.1 RFC sections to their Kotlin SDK
implementations. Each row distinguishes three layers:

- **Catalog** — the wire types and serializers exist in `messages/*` and
  round-trip via `Envelope.serializer()`.
- **Helpers** — storage, registry, or in-memory data structures back the
  feature (`store/EventLog.kt`, `lease/*`, etc.).
- **Runtime dispatch** — `ARCPRuntime.handleEnvelope` actually handles
  the message and produces a correlated reply. Anything that falls
  through to the `UNIMPLEMENTED` Nack is *not* dispatched, even if
  catalog and helpers exist.

A surface is only **✅ Supported** when all three layers are present.
Partial rows give a brief note.

## Implementation status

| RFC § | Title | Catalog | Helpers | Dispatch | Notes |
|-------|-------|---------|---------|----------|-------|
| §6.1 | Envelope format | ✅ | ✅ | ✅ | `envelope/Envelope.kt` |
| §6.2 | Message catalog | ✅ | ✅ | partial | runtime handles ping, list_jobs, job.submit, metric, cancel, terminal job events, session.close; everything else returns `UNIMPLEMENTED` Nack |
| §6.3 | Resume | ✅ | ✅ | ⚠ deferred | `EventLog.replay()` exists; the runtime does not yet drive a `session.resume` flow |
| §6.4 | Idempotency | ✅ | ✅ | partial | `EventLog.recordIdempotent/lookupIdempotent`; runtime does not yet replay idempotent outcomes on resubmit |
| §6.6 | `session.list_jobs` / `session.jobs` | ✅ | ✅ | ✅ | `messages/Session.kt`, `runtime/JobInventory.kt`, `ARCPRuntime.handleListJobs` |
| §7 | Capability negotiation | ✅ | ✅ | ✅ | `runtime/CapabilityNegotiation.kt` |
| §7.5 | Agent versioning (`name@version`) | ✅ | ✅ | ✅ | `runtime/AgentRegistry.kt` |
| §8.1 | Session handshake (direct-credential) | ✅ | ✅ | ✅ | `runtime/ARCPRuntime.kt`, `client/ARCPClient.kt` |
| §8.1 | Session challenge/authenticate flow | ✅ | — | ⚠ deferred | runtime returns explicit `UNIMPLEMENTED` Nack; client cannot respond to a challenge |
| §8.2 | Authentication (`bearer`, `signed_jwt`) | ✅ | ✅ | ✅ | `auth/BearerAuth.kt`, `auth/JwtAuth.kt` (clock skew + optional issuer) |
| §8.4 | `result_chunk` streaming | ✅ | ✅ | partial | client-side `ResultChunkAssembler`; runtime does not emit result chunks itself |
| §9 | Leases & budgets | ✅ | ✅ | partial | runtime enforces `cost.budget` on `Metric` envelopes; lease subset checks live in `lease/LeaseSubset.kt` |
| §9.6 | `cost.budget` lease | ✅ | ✅ | ✅ | `lease/CostBudget.kt`, `lease/BudgetRegistry.kt`, `ARCPRuntime.handleMetric` |
| §9.7 | `model.use` lease | ✅ | ✅ | partial | parsed at submit; runtime does not yet enforce per-call model use |
| §9.8 | Provisioned credentials | ✅ | ✅ | ✅ | issued at job submit, revoked at terminal cleanup, retried with exponential backoff |
| §10.4 | Cancellation | ✅ | ✅ | ✅ | `messages/Control.kt`, `ARCPRuntime.handleCancel` (job-target only) |
| §10.5 | Interrupt | ✅ | — | ⚠ deferred | `Capabilities.interrupt` is negotiated; runtime does not yet dispatch the interrupt flow |
| §11 | Observability / metrics | ✅ | ✅ | partial | `Metric` is dispatched for `cost.*`; other metrics flow through as informational |
| §12 | Error taxonomy | ✅ | ✅ | ✅ | `error/ErrorCode.kt`, `error/ARCPException.kt` |
| §13 | Subscriptions | ✅ | partial | ⚠ deferred | `runtime/SubscriptionManager.kt`, `CompiledSubscriptionFilter.kt` exist; runtime does not dispatch `subscribe`/`unsubscribe` |
| §14 | Agent handoff / delegation | ✅ | — | ⚠ deferred | wire types exist; runtime returns `UNIMPLEMENTED` Nack |
| §15 | Vendor extensions | ✅ | ✅ | ✅ | `extensions/ExtensionRegistry.kt`; unknown extensions are silently dropped from negotiation rather than rejecting the session (RFC §21) |
| §16 | Artifacts | ✅ | ✅ | partial | `runtime/ArtifactStore.kt` for in-process use; runtime does not yet route `artifact.put`/`artifact.fetch` envelopes |
| §17.1 | Distributed tracing (W3C TraceContext) | ✅ | ✅ | ✅ | `trace/TraceContext.kt` |
| §18 | Error codes | ✅ | ✅ | ✅ | `error/ErrorCode.kt` |
| §19 | Session resume | ✅ | ✅ | ⚠ deferred | log-side replay works; runtime does not yet honor a `session.resume` envelope |
| §21 | Extension naming (`arcpx.*`) | ✅ | ✅ | ✅ | `extensions/ExtensionRegistry.kt`; unknown extensions on the wire produce `UNIMPLEMENTED` Nack at dispatch time |
| §22 | Reference transports | ✅ | ✅ (memory) | ✅ (memory) | `transport/MemoryTransport.kt` |
| WebSocket transport | — | — | 🔜 v0.2 | not on the public API |
| Stdio transport | — | — | 🔜 v0.2 | not on the public API |

## Notable v1.1 additions

- **`session.list_jobs` / `session.jobs`** (§6.6): principal-scoped
  in-memory inventory with opaque, sort-key-tied cursors.
- **Agent versioning** (§7.5): `name@version` parsing, advertised
  descriptors, and `AGENT_VERSION_NOT_AVAILABLE` error.
- **`result_chunk`** (§8.4): wire payloads plus client-side chunk
  assembly with content equality on the assembled bytes.
- **`cost.budget`** (§9.6): budget parser, counters, subset checks, and
  `BUDGET_EXHAUSTED` error. The counter clamps over-spend so a misbehaving
  agent cannot push the balance negative.
- **`model.use` and provisioned credentials** (§9.7, §9.8): lease
  matching, credential wire types, provisioner interface, in-memory
  implementation, redaction, issue/revoke hooks, and rotation status
  events.
- **Error taxonomy** (§12): `BUDGET_EXHAUSTED`,
  `AGENT_VERSION_NOT_AVAILABLE`, and `LEASE_SUBSET_VIOLATION` are
  recognized wire codes.

## Conformance testing

Integration tests live in `:tests` and target the public SDK surface
over `MemoryTransport`. Run with:

```bash
./gradlew :tests:test
```

For cross-language conformance tracking, refer to the ARCP spec
repository and shared issue milestones.

## Version reporting

The Gradle artifact ships as `dev.arcp:arcp:1.1.0`, but the in-process
`dev.arcp.Version.SDK_VERSION` constant (which feeds
`RuntimeIdentity.version` on handshakes and the CLI `version` output)
currently reads `0.1.0`. The two are intentionally decoupled while the
protocol-driving CLI subcommands catch up; both numbers will align in a
future release.
