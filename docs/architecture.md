# Architecture

## Diagram

<picture>
  <source media="(prefers-color-scheme: dark)"
          srcset="diagrams/architecture-dark.svg">
  <img alt="ARCP Kotlin SDK architecture"
       src="diagrams/architecture-light.svg">
</picture>

> The diagram includes `WebSocket` and `stdio` next to `Memory` to show the
> intended transport surface; only `MemoryTransport` ships in v0.1. The
> WebSocket and stdio transports land in v0.2.

## Layers

```
┌──────────────────────────────────────────────────────────────────────┐
│  Application / Agent                                                 │
├──────────────────────────┬───────────────────────────────────────────┤
│  ARCPClient              │  ARCPRuntime                              │
│  dev.arcp.client         │  dev.arcp.runtime                         │
├──────────────────────────┴───────────────────────────────────────────┤
│  Messages / Envelope                                                 │
│  dev.arcp.messages   dev.arcp.envelope                               │
├──────────────────────────────────────────────────────────────────────┤
│  Transport                                                           │
│  dev.arcp.transport (Memory; WebSocket / stdio land in v0.2)         │
├──────────────────────────────────────────────────────────────────────┤
│  Supporting services                                                 │
│  auth  credentials  lease  store  trace  extensions  ids  error      │
└──────────────────────────────────────────────────────────────────────┘
```

## Modules

### `:lib` — protocol library (`dev.arcp:arcp`)

The publishable artifact. All public API lives here.

| Package | Contents |
|---------|----------|
| `dev.arcp.envelope` | `Envelope` — the canonical wire container; custom serializer hoists the `type` discriminator per RFC §6.1 |
| `dev.arcp.messages` | Every RFC §6.2 message type as a `@Serializable @SerialName` data class implementing `MessageType` |
| `dev.arcp.client` | `ARCPClient` — opens sessions, sends messages, assembles result chunks |
| `dev.arcp.runtime` | `ARCPRuntime` — handshake, dispatch loop, capability negotiation, job inventory, budget tracking |
| `dev.arcp.transport` | `Transport` interface + `MemoryTransport` |
| `dev.arcp.auth` | `BearerAuth`, `JwtAuth`, `StaticBearerAuth` |
| `dev.arcp.credentials` | `Credential`, `CredentialStore`, `CredentialProvisioner` |
| `dev.arcp.lease` | `CostBudget`, `ModelUseLease`, `BudgetRegistry`, subset validation |
| `dev.arcp.store` | `EventLog` — append-only SQLite store with idempotency, replay, and resume |
| `dev.arcp.trace` | `TraceContext` — W3C TraceContext propagation |
| `dev.arcp.extensions` | `ExtensionRegistry` — vendor extension dispatch |
| `dev.arcp.ids` | Typed ID wrappers (`SessionId`, `JobId`, `MessageId`, ...) |
| `dev.arcp.error` | `ARCPException` hierarchy, `ErrorCode` enum |
| `dev.arcp.json` | `arcpJson` — pre-configured `Json` instance (lenient, ignores unknown keys) |

### `:cli` — the `arcp` binary (`dev.arcp:arcp-cli`)

A thin JVM command-line tool built on the library. See [CLI](cli.md).

### `:samples` — runnable examples

One Kotlin file (or small directory) per scenario. Each scenario is
independently runnable via `./gradlew :samples:run<NN>`. See the
`samples/` directory for the full list.

### `:tests` — integration tests

End-to-end tests that pair an `ARCPRuntime` with an `ARCPClient` over
`MemoryTransport`. These tests target the public SDK surface, not internals.

## Wire format

ARCP uses JSON over a bidirectional transport (RFC §6.1). Every message is
wrapped in an `Envelope`. The on-wire shape (top-level fields only; optional
fields are omitted when unset, and `priority` is omitted when `"normal"`):

```json
{
  "arcp":       "1.1",
  "id":         "msg_01234",
  "type":       "session.open",
  "timestamp":  "2026-05-09T13:00:00Z",
  "session_id": "sess_abcde",
  "payload":    { /* message-specific fields */ }
}
```

Additional optional envelope fields (RFC §6.1.1): `source`, `target`,
`job_id`, `stream_id`, `subscription_id`, `trace_id`, `span_id`,
`parent_span_id`, `correlation_id`, `causation_id`, `idempotency_key`,
`priority` (`low`/`normal`/`high`/`critical`), and `extensions`.

The `type` field drives polymorphic deserialization: `arcpJson` (a
`kotlinx.serialization` `Json` instance, configured with
`classDiscriminator = "type"`, `ignoreUnknownKeys = true`, and
`explicitNulls = false`) decodes the payload into the correct `MessageType`
subclass via `@SerialName` annotations. A custom `EnvelopeSerializer` then
hoists the `type` discriminator from the payload to the envelope root to
match the RFC §6.1 wire layout.

## RFC section map

| RFC § | Implementation |
|-------|----------------|
| §6.1 envelope | `envelope/Envelope.kt` |
| §6.2 message types | `messages/*.kt` |
| §6.3 resume / replay | `store/EventLog.kt` |
| §6.4 idempotency | `store/EventLog.kt` |
| §7 capability negotiation | `runtime/CapabilityNegotiation.kt` |
| §8 session handshake | `runtime/ARCPRuntime.kt`, `client/ARCPClient.kt` |
| §9 leases & budgets | `lease/`, `runtime/ARCPRuntime.kt` |
| §10 cancellation & delegation | `messages/Control.kt`, `runtime/ARCPRuntime.kt` |
| §11 observability / metrics | `messages/Telemetry.kt`, `trace/TraceContext.kt` |
| §12 error taxonomy | `error/ErrorCode.kt`, `error/ARCPException.kt` |
| §15 vendor extensions | `extensions/ExtensionRegistry.kt` |
| §18 error codes | `error/ErrorCode.kt` |
| §19 resume | `store/EventLog.kt` |
| §21 extensions | `extensions/ExtensionRegistry.kt` |
