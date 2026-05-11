# ARCP Kotlin Samples

Fourteen single-purpose programs, each named for the protocol
primitive it demonstrates.

> **Illustrative, not runnable.** Each example imports from the
> in-repo `dev.arcp.*` package as if it were a published
> `dev.arcp:arcp-sdk:1.0` SDK. Setup boilerplate (transport URL,
> identity, auth) is elided with `val client: ARCPClient = TODO("...")`.
> LLM and framework calls live in tiny stub files (`Agents.kt`,
> `Steps.kt`, `Cheap.kt`, …) so the protocol code in `Main.kt` is what
> you read. The shared `com.arcp.samples._Wire.kt` provides the
> Python-style `client.envelope(...)` / `client.request(...)` /
> `client.events()` helpers that the v1.0 SDK will expose natively.

## The fourteen

| Directory | Demonstrates | Spec |
|---|---|---|
| [`subscriptions/`](./src/main/kotlin/com/arcp/samples/subscriptions) | Three Observer clients on one session, three filters, three sinks. | §5, §13 |
| [`leases/`](./src/main/kotlin/com/arcp/samples/leases) | Lease-gated shell agent. Read leases coarse, write leases scoped. | §15.4–§15.5 |
| [`lease_revocation/`](./src/main/kotlin/com/arcp/samples/lease_revocation) | Per-table leases with `lease.revoked` / `lease.extended` mid-flight. | §15.5 |
| [`permission_challenge/`](./src/main/kotlin/com/arcp/samples/permission_challenge) | Two-party permission challenge — generator asks, reviewer holds veto. | §15.4, §6.4 |
| [`delegation/`](./src/main/kotlin/com/arcp/samples/delegation) | `agent.delegate` fan-out + `JobMux` to demux events by `job_id`. | §14, §6.4 |
| [`handoff/`](./src/main/kotlin/com/arcp/samples/handoff) | `agent.handoff` with transcript packed as an artifact, runtime fingerprint pinned. | §14, §16, §8.3 |
| [`heartbeats/`](./src/main/kotlin/com/arcp/samples/heartbeats) | Worker federation; heartbeat-loss reroute via `idempotency_key`. | §10.3, §6.4 |
| [`capability_negotiation/`](./src/main/kotlin/com/arcp/samples/capability_negotiation) | Capability-driven peer routing; standard `cost.usd` rollups. | §7, §17.3.1, §18.3 |
| [`resumability/`](./src/main/kotlin/com/arcp/samples/resumability) | Crash and resume via `exitProcess(137)` + `resume` envelope. | §10, §19, §6.4 |
| [`reasoning_streams/`](./src/main/kotlin/com/arcp/samples/reasoning_streams) | `kind: thought` stream + a peer runtime that subscribes and delegates critiques back. | §11.4, §13, §14 |
| [`extensions/`](./src/main/kotlin/com/arcp/samples/extensions) | Custom `arcpx.sdr.*.v1` extension namespace with correct unknown-message handling. | §21 |
| [`human_input/`](./src/main/kotlin/com/arcp/samples/human_input) | `human.input.request` fanned across phone/email/Slack; first-wins resolution. | §12 |
| [`cancellation/`](./src/main/kotlin/com/arcp/samples/cancellation) | Cooperative `cancel` (terminate) vs `interrupt` (pause and ask). | §10.4–§10.5 |
| [`mcp/`](./src/main/kotlin/com/arcp/samples/mcp) | ARCP runtime fronting an MCP server: `tool.invoke` → MCP `call_tool`. | §20 |

## Conventions

- Kotlin 1.9+, JVM toolchain 21, kotlinx.coroutines for async work.
- Each example is one `Main.kt` (the protocol code) + 0–2 stub files
  named for what they elide (`Agents.kt`, `Steps.kt`, `Cheap.kt`,
  `Synth.kt`, `Work.kt`, `Channels.kt`, `Sql.kt`, `Upstream.kt`,
  `Sinks.kt`).
- `val client: ARCPClient = TODO("transport, identity, auth elided")`
  literally — transport, identity, and auth blocks are setup noise,
  not the point.
- Envelopes match RFC-0001 v2 exactly. Custom message types follow
  §21.1 `arcpx.<domain>.<name>.v<n>` naming.

## What's where in the SDK

- `dev.arcp.client.ARCPClient` — handshake driver. The samples extend
  this with Python-style mint/request/events helpers in
  `com.arcp.samples._Wire.kt` until the v1.0 SDK exposes them
  natively.
- `dev.arcp.envelope.Envelope`, `dev.arcp.error.ErrorCode`,
  `dev.arcp.error.ARCPException` — wire primitives.
- `dev.arcp.transport.Transport` / `MemoryTransport` — transports.
- `dev.arcp.store.EventLog` — SQLite schema reused by the
  `subscriptions` SQLite sink.

## Reading order

For a brisk tour: `subscriptions`, `leases`, `delegation`,
`resumability` (this one actually crashes and recovers),
`cancellation`, `extensions`, `mcp`. These seven exercise the bulk
of the protocol.

## Numbered samples

The `dev.arcp.samples.Sample0{1..6}*.kt` set under
`src/main/kotlin/dev/arcp/samples/` are the v0.1 walkthroughs that
ship with the in-repo runtime. They're complementary to the
fourteen RFC examples here: those drive a real `MemoryTransport`
and exercise the v0.1 surface, while `com.arcp.samples.*` show the
v1.0 protocol shape.
