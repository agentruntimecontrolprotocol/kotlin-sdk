# Recipe: mcp-skill

Demonstrates the **MCP-to-ARCP bridge pattern** — exposing an ARCP skill as an
MCP tool that any MCP-compatible host (Claude Desktop, Cursor, etc.) can call
(RFC §16).

```
MCP Host (simulated)
  │
  │  JSON-RPC 2.0 (stdio)
  ▼
McpBridge
  ├── initialize        → serverInfo + capabilities
  ├── tools/list        → [{ name: "research", inputSchema: {...} }]
  └── tools/call research
        │
        │  ARCP
        ▼
      ARCPRuntime
        └── planner@1.0.0
              ├── job.submit        → job.accepted
              ├── [agent simulated] → job.completed(result)
              └── Ack
```

The recipe shows three bridge patterns from RFC §16:

1. **MCP capability handshake** — the bridge responds to `initialize` with its
   server info and declares `tools` capability.
2. **Skill-as-tool** — `tools/list` translates the ARCP skill manifest
   (`skills/research/SKILL.md`) into an MCP `Tool` object with a JSON Schema
   `inputSchema`.
3. **End-to-end `tools/call`** — `tools/call research` submits an ARCP
   `job.submit`, awaits `job.accepted`, simulates the agent result, sends
   `job.completed`, and wraps the result in an MCP `TextContent` block.

Because the recipe is self-contained (no real stdin or MCP client required),
`Main.kt` replays four canned JSON-RPC 2.0 messages — `initialize`,
`notifications/initialized`, `tools/list`, and `tools/call research` — against
the bridge to show the full flow.

## API keys

No external services are used — everything runs in-process with
`MemoryTransport`.

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :recipes:runMcpSkill
```

## What to look for

- `[bridge] connected  sessionId=…` — ARCP session established.
- `[host ] ← {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05",…}}`
  — MCP `initialize` handshake completed.
- `[host ] ← {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"research",…}]}}`
  — `tools/list` returned the research skill.
- `[bridge] tools/call research  query="advances in …"` — bridge received the
  `tools/call` and is dispatching to ARCP.
- `[bridge] job accepted  jobId=…` — runtime accepted the job.
- `[bridge] agent result  summary=…` — simulated agent produced a result.
- `[bridge] job completed  ackReceived=true` — runtime Acked the completion.
- `[host ] ← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":…}]}}`
  — final MCP tool result returned to the host.
