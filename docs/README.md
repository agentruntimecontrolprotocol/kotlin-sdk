# ARCP Kotlin SDK — Documentation

Reference Kotlin implementation of the
[Agent Runtime Control Protocol (ARCP) v1.1](https://github.com/agentruntimecontrolprotocol/spec).

---

## Start here

- [Getting started](getting-started.md) — install, quickstart, first session
- [Architecture](architecture.md) — layering diagram, module descriptions, wire format
- [Conformance](conformance.md) — spec section-by-section coverage table
- [Troubleshooting](troubleshooting.md) — common failure modes and fixes

---

## Guides

Concept-first explanations of each protocol surface:

| Guide | RFC |
|-------|-----|
| [Sessions](guides/sessions.md) | §6 |
| [Authentication](guides/auth.md) | §6.1 |
| [Resume & replay](guides/resume.md) | §6.3 |
| [Jobs](guides/jobs.md) | §7 |
| [Job events](guides/job-events.md) | §8 |
| [Leases & budgets](guides/leases.md) | §9 |
| [Delegation & handoff](guides/delegation.md) | §10 |
| [Observability](guides/observability.md) | §11 |
| [Errors](guides/errors.md) | §12 |
| [Vendor extensions](guides/vendor-extensions.md) | §15 |

---

## Modules

API reference for each Gradle module:

- [`arcp` (lib)](modules/arcp.md) — the protocol library
- [`arcp-cli` (cli)](modules/arcp-cli.md) — the `arcp` binary

---

## Reference

- [Transports](transports.md) — WebSocket, stdio, in-memory
- [CLI](cli.md) — `arcp serve`, `arcp submit`, `arcp replay`
- [Recipes](recipes.md) — copy-paste solutions for common patterns
