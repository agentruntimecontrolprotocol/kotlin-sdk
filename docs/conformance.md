# Conformance

This document maps ARCP v1.1 RFC sections to their Kotlin SDK implementations.

## Implementation status

| RFC § | Title | Status | Implementation |
|-------|-------|--------|----------------|
| §6.1 | Envelope format | ✅ | `envelope/Envelope.kt` |
| §6.2 | Message catalog | ✅ | `messages/*.kt` |
| §6.3 | Resume | ✅ | `store/EventLog.kt` |
| §6.4 | Idempotency | ✅ | `store/EventLog.kt` |
| §6.6 | `session.list_jobs` / `session.jobs` | ✅ | `messages/Session.kt`, `runtime/ARCPRuntime.kt` |
| §7 | Capability negotiation | ✅ | `runtime/CapabilityNegotiation.kt` |
| §7.5 | Agent versioning (`name@version`) | ✅ | `runtime/AgentRegistry.kt` |
| §8 | Session handshake | ✅ | `runtime/ARCPRuntime.kt`, `client/ARCPClient.kt` |
| §8.2 | Authentication (`bearer`, `signed_jwt`) | ✅ | `auth/BearerAuth.kt`, `auth/JwtAuth.kt` |
| §8.4 | `result_chunk` streaming | ✅ | `messages/Execution.kt`, `client/ARCPClient.kt` |
| §9 | Leases & budgets | ✅ | `lease/` |
| §9.6 | `cost.budget` lease | ✅ | `lease/CostBudget.kt`, `lease/BudgetRegistry.kt` |
| §9.7 | `model.use` lease | ✅ | `lease/ModelUseLease.kt` |
| §9.8 | Provisioned credentials | ✅ | `credentials/` |
| §10 | Cancellation & delegation | ✅ | `messages/Control.kt`, `runtime/ARCPRuntime.kt` |
| §11 | Observability / metrics | ✅ | `messages/Telemetry.kt`, `trace/TraceContext.kt` |
| §12 | Error taxonomy | ✅ | `error/ErrorCode.kt`, `error/ARCPException.kt` |
| §15 | Vendor extensions | ✅ | `extensions/ExtensionRegistry.kt` |
| §16 | Artifacts | ✅ | `messages/Artifacts.kt` |
| §17.1 | Distributed tracing (W3C TraceContext) | ✅ | `trace/TraceContext.kt` |
| §18 | Error codes | ✅ | `error/ErrorCode.kt` |
| §19 | Session resume | ✅ | `store/EventLog.kt` |
| §21 | Extension naming (`arcpx.*`) | ✅ | `extensions/ExtensionRegistry.kt` |
| §22 | Reference transports | ✅ (memory) | `transport/MemoryTransport.kt` |
| WebSocket transport | — | 🔜 v0.2 | `transport/WebSocketTransport.kt` |
| Stdio transport | — | 🔜 v0.2 | `transport/StdioTransport.kt` |

## Notable v1.1 additions

- **`session.list_jobs` / `session.jobs`** (§6.6): principal-scoped in-memory
  inventory with cursor pagination.
- **Agent versioning** (§7.5): `name@version` parsing, advertised descriptors,
  and `AGENT_VERSION_NOT_AVAILABLE` error.
- **`result_chunk`** (§8.4): wire payloads plus client-side chunk assembly.
- **`cost.budget`** (§9.6): budget parser, counters, subset checks, and
  `BUDGET_EXHAUSTED` error.
- **`model.use` and provisioned credentials** (§9.7, §9.8): lease matching,
  credential wire types, provisioner interface, in-memory implementation,
  redaction, issue/revoke hooks, and rotation status events.
- **Error taxonomy** (§12): `BUDGET_EXHAUSTED`, `AGENT_VERSION_NOT_AVAILABLE`,
  and `LEASE_SUBSET_VIOLATION` are recognized wire codes.

## Conformance testing

Integration tests live in `:tests` and target the public SDK surface over
`MemoryTransport`. Run with:

```bash
./gradlew :tests:test
```

For cross-language conformance tracking, refer to the ARCP spec repository
and shared issue milestones.
