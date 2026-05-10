# ARCP Kotlin SDK — RFC Conformance

This document tracks the implementation status of every section of
[RFC-0001-v2.md](RFC-0001-v2.md) for the `kotlin-sdk` v0.1 release.

Status legend:

- ✅ **Implemented** — full surface present, tests cover the spec wording.
- 🟡 **Partial** — core present, edges deferred (v0.2 candidate). The specific deferral is described in each row's Notes column.
- ⏭️ **Deferred** — declared on the wire but unimplemented in runtime/client. `ARCPException.Unimplemented` is thrown if invoked.
- ❌ **Not started** — neither wire nor runtime support.

| § | Section | Status | Notes |
|---|---------|--------|-------|
| 1 | Goals | ✅ | N/A — informative. |
| 2 | Non-Goals | ✅ | N/A — informative. |
| 3 | Terminology | ✅ | N/A — informative. |
| 4 | Design Principles | ✅ | Honored end-to-end. |
| 5 | Architecture | ✅ | `ARCPRuntime` + `Transport` + `ARCPClient`. |
| 6.1 | Envelope | ✅ | Custom `EnvelopeSerializer` hoists `type` to envelope level. All §6.1.1 fields present including `correlation_id`, `causation_id`, `idempotency_key`, `priority`, `extensions`. |
| 6.2 | Message Types | ✅ | All §6.2 types present as `@SerialName` data classes implementing `MessageType`. |
| 6.3 | Command/Result/Event flow | 🟡 | Handshake fully wired. Job/stream flows defined as messages but full runtime dispatch is v0.2. |
| 6.4 | Delivery semantics | ✅ | `EventLog` enforces `(session_id, message_id)` idempotency. Logical idempotency keys (RFC §6.4) supported via `recordIdempotent` / `lookupIdempotent`. |
| 6.5 | Priority and QoS | 🟡 | Priority round-trips on the wire and is persisted. The runtime does not yet weight a scheduling queue — v0.2. |
| 7 | Capability Negotiation | ✅ | `runtime.negotiate(...)` computes the intersection; required-but-unsupported features yield `session.rejected` `UNIMPLEMENTED`. |
| 8.1 | Session Establishment | ✅ | Four-message handshake driven by `ARCPRuntime.accept` and `ARCPClient.open`. |
| 8.2 | Credentials — `bearer` | ✅ | `BearerAuth` interface; `StaticBearerAuth` for tests. |
| 8.2 | Credentials — `signed_jwt` | ✅ | `JwtAuth` via `nimbus-jose-jwt`. |
| 8.2 | Credentials — `none` | ✅ | Accepted only when `capabilities.anonymous: true` is negotiated. |
| 8.2 | Credentials — `mtls`, `oauth2` | ⏭️ | Sender returns `UNAUTHENTICATED`; data classes accept the scheme but the runtime refuses. v0.2. |
| 8.3 | Runtime Identity | ✅ | `RuntimeIdentity` returned in `session.accepted`. |
| 8.4 | Re-authentication | 🟡 | Wire format defined; runtime does not auto-issue `session.refresh` on policy events. v0.2. |
| 8.5 | Eviction | ✅ | `ARCPRuntime.evict()` emits `session.evicted` with canonical reason code. |
| 9 | Sessions | 🟡 | Stateless and stateful covered. Durable sessions (cross-reconnect persistence) deferred. v0.2. |
| 10.1 | Durable Jobs | ⏭️ | Wire types present (`JobAccepted`, `JobProgress`, etc.). `JobManager` is v0.2. |
| 10.2 | Job States | ⏭️ | `JobLifecycleState` enum present. `JobManager` state machine v0.2. |
| 10.3 | Heartbeats | ⏭️ | `JobHeartbeat` message present. Watchdog v0.2. |
| 10.4 | Cancellation | ⏭️ | `Cancel`, `CancelAccepted`, `CancelRefused` messages present. Cooperative cancel logic v0.2. |
| 10.5 | Interrupts | ⏭️ | `Interrupt` message present. Job/state coupling v0.2. |
| 10.6 | Scheduled Jobs | ❌ | Out of scope for v0.1. `JobSchedule` round-trips on the wire; runtime returns `nack UNIMPLEMENTED`. |
| 11.1 | Stream Kinds | ✅ | `StreamKind` enum: `text`, `binary`, `event`, `log`, `metric`, `thought`. |
| 11.2 | Backpressure | 🟡 | `Backpressure` message round-trips. Producer rate-limit honoring is v0.2. |
| 11.3 | Binary Encoding | 🟡 | Base64 in-envelope only. Sidecar binary frames out of scope. |
| 11.4 | Reasoning Streams | 🟡 | `StreamChunk` carries `role`/`content`/`redacted`. Stream pump that emits these is v0.2. |
| 12.1 | Input Requests | ⏭️ | `HumanInputRequest` / `HumanInputResponse` round-trip. Handler v0.2. |
| 12.2 | Choice Requests | ⏭️ | `HumanChoiceRequest` / `HumanChoiceResponse` round-trip. Handler v0.2. |
| 12.3 | Provenance / multi-channel | ⏭️ | First-response-wins semantics noted; relay logic v0.2. |
| 12.4 | Expiration | ⏭️ | `expires_at` round-trips. Deadline-watching coroutine v0.2. |
| 13 | Subscriptions | ⏭️ | All `Subscribe*` messages round-trip. `SubscriptionManager` v0.2. |
| 14 | Multi-agent | ⏭️ | Out of scope for v0.1. `AgentDelegate` / `AgentHandoff` round-trip; runtime returns `nack UNIMPLEMENTED`. |
| 15.1 | Permission Model | ✅ | `PermissionName` value class; vocabulary preserved. |
| 15.2 | Sandboxing | ❌ | Out of scope — runtime-policy concern. |
| 15.3 | Trust Levels | ✅ | `TrustLevel` enum reflected in `RuntimeIdentity`. |
| 15.4 | Permission Challenge Flow | ⏭️ | Messages round-trip. `LeaseManager` runtime is v0.2. |
| 15.5 | Lease Lifecycle | ⏭️ | Lease messages round-trip; `LeaseManager` v0.2. |
| 15.6 | Trust Elevation | ❌ | Out of scope for v0.1. |
| 16 | Artifacts | ⏭️ | Wire types present and `EventLog` schema includes `arcp_artifact`. `ArtifactStore` runtime v0.2. |
| 17.1 | Tracing | 🟡 | `trace_id`, `span_id`, `parent_span_id` envelope fields supported. `CoroutineContext`-based propagation v0.2. |
| 17.2 | Structured Logs | ✅ | `Log` message + SLF4J via `kotlin-logging`. |
| 17.3 | Metrics | ✅ | `Metric` message; `StandardMetrics` constants for §17.3.1 reserved names. |
| 18 | Error Model | ✅ | `ErrorCode` enum + sealed `ARCPException` hierarchy. `RATE_LIMITED` decoded as `RESOURCE_EXHAUSTED`. |
| 19 | Resumability | 🟡 | Message-id-based replay via `EventLog.replay`. Checkpoint-based resume is v0.2. |
| 20 | MCP Compatibility | ❌ | Out of scope — informative section in the RFC. |
| 21.1 | Extension Naming | ✅ | `ExtensionRegistry.isValidName`. |
| 21.2 | Extension Negotiation | ✅ | Unadvertised extensions cause `session.rejected UNIMPLEMENTED`. |
| 21.3 | Unknown Message Handling | ✅ | `classifyUnknown` decides drop vs nack per §21.3. Runtime returns `nack UNIMPLEMENTED` for unknown core types. |
| 21.4 | Promotion to Core | ✅ | N/A — design rule. |
| 22 | Reference Transports — WebSocket | ⏭️ | `Transport` interface present. WebSocket impl v0.2. |
| 22 | Reference Transports — stdio | ⏭️ | `Transport` interface present. stdio impl v0.2. |
| 22 | Reference Transports — Memory (test) | ✅ | `MemoryTransport.pair()` for in-process tests. |

## v0.1 surface summary

The v0.1 surface is **specification-complete on the wire** — every RFC §6.2
message type is a typed data class that round-trips through `arcpJson` — and
**runtime-complete on the handshake / capability / extension surfaces**. Job
execution, streaming, human-in-the-loop, permissions, subscriptions,
artifacts, and the WebSocket/stdio transports remain v0.2 work. The codebase
is structured so each of these is a single coherent module addition rather
than a refactor.
