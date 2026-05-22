# ARCP Kotlin SDK — Recipes

Runnable end-to-end examples for the most common ARCP integration patterns.
Each recipe is a self-contained directory with its own `README.md` and
Kotlin source files.  All recipes run **in-process** using `MemoryTransport`,
so no network setup or external server is needed.

| Recipe | Highlights |
|---|---|
| [multi-agent-budget](multi-agent-budget/) | Budget cascade across a planner → worker delegation tree (RFC §13.2, §9.6) |
| [email-vendor-leases](email-vendor-leases/) | Read-only tool leases, vendor-extension events, graceful PERMISSION_DENIED (RFC §13.4, §15) |
| [stream-resume](stream-resume/) | Chunked streaming + EventLog replay after a transport drop (RFC §8.4, §19) |
| [mcp-skill](mcp-skill/) | MCP ↔ ARCP bridge: expose a planner agent as a Claude Code skill (RFC §4.4) |

## Prerequisites

- **JDK 21** — `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- **Gradle wrapper** — all builds are run via `./gradlew`

## Running a recipe

```bash
# multi-agent budget cascade
./gradlew :recipes:runMultiAgentBudget

# email parsing with read-only leases and vendor events
./gradlew :recipes:runEmailVendorLeases

# streaming with EventLog resume
./gradlew :recipes:runStreamResume

# MCP ↔ ARCP bridge (stdio skill)
./gradlew :recipes:runMcpSkill
```

> **Note**: The LLM-backed recipes (`multi-agent-budget`, `email-vendor-leases`,
> `stream-resume`) require API keys exported in the environment before you run
> Gradle.  See each recipe's `README.md` for the exact variable name.

## Relationship to samples

The `samples/` tree demonstrates individual protocol features in isolation.
These recipes combine multiple features into realistic end-to-end flows that
mirror real production usage.
