# Skill: research

**Version**: 1.0.0  
**Agent**: planner

## Description

Performs structured research on a given topic and returns a summarised answer
with source references.  The skill runs inside the `planner` ARCP agent and is
exposed to MCP hosts via the `McpBridge` (see `../Bridge.kt`).

## Input schema

| Field   | Type   | Required | Description                    |
|---------|--------|----------|--------------------------------|
| `query` | string | yes      | The research question to answer |

## Output schema

| Field     | Type            | Description                                          |
|-----------|-----------------|------------------------------------------------------|
| `summary` | string          | One-sentence summary of research findings            |
| `sources` | array of string | URLs or identifiers of supporting reference materials |

## Lease requirements

None — the research skill operates on an internal knowledge corpus and does
not require `tool.call` leases or provisioned credentials.

## Example

**Input** (passed as `job.submit.input`):

```json
{
  "query": "advances in multi-agent coordination protocols"
}
```

**Output** (returned in `job.completed.result`):

```json
{
  "summary": "Research complete for: advances in multi-agent coordination protocols",
  "sources": [
    "https://example.invalid/paper-1",
    "https://example.invalid/paper-2"
  ]
}
```

## MCP tool surface

When exposed through the bridge, this skill appears as a single MCP tool:

```json
{
  "name": "research",
  "description": "Research a topic using the ARCP planner skill",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": { "type": "string", "description": "The research query to run" }
    },
    "required": ["query"]
  }
}
```
