# Recipes

Copy-paste solutions for complete, real-world ARCP patterns. Each recipe wires
together multiple protocol features around an actual LLM workload; unlike the
[`samples/`](../samples/) programs — which isolate a single concept — recipes
are end-to-end shapes you can adapt directly to production use cases.

Kotlin recipe source lives in [`recipes/`](../recipes/).

---

## [multi-agent-budget](../recipes/multi-agent-budget/) — OpenAI

A planner decomposes a question into sub-questions and delegates each to a
worker that carries a budget slice carved from the planner's remaining cap.

**Features demonstrated**

- `CostBudget` / `BudgetAmount` subset enforcement
- `LeaseGranted` / `LeaseRevoked` delegation handshake
- `BUDGET_EXHAUSTED` handling — sub-questions that no longer fit are skipped
  before the `agent.delegate` rather than failing mid-flight
- `StandardMetrics.COST_USD` emitted after each delegate so the runtime's
  subset check sees an honest remaining balance

**Key types**: `BudgetRegistry`, `BudgetCounter`, `ARCPException.BudgetExhausted`

See [guides/leases.md](guides/leases.md) and [guides/delegation.md](guides/delegation.md).

---

## [email-vendor-leases](../recipes/email-vendor-leases/) — Claude

A triage agent drives Claude through a tool-use loop with three tools, but the
lease grants only the two read-only ones. When the model proposes `send_reply`
the agent's lease validator throws `PERMISSION_DENIED` and feeds the denial
back to Claude, which observes the deny and returns a drafted-but-unsent reply.

**Features demonstrated**

- `PermissionRequest` / `PermissionGrant` / `PermissionDeny` flow
- `LeaseGranted` with `operation` constraints on individual tool names
- `EventEmit` with `arcpx.acme.email.v1.parsed` vendor event type — dashboards
  recognising the namespace can render parsed metadata specially
- `ExtensionRegistry.advertise("arcpx.acme.email.v1")` capability negotiation

**Key types**: `PermissionRequest`, `PermissionDeny`, `EventEmit`, `ExtensionRegistry`

See [guides/leases.md](guides/leases.md) and [guides/vendor-extensions.md](guides/vendor-extensions.md).

---

## [stream-resume](../recipes/stream-resume/) — GLM-5

A writer pipes GLM-5's streaming deltas into `StreamChunk` envelopes
(~200 chars each). The client deliberately drops the transport mid-stream,
opens a fresh connection with `Resume`, and the runtime replays every envelope
past the cutoff from the `EventLog` so reassembly completes seamlessly across
the gap.

**Features demonstrated**

- `StreamOpen` / `StreamChunk` / `StreamClose` with `ResultChunkEncoding.UTF8`
- `EventLog.openFile(path)` — every envelope lands in a SQLite log under a
  monotonic `event_seq`
- `Resume(afterMessageId, includeOpenStreams = true)` — client reconnects and
  receives only the envelopes it missed
- `JobCheckpoint` before the gap so resume can skip already-processed steps
- `Backpressure` signalling when the consumer falls behind

**Key types**: `EventLog`, `Resume`, `StreamChunk`, `JobCheckpoint`

See [guides/resume.md](guides/resume.md) and [guides/job-events.md](guides/job-events.md).

---

## [mcp-skill](../recipes/mcp-skill/) — MCP bridge

An MCP server fronts the `multi-agent-budget` planner so any MCP host
(Claude Code, Cursor, Desktop) can invoke it as a single `research` tool.
The bridge keeps one long-lived ARCP session; each MCP tool call submits a
fresh planner job and returns the terminal result as the tool's text response.

**Features demonstrated**

- Long-lived `ARCPClient` session shared across many short MCP calls
- `JobSubmit` with `idempotencyKey` so duplicate MCP retries don't re-execute
- `JobResult` / `JobCompleted` terminal-event collection
- `Ping` / `Pong` keepalive loop on the shared session

**Key types**: `ARCPClient`, `JobSubmit`, `JobCompleted`, `Ping`

See [guides/jobs.md](guides/jobs.md) and [transports.md](transports.md).

---

## Related reading

| Topic | Guide |
|-------|-------|
| Lease delegation and budget subsets | [guides/leases.md](guides/leases.md) |
| Vendor extension naming and `EventEmit` | [guides/vendor-extensions.md](guides/vendor-extensions.md) |
| `EventLog` append and replay | [guides/resume.md](guides/resume.md) |
| Job lifecycle and `JobSubmit` fields | [guides/jobs.md](guides/jobs.md) |
| Streaming chunks and `Backpressure` | [guides/job-events.md](guides/job-events.md) |
| `Ping`/`Pong`, `Ack`/`Nack`, `Cancel` | [guides/delegation.md](guides/delegation.md) |
