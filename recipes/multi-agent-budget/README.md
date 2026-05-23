# Recipe: multi-agent-budget

Demonstrates a **planner → worker** delegation tree where each agent receives
a proportional slice of the parent budget (RFC §13.2, §9.6).

```
Client
  └── planner@1.0.0  (budget: USD:0.50)
        └── worker@1.0.0  (budget: USD:0.20 — sub-slice granted by planner)
```

Each agent:
1. Inspects the `lease_request.cost.budget` granted to it.
2. Emits a `metric` envelope recording simulated spend.
3. Forwards a sub-budget to its downstream agent via a nested `job.submit`.
4. Reports `job.completed` when all downstream work is done.

The client prints running budget metrics as they arrive and confirms the
terminal `job.completed` from the planner.

## API keys

This recipe simulates LLM cost without a real API call, so **no API key is
required**.  To wire in a real LLM replace the `simulateLlmWork` stub in
`Server.kt`.

## Running

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :recipes:runMultiAgentBudget
```

## What to look for

- `[client]  metric  cost.usd worker ...` — worker spend reported while running.
- `[client]  metric  cost.usd planner ...` — planner overhead reported after
  worker completes.
- `[client]  planner completed` — terminal event; budget state is consistent.
