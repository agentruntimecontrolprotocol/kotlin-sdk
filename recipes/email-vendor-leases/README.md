# Recipe: email-vendor-leases

Demonstrates **read-only tool leases**, **vendor-extension events**, and
**graceful PERMISSION_DENIED** handling in a simulated email triage workflow
(RFC §13.4, §15).

```
Client
  └── triage@1.0.0  (lease: tool.call=[inbox_list, inbox_read])
        ├── inbox_list   → allowed
        ├── inbox_read   → allowed
        ├── send_reply   → PERMISSION_DENIED (self-enforced, not in lease)
        ├── event.emit   → Nack(UNIMPLEMENTED) — handled gracefully
        └── job.completed
```

The recipe shows two key RFC §13.4 patterns:

1. **Self-enforced lease check** — the agent (simulated client-side) inspects
   `ALLOWED_TOOLS` before each tool call and refuses `send_reply` because it
   was intentionally omitted from the `tool.call` lease.
2. **Vendor-extension events** — `event.emit` with type
   `x-vendor.acme.email.parsed` is Nacked as UNIMPLEMENTED by the runtime;
   the client logs the response and continues rather than crashing.

The runtime also provisions short-lived credentials (via
`InMemoryCredentialProvisioner`) because the job carries a tool lease. If
credentials are returned in `job.accepted`, the client prints their
scheme and endpoint.

## API keys

This recipe simulates tool calls without any real I/O, so **no API key is
required**.

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :recipes:runEmailVendorLeases
```

## What to look for

- `[client] credential issued …` — provisioned bearer credential for the job.
- `[client] tool  inbox_list  → N messages` — lease check passed, call allowed.
- `[client] tool  inbox_read(msg-001)  → subject: …` — read allowed.
- `[client] tool  send_reply  → PERMISSION_DENIED (self-enforced, …)` — agent
  self-gates without even contacting the runtime.
- `[client] event  x-vendor.acme.email.parsed  → nack(UNIMPLEMENTED) …` —
  vendor event Nacked; client continues gracefully.
- `[client] job completed` — terminal event confirming clean shutdown.
